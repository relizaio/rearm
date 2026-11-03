/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * TPF-137 architecture 6.1 case 2: the uuid-space slicing of {@link FanOutWalkSlice}, against
 * PostgreSQL's order (the 16 bytes compared unsigned, done here byte by byte, not with
 * UUID.compareTo).
 */
class FanOutWalkSliceTest {

	/** PostgreSQL's uuid comparison: memcmp of the 16 bytes. */
	private static int pgCompare(UUID a, UUID b) {
		byte[] x = ByteBuffer.allocate(16).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits()).array();
		byte[] y = ByteBuffer.allocate(16).putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
		for (int i = 0; i < 16; i++) {
			int c = Integer.compare(x[i] & 0xff, y[i] & 0xff);
			if (c != 0) return c;
		}
		return 0;
	}

	@Test
	void theBoundsOfTheSpaceAndOfTheFirstSlices() {
		assertEquals(0, FanOutWalkSlice.sliceOf(FanOutWalkSlice.START));
		assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000000"), FanOutWalkSlice.START);
		assertEquals(UUID.fromString("00ffffff-ffff-ffff-ffff-ffffffffffff"), FanOutWalkSlice.endOf(0));
		assertEquals(UUID.fromString("01000000-0000-0000-0000-000000000000"), FanOutWalkSlice.startOf(1));
		assertEquals(FanOutWalkSlice.END, FanOutWalkSlice.endOf(255));
		assertEquals(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), FanOutWalkSlice.END);
		assertEquals(256, FanOutWalkSlice.SLICES);
	}

	@Test
	void everySliceContainsItsOwnBounds() {
		for (int k = 0; k < FanOutWalkSlice.SLICES; k++) {
			assertEquals(k, FanOutWalkSlice.sliceOf(FanOutWalkSlice.startOf(k)), "startOf " + k);
			assertEquals(k, FanOutWalkSlice.sliceOf(FanOutWalkSlice.endOf(k)), "endOf " + k);
			assertTrue(pgCompare(FanOutWalkSlice.startOf(k), FanOutWalkSlice.endOf(k)) < 0);
		}
	}

	@Test
	void theTopByteIsReadUnsigned() {
		assertEquals(127, FanOutWalkSlice.sliceOf(UUID.fromString("7fffffff-ffff-4fff-bfff-ffffffffffff")));
		assertEquals(128, FanOutWalkSlice.sliceOf(UUID.fromString("80000000-0000-4000-8000-000000000000")));
		assertEquals(255, FanOutWalkSlice.sliceOf(UUID.fromString("ff000000-0000-4000-8000-000000000000")));
	}

	@Test
	void theSlicesAreAdjacentInPostgresOrder() {
		for (int k = 0; k + 1 < FanOutWalkSlice.SLICES; k++) {
			assertTrue(pgCompare(FanOutWalkSlice.startOf(k + 1), FanOutWalkSlice.endOf(k)) > 0,
					"startOf(" + (k + 1) + ") after endOf(" + k + ")");
			assertTrue(pgCompare(FanOutWalkSlice.endOf(k), FanOutWalkSlice.startOf(k)) > 0);
		}
		// UUID.compareTo would put slice 128 before slice 127: the pitfall sliceOf avoids.
		assertTrue(FanOutWalkSlice.startOf(128).compareTo(FanOutWalkSlice.endOf(127)) < 0);
		assertTrue(pgCompare(FanOutWalkSlice.startOf(128), FanOutWalkSlice.endOf(127)) > 0);
	}

	@Test
	void noVersionFourUuidEqualsASliceStart() {
		for (int k = 0; k < FanOutWalkSlice.SLICES; k++) {
			UUID start = FanOutWalkSlice.startOf(k);
			assertEquals(0, start.version(), "a slice start has version nibble 0");
		}
		assertEquals(4, UUID.randomUUID().version());
	}
}
