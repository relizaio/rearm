/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;

/**
 * Reads a CycloneDX 2.0 document into the {@link Cdx20Document} subset. Tolerant: fields outside
 * the subset are ignored and an unknown enum value reads as null. It checks only that the input
 * is a JSON CycloneDX document with a 2.x {@code specVersion}; it does not validate against the
 * schema. The subset is bound to the 2.0-dev snapshot at {@link #CDX_2_0_DEV_PINNED_COMMIT}
 * ({@code src/test/resources/schema/cyclonedx-2.0-dev-ea8c3605.bundled.schema.json}), checked by
 * {@code Cdx20SchemaConformanceTest}.
 */
@Slf4j
public final class Cdx20DocumentReader {

	/** The CycloneDX specification commit (branch 2.0-dev) the subset and its tests are pinned to. */
	public static final String CDX_2_0_DEV_PINNED_COMMIT = "ea8c3605829d1da13339a0790b6da73548e42a6b";

	private static final String SPEC_FORMAT = "CycloneDX";

	private Cdx20DocumentReader() {}

	/** Reads {@code json}; refuses it when it is not a JSON CycloneDX document with a 2.x specVersion. */
	public static Cdx20Document read(String json) throws RelizaException {
		if (json == null || json.isBlank()) throw new RelizaException("CycloneDX 2.0 document is empty");
		Cdx20Document doc;
		try {
			doc = Utils.OM.readValue(json, Cdx20Document.class);
		} catch (JacksonException e) {
			// The parser's message can quote the input; keep it in the log, not in the refusal.
			log.debug("CycloneDX 2.0 document not readable: {}", e.getOriginalMessage());
			throw new RelizaException("Not a readable CycloneDX 2.0 JSON document");
		}
		if (doc == null || !SPEC_FORMAT.equals(doc.getSpecFormat())) {
			throw new RelizaException("Not a CycloneDX document: specFormat must be " + SPEC_FORMAT);
		}
		String specVersion = doc.getSpecVersion();
		if (specVersion == null || !specVersion.startsWith("2.")) {
			throw new RelizaException("Unsupported CycloneDX specVersion " + specVersion + ": 2.x required");
		}
		return doc;
	}
}
