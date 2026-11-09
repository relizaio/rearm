/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One component of a draft build: its key and the support row and revision it read, a join recorded
 * for display and recomputed from the build's componentKeys at every use. Not
 * {@link ComponentData.ComponentRef}, which is a component row's summary.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftComponentRef(String key, UUID supportRow, Integer supportRevision) implements Serializable {}
