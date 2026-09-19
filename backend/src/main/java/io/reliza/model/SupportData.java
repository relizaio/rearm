/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.reliza.common.Utils;

/**
 * The whole per-component support attestation, stored as
 * {@code sbom_component_support.support_data} JSONB (V83).
 *
 * <p><b>The row's existence means a human ASSESSED the component.</b> It does
 * NOT mean any date was found. "I looked and the upstream publishes nothing" is
 * a diligence record in its own right and is stored as a row whose
 * {@link #milestones()} is empty -- precisely the case V901's NOT NULL milestone
 * date could not represent.
 *
 * <p><b>{@code levelOfSupport} is a STORED HUMAN ATTESTATION, not a cache of
 * {@link SupportStatus#derive}.</b> FDA asks for "the level of support provided
 * through monitoring and maintenance from the software component manufacturer"
 * -- a claim about what the upstream maintainer is actually doing, which cannot
 * be computed from dates. A library can carry a far-future end-of-life on paper
 * and have been abandoned in practice two years ago. Keeping this derived left
 * {@code ABANDONED}, FDA's own example value, permanently unwritable.
 *
 * <p><b>The attested and derived values must never be silently reconciled.</b>
 * When a human records "no longer maintained" and the dates imply otherwise,
 * both are shown and the reviewer sees the disagreement. Collapsing them in code
 * would re-create, in the opposite direction, the bug this replaces.
 *
 * <p>Staleness is handled by DATING the claim, never by suppressing it: a level
 * is never displayed or exported bare, always alongside {@code assessedAt} and
 * the attester. A dated claim can be judged by a reviewer; an undated one
 * cannot. Review intervals FLAG, they never block an export.
 *
 * <p>{@code assessmentSource} is the provenance of the ASSESSMENT ACT (who last
 * wrote this record), which is a different fact from each milestone's own
 * source. It is what makes "assessed, nothing published" attributable at all:
 * that record carries no milestones, so it has no milestone provenance to read.
 *
 * <p>Timestamps are RFC-3339 UTC instant strings -- see
 * {@link SupportMilestoneFact} for why text rather than temporal types.
 *
 * <p><b>An unrecognised enum value FAILS LOUDLY. It is never degraded to null.</b>
 * An earlier revision annotated every scalar enum with
 * {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} so that a payload from a newer build would
 * not break an older one. That was WRONG in a way that only shows up on the write
 * path: degrade-to-null is a READ property, and the write core re-serializes whatever
 * it read. An old build touching a new-format row -- even to correct an unrelated note
 * -- would null the value it did not recognise, persist the null, and file the nulled
 * after-image into the append-only audit as the record of record. A regulatory claim
 * would disappear from current state AND from its own history, with no error anywhere.
 * The reasoning already written on {@code state} applies to every field here; the
 * exemption was the correct case and the annotation was the mistake.
 *
 * <p>The cost of failing loudly is blast radius, because these rows are bulk-loaded
 * for a whole release at once. That is handled by PER-ROW ISOLATION on read rather
 * than by weakening the types -- see {@link #parse}. One unreadable row is
 * excluded from the export and from coverage; it never fails the release.
 *
 * <p>A recorded CONTRADICTION (BEAR later finding a published date for a
 * component attested as "nothing published") belongs here as an additional
 * field when that slice is built. Because this is JSONB, adding it is a code
 * change and not a migration, so it is deliberately not pre-built.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SupportData(
		LevelOfSupport levelOfSupport,
		// DELIBERATELY NOT degraded to null on an unknown value, unlike its neighbours.
		// A null state is defaulted to ATTESTED by the compact constructor, so silently
		// accepting an unrecognised state would turn a future retraction-like value into
		// a live claim on a rollback -- republishing something a manufacturer withdrew.
		// Failing loudly is the safe direction for this one field.
		SupportState state,
		SupportParty party,
		SupportSource assessmentSource,
		String assessedAt,
		UUID assertedBy,
		String justification,
		Map<SupportMilestoneType, SupportMilestoneFact> milestones) implements Serializable {


	public SupportData {
		// A row read back with a null state predates nothing -- there is no such
		// data -- but defaulting keeps a hand-written or partially-built payload
		// from making state null, which every reader would then have to guard.
		if (null == state) state = SupportState.ATTESTED;
		milestones = (null == milestones || milestones.isEmpty())
				? Map.of()
				: Collections.unmodifiableMap(new EnumMap<>(milestones));
	}

	/**
	 * Parse a stored payload with the same mapper the entity path uses, so a payload
	 * this rejects is exactly one that would otherwise have thrown mid result-set and
	 * failed every component in the same batch.
	 *
	 * <p>THROWS on an unreadable payload -- most plausibly an unrecognised enum value
	 * after a rollback across an enum addition, or a hand-edited row. Callers doing a
	 * BULK read must catch, LOG WITH THE CAUSE, and skip that component; the cause is
	 * the only thing that says which field and value failed, and without it the
	 * condition is undiagnosable.
	 *
	 * <p>A caller must treat the failure as "exclude this component", never as "no
	 * attestation": the two are indistinguishable downstream, and silently downgrading
	 * an unreadable regulatory claim to "not assessed" is the failure this class exists
	 * to prevent. Leave the row untouched so a newer build can still read it.
	 */
	public static SupportData parse(String json) {
		try {
			return Utils.JSONB_OM.readValue(json == null ? "{}" : json, SupportData.class);
		} catch (JsonProcessingException e) {
			// Unchecked so the bulk read paths stay readable, but the cause is preserved:
			// it is the only thing that names the field and value that failed.
			throw new IllegalStateException("unreadable support attestation payload", e);
		}
	}

	/** The date asserted for {@code type}, or null when this attestation carries none. */
	public LocalDate milestoneDate(SupportMilestoneType type) {
		SupportMilestoneFact f = milestones.get(type);
		return null == f ? null : f.dateValue();
	}

	/**
	 * True when a manufacturer made this assertion by hand, as opposed to it
	 * arriving from a supplier BOM or machine enrichment. Drives the coverage
	 * numerator: counting machine sources there would inflate a pre-submission
	 * readiness signal toward 100% without anyone having attested anything.
	 *
	 * <p>Checks {@link #assessmentSource()} as well as the per-milestone sources
	 * because the case that matters most carries no milestones at all -- "I
	 * looked, nothing is published" -- and so cannot be recognised from milestone
	 * provenance. Inferring MANUAL from an empty milestone map instead would be a
	 * guess: nothing structurally stops a supplier path writing an empty row.
	 */
	public boolean hasManualAttestation() {
		return SupportSource.MANUAL == assessmentSource
				|| milestones.values().stream().anyMatch(f -> SupportSource.MANUAL == f.source());
	}
}
