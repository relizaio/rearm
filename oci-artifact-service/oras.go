package main

import (
	"context"
	"fmt"
	"log"
	"os"

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

func (o *OrasClient) PushArtifact(ctx context.Context, uploadedFile *os.File, tag string) (v1.Descriptor, error) {
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
	mediaType := mimeType.String()
	fileNames := []string{uploadedFile.Name()}
	fileDescriptors := make([]v1.Descriptor, 0, len(fileNames))
	for _, name := range fileNames {
		fileDescriptor, err := fs.Add(ctx, tag, mediaType, name)
		if err != nil {
			return resp, err
		}
		fileDescriptors = append(fileDescriptors, fileDescriptor)
	}

	fileStat, err := uploadedFile.Stat()
	if err != nil {
		return resp, err
	}
	// 2. Pack the files and tag the packed manifest
	artifactType := mediaType
	manifestDescriptor, err := oras.Pack(ctx, fs, artifactType, fileDescriptors, oras.PackOptions{
		PackImageManifest: true,
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

func (o *OrasClient) PullArtifact(ctx context.Context, tag string) error {

	fs, err := file.New("/tmp/" + tag + "/")
	if err != nil {
		log.Println("Error creating temp", err)
		return err
	}
	defer fs.Close()

	_, err = oras.Copy(ctx, o.Repository, tag, fs, tag, oras.DefaultCopyOptions)
	if err != nil {
		log.Println("Error pulling artifact ", err)
		return err
	}

	return nil
}
