/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * The SBOM profile a submission version's aggregate applied (task TEA-35), by name: {@code row}
 * null and {@code revision} 0 for the built-in {@code default}.
 */
public record SubmissionProfileRef(String name, UUID row, Integer revision, ScopedSettingsSource source,
		UUID sourceObject) implements Serializable {}
