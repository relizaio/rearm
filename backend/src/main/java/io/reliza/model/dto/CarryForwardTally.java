/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

/**
 * Outcome of one rebuild's findings carry-forward pass.
 *
 * <p>The three decline counters matter as much as {@code seeded}: unmatched deliverables fall back
 * to today's behaviour silently, so these are the only way to see the pairing heuristic
 * underperforming before a customer does. Returned rather than logged-only so tests can pin them.
 *
 * <p>{@code seeded} counts FINDINGS WRITTEN, not pairings attempted -- a predecessor that was itself
 * unscanned pairs fine and carries nothing, and counting that as a seed made this instrument read
 * 100% success in exactly the case it exists to catch.
 *
 * <p>{@code seedWriteFailed} is kept apart from {@code alreadyScanned}: a scan winning the race is
 * benign (the replacement holds an authoritative result), whereas a seed write THROWING leaves the
 * replacement collapsed to zero. Folding the two hid a real failure inside the healthy shape.
 *
 * <p>Lives here rather than on either service because BOTH produce one: the deliverable arm in
 * {@code DeliverableService} and the release-direct / SCE arms in {@code ArtifactService}. Declaring
 * it on DeliverableService inverted the layering -- DeliverableService depends on ArtifactService,
 * so ArtifactService's public signature would have referenced a type owned by a service that already
 * depends on it, with no import to make that visible to a reviewer.
 */
public record CarryForwardTally(int seeded, int candidates, int unpaired, int purlConflict,
		int bomCountAmbiguous, int noBom, int nothingToCarry, int alreadyScanned,
		int artifactMissing, int seedWriteFailed) {

}
