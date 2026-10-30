/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;

/**
 * TEA-4: ReleaseData.syntheticArtifacts, the list of artifacts ReARM generated for a release. It
 * persists with the record, reads as empty on every existing row, and cannot be mutated through
 * its getter, so no reader can fold it into the inventory list by accident.
 */
class ReleaseDataSyntheticArtifactsTest {

	private static ReleaseData roundTrip(Map<String, Object> recordData) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setRecordData(recordData);
		return ReleaseData.dataFromRecord(r);
	}

	@Test
	void theListRoundTripsInOrderBesideAnUntouchedArtifactsList() {
		UUID inventory = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		ReleaseData rd = new ReleaseData();
		rd.setArtifacts(new LinkedList<>(List.of(inventory)));
		rd.addSyntheticArtifact(first);
		rd.addSyntheticArtifact(second);

		Map<String, Object> record = Utils.dataToRecord(rd);
		ReleaseData back = roundTrip(record);

		assertEquals(List.of(first.toString(), second.toString()),
				record.get(CommonVariables.SYNTHETIC_ARTIFACTS_FIELD), "stored under syntheticArtifacts");
		assertEquals(List.of(first, second), back.getSyntheticArtifacts());
		assertEquals(List.of(inventory), back.getArtifacts());
	}

	@Test
	void aRecordWithoutTheKeyReadsAsAnEmptyList() {
		ReleaseData rd = new ReleaseData();
		rd.setArtifacts(new LinkedList<>(List.of(UUID.randomUUID())));
		Map<String, Object> record = Utils.dataToRecord(rd);
		record.remove(CommonVariables.SYNTHETIC_ARTIFACTS_FIELD);

		assertEquals(List.of(), roundTrip(record).getSyntheticArtifacts());
	}

	@Test
	void theGetterReturnsACopy() {
		ReleaseData rd = new ReleaseData();

		rd.getSyntheticArtifacts().add(UUID.randomUUID());

		assertEquals(List.of(), rd.getSyntheticArtifacts());
	}

	@Test
	void addIsIdempotentAndRemoveOfAnAbsentIdIsFalse() {
		ReleaseData rd = new ReleaseData();
		UUID id = UUID.randomUUID();

		assertTrue(rd.addSyntheticArtifact(id));
		assertFalse(rd.addSyntheticArtifact(id), "a second add of the same id adds nothing");
		assertEquals(List.of(id), rd.getSyntheticArtifacts());
		assertFalse(rd.removeSyntheticArtifact(UUID.randomUUID()));
		assertTrue(rd.removeSyntheticArtifact(id));
		assertEquals(List.of(), rd.getSyntheticArtifacts());
	}
}
