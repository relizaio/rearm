/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.model.IssuerClass;
import io.reliza.model.SafetyContextData.GateRule;
import io.reliza.model.SafetyContextData.LevelChange;
import io.reliza.model.SafetyContextData.LevelNote;
import io.reliza.model.TrustAction;

class SafetyContextTrustPolicyTest {

	private static void assertGate(TrustAction action, GateRule rule, SafetyContextTrustPolicy.Gate gate) {
		assertEquals(action, gate.action());
		assertEquals(rule, gate.rule());
	}

	@Test
	void downgradeStagesForEveryIssuer() {
		for (IssuerClass issuer : IssuerClass.values()) {
			assertGate(TrustAction.STAGE, GateRule.DOWNGRADE,
				SafetyContextTrustPolicy.gate(issuer, LevelNote.OK, 1, LevelChange.DOWNGRADE));
		}
	}

	@Test
	void thirdPartyStages() {
		assertGate(TrustAction.STAGE, GateRule.THIRD_PARTY_STAGE,
			SafetyContextTrustPolicy.gate(IssuerClass.THIRD_PARTY, LevelNote.OK, 3, LevelChange.NEW));
	}

	@Test
	void selfAutoAcceptsIncludingBottomAndCustom() {
		assertGate(TrustAction.AUTO_ACCEPT, GateRule.SELF_AUTO,
			SafetyContextTrustPolicy.gate(IssuerClass.SELF, LevelNote.OK, 0, LevelChange.NEW));
		assertGate(TrustAction.AUTO_ACCEPT, GateRule.SELF_AUTO,
			SafetyContextTrustPolicy.gate(IssuerClass.SELF, LevelNote.CUSTOM_SCHEME, null, LevelChange.UNCOMPARABLE));
	}

	@Test
	void vendorRows() {
		assertGate(TrustAction.STAGE, GateRule.VENDOR_CUSTOM,
			SafetyContextTrustPolicy.gate(IssuerClass.VENDOR, LevelNote.CUSTOM_SCHEME, null, LevelChange.NEW));
		assertGate(TrustAction.STAGE, GateRule.VENDOR_DECOMPOSITION,
			SafetyContextTrustPolicy.gate(IssuerClass.VENDOR, LevelNote.DECOMPOSITION, null, LevelChange.NEW));
		assertGate(TrustAction.STAGE, GateRule.VENDOR_UNMAPPED,
			SafetyContextTrustPolicy.gate(IssuerClass.VENDOR, LevelNote.UNMAPPED, null, LevelChange.NEW));
		assertGate(TrustAction.STAGE, GateRule.VENDOR_BOTTOM,
			SafetyContextTrustPolicy.gate(IssuerClass.VENDOR, LevelNote.OK, 0, LevelChange.NEW));
		assertGate(TrustAction.AUTO_ACCEPT, GateRule.VENDOR_AUTO,
			SafetyContextTrustPolicy.gate(IssuerClass.VENDOR, LevelNote.OK, 2, LevelChange.UPGRADE));
	}

	@Test
	void levelChangeAgainstActive() {
		assertEquals(LevelChange.NEW, SafetyContextTrustPolicy.levelChange(false, null, null, 2, "Class C"));
		assertEquals(LevelChange.UNCHANGED, SafetyContextTrustPolicy.levelChange(true, 2, "Class C", 2, "Class C"));
		assertEquals(LevelChange.UPGRADE, SafetyContextTrustPolicy.levelChange(true, 1, "Class B", 2, "Class C"));
		assertEquals(LevelChange.DOWNGRADE, SafetyContextTrustPolicy.levelChange(true, 2, "Class C", 1, "Class B"));
		// Unranked on either side cannot be compared, so the downgrade row never fires for it.
		assertEquals(LevelChange.UNCOMPARABLE, SafetyContextTrustPolicy.levelChange(true, null, null, 1, "SIL 1"));
		assertEquals(LevelChange.UNCOMPARABLE, SafetyContextTrustPolicy.levelChange(true, 1, "SIL 1", null, null));
	}

	@Test
	void overrideNeedsWritePermission() {
		SafetyContextTrustPolicy.Issuer ignored = SafetyContextTrustPolicy.effectiveIssuer(
			IssuerClass.THIRD_PARTY, IssuerClass.SELF, false);
		assertEquals(IssuerClass.THIRD_PARTY, ignored.effective());
		assertTrue(ignored.overrideIgnored());
		assertFalse(ignored.overrideHonoured());

		SafetyContextTrustPolicy.Issuer honoured = SafetyContextTrustPolicy.effectiveIssuer(
			IssuerClass.THIRD_PARTY, IssuerClass.SELF, true);
		assertEquals(IssuerClass.SELF, honoured.effective());
		assertTrue(honoured.overrideHonoured());
		assertTrue(honoured.elevatedByOverride());

		SafetyContextTrustPolicy.Issuer lowered = SafetyContextTrustPolicy.effectiveIssuer(
			IssuerClass.SELF, IssuerClass.THIRD_PARTY, true);
		assertEquals(IssuerClass.THIRD_PARTY, lowered.effective());
		assertFalse(lowered.elevatedByOverride());
	}

	@Test
	void overrideEqualToDerivedIsANoOpWithoutWarning() {
		SafetyContextTrustPolicy.Issuer same = SafetyContextTrustPolicy.effectiveIssuer(
			IssuerClass.SELF, IssuerClass.SELF, false);
		assertEquals(IssuerClass.SELF, same.effective());
		assertFalse(same.overrideIgnored());
		assertFalse(same.overrideHonoured());
	}
}
