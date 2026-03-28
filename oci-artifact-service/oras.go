package main

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/gabriel-vasile/mimetype"
	v1 "github.com/opencontainers/image-spec/specs-go/v1"
	"oras.land/oras-go/v2"
	"oras.land/oras-go/v2/content/file"
	"oras.land/oras-go/v2/registry/remote"
	"oras.land/oras-go/v2/registry/remote/auth"
	"oras.land/oras-go/v2/registry/remote/retry"
)

type OrasClient struct {
	Repository *remote.Repository
}

func NewOrasClient(repoName string) (*OrasClient, error) {
	host := os.Getenv("REGISTRY_HOST")
	repo, err := remote.NewRepository(host + "/" + repoName)
	getLogger().Infow("Initializing ORAS client", "repository", host+"/"+repoName)
	if err != nil {
		getLogger().Errorw("Failed to create repository", "error", err, "repository", host+"/"+repoName)
		return nil, err
	}
	repo.Client = &auth.Client{
		Client: retry.DefaultClient,
		Cache:  auth.DefaultCache,
		Credential: auth.StaticCredential(host, auth.Credential{
			Username: os.Getenv("REGISTRY_USERNAME"),
			Password: os.Getenv("REGISTRY_TOKEN"),
		}),
	}
	// USE_PLAIN_HTTP env var controls whether to use plain HTTP (default false)
	repo.PlainHTTP = os.Getenv("USE_PLAIN_HTTP") == "true"
	return &OrasClient{repo}, nil
}

func (o *OrasClient) PushArtifact(ctx context.Context, uploadedFile *os.File, tag string, compressionMeta *CompressionMetadata) (v1.Descriptor, error) {
	// 0. Create a file store
	getLogger().Infow("Pushing artifact", "tag", tag)
	resp := v1.Descriptor{}
	fs, err := file.New("")
	if err != nil {
		getLogger().Errorw("Failed to create file store", "error", err)
		return resp, err
	}
	defer fs.Close()
	// 1. Add files to a file store
	mimeType, err := mimetype.DetectFile(uploadedFile.Name())
	if err != nil {
		return resp, err
	}
	originalMediaType := mimeType.String()
	mediaType := originalMediaType

	// Add compression suffix if compressed
	if compressionMeta != nil && compressionMeta.Algorithm == CompressionZstd {
		mediaType += "+zstd"
	}

	fileNames := []string{uploadedFile.Name()}
	fileDescriptors := make([]v1.Descriptor, 0, len(fileNames))
	for _, name := range fileNames {
		fileDescriptor, err := fs.Add(ctx, tag, mediaType, name)
		if err != nil {
			return resp, err
		}

		// Add compression annotations
		if compressionMeta != nil {
			if fileDescriptor.Annotations == nil {
				fileDescriptor.Annotations = make(map[string]string)
			}
			fileDescriptor.Annotations["io.reliza.compression.algorithm"] = string(compressionMeta.Algorithm)
			fileDescriptor.Annotations["io.reliza.original.mediatype"] = originalMediaType
			fileDescriptor.Annotations["io.reliza.original.size"] = fmt.Sprintf("%d", compressionMeta.OriginalSize)
			fileDescriptor.Annotations["io.reliza.compressed.size"] = fmt.Sprintf("%d", compressionMeta.CompressedSize)
			fileDescriptor.Annotations["io.reliza.original.sha256"] = compressionMeta.OriginalSHA256
		}

		fileDescriptors = append(fileDescriptors, fileDescriptor)
	}

	fileStat, err := uploadedFile.Stat()
	if err != nil {
		return resp, err
	}
	// 2. Pack the files and tag the packed manifest using PackManifest (replaces deprecated Pack)
	// Strip media type parameters (e.g., "text/plain; charset=utf-8" -> "text/plain")
	// as artifactType must be a valid media type without parameters per RFC 6838
	artifactType := mediaType
	if idx := strings.Index(artifactType, ";"); idx != -1 {
		artifactType = strings.TrimSpace(artifactType[:idx])
	}
	manifestDescriptor, err := oras.PackManifest(ctx, fs, oras.PackManifestVersion1_1, artifactType, oras.PackManifestOptions{
		Layers: fileDescriptors,
	})
	if err != nil {
		getLogger().Errorw("Error packing manifest", "error", err)
		return resp, err
	}

	if err = fs.Tag(ctx, manifestDescriptor, tag); err != nil {
		getLogger().Errorw("Error tagging artifact", "error", err, "tag", tag)
		return resp, err
	}
	// 3. Copy from the file store to the remote repository
	resp, err = oras.Copy(ctx, fs, tag, o.Repository, tag, oras.DefaultCopyOptions)
	if err != nil {
		getLogger().Errorw("Error pushing to registry", "error", err, "tag", tag)
		return resp, err
	}
	resp.Size = fileStat.Size()
	return resp, nil
}

func (o *OrasClient) PullArtifact(ctx context.Context, tagDigest string, dirName string) (v1.Descriptor, error) {
	const maxRetries = 5
	const baseDelay = 500 * time.Millisecond

	fs, err := file.New("/tmp/" + dirName + "/")
	if err != nil {
		getLogger().Errorw("Error creating temp directory", "error", err, "dir", dirName)
		return v1.Descriptor{}, err
	}
	defer fs.Close()

	var descriptor v1.Descriptor
	var lastErr error

	for attempt := 1; attempt <= maxRetries; attempt++ {
		descriptor, lastErr = oras.Copy(ctx, o.Repository, tagDigest, fs, tagDigest, oras.DefaultCopyOptions)
		if lastErr == nil {
			return descriptor, nil
		}

		getLogger().Warnw("Pull attempt failed",
			"attempt", attempt,
			"max_retries", maxRetries,
			"tag_digest", tagDigest,
			"error", lastErr,
		)

		if attempt < maxRetries {
			// Exponential backoff: 500ms, 1s, 2s, 4s, 8s
			delay := baseDelay * time.Duration(1<<(attempt-1))
			getLogger().Infow("Retrying pull", "delay", delay.String())

			select {
			case <-ctx.Done():
				return v1.Descriptor{}, ctx.Err()
			case <-time.After(delay):
			}
		}
	}

	getLogger().Errorw("Error pulling artifact after all retries",
		"attempts", maxRetries,
		"tag_digest", tagDigest,
		"error", lastErr,
	)
	return v1.Descriptor{}, lastErr
}
