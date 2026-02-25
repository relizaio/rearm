/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Data;

@Data
public class AttachSbomToArtifactDto {
    @JsonProperty(CommonVariables.RELEASE_FIELD)
    private UUID release;

    @JsonProperty(CommonVariables.DIGEST_FEILD)
    private String digest;

    @JsonProperty(CommonVariables.ARTIFACT_FIELD)
    private UUID artifact;
}
