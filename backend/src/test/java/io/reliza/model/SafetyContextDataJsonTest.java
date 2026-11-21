/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.SafetyContextData.Note;

/** Stored timestamps are UTC RFC 3339 strings, not Utils.OM's default numeric form. */
class SafetyContextDataJsonTest {

	@Test
	void instantsRoundTripAsUtcStrings() {
		Instant at = Instant.parse("2026-11-21T10:15:30.123Z");
		SafetyContextRecordData record = new SafetyContextRecordData();
		record.setAcceptedAt(at);
		record.getNotes().add(Note.CROSS_DOC_CONFLICT);
		Map<String, Object> stored = record.toRecordData();
		assertEquals("2026-11-21T10:15:30.123Z", stored.get("acceptedAt"));
		assertEquals("UNVERIFIED", stored.get("verificationStatus"));

		SafetyContextRecord entity = new SafetyContextRecord();
		entity.setRecordData(stored);
		SafetyContextRecordData back = SafetyContextRecordData.dataFromRecord(entity);
		assertEquals(at, back.getAcceptedAt());
		assertEquals(Note.CROSS_DOC_CONFLICT, back.getNotes().get(0));
	}

	@Test
	void proposalActedAtIsAString() {
		SafetyContextProposalData proposal = new SafetyContextProposalData();
		proposal.setActedAt(Instant.parse("2026-11-21T00:00:00Z"));
		assertEquals("2026-11-21T00:00:00Z", proposal.toRecordData().get("actedAt"));
	}
}
