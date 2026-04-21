/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AnalysisState {
	EXPLOITABLE,
	IN_TRIAGE,
	FALSE_POSITIVE,
	NOT_AFFECTED,
	RESOLVED;

	private static final Logger log = LoggerFactory.getLogger(AnalysisState.class);

	/**
	 * Lenient deserializer for persisted/legacy values. Maps known aliases
	 * (e.g. "FIXED" → RESOLVED) and returns null for unknown values so that
	 * downstream analytics/VDR code can keep processing instead of failing.
	 * Unknown values are logged at WARN so ops can spot drift.
	 */
	@JsonCreator
	public static AnalysisState fromJson(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		switch (normalized) {
			case "FIXED":
				return RESOLVED;
			default:
				try {
					return AnalysisState.valueOf(normalized);
				} catch (IllegalArgumentException e) {
					log.warn("Unknown AnalysisState value encountered during deserialization: '{}' — returning null", value);
					return null;
				}
		}
	}
}
