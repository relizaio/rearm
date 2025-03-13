/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Data;

@Data
public class ArtifactUploadResponseDto {
    @JsonProperty(CommonVariables.MEDIA_TYPE_FIELD)
    private String mediaType;

    @JsonProperty(CommonVariables.DIGEST_FEILD)
    private String digest;

    @JsonProperty(CommonVariables.SIZE_FEILD)
    private String size;

    private String artifactType;
}
