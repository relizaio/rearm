/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.DigestAlgorithm;
import tools.jackson.core.type.TypeReference;

/**
 * SCORE-30 (ADR-3): a deliverable's digest records read back from its stored record in the order
 * they were recorded, so the merged SBOM's release node lists them in that order. Every algorithm
 * is recorded, last first, so a set that ordered them any other way would show.
 */
class DeliverableDataDigestRecordOrderTest {

	@Test
	void digestRecordsReadBackInTheOrderRecorded() {
		List<DigestRecord> recorded = new ArrayList<>();
		DigestAlgorithm[] algos = DigestAlgorithm.values();
		for (int i = algos.length - 1; i >= 0; i--) {
			recorded.add(new DigestRecord(algos[i], UUID.randomUUID().toString().replace("-", "")));
		}
		DeliverableData dd = new DeliverableData();
		dd.setSoftwareMetadata(SoftwareDeliverableMetadata.builder().digestRecords(new LinkedHashSet<>(recorded)).build());

		Map<String, Object> stored = Utils.OM.convertValue(dd, new TypeReference<Map<String, Object>>() {});
		DeliverableData read = Utils.OM.convertValue(stored, DeliverableData.class);

		assertEquals(recorded, new ArrayList<>(read.getSoftwareMetadata().getDigestRecords()));
	}
}
