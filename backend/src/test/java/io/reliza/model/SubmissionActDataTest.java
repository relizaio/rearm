/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.SubmissionActData.ConfirmationRecord;

/**
 * FDAW-8 T-9 (FDAW-1 D2, D6 item 2, F-26): the act record round-trips every key, the columns are never
 * part of it, a schema version other than zero is refused, and an act that names no person carries no
 * person key.
 */
class SubmissionActDataTest {

	private static final ZonedDateTime AT = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, ZoneOffset.UTC);

	private static SubmissionActData read(SubmissionActData d) {
		return SubmissionActData.dataFromRecord(d.toRecordData(), 0, d.getUuid(), d.getOrg(), d.getSubmission(),
				d.getDraft(), d.getRelease(), d.getVersion(), d.getKind(), d.getActor(), d.getCause(), d.getCausedBy(),
				d.getActedAt());
	}

	private static SubmissionActData columns(SubmissionActKind kind, ActCause cause) {
		SubmissionActData d = new SubmissionActData();
		d.setUuid(UUID.randomUUID());
		d.setOrg(UUID.randomUUID());
		d.setSubmission(UUID.randomUUID());
		d.setDraft(UUID.randomUUID());
		d.setRelease(UUID.randomUUID());
		d.setVersion(3);
		d.setKind(kind);
		d.setCause(cause);
		d.setActedAt(AT);
		return d;
	}

	@Test
	void everyKeyRoundTripsAndNoColumnIsInTheRecord() {
		SubmissionActData d = columns(SubmissionActKind.FINALIZE, ActCause.USER);
		d.setActor(UUID.randomUUID());
		d.setCausedBy(UUID.randomUUID());
		d.setActorName("Writer One");
		d.setActorEmail("writer@example.com");
		d.setCapacity(ConfirmationCapacity.AUTHOR);
		d.setReasonText("after review");
		d.setManifestSha256("ab".repeat(32));
		d.setConfirmationRecord(ConfirmationRecord.NONE);
		d.setByVersion(4);
		d.setFromState(SubmissionLegacyState.WITHDRAWN);
		d.setToState(SubmissionState.CANCELLED);
		d.setLatestVersion(3);
		d.setBeforeHash("01".repeat(32));
		d.setAfterHash("02".repeat(32));
		d.setWithdrawnDate(AT.minusDays(2));
		d.setWithdrawnBy(UUID.randomUUID());
		d.setWithdrawReason("deficiency not addressed");

		Map<String, Object> rec = d.toRecordData();
		assertEquals(Set.of("actorName", "actorEmail", "capacity", "reasonText", "manifestSha256",
				"confirmationRecord", "byVersion", "fromState", "toState", "latestVersion", "beforeHash", "afterHash",
				"withdrawnDate", "withdrawnBy", "withdrawReason"), rec.keySet());
		SubmissionActData r = read(d);
		assertTrue(d.getWithdrawnDate().isEqual(r.getWithdrawnDate()));
		r.setWithdrawnDate(d.getWithdrawnDate());
		assertEquals(d, r);
	}

	@Test
	void aSchemaVersionOtherThanZeroIsRefused() {
		SubmissionActData d = columns(SubmissionActKind.CANCEL, ActCause.USER);
		assertThrows(IllegalStateException.class, () -> SubmissionActData.dataFromRecord(d.toRecordData(), 1,
				d.getUuid(), d.getOrg(), d.getSubmission(), null, d.getRelease(), 1, d.getKind(), UUID.randomUUID(),
				d.getCause(), null, AT));
	}

	/** FDAW-1 F-26 item 3: a SYSTEM or MIGRATION act names no person, so its record holds no person key. */
	@Test
	void aSystemAndAMigratedActCarryNoPersonKey() {
		SubmissionActData superseded = columns(SubmissionActKind.SUPERSEDED, ActCause.SYSTEM);
		superseded.setByVersion(4);
		assertEquals(Set.of("byVersion"), superseded.toRecordData().keySet());
		SubmissionActData migrated = columns(SubmissionActKind.MIGRATED, ActCause.MIGRATION);
		migrated.setFromState(SubmissionLegacyState.ACTIVE);
		migrated.setToState(SubmissionState.FINAL);
		migrated.setLatestVersion(2);
		Map<String, Object> rec = migrated.toRecordData();
		for (String person : Set.of("actorName", "actorEmail", "capacity", "actor")) {
			assertFalse(rec.containsKey(person), person);
		}
	}

	/** A record written by V107's SQL (string values, unknown keys) reads through the class. */
	@Test
	void aMigrationWrittenRecordReads() {
		Map<String, Object> rec = Map.of("fromState", "ACTIVE", "toState", "FINAL", "latestVersion", 2, "beforeHash", "aa",
				"afterHash", "bb", "laterKey", "ignored");
		SubmissionActData r = SubmissionActData.dataFromRecord(rec, 0, UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), null, UUID.randomUUID(), 2, SubmissionActKind.MIGRATED, null, ActCause.MIGRATION, null,
				AT);
		assertEquals(SubmissionLegacyState.ACTIVE, r.getFromState());
		assertEquals(SubmissionState.FINAL, r.getToState());
		assertEquals(2, r.getLatestVersion());
		assertNull(r.getActorName());
		assertNull(r.getDraft());
	}
}
