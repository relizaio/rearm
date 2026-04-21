/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.VdrSnapshotType;

/**
 * Pins the deterministic VDR {@code serialNumber} seed shape used by
 * {@link ReleaseService#buildVdrSerialNumber(UUID, VdrSnapshotType, String, ZonedDateTime, Boolean)}.
 *
 * Rationale: the seed format is part of the external VDR contract. Any change silently
 * re-keys every previously-exported VDR and breaks consumer idempotency. If this test
 * fails, the seed shape changed — update the consumer-facing docs and bump any migration
 * guidance before adjusting the expected UUIDs.
 */
public class ReleaseServiceVdrSerialTest {

	private static final UUID FIXED_RELEASE = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final ZonedDateTime FIXED_CUTOFF = ZonedDateTime.parse("2024-01-02T03:04:05Z");

	@Test
	void livePrepared_serial_isStable() {
		// No snapshot type, no snapshot value, no cutoff, no suppressed flag.
		assertEquals("urn:uuid:8dcee36b-37bf-39a0-9344-3bd30e201a9c",
				ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, null, null, null, null));
	}

	@Test
	void dateSnapshot_serial_isStable() {
		assertEquals("urn:uuid:f301d826-309c-3ec8-8d3d-85812bceef50",
				ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, VdrSnapshotType.DATE, null,
						FIXED_CUTOFF, Boolean.FALSE));
	}

	@Test
	void lifecycleSnapshotWithSuppressed_serial_isStable() {
		assertEquals("urn:uuid:b4c17b50-95ac-3152-9a93-6140acadd6a3",
				ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, VdrSnapshotType.LIFECYCLE, "release",
						FIXED_CUTOFF, Boolean.TRUE));
	}

	@Test
	void serial_isIdempotentForSameInputs() {
		String a = ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, VdrSnapshotType.DATE, null,
				FIXED_CUTOFF, Boolean.FALSE);
		String b = ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, VdrSnapshotType.DATE, null,
				FIXED_CUTOFF, Boolean.FALSE);
		assertEquals(a, b);
	}

	@Test
	void serial_differsWhenIncludeSuppressedFlipped() {
		String without = ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, VdrSnapshotType.DATE, null,
				FIXED_CUTOFF, Boolean.FALSE);
		String with = ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, VdrSnapshotType.DATE, null,
				FIXED_CUTOFF, Boolean.TRUE);
		assertNotEquals(without, with);
	}

	@Test
	void serial_differsPerRelease() {
		String a = ReleaseService.buildVdrSerialNumber(FIXED_RELEASE, null, null, null, null);
		String b = ReleaseService.buildVdrSerialNumber(UUID.fromString("00000000-0000-0000-0000-000000000002"),
				null, null, null, null);
		assertNotEquals(a, b);
	}
}
