/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * The pin of one release SBOM version (FDAW-1 D1): the CycloneDX serialNumber (the head uuid), the
 * document version, the AS_UPLOADED sha256 of the stored bytes (lower-case hex) and the artifact row
 * that holds them. A package version records it; the bytes of a pin are read through
 * {@code ArtifactService.getArtifactDataByVersion(artifact, version)}.
 */
public record ReleaseSbomRef(UUID serial, int version, String sha256, UUID artifact) implements Serializable {}
