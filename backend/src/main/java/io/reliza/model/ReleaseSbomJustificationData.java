/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The hash justifications of one release SBOM identity head (FDAW-1 F-14 item 2, FDAW-14 3.3), stored
 * as {@code release_sbom_justifications.record_data}: one {@link JustifiedUnknown} per component key
 * of the head's document ({@code SupportBomInjector.componentKey}), each with a code
 * {@link JustifiedUnknownField#HASH} accepts. Keys are strings of the document, never component uuids:
 * an OS package of an image often has no {@code sbom_components} row of its own spelling.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseSbomJustificationData(Map<String, JustifiedUnknown> hashes) implements Serializable {

	public ReleaseSbomJustificationData {
		hashes = (null == hashes || hashes.isEmpty()) ? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
	}

	public static ReleaseSbomJustificationData empty() {
		return new ReleaseSbomJustificationData(Map.of());
	}
}
