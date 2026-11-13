/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.List;

/**
 * What moved since a draft's build (FDAW-9 3.6, FDAW-30 3.6): computed on every read, never stored.
 * {@code fingerprintEqual} says whether the recomputed fingerprint equals the build's; {@code movedLines}
 * names the fingerprint lines that differ, added or vanished; {@code count} counts the writes behind
 * them, each once. With no build: 0, false and no lines.
 */
public record ChangesSinceBuild(int count, boolean fingerprintEqual, List<String> movedLines) {

	/** The answer for a draft without a build. */
	public static final ChangesSinceBuild NOT_BUILT = new ChangesSinceBuild(0, false, List.of());
}
