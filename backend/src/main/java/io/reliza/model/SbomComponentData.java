/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.reliza.common.Utils;

/**
 * Typed view of {@link SbomComponent}'s {@code recordData} JSONB — the descriptive
 * attributes copied from the parsed BOM component ({@code type}, {@code group},
 * {@code name}, {@code version}) plus the {@code isRoot} self-component flag. These
 * have no dedicated columns; this mirrors the {@code *Data} convention used by
 * {@code ComponentData} / {@code ReleaseData} / etc. so call sites read typed
 * accessors instead of {@code recordData.get("...")}.
 *
 * <p>{@code @JsonInclude(NON_NULL)} keeps {@link #toRecordMap()} writing only the
 * set fields (so {@code isRoot} is stored only when true, matching the prior
 * hand-built map shape).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SbomComponentData(String type, String group, String name, String version, Boolean isRoot) {

	public static SbomComponentData dataFromRecord(SbomComponent sc) {
		Map<String, Object> rd = sc.getRecordData();
		if (rd == null) return new SbomComponentData(null, null, null, null, null);
		return Utils.OM.convertValue(rd, SbomComponentData.class);
	}

	/** Serialize back to the {@code recordData} map shape (null fields omitted). */
	@SuppressWarnings("unchecked")
	public Map<String, Object> toRecordMap() {
		return Utils.OM.convertValue(this, Map.class);
	}

	/** Convenience boolean over the nullable {@link #isRoot()} flag. */
	public boolean root() {
		return Boolean.TRUE.equals(isRoot);
	}
}
