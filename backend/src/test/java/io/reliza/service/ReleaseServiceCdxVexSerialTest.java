/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.VdrSnapshotType;

/**
 * Pins the deterministic VEX {@code serialNumber} seed shape used by
 * {@link ReleaseService#buildCdxVexSerialNumber}.
 *
 * Contract:
 * <ul>
 *   <li>Same inputs → same urn:uuid (idempotent).</li>
 *   <li>VDR and VEX for the same release snapshot get <b>distinct</b> urn:uuids (two documents).</li>
 *   <li>Toggling {@code includeInTriage} produces a distinct VEX serial.</li>
 *   <li>Toggling {@code includeSuppressed} produces a distinct VEX serial.</li>
 *   <li>Output matches the CDX 1.6 serialNumber regex.</li>
 * </ul>
 */
public class ReleaseServiceCdxVexSerialTest {

	private static final UUID FIXED_RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final ZonedDateTime FIXED_CUTOFF = ZonedDateTime.parse("2024-01-02T03:04:05Z");

	@Test
	void vex_serial_matchesCdxPattern() {
		String serial = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.LIFECYCLE, "release", FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertTrue(serial.matches("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
				() -> "serial violates CDX urn:uuid pattern: " + serial);
	}

	@Test
	void vex_serial_isIdempotentForSameInputs() {
		String a = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null, FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String b = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null, FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertEquals(a, b);
	}

	@Test
	void vex_serial_differsFromVdrSerial_forIdenticalSnapshot() {
		// Most important property: VDR and VEX for the same release are DIFFERENT documents
		// and must have different urn:uuid identifiers.
		String vdr = ReleaseService.buildVdrSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.LIFECYCLE, "release", FIXED_CUTOFF, Boolean.FALSE);
		String vex = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.LIFECYCLE, "release", FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertNotEquals(vdr, vex);
	}

	@Test
	void vex_serial_differsWhenIncludeInTriageFlipped() {
		String withoutTriage = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null, FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String withTriage = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null, FIXED_CUTOFF, Boolean.FALSE, Boolean.TRUE);
		assertNotEquals(withoutTriage, withTriage);
	}

	@Test
	void vex_serial_differsWhenIncludeSuppressedFlipped() {
		String without = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null, FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String with = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null, FIXED_CUTOFF, Boolean.TRUE, Boolean.FALSE);
		assertNotEquals(without, with);
	}

	@Test
	void vex_serial_differsPerRelease() {
		String a = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, null, null, null, null, null);
		String b = ReleaseService.buildCdxVexSerialNumber(
				UUID.fromString("00000000-0000-0000-0000-000000000002"),
				null, null, null, null, null);
		assertNotEquals(a, b);
	}

	@Test
	void vex_serial_approvalSnapshot_isStableAndDistinctFromLifecycleAndDate() {
		// SaaS APPROVAL snapshot must produce a valid, idempotent serial.
		String approval = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.APPROVAL, "production-signoff",
				FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String approvalAgain = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.APPROVAL, "production-signoff",
				FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertEquals(approval, approvalAgain);
		assertTrue(approval.matches("^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));

		// Must not collide with LIFECYCLE / DATE snapshots at the same cutoff.
		String lifecycle = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.LIFECYCLE, "production-signoff",
				FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String date = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.DATE, null,
				FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertNotEquals(approval, lifecycle);
		assertNotEquals(approval, date);
		assertNotEquals(lifecycle, date);
	}

	@Test
	void vex_serial_approvalSnapshot_differsByApprovalName() {
		// Two different approval entries on the same release → distinct VEX serials.
		String prod = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.APPROVAL, "production-signoff",
				FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		String stage = ReleaseService.buildCdxVexSerialNumber(
				FIXED_RELEASE, VdrSnapshotType.APPROVAL, "staging-signoff",
				FIXED_CUTOFF, Boolean.FALSE, Boolean.FALSE);
		assertNotEquals(prod, stage);
	}
}
