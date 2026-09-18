/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.DeviceLifecycle;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;

/**
 * A WITHDRAWN attestation must not be served as a live derived support status.
 *
 * The row keeps its milestone dates -- withdrawal supersedes, it does not erase -- so deriving
 * from them produced "End of support" for a claim the manufacturer had taken back, and the
 * component list rendered it as current fact. An operator running the walkthrough saw that chip
 * beside the same component the Not-disclosed filter counted as unattested: one screen
 * disagreeing with itself about whether a claim exists (board t20260909-061338-23148).
 *
 * The EXPORT never had this bug -- SupportBomInjector skips a WITHDRAWN row outright -- so this
 * is the list catching up with the served document, not a new rule.
 */
class SbomComponentSupportStatusTest {

	private static final String ASSESSED_AT = "2026-08-26T12:00:00Z";

	private SupportData support(SupportState state, LocalDate eos) {
		Map<SupportMilestoneType, SupportMilestoneFact> milestones =
				new EnumMap<>(SupportMilestoneType.class);
		if (eos != null) {
			milestones.put(SupportMilestoneType.END_OF_SUPPORT,
					new SupportMilestoneFact(eos.toString(), SupportSource.MANUAL, ASSESSED_AT, null, null));
		}
		return new SupportData(LevelOfSupport.NO_LONGER_MAINTAINED, state, SupportParty.THIRD_PARTY,
				SupportSource.MANUAL, ASSESSED_AT, null, "upstream announced end of maintenance",
				milestones);
	}

	private Map<String, Object> dto(SupportData support) {
		return SbomComponentDataFetcher.toComponentDto(new SbomComponent(), support, null);
	}

	/** A device whose own support runs well past the component's retracted date. */
	private static final DeviceLifecycle DEVICE =
			new DeviceLifecycle(LocalDate.of(2031, 1, 31), null);

	private Map<String, Object> dtoOnDevice(SupportData support) {
		return SbomComponentDataFetcher.toComponentDto(new SbomComponent(), support, DEVICE);
	}

	/** A date in the past, so a live derivation would unambiguously say END_OF_SUPPORT. */
	private static final LocalDate PAST_EOS = LocalDate.of(2020, 1, 1);

	@Test
	void anAttestedRowStillDerivesFromItsDates() {
		assertEquals("END_OF_SUPPORT", dto(support(SupportState.ATTESTED, PAST_EOS)).get("supportStatus"),
				"the ordinary case must keep deriving, or this fix has broken the feature");
	}

	@Test
	void aWithdrawnRowDerivesUnknownRatherThanItsRetractedDates() {
		assertEquals("UNKNOWN", dto(support(SupportState.WITHDRAWN, PAST_EOS)).get("supportStatus"),
				"a withdrawn claim was served as a live end-of-support status");
	}

	/**
	 * The withdrawal is still ON RECORD and must remain visible as such. UNKNOWN alone would say
	 * "not assessed", which is a different fact from "assessed, then retracted" -- and the row
	 * is the evidence of the second.
	 */
	@Test
	void aWithdrawnRowStillReportsItsStateSoAClientCanSayWithdrawn() {
		assertEquals("WITHDRAWN", dto(support(SupportState.WITHDRAWN, PAST_EOS)).get("attestationState"));
	}

	/** An unassessed component is not a withdrawal, and must not read as one. */
	@Test
	void anAbsentAttestationIsUnknownWithNoState() {
		Map<String, Object> d = dto(null);
		assertEquals("UNKNOWN", d.get("supportStatus"));
		assertNull(d.get("attestationState"));
	}

	/**
	 * The device verdict is derived from the same retracted date and must go with it.
	 *
	 * The first revision of this fix set only supportStatus, so a withdrawn row rendered a
	 * neutral "Withdrawn" chip beside a red "EOS before device" tag -- half-closing the exact
	 * divergence it was written to close. Caught in review, not by the tests that shipped with
	 * it, which is why this one exists.
	 */
	@Test
	void aWithdrawnRowDerivesNoDeviceRiskEither() {
		assertEquals("EOS_BEFORE_DEVICE", dtoOnDevice(support(SupportState.ATTESTED, PAST_EOS)).get("deviceSupportRisk"),
				"the ordinary case must still flag, or this fix has broken the verdict");
		assertEquals("UNKNOWN", dtoOnDevice(support(SupportState.WITHDRAWN, PAST_EOS)).get("deviceSupportRisk"),
				"a retracted date produced a live device-risk verdict");
	}

	/** Withdrawal does not depend on there being a date to withdraw. */
	@Test
	void aWithdrawnRowWithNoMilestonesIsAlsoUnknown() {
		assertEquals("UNKNOWN", dto(support(SupportState.WITHDRAWN, null)).get("supportStatus"));
	}
}
