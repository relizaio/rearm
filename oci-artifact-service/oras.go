package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	v1 "github.com/opencontainers/image-spec/specs-go/v1"
	"oras.land/oras-go/v2"
	"oras.land/oras-go/v2/errdef"
	"oras.land/oras-go/v2/content/file"
	"oras.land/oras-go/v2/registry/remote"
	"oras.land/oras-go/v2/registry/remote/auth"
	"oras.land/oras-go/v2/registry/remote/retry"
)

type OrasClient struct {
	Repository *remote.Repository
}

// Client cache, keyed by repository name. Rebuilding the client per request
// re-runs registry auth discovery (a GET /v2/ challenge round trip) on every
// push; under sustained SBOM ingest that probe was measured at 3 of the 13
// registry requests a single push costs. remote.Repository is safe for
// concurrent use (its fields are set once here and oras-go's auth.Client is
// concurrency-safe), so one instance per repo can serve parallel requests.
//
// Only cache VALIDATED repo names (see validateRepo) -- the key is
// caller-supplied, so an unbounded cache on raw input would be a memory
// exhaustion vector. The size cap is a backstop on top of that: past the
// cap the whole map is dropped, which merely costs the next requests a
// rebuild.
var (
	clientCache     sync.Map // repo name -> *OrasClient
	clientCacheSize atomic.Int64
)

const clientCacheMax = 128

// GetOrasClient returns a cached client for repoName, building one on miss.
func GetOrasClient(repoName string) (*OrasClient, error) {
	if v, ok := clientCache.Load(repoName); ok {
		return v.(*OrasClient), nil
	}
	oc, err := NewOrasClient(repoName)
	if err != nil {
		return nil, err
	}
	if clientCacheSize.Load() >= clientCacheMax {
		clientCache.Range(func(k, _ any) bool { clientCache.Delete(k); return true })
		clientCacheSize.Store(0)
		getLogger().Warnw("ORAS client cache cap reached; cache dropped", "cap", clientCacheMax)
	}
	if _, loaded := clientCache.LoadOrStore(repoName, oc); !loaded {
		clientCacheSize.Add(1)
	}
	return oc, nil
}

// invalidateOrasClient evicts a cached client after a failed operation so a
// client that acquired bad state (e.g. a rejected token) cannot poison every
// subsequent request for its repo.
func invalidateOrasClient(repoName string) {
	if _, ok := clientCache.LoadAndDelete(repoName); ok {
		clientCacheSize.Add(-1)
	}
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

// PushArtifact stores the file under tag. originalMediaType is the media type
// of the ORIGINAL (pre-compression) content as detected by the caller -- the
// file handed in here may already be zstd-compressed, so detecting on it would
// yield application/zstd rather than the content's real type.
func (o *OrasClient) PushArtifact(ctx context.Context, uploadedFile *os.File, tag string, originalMediaType string, compressionMeta *CompressionMetadata) (v1.Descriptor, error) {
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
	// Strip media type parameters (e.g. "text/plain; charset=utf-8" ->
	// "text/plain"): OCI descriptor mediaType must be a bare media type.
	// Spec-strict registries (zot) reject manifests whose layer mediaType
	// carries parameters; lax ones (distribution/Harbor) let it through.
	strippedMediaType := originalMediaType
	if idx := strings.Index(strippedMediaType, ";"); idx != -1 {
		strippedMediaType = strings.TrimSpace(strippedMediaType[:idx])
	}

	// The layer carries the blob encoding: +zstd suffix when compressed
	mediaType := strippedMediaType
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
	// artifactType describes the content, not the blob encoding, so it takes
	// the stripped original type without the compression suffix.
	artifactType := strippedMediaType
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

		// A definitive registry not-found will not change on retry -- fail
		// fast so callers probing multiple repositories (rebom's raw-BOM
		// resolver) don't pay the full backoff (~7.5s) per absent candidate.
		if errors.Is(lastErr, errdef.ErrNotFound) {
			getLogger().Infow("Artifact not found; not retrying",
				"tag_digest", tagDigest,
				"error", lastErr,
			)
			return v1.Descriptor{}, lastErr
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
