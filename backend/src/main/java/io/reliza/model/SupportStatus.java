/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The DERIVED per-component support state: what the stored dates entail as of a
 * clock, and nothing more. Produced only by {@link #derive}, never stored.
 *
 * <p><b>This enum no longer carries the manufacturer's claim.</b> What FDA asks for
 * -- "the level of support provided through monitoring and maintenance from the
 * software component manufacturer" -- is a statement about what an upstream
 * maintainer is actually DOING, which no date can establish. That is now
 * {@link LevelOfSupport}, attested by a human and stored in
 * {@link SupportData#levelOfSupport()}.
 *
 * <p>The two are surfaced side by side and are NEVER reconciled. A maintainer can
 * publish a far-future end-of-support and have abandoned the library in practice;
 * when the human says so and the dates disagree, both are shown and a reviewer
 * judges. Collapsing them in code would discard exactly the signal worth having.
 *
 * <p>Three constants are RETAINED BUT NEVER RETURNED. This enum is published in the CE
 * repository (`relizaio/rearm`, on `main` and in released tag 26.08.95) with all six
 * members, so removing a value would break a schema self-hosted consumers already hold.
 * Note the rationale is about CE specifically: these values are NOT on rearm-saas `main`,
 * because #473 removed them there, and a reviewer checking only this repo will conclude
 * the retention is unjustified. It is not.
 * <ul>
 *   <li>{@link #ACTIVELY_SUPPORTED} and {@link #ABANDONED} -- moved to
 *       {@link LevelOfSupport} as attested claims.</li>
 *   <li>{@link #END_OF_LIFE} -- retired by operator decision D5 (2026-09-03).
 *       CycloneDX defines end-of-life as END OF SALE, which routinely precedes end
 *       of support by years, so it is not a support state at all. A component going
 *       off sale says nothing about whether it is still patched.</li>
 * </ul>
 *
 * <p>What remains derivable is {@link #SECURITY_ONLY}, {@link #END_OF_SUPPORT} and
 * {@link #UNKNOWN}.
 */
public enum SupportStatus {
	/** @deprecated attested, not derived -- see {@link LevelOfSupport#ACTIVELY_MAINTAINED}. */
	@Deprecated
	ACTIVELY_SUPPORTED,
	SECURITY_ONLY,
	END_OF_SUPPORT,
	/** @deprecated end-of-life means END OF SALE (D5) and is not a support state. */
	@Deprecated
	END_OF_LIFE,
	/** @deprecated attested, not derived -- see {@link LevelOfSupport#ABANDONED}. */
	@Deprecated
	ABANDONED,
	UNKNOWN;

	/**
	 * Single source of truth for the derived status. Pure: identical inputs
	 * always yield the same value. {@code asOf} is the clock (now for the live
	 * view; a cutoff for a future frozen snapshot).
	 *
	 * <p>The {@code source} parameter this method used to take is GONE. It was
	 * reserved for a future ABANDONED inference, and that reservation is withdrawn:
	 * ABANDONED is a claim about a named third party's business, and a machine
	 * inferring it -- from, say, commit silence -- and publishing it under the
	 * manufacturer's name is precisely what section 3's governing principle
	 * forbids. If enrichment ever produces such a signal it belongs in the
	 * contradiction path for human review, never as a derived status. The removed
	 * parameter also never affected the outcome.
	 *
	 * @param endOfGuaranteedSupportDate when bug fixes stop but security fixes
	 *                                   continue, or null
	 * @param endOfSupportDate           when all support ceases (transfer of
	 *                                   risk), or null
	 * @param asOf                       the clock the status is read against
	 * @return the derived status; UNKNOWN whenever no milestone has yet passed --
	 *         including when future dates ARE on record. Never
	 *         {@link #ACTIVELY_SUPPORTED} or {@link #ABANDONED}, which are
	 *         attested-only.
	 */
	public static SupportStatus derive(LocalDate endOfGuaranteedSupportDate,
			LocalDate endOfSupportDate, LocalDate asOf) {
		// asOf is the clock every read/export/snapshot surface passes; a null here is a
		// caller contract violation, not a data state -- fail loud rather than NPE deep inside.
		Objects.requireNonNull(asOf, "asOf");
		// Boundaries are inclusive: a milestone takes effect ON its date (asOf == date ->
		// the milestone status). EOS outranks EOGS when both have passed; the coherence
		// guard enforces EOGS <= EOS, so a later milestone cannot be past while an earlier
		// one is still future. A milestone still in the future establishes nothing about
		// today -- see the UNKNOWN return below.
		//
		// END-OF-LIFE IS DELIBERATELY ABSENT (operator decision D5, 2026-09-03). CycloneDX
		// defines it as END OF SALE, which routinely precedes end of support by years. A
		// product leaving the price list is not a support state and must not derive one.
		if (endOfSupportDate != null && !asOf.isBefore(endOfSupportDate)) {
			return END_OF_SUPPORT;
		}
		if (endOfGuaranteedSupportDate != null && !asOf.isBefore(endOfGuaranteedSupportDate)) {
			return SECURITY_ONLY;
		}
		// NO PASSED MILESTONE MEANS UNKNOWN, NOT ACTIVELY_SUPPORTED. A declared future date
		// says only that the maintainer published a horizon; it says nothing about whether
		// anyone is maintaining the component TODAY. A library can carry "end of life 2030"
		// and have been abandoned in practice two years ago. Returning ACTIVELY_SUPPORTED
		// here made ReARM assert current upstream maintenance on the manufacturer's behalf
		// from evidence that does not support it -- and made it loudest for exactly the
		// components a reviewer should look at hardest.
		//
		// ACTIVELY_SUPPORTED is therefore NOT derivable at all. It is a claim about what a
		// third party is doing, so it can only be ATTESTED by a human -- see
		// SupportData#levelOfSupport, typed LevelOfSupport. ABANDONED moved there too; the
		// enrichment inference once reserved for it is withdrawn -- see the note on derive().
		return UNKNOWN;
	}
}
