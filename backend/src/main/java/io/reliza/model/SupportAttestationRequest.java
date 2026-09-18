/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One support attestation write, as submitted by a caller. A record rather than a
 * positional argument list because the write core now carries level, state, party,
 * justification, an assessment instant and three dates, and a nine-argument call
 * is where a caller silently swaps two same-typed parameters.
 *
 * <p><b>A null date means LEAVE THAT MILESTONE ALONE</b>, not "clear it". Writes
 * merge onto whatever is already stored, so a partial edit cannot wipe a milestone
 * the caller never mentioned.
 *
 * <p><b>{@link #clearMilestones()} is how a milestone is removed</b>, and it exists
 * because withdrawal is NOT an adequate substitute. An earlier revision argued a
 * mistaken date should be corrected by retracting the whole record. That leaves a
 * typo unfixable: null means leave alone, so a wrong {@code endOfLife} is re-merged
 * by every subsequent write; WITHDRAWN suppresses the entire component from exports
 * and from coverage rather than removing the one bad fact; and the ordering guard
 * then permanently blocks any later date that would sit after it. The only remaining
 * exits were hand-editing JSONB or abandoning the component's attestation. An
 * affirmative regulatory claim that was never true must be removable on its own.
 *
 * <p>Clearing is still SUPERSEDING, not erasing: the removal writes a new audit row
 * carrying the after-image, so the history continues to show what was claimed and
 * when it was withdrawn. Setting and clearing the same milestone in one call is
 * rejected rather than resolved by precedence.
 *
 * <p><b>{@link #reason()} is why THIS WRITE happened; {@link #justification()} is the
 * basis for the level-of-support CLAIM.</b> Two different facts, deliberately two
 * fields. An earlier revision required a justification when clearing a milestone,
 * which wrote the removal note into the payload and overwrote the basis of any
 * negative level attestation on the same row -- so an export would have shipped
 * "removed a typo'd EOL" as the reason a third party's project was called abandoned.
 * {@code reason} is REQUIRED on a removal, optional otherwise, and never exported.
 *
 * <p>{@code assessedAt} is when the HUMAN did the assessment and is caller-supplied;
 * null falls back to now. It is not the same fact as the audit row's
 * {@code assertedDate}, which is when the system recorded the write.
 */
public record SupportAttestationRequest(
		LevelOfSupport levelOfSupport,
		SupportState state,
		SupportParty party,
		String justification,
		ZonedDateTime assessedAt,
		LocalDate endOfGuaranteedSupportDate,
		LocalDate endOfSupportDate,
		LocalDate endOfLifeDate,
		String supportNotes,
		Set<SupportMilestoneType> clearMilestones,
		String reason) {

	public SupportAttestationRequest {
		clearMilestones = (null == clearMilestones || clearMilestones.isEmpty())
				? Set.of()
				: Collections.unmodifiableSet(EnumSet.copyOf(clearMilestones));
	}
}
