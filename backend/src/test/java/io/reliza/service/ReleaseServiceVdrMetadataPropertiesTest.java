/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZonedDateTime;

import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.Property;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.KevSource;
import io.reliza.model.VdrAnalysisState;
import io.reliza.model.VdrMetadataProperty;
import io.reliza.model.VdrSnapshotType;
import io.reliza.service.kev.KevCatalogStateService.KevCatalogState;

/**
 * FDAW-2 (B16): every timestamp a VDR's metadata carries is an RFC-3339 UTC instant with a trailing Z,
 * never ZonedDateTime.toString() with its [zone] suffix (ai-agents/coding_principles.md). Design T3.
 */
class ReleaseServiceVdrMetadataPropertiesTest {

	private static final ZonedDateTime PARIS = ZonedDateTime.parse("2024-01-02T03:04:05+01:00[Europe/Paris]");
	private static final String PARIS_UTC = "2024-01-02T02:04:05Z";

	private static String property(Metadata metadata, VdrMetadataProperty name) {
		return metadata.getProperties().stream()
				.filter(p -> name.toString().equals(p.getName()))
				.map(Property::getValue)
				.findFirst().orElse(null);
	}

	private static void assertRfc3339Utc(String expected, String actual) {
		assertEquals(expected, actual);
		assertFalse(actual.contains("["), actual);
		assertDoesNotThrow(() -> Instant.parse(actual));
	}

	@Test
	void theCutOffDateIsAUtcInstant() {
		Metadata metadata = new Metadata();
		ReleaseService.attachVdrMetadataProperties(metadata, PARIS, VdrSnapshotType.DATE, null,
				new VdrAnalysisState.Derived(VdrAnalysisState.NOT_ANALYSED, VdrAnalysisState.Reason.NO_SBOM, null, 0, 1, 0),
				null);
		assertRfc3339Utc(PARIS_UTC, property(metadata, VdrMetadataProperty.VDR_CUTOFF_DATE));
	}

	@Test
	void theCompletionTimeIsAUtcInstant() {
		Metadata metadata = new Metadata();
		ReleaseService.attachVdrMetadataProperties(metadata, null, null, null,
				new VdrAnalysisState.Derived(VdrAnalysisState.COMPLETED, null, PARIS, 1, 0, 0), null);
		assertRfc3339Utc(PARIS_UTC, property(metadata, VdrMetadataProperty.VDR_ANALYSIS_COMPLETED_AT));
		assertNull(property(metadata, VdrMetadataProperty.VDR_CUTOFF_DATE));
	}

	@Test
	void theKevCatalogDateAndSyncAreUtcInstants() {
		Metadata metadata = new Metadata();
		ReleaseService.attachVdrMetadataProperties(metadata, null, null, null,
				new VdrAnalysisState.Derived(VdrAnalysisState.COMPLETED, null, PARIS, 1, 0, 0),
				new KevCatalogState(KevSource.CISA, PARIS.toInstant(), Instant.parse("2024-01-03T00:00:00.5Z")));
		assertRfc3339Utc(PARIS_UTC, property(metadata, VdrMetadataProperty.VDR_KEV_CATALOG_DATE));
		assertRfc3339Utc("2024-01-03T00:00:00.500Z", property(metadata, VdrMetadataProperty.VDR_KEV_CATALOG_SYNCED_AT));
	}

	@Test
	void aVulnCheckCatalogNamesNoDate_onlyTheSync() {
		Metadata metadata = new Metadata();
		ReleaseService.attachVdrMetadataProperties(metadata, null, null, null,
				new VdrAnalysisState.Derived(VdrAnalysisState.COMPLETED, null, PARIS, 1, 0, 0),
				new KevCatalogState(KevSource.VULNCHECK, null, PARIS.toInstant()));
		assertEquals("VULNCHECK", property(metadata, VdrMetadataProperty.VDR_KEV_CATALOG_SOURCE));
		assertNull(property(metadata, VdrMetadataProperty.VDR_KEV_CATALOG_DATE));
		assertRfc3339Utc(PARIS_UTC, property(metadata, VdrMetadataProperty.VDR_KEV_CATALOG_SYNCED_AT));
	}

	@Test
	void toRfc3339Utc_dropsTheZoneSuffix() {
		assertRfc3339Utc("2024-01-02T03:04:05Z", Utils.toRfc3339Utc(ZonedDateTime.parse("2024-01-02T03:04:05Z[GMT]")));
		assertRfc3339Utc("2024-01-01T21:34:05Z", Utils.toRfc3339Utc(ZonedDateTime.parse("2024-01-02T03:04:05+05:30")));
		assertNull(Utils.toRfc3339Utc(null));
	}
}
