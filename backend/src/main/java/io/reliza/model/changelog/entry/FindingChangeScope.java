/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.changelog.entry;

/**
 * Read scope for the over-time finding-change timeline ({@code findingChangeTimelineByDate}).
 *
 * <ul>
 *   <li>{@code RELEASE_ANCHORED} (default, back-compat) -- events are bounded to the releases
 *       PRODUCED in the query window (the historical behavior). A finding change on a release
 *       shipped before the window is not surfaced.</li>
 *   <li>{@code ALL_POSTURE} -- every finding change whose {@code changeDate} falls in the window
 *       for any authorized component, INCLUDING re-scan-driven changes (a newly-disclosed CVE
 *       matched against an already-shipped release emits an event dated at re-scan time even
 *       though its release predates the window). Surfaces the org-wide posture movement the
 *       release-anchored view structurally misses. Org scope only; a v3 (branch-grain) read.</li>
 * </ul>
 */
public enum FindingChangeScope {
	RELEASE_ANCHORED,
	ALL_POSTURE;
}
