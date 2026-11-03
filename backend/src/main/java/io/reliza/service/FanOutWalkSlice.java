/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

/**
 * The uuid-space slicing of the change-driven fan-out walk (TPF-137). The space is cut into
 * {@link #SLICES} fixed slices by the top byte: slice k is the interval
 * (k:00000000-0000-0000-0000-000000000000, k:ffffff-ffff-ffff-ffff-ffffffffffff], so one slice
 * statement touches at most 1/256 of artifact_sbom_components_unique whatever the match density.
 * PostgreSQL orders uuid as 16 unsigned bytes; the unsigned shift in {@link #sliceOf} agrees with
 * that order (UUID.compareTo compares the two longs signed and would put slices 128-255 first).
 * No arithmetic on a cursor is needed: a resumed slice continues from the last returned
 * canonical, a drained slice jumps to {@code startOf(k + 1)}.
 */
final class FanOutWalkSlice {

	static final int SLICES = 256;

	/** The cursor before the first slice: the all-zero uuid, which no version-4 uuid equals. */
	static final UUID START = new UUID(0L, 0L);

	/** The cursor after the last slice: the all-ones uuid; a persisted cursor equal to it means the walk is complete. */
	static final UUID END = new UUID(-1L, -1L);

	private FanOutWalkSlice() {}

	/** The slice a cursor lies in: its top byte, read unsigned. */
	static int sliceOf(UUID cursor) {
		return (int) (cursor.getMostSignificantBits() >>> 56);
	}

	/** The exclusive lower bound of slice k (its version nibble is 0, so never a version-4 uuid). */
	static UUID startOf(int slice) {
		return new UUID(((long) slice) << 56, 0L);
	}

	/** The inclusive upper bound of slice k; endOf(255) is {@link #END}. */
	static UUID endOf(int slice) {
		return new UUID((((long) slice) << 56) | 0x00FFFFFFFFFFFFFFL, -1L);
	}
}
