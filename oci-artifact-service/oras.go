package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"strings"

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
	fmt.Println(host + "/" + repoName)
	if err != nil {
		fmt.Println(err)
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
	return &OrasClient{repo}, nil
}

func (o *OrasClient) PushArtifact(ctx context.Context, uploadedFile *os.File, tag string, compressionMeta *CompressionMetadata) (v1.Descriptor, error) {
	// 0. Create a file store
	fmt.Println("pushing")
	resp := v1.Descriptor{}
	fs, err := file.New("")
	if err != nil {
		fmt.Println(err)
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
		log.Println("Error Packing", err)
		return resp, err
	}

	if err = fs.Tag(ctx, manifestDescriptor, tag); err != nil {
		log.Println("Error tagging artifact", err)
		return resp, err
	}
	// 3. Copy from the file store to the remote repository
	resp, err = oras.Copy(ctx, fs, tag, o.Repository, tag, oras.DefaultCopyOptions)
	if err != nil {
		log.Println("Error pushing", err)
		return resp, err
	}
	resp.Size = fileStat.Size()
	return resp, nil
}

func (o *OrasClient) PullArtifact(ctx context.Context, tagDigest string, dirName string) (v1.Descriptor, error) {

	fs, err := file.New("/tmp/" + dirName + "/")
	if err != nil {
		log.Println("Error creating temp", err)
		return v1.Descriptor{}, err
	}
	defer fs.Close()

	descriptor, err := oras.Copy(ctx, o.Repository, tagDigest, fs, tagDigest, oras.DefaultCopyOptions)
	if err != nil {
		log.Println("Error pulling artifact ", err)
		return v1.Descriptor{}, err
	}

	return descriptor, nil
}
