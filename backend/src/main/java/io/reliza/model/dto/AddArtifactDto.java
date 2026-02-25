/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import lombok.Data;

@Data
public class AddArtifactDto {
    private UUID release;
    private UUID component;
    private UUID deliverable;
    private String releaseVersion;
    private ArtifactDto artifact;

}
