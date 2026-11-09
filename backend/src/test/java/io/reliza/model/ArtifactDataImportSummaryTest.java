/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.dto.ImportSummary;

/**
 * The import summary keeps its stored JSONB key {@code vexImportSummary}, so rows written
 * before the rename read unchanged, and {@code importSummary} is served from it without
 * being stored.
 */
class ArtifactDataImportSummaryTest {

	@Test
	void rowStoredBeforeTheRenameReadsAsImportSummary() {
		Map<String, Object> oldRow = Map.of(
			"type", "VEX",
			"vexImportSummary", Map.of(
				"statementsTotal", 4,
				"proposalsCreated", 1,
				"proposalsAutoAccepted", 2,
				"attestationsDeferred", 1,
				"statementsUnmatched", 1,
				"errorMessages", List.of("unparsed statement")));
		ArtifactData ad = Utils.OM.convertValue(oldRow, ArtifactData.class);
		ImportSummary s = ad.getImportSummary();
		assertEquals(4, s.getStatementsTotal());
		assertEquals(1, s.getProposalsCreated());
		assertEquals(2, s.getProposalsAutoAccepted());
		assertEquals(1, s.getAttestationsDeferred());
		assertEquals(1, s.getStatementsUnmatched());
		assertEquals(List.of("unparsed statement"), s.getErrorMessages());
		assertSame(ad.getVexImportSummary(), s);
	}

	@Test
	void summaryIsStoredUnderTheOldKeyOnly() {
		ArtifactData ad = new ArtifactData();
		ImportSummary s = new ImportSummary();
		s.setStatementsTotal(3);
		ad.setVexImportSummary(s);
		@SuppressWarnings("unchecked")
		Map<String, Object> stored = Utils.OM.convertValue(ad, Map.class);
		assertTrue(stored.containsKey("vexImportSummary"));
		assertFalse(stored.containsKey("importSummary"));
	}
}
