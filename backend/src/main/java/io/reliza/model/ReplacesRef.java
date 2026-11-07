/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The version a new version replaces: fills the manifest's replaces. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplacesRef(UUID submission, Integer version) implements Serializable {}
