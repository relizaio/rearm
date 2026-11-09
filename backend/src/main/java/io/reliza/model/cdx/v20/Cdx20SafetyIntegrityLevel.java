/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * A safety integrity level under a scheme (spec PR #1120). The scheme is predefined (see
 * {@link Cdx20SafetyScheme}) or custom; {@code level} is the scheme's own value, such as
 * {@code class-c} for IEC-62304, and is not interpreted here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/safetyIntegrityLevel")
public class Cdx20SafetyIntegrityLevel {
	private Cdx20Scheme scheme;
	private String level;

	/** The predefined safety scheme, or null for a custom or unknown one. */
	public Cdx20SafetyScheme predefinedScheme() {
		return scheme == null ? null : Cdx20SafetyScheme.fromValue(scheme.getPredefined());
	}
}
