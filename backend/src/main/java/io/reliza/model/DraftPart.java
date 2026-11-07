/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One part a draft build generated: an unbound artifact until Finalize binds it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftPart(PackagePart part, UUID artifact, String sha256, Long sizeBytes, String documentSerial,
		Integer documentVersion) implements Serializable {}
