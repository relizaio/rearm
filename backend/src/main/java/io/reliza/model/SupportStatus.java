/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Per-component support level. DERIVED, never persisted (see the FDA-Readiness-1
 * scope): computed from the stored support facts plus an asOf clock by
 * {@link #derive} so the value can never rot or contradict its own dates. Every
 * read and export surface calls this one method (single source of truth).
 *
 * <p>Values align to FDA's support adjectives and IMDRF N60's lifecycle stages.
 * SECURITY_ONLY (IMDRF Limited Support) and ABANDONED (inferred, no declared
 * date) are valid values reserved for later slices: SECURITY_ONLY needs a
 * security-only boundary date and ABANDONED an ENRICHED inference, so neither is
 * produced from the two stored dates alone. PR1 derives the four date-driven
 * states plus UNKNOWN.
 */
public enum SupportStatus {
	ACTIVELY_SUPPORTED,
	SECURITY_ONLY,
	END_OF_SUPPORT,
	END_OF_LIFE,
	ABANDONED,
	UNKNOWN;

	/**
	 * Single source of truth for the derived status. Pure: identical inputs
	 * always yield the same value. {@code asOf} is the clock (now for the live
	 * view; a cutoff for a future frozen snapshot). {@code source} is reserved
	 * for the ENRICHED-only ABANDONED inference (later slice) and does not affect
	 * the date-driven states.
	 *
	 * @param endOfSupportDate when all support ceases (transfer of risk), or null
	 * @param endOfLifeDate    when the component reaches end of life, or null
	 * @param source           provenance of the assertion (reserved), or null
	 * @param asOf             the clock the status is read against
	 * @return the derived status; UNKNOWN when no dates are on record
	 */
	public static SupportStatus derive(LocalDate endOfSupportDate, LocalDate endOfLifeDate,
			SupportSource source, LocalDate asOf) {
		// asOf is the clock every read/export/snapshot surface passes; a null here is a
		// caller contract violation, not a data state -- fail loud rather than NPE deep inside.
		Objects.requireNonNull(asOf, "asOf");
		// Boundaries are inclusive: a milestone takes effect ON its date (asOf == date
		// -> the milestone status). EOL outranks EOS when both have passed. A future EOS
		// (or a lone future EOL) reads as ACTIVELY_SUPPORTED -- the manufacturer has
		// declared a horizon but support is current. (The coherence guard forbids EOS
		// after EOL, so "EOL past while EOS future" cannot be stored.)
		if (endOfLifeDate != null && !asOf.isBefore(endOfLifeDate)) {
			return END_OF_LIFE;
		}
		if (endOfSupportDate != null && !asOf.isBefore(endOfSupportDate)) {
			return END_OF_SUPPORT;
		}
		if (endOfSupportDate != null || endOfLifeDate != null) {
			return ACTIVELY_SUPPORTED;
		}
		return UNKNOWN;
	}
}
