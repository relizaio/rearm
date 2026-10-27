/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.util.UUID;

/**
 * A product release met again while it is being unwound higher on the same merge stack: only a
 * cycle in the stored release graph does that (SCORE-15 ADR-12). The product merge that meets it
 * leaves that one entry out ({@code ChildBomStatus.SKIPPED_CYCLE}) and completes; it is never a
 * failed child, else a cyclic graph would fail the product permanently (TEA-14, decision 1).
 */
public class ReleaseGraphCycleException extends RelizaException {

	private static final long serialVersionUID = 4127310981253667013L;

	private final UUID release;
	private final UUID metWhileMerging;

	public ReleaseGraphCycleException(UUID release, UUID metWhileMerging) {
		super("release graph cycle at release " + release + ", met while merging release " + metWhileMerging);
		this.release = release;
		this.metWhileMerging = metWhileMerging;
	}

	/** The release met again. */
	public UUID release() {
		return release;
	}

	/** The release being merged when it was met again, innermost on the stack. */
	public UUID metWhileMerging() {
		return metWhileMerging;
	}
}
