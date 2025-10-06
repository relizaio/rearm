package main

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/gabriel-vasile/mimetype"
	"github.com/klauspost/compress/zstd"
)

// CompressionType represents the compression algorithm used
type CompressionType string

const (
	CompressionNone CompressionType = "none"
	CompressionZstd CompressionType = "zstd"
)

// CompressionMetadata holds information about compressed data
type CompressionMetadata struct {
	Algorithm      CompressionType
	OriginalSize   int64
	CompressedSize int64
	OriginalSHA256 string
}

// Compressible MIME types - text-based formats that compress well
var compressibleMimeTypes = []string{
	"application/json",
	"application/xml",
	"text/xml",
	"text/plain",
	"text/html",
	"text/csv",
	"application/x-yaml",
	"application/yaml",
	"text/yaml",
	"application/ld+json",
	"application/vnd.cyclonedx+json",
	"application/vnd.cyclonedx+xml",
	"application/spdx+json",
	"application/spdx+xml",
}

// Non-compressible MIME types - already compressed or binary formats
var nonCompressibleMimeTypes = []string{
	"application/gzip",
	"application/x-gzip",
	"application/zip",
	"application/x-tar",
	"application/x-bzip2",
	"application/x-xz",
	"application/zstd",
	"application/vnd.oci.image",
	"application/vnd.docker",
	"application/octet-stream", // Generic binary
	"image/jpeg",
	"image/png",
	"image/gif",
	"image/webp",
	"video/",
	"audio/",
}

// ShouldCompress determines if a file should be compressed based on its MIME type
func ShouldCompress(mimeType string) bool {
	mimeTypeLower := strings.ToLower(mimeType)

	// Check if explicitly non-compressible
	for _, nct := range nonCompressibleMimeTypes {
		if strings.HasPrefix(mimeTypeLower, nct) {
			return false
		}
	}

	// Check if explicitly compressible
	for _, ct := range compressibleMimeTypes {
		if strings.HasPrefix(mimeTypeLower, ct) {
			return true
		}
	}

	// Check if it's any text/* type
	if strings.HasPrefix(mimeTypeLower, "text/") {
		return true
	}

	// Default: don't compress unknown types
	return false
}

// DetectMimeType detects the MIME type of a file
func DetectMimeType(file *os.File) (string, error) {
	// Save current position
	currentPos, err := file.Seek(0, io.SeekCurrent)
	if err != nil {
		return "", fmt.Errorf("failed to get current position: %w", err)
	}

	// Reset to beginning for detection
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return "", fmt.Errorf("failed to seek to start: %w", err)
	}

	// Detect MIME type
	mtype, err := mimetype.DetectReader(file)
	if err != nil {
		return "", fmt.Errorf("failed to detect MIME type: %w", err)
	}

	// Restore original position
	if _, err := file.Seek(currentPos, io.SeekStart); err != nil {
		return "", fmt.Errorf("failed to restore position: %w", err)
	}

	return mtype.String(), nil
}

// CompressData compresses data using Zstd with default level
func CompressData(data []byte) ([]byte, error) {
	encoder, err := zstd.NewWriter(nil, zstd.WithEncoderLevel(zstd.SpeedDefault))
	if err != nil {
		return nil, fmt.Errorf("failed to create zstd encoder: %w", err)
	}
	defer encoder.Close()

	compressed := encoder.EncodeAll(data, make([]byte, 0, len(data)))
	return compressed, nil
}

// DecompressData decompresses Zstd data
func DecompressData(data []byte) ([]byte, error) {
	decoder, err := zstd.NewReader(nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create zstd decoder: %w", err)
	}
	defer decoder.Close()

	decompressed, err := decoder.DecodeAll(data, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to decompress data: %w", err)
	}

	return decompressed, nil
}

// CompressFile compresses a file if appropriate and returns metadata
func CompressFile(file *os.File, mimeType string) (*os.File, *CompressionMetadata, error) {
	// Check if file should be compressed
	if !ShouldCompress(mimeType) {
		return file, nil, nil // Return original file, no compression
	}

	// Reset file position
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, nil, fmt.Errorf("failed to seek file: %w", err)
	}

	// Read original file
	originalData, err := io.ReadAll(file)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to read file: %w", err)
	}

	originalSize := int64(len(originalData))

	// Skip compression for very small files (overhead not worth it)
	if originalSize < 1024 {
		// Reset file position and return original
		if _, err := file.Seek(0, io.SeekStart); err != nil {
			return nil, nil, fmt.Errorf("failed to seek file: %w", err)
		}
		return file, nil, nil
	}

	// Calculate original checksum
	originalChecksum, err := CalculateSHA256Bytes(originalData)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to calculate checksum: %w", err)
	}

	// Compress data
	compressed, err := CompressData(originalData)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to compress data: %w", err)
	}

	compressedSize := int64(len(compressed))

	// If compression doesn't help (file grew), return original
	if compressedSize >= originalSize {
		if _, err := file.Seek(0, io.SeekStart); err != nil {
			return nil, nil, fmt.Errorf("failed to seek file: %w", err)
		}
		return file, nil, nil
	}

	// Create temporary file for compressed data
	tempFile, err := os.CreateTemp("", "compressed-*.zst")
	if err != nil {
		return nil, nil, fmt.Errorf("failed to create temp file: %w", err)
	}

	// Write compressed data
	if _, err := tempFile.Write(compressed); err != nil {
		tempFile.Close()
		os.Remove(tempFile.Name())
		return nil, nil, fmt.Errorf("failed to write compressed data: %w", err)
	}

	// Reset file position
	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		tempFile.Close()
		os.Remove(tempFile.Name())
		return nil, nil, fmt.Errorf("failed to seek file: %w", err)
	}

	metadata := &CompressionMetadata{
		Algorithm:      CompressionZstd,
		OriginalSize:   originalSize,
		CompressedSize: compressedSize,
		OriginalSHA256: originalChecksum,
	}

	return tempFile, metadata, nil
}

// DecompressFile decompresses a file
func DecompressFile(compressedFile *os.File) (*os.File, error) {
	// Reset file position
	if _, err := compressedFile.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("failed to seek file: %w", err)
	}

	// Read compressed data
	compressedData, err := io.ReadAll(compressedFile)
	if err != nil {
		return nil, fmt.Errorf("failed to read compressed file: %w", err)
	}

	// Decompress
	decompressed, err := DecompressData(compressedData)
	if err != nil {
		return nil, err
	}

	// Create temporary file for decompressed data
	tempFile, err := os.CreateTemp("", "decompressed-*.tmp")
	if err != nil {
		return nil, fmt.Errorf("failed to create temp file: %w", err)
	}

	// Write decompressed data
	if _, err := tempFile.Write(decompressed); err != nil {
		tempFile.Close()
		os.Remove(tempFile.Name())
		return nil, fmt.Errorf("failed to write decompressed data: %w", err)
	}

	// Reset file position
	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		tempFile.Close()
		os.Remove(tempFile.Name())
		return nil, fmt.Errorf("failed to seek file: %w", err)
	}

	return tempFile, nil
}

// IsCompressed checks if data appears to be Zstd compressed
func IsCompressed(data []byte) bool {
	// Zstd magic number: 0x28, 0xB5, 0x2F, 0xFD
	if len(data) < 4 {
		return false
	}
	return data[0] == 0x28 && data[1] == 0xB5 && data[2] == 0x2F && data[3] == 0xFD
}

// IsFileCompressed checks if a file is Zstd compressed
func IsFileCompressed(file *os.File) (bool, error) {
	// Save current position
	currentPos, err := file.Seek(0, io.SeekCurrent)
	if err != nil {
		return false, fmt.Errorf("failed to get current position: %w", err)
	}

	// Reset to beginning
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return false, fmt.Errorf("failed to seek to start: %w", err)
	}

	// Read first 4 bytes
	header := make([]byte, 4)
	n, err := file.Read(header)
	if err != nil && err != io.EOF {
		return false, fmt.Errorf("failed to read header: %w", err)
	}

	// Restore original position
	if _, err := file.Seek(currentPos, io.SeekStart); err != nil {
		return false, fmt.Errorf("failed to restore position: %w", err)
	}

	if n < 4 {
		return false, nil
	}

	return IsCompressed(header), nil
}

// CalculateSHA256Bytes calculates SHA256 checksum of byte data
func CalculateSHA256Bytes(data []byte) (string, error) {
	tempFile, err := os.CreateTemp("", "checksum-*.tmp")
	if err != nil {
		return "", err
	}
	defer os.Remove(tempFile.Name())
	defer tempFile.Close()

	if _, err := tempFile.Write(data); err != nil {
		return "", err
	}

	if _, err := tempFile.Seek(0, io.SeekStart); err != nil {
		return "", err
	}

	_, checksum, err := CalculateSHA256(tempFile)
	return checksum, err
}

// GetCompressionStats returns statistics about compression effectiveness
func GetCompressionStats(metadata *CompressionMetadata) string {
	if metadata == nil {
		return "No compression applied"
	}

	ratio := float64(metadata.OriginalSize) / float64(metadata.CompressedSize)
	savings := (1.0 - float64(metadata.CompressedSize)/float64(metadata.OriginalSize)) * 100

	return fmt.Sprintf("Compressed %d bytes → %d bytes (%.2fx ratio, %.2f%% savings)",
		metadata.OriginalSize,
		metadata.CompressedSize,
		ratio,
		savings)
}
