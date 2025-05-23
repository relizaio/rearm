package main

import (
	"crypto/sha256"
	"fmt"
	"io"
	"os"
)

func CalculateSHA256(tempFile *os.File) (*os.File, string, error) {

	// Reset file position to beginning
	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		return nil, "", fmt.Errorf("failed to seek file: %v", err)
	}

	hash := sha256.New()
	if _, err := io.Copy(hash, tempFile); err != nil {
		return nil, "", fmt.Errorf("failed to calculate hash: %v", err)
	}

	checksum := hash.Sum(nil)
	return tempFile, fmt.Sprintf("%x", checksum), nil
}
