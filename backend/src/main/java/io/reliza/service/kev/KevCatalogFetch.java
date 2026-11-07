/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.time.Instant;
import java.util.List;

import io.reliza.model.KevAssertionData;

/**
 * One fetch of a KEV source's catalog (FDAW-2): the normalized entries plus the catalog header when
 * the source publishes one -- CISA's {@code catalogVersion} and {@code dateReleased}; VulnCheck
 * publishes neither, so both stay null. Empty entries mean a failed or empty fetch, as before.
 */
public record KevCatalogFetch(List<KevAssertionData> entries, String catalogVersion, Instant dateReleased) {

	public static final KevCatalogFetch EMPTY = new KevCatalogFetch(List.of(), null, null);

	public KevCatalogFetch {
		entries = null == entries ? List.of() : entries;
	}

	/** Entries without a header: a source that publishes none. */
	public static KevCatalogFetch entriesOnly(List<KevAssertionData> entries) {
		return new KevCatalogFetch(entries, null, null);
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}
}
