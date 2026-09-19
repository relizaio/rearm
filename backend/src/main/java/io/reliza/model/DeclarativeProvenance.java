/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provenance of the last declarative apply that touched a row (component, branch, ...).
 * Stored inside the row's JSONB record_data; no migration needed. Rows never touched by
 * a declarative apply carry null.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeclarativeProvenance implements Serializable {
	private static final long serialVersionUID = 1L;

	/** Where the spec came from when applied from CI; all fields optional. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Source implements Serializable {
		private static final long serialVersionUID = 1L;
		@JsonProperty
		private String repo;
		@JsonProperty
		private String path;
		@JsonProperty
		private String commit;
	}

	/** SHA-256 hex of the canonical JSON of the whole spec that touched this row. */
	@JsonProperty
	private String specHash;
	@JsonProperty
	private ZonedDateTime appliedAt;
	@JsonProperty
	private Source source;
}
