/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * The package settings row a submission version applied (task TEA-35): {@code row} null and
 * {@code revision} 0 for the built-in defaults.
 */
public record SubmissionSettingsRef(UUID row, Integer revision, ScopedSettingsSource source, UUID sourceObject)
		implements Serializable {}
