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
	OciResponse ociSpecv1.Descriptor `json:"ociResponse"`
	//File digest calculated by
	FileSHA256Digest string `json:"fileSHA256Digest"`
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

	oc, err := NewOrasClient(form.Repo)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error creating oras client: ", err)
		return
	}
	tempFile, checksum, err := CalculateSHA256(tempFile)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Calculating Checksum: ", err)
		return
	}

	//verify integrity
	if len(form.InputDigest) > 0 && form.InputDigest != "" && checksum != form.InputDigest {
		log.Panic("File Integrity Check Failed: ", err)
		c.String(http.StatusBadRequest, "File Integrity Check Failed")
	}

	resp, err := oc.PushArtifact(c, tempFile, form.Tag)
	if err != nil {
		log.Panic("Error Pushing Artifact: ", err)
		c.String(http.StatusBadRequest, "Error Pushing Artifact: ", err)
		return
	}

	c.JSON(200, &OASResponse{
		OciResponse:      resp,
		FileSHA256Digest: checksum,
	})

}
func downloadFile(c *gin.Context) {
	var form Form
	c.ShouldBind(&form)

	oc, err := NewOrasClient(form.Repo)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error creating oras client: ", err)
		return
	}
	err = oc.PullArtifact(c, form.Tag)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Pulling artifact: ", err)
		return
	}
	targetDir := filepath.Join("/tmp", form.Tag)
	files, err := os.ReadDir(targetDir)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Pulling artifact: ", err)
		return
	}

	targetFile := files[0]

	targetPath := filepath.Join("/tmp", form.Tag, targetFile.Name())

	mimeType, err := mimetype.DetectFile(targetPath)
	if err != nil {
		c.String(http.StatusInternalServerError, "Error Determining mimetype: ", err)
		return
	}

	c.Header("Content-Description", "File Transfer")
	c.Header("Content-type", mimeType.String())
	c.Header("Content-Disposition", "attachment; filename="+form.Tag+mimeType.Extension())
	c.File(targetPath)

	err = os.RemoveAll("/tmp/" + form.Tag)
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
