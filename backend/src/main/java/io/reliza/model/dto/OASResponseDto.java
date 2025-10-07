/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class OASResponseDto {
    @JsonProperty("ociResponse")
    private ArtifactUploadResponseDto ociResponse;

    @JsonProperty("fileSHA256Digest")
    private String fileSHA256Digest;

    @JsonProperty("compressed")
    private Boolean compressed;

    @JsonProperty("compressionStats")
    private String compressionStats;

    @JsonProperty("originalMediaType")
    private String originalMediaType;

    @JsonProperty("originalSize")
    private Long originalSize;

    @JsonProperty("compressedSize")
    private Long compressedSize;
}
