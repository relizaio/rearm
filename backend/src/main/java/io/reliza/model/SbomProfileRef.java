/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The SBOM profile row and revision a draft build used. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SbomProfileRef(UUID uuid, Integer revision) implements Serializable {}
