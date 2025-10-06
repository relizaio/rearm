package main

import (
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"

	"github.com/gabriel-vasile/mimetype"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	ociSpecv1 "github.com/opencontainers/image-spec/specs-go/v1"
)

func main() {
	fmt.Println("init")
	r := gin.Default()
	setGinConfigurations(r)

	r.POST("/push", uploadFile)
	r.GET("/pull", downloadFile)
	r.GET("/health", healthCheck)
	r.Run(":8083")

}

func setGinConfigurations(router *gin.Engine) {
	router.Use(gin.Logger())
}

type Form struct {
	File        *multipart.FileHeader `form:"file" binding:"required"`
	Repo        string                `form:"repo"`
	Tag         string                `form:"tag"`
	InputDigest string                `form:"inputDigest"`
}

type OASResponse struct {
	OciResponse      ociSpecv1.Descriptor `json:"ociResponse"`
	FileSHA256Digest string               `json:"fileSHA256Digest"` // Original file digest
	Compressed       bool                 `json:"compressed"`       // Whether file was compressed
	CompressionStats string               `json:"compressionStats,omitempty"` // Compression statistics
}

func uploadFile(c *gin.Context) {
	var form Form
	c.ShouldBind(&form)

	// Open the uploaded file
	uploadedFile, err := form.File.Open()
	if err != nil {
		c.String(http.StatusInternalServerError, "Error opening uploaded file")
		return
	}
	defer uploadedFile.Close()

	// Create a temporary file to store the uploaded file
	tempFile, err := os.CreateTemp("", "uploaded-*.tmp")
	if err != nil {
		c.String(http.StatusInternalServerError, "Error creating temporary file")
		return
	}
	defer tempFile.Close()
	defer os.Remove(tempFile.Name())
	// Copy the uploaded file to the temporary file
	_, err = io.Copy(tempFile, uploadedFile)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error copying uploaded file to temporary file")
		return
	}

	// Calculate checksum on ORIGINAL data (before compression)
	tempFile, checksum, err := CalculateSHA256(tempFile)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Calculating Checksum: ", err)
		return
	}

	// Verify integrity
	if len(form.InputDigest) > 0 && form.InputDigest != "" && checksum != form.InputDigest {
		log.Printf("File Integrity Check Failed: expected %s, got %s", form.InputDigest, checksum)
		c.String(http.StatusBadRequest, "File Integrity Check Failed")
		return
	}

	// Detect MIME type
	mimeType, err := DetectMimeType(tempFile)
	if err != nil {
		mimeType = "application/octet-stream"
	}

	// Smart compression based on MIME type
	fileToUpload := tempFile
	var compressionMetadata *CompressionMetadata
	var compressedFile *os.File

	if ShouldCompress(mimeType) {
		compressedFile, compressionMetadata, err = CompressFile(tempFile, mimeType)
		if err != nil {
			log.Printf("Compression failed: %v", err)
			// Continue with uncompressed file
		} else if compressionMetadata != nil {
			fileToUpload = compressedFile
			defer compressedFile.Close()
			defer os.Remove(compressedFile.Name())
		}
	}

	oc, err := NewOrasClient(form.Repo)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error creating oras client: ", err)
		return
	}

	resp, err := oc.PushArtifact(c, fileToUpload, form.Tag, compressionMetadata)
	if err != nil {
		log.Printf("Error Pushing Artifact: %v", err)
		c.String(http.StatusBadRequest, "Error Pushing Artifact: ", err)
		return
	}

	response := &OASResponse{
		OciResponse:      resp,
		FileSHA256Digest: checksum, // Original file checksum
		Compressed:       compressionMetadata != nil,
	}

	if compressionMetadata != nil {
		response.CompressionStats = GetCompressionStats(compressionMetadata)
	}

	c.JSON(200, response)

}
func downloadFile(c *gin.Context) {
	var form Form
	c.ShouldBind(&form)

	oc, err := NewOrasClient(form.Repo)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error creating oras client: ", err)
		return
	}
	dirToDownload := uuid.New().String()
	descriptor, err := oc.PullArtifact(c, form.Tag, dirToDownload)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Pulling artifact: ", err)
		return
	}

	targetDir := filepath.Join("/tmp", dirToDownload)
	files, err := os.ReadDir(targetDir)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error reading directory: ", err)
		return
	}

	targetFile := files[0]
	targetPath := filepath.Join("/tmp", dirToDownload, targetFile.Name())

	// Check if file is compressed based on annotations or magic number
	isCompressed := false
	if descriptor.Annotations != nil {
		if algo, ok := descriptor.Annotations["io.reliza.compression.algorithm"]; ok && algo == "zstd" {
			isCompressed = true
		}
	}

	// If not detected from annotations, check file magic number
	if !isCompressed {
		file, err := os.Open(targetPath)
		if err == nil {
			defer file.Close()
			compressed, err := IsFileCompressed(file)
			if err == nil && compressed {
				isCompressed = true
			}
		}
	}

	finalPath := targetPath
	var decompressedFile *os.File

	if isCompressed {
		compressedFile, err := os.Open(targetPath)
		if err != nil {
			c.String(http.StatusInternalServerError, "Error opening compressed file: ", err)
			return
		}
		defer compressedFile.Close()

		decompressedFile, err = DecompressFile(compressedFile)
		if err != nil {
			log.Printf("Decompression failed: %v", err)
			c.String(http.StatusInternalServerError, "Error decompressing file: ", err)
			return
		}
		defer decompressedFile.Close()
		defer os.Remove(decompressedFile.Name())

		finalPath = decompressedFile.Name()
	}

	mimeType, err := mimetype.DetectFile(finalPath)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Determining mimetype: ", err)
		return
	}

	c.Header("Content-Description", "File Transfer")
	c.Header("Content-type", mimeType.String())
	c.Header("Content-Disposition", "attachment; filename="+form.Tag+mimeType.Extension())
	c.File(finalPath)

	err = os.RemoveAll("/tmp/" + dirToDownload)
	if err != nil {
		log.Printf("Error deleting file: %v", err)
	}

}

func healthCheck(c *gin.Context) {
	usernamePresent := os.Getenv("REGISTRY_USERNAME")
	tokenPresent := os.Getenv("REGISTRY_TOKEN")
	hostPresent := os.Getenv("REGISTRY_HOST")
	if usernamePresent != "" && tokenPresent != "" && hostPresent != "" {
		c.IndentedJSON(http.StatusOK, gin.H{"health": "OK"})
	} else {
		log.Fatal("REGISTRY_USERNAME or REGISTRY_TOKEN or REGISTRY_HOST not set")
		panic("REGISTRY_USERNAME or REGISTRY_TOKEN not set or REGISTRY_HOST")
	}

}
