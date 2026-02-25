/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.ArtifactData.ArtifactType;
import lombok.Data;

@Data
public class DownloadableArtifactDto {
    @JsonProperty(CommonVariables.UUID_FIELD)
    private UUID uuid;

    @JsonProperty(CommonVariables.COMPONENT_FIELD)
    private UUID component;

    @JsonProperty(CommonVariables.VERSION_FIELD)
    private String version;

    private ArtifactType artifactType;
}
