/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import io.reliza.model.IssuerClass;
import io.reliza.model.SafetyContextData.GateRule;
import io.reliza.model.SafetyContextData.LevelChange;
import io.reliza.model.SafetyContextData.LevelNote;
import io.reliza.model.TrustAction;

/**
 * Trust decisions for SAFETY_CONTEXT imports, pure so every row is unit-testable.
 *
 * <p>Issuer policy: an upload's {@code userIssuerClassOverride} is honoured only when the
 * uploading actor holds FINDING_ANALYSIS_WRITE on the org (manual acceptance needs the same);
 * otherwise the binding-derived class applies. VEX keeps its own unconditional override.
 *
 * <p>Gate, first matching row wins (ranks compare only within one scheme):
 * <ol start="0">
 * <li>a lower rank than the ACTIVE record at the same identity stages for every issuer (skipped
 * when either rank is unknown);</li>
 * <li>THIRD_PARTY stages;</li>
 * <li>SELF auto-accepts, its scheme's bottom level included;</li>
 * <li>VENDOR stages a custom scheme, an unmapped level, an ISO 26262 decomposition, or the
 * scheme's bottom level (IEC 62304 Class A included);</li>
 * <li>VENDOR otherwise auto-accepts.</li>
 * </ol>
 */
public final class SafetyContextTrustPolicy {

	/** The effective issuer after the override rule. */
	public record Issuer(IssuerClass effective, boolean overrideHonoured, boolean elevatedByOverride,
			boolean overrideIgnored) {}

	/** One gate outcome. */
	public record Gate(TrustAction action, GateRule rule) {}

	private SafetyContextTrustPolicy() {}

	public static Issuer effectiveIssuer(IssuerClass derived, IssuerClass override, boolean actorHoldsWrite) {
		if (override == null || override == derived) return new Issuer(derived, false, false, false);
		if (!actorHoldsWrite) return new Issuer(derived, false, false, true);
		return new Issuer(override, true, trust(override) > trust(derived), false);
	}

	public static Gate gate(IssuerClass issuer, LevelNote note, Integer rank, LevelChange change) {
		if (change == LevelChange.DOWNGRADE) return new Gate(TrustAction.STAGE, GateRule.DOWNGRADE);
		if (issuer == IssuerClass.THIRD_PARTY || issuer == null) {
			return new Gate(TrustAction.STAGE, GateRule.THIRD_PARTY_STAGE);
		}
		if (issuer == IssuerClass.SELF) return new Gate(TrustAction.AUTO_ACCEPT, GateRule.SELF_AUTO);
		if (note == LevelNote.CUSTOM_SCHEME) return new Gate(TrustAction.STAGE, GateRule.VENDOR_CUSTOM);
		if (note == LevelNote.DECOMPOSITION) return new Gate(TrustAction.STAGE, GateRule.VENDOR_DECOMPOSITION);
		if (note != LevelNote.OK || rank == null) return new Gate(TrustAction.STAGE, GateRule.VENDOR_UNMAPPED);
		if (rank == 0) return new Gate(TrustAction.STAGE, GateRule.VENDOR_BOTTOM);
		return new Gate(TrustAction.AUTO_ACCEPT, GateRule.VENDOR_AUTO);
	}

	/** The level compared with the ACTIVE record at the same identity. */
	public static LevelChange levelChange(boolean activeExists, Integer activeRank, String activeNormalized,
			Integer rank, String normalized) {
		if (!activeExists) return LevelChange.NEW;
		if (rank == null || activeRank == null) {
			return normalized != null && normalized.equals(activeNormalized)
				? LevelChange.UNCHANGED : LevelChange.UNCOMPARABLE;
		}
		int cmp = Integer.compare(rank, activeRank);
		return cmp == 0 ? LevelChange.UNCHANGED : cmp > 0 ? LevelChange.UPGRADE : LevelChange.DOWNGRADE;
	}

	private static int trust(IssuerClass c) {
		return switch (c) {
			case THIRD_PARTY -> 0;
			case VENDOR -> 1;
			case SELF -> 2;
		};
	}
}
