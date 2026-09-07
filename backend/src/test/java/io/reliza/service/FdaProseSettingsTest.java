/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.VexComplianceFramework;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The four manufacturer-authored prose slots the FDA document set renders (plan 7f).
 *
 * <p>What is asserted here is mostly about ABSENCE being preserved. The documents block
 * generation on an empty required slot, and that check is only trustworthy if the storage
 * layer cannot quietly turn "nothing recorded" into "something that looks recorded" -- a
 * blank string being the obvious way that happens.
 */
@SpringBootTest(classes = { App.class })
class FdaProseSettingsTest {

	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TestInitializer testInitializer;

	private OrganizationData.Settings patch() {
		return new OrganizationData.Settings();
	}

	private OrganizationData.Settings settingsOf(UUID orgUuid) {
		return getOrganizationService.getOrganizationData(orgUuid).orElseThrow().getSettings();
	}

	@Test
	void storesAllFourAndLeavesEveryOtherSettingAlone() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings p = patch();
		p.setFdaAssessmentNarrative("No upstream publishes a machine-readable support level"
				+ " for the majority of these components; each was checked against its"
				+ " project's published policy on 2026-09-07.");
		p.setFdaPatchesMayCeaseStatement("After end of support we may no longer issue"
				+ " security patches or software updates for this device.");
		p.setFdaRiskTransferProcessRef("DHF-PROC-4471 rev C");
		p.setFdaRiskIncreasesNotice("Cybersecurity risk to users can be expected to increase"
				+ " over time after end of support.");
		organizationService.updateSettings(org.getUuid(), p, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings s = settingsOf(org.getUuid());
		assertTrue(s.getFdaAssessmentNarrative().startsWith("No upstream publishes"));
		assertTrue(s.getFdaPatchesMayCeaseStatement().startsWith("After end of support"));
		assertEquals("DHF-PROC-4471 rev C", s.getFdaRiskTransferProcessRef());
		assertTrue(s.getFdaRiskIncreasesNotice().contains("increase"));
	}

	/**
	 * The prose block was inserted BEFORE the existing field handling in updateSettings, so
	 * the question "did that change anything for its neighbours" needs an answer that is not
	 * a reading of the diff. Seeds the other settings, patches only prose, and asserts they
	 * survive.
	 */
	@Test
	void aProseOnlyPatchLeavesTheOtherSettingsIntact() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings seed = patch();
		seed.setJustificationMandatory(true);
		seed.setVexComplianceFramework(VexComplianceFramework.CISA);
		seed.setNotificationRetentionDays(180);
		organizationService.updateSettings(org.getUuid(), seed, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings proseOnly = patch();
		proseOnly.setFdaAssessmentNarrative("only the narrative changes here");
		organizationService.updateSettings(org.getUuid(), proseOnly, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings s = settingsOf(org.getUuid());
		assertEquals(Boolean.TRUE, s.getJustificationMandatory());
		assertEquals(VexComplianceFramework.CISA, s.getVexComplianceFramework());
		assertEquals(Integer.valueOf(180), s.getNotificationRetentionDays());
		assertEquals("only the narrative changes here", s.getFdaAssessmentNarrative());
	}

	/**
	 * Exactly at the bound is ACCEPTED. Only MAX+1 was covered, so a flip to >= would have
	 * passed the suite while silently refusing a document someone had already written.
	 */
	@Test
	void exactlyTheBoundIsAccepted() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String atLimit = "y".repeat(OrganizationData.Settings.FDA_PROSE_MAX_LENGTH);
		OrganizationData.Settings p = patch();
		p.setFdaAssessmentNarrative(atLimit);
		organizationService.updateSettings(org.getUuid(), p, WhoUpdated.getTestWhoUpdated());
		assertEquals(atLimit.length(), settingsOf(org.getUuid()).getFdaAssessmentNarrative().length());
	}

	/**
	 * A patch that is valid in one field and over-long in another must write NEITHER.
	 *
	 * <p>Holds today because getOrganizationData materialises a fresh POJO per call, so the
	 * half-mutated object is unreachable when the throw happens and saveOrganization is the
	 * only write. That is a property of the current structure rather than an explicit
	 * guarantee, which is exactly why it is worth pinning: moving the save earlier, or
	 * splitting the method, would break it silently.
	 */
	@Test
	void aPatchThatFailsPartWayWritesNothing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings p = patch();
		p.setFdaPatchesMayCeaseStatement("this one is fine");
		p.setFdaAssessmentNarrative("z".repeat(OrganizationData.Settings.FDA_PROSE_MAX_LENGTH + 1));
		assertThrows(RelizaException.class,
				() -> organizationService.updateSettings(org.getUuid(), p, WhoUpdated.getTestWhoUpdated()));

		OrganizationData.Settings after = settingsOf(org.getUuid());
		assertTrue(after == null || after.getFdaPatchesMayCeaseStatement() == null,
				"the valid field must not survive a patch that failed on another field");
	}

	/**
	 * PATCH semantics: an omitted field leaves the stored value alone. Without this, saving
	 * the settings form to change one unrelated toggle would erase prose a manufacturer
	 * signed -- and erase it silently, since nothing else reads these until a document is
	 * generated.
	 */
	@Test
	void omittingAFieldLeavesItUnchanged() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings first = patch();
		first.setFdaAssessmentNarrative("original narrative");
		first.setFdaRiskTransferProcessRef("DHF-1");
		organizationService.updateSettings(org.getUuid(), first, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings second = patch();
		second.setFdaRiskTransferProcessRef("DHF-2");
		organizationService.updateSettings(org.getUuid(), second, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings s = settingsOf(org.getUuid());
		assertEquals("original narrative", s.getFdaAssessmentNarrative(),
				"an unrelated settings save must not erase prose the manufacturer authored");
		assertEquals("DHF-2", s.getFdaRiskTransferProcessRef());
	}

	/**
	 * SUPPLIED-BLANK CLEARS. Same contract as the attestation write, and the reason is the
	 * operator's: someone who empties a wrong risk-transfer reference and saves must end up
	 * with it gone. Refusing the blank instead would leave the stale value in place while
	 * telling them nothing, and the generator would later ship it into a patient-facing
	 * document -- the exact fabrication the empty-slot block exists to prevent.
	 *
	 * <p>Normalised to NULL, not to "", so the generator's missing-slot test stays a null
	 * test and storage never holds whitespace that reads as content.
	 */
	@Test
	void blankClearsToNull() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings seed = patch();
		seed.setFdaRiskTransferProcessRef("DHF-WRONG-9 rev A");
		organizationService.updateSettings(org.getUuid(), seed, WhoUpdated.getTestWhoUpdated());
		assertEquals("DHF-WRONG-9 rev A", settingsOf(org.getUuid()).getFdaRiskTransferProcessRef());

		for (String blank : new String[] { "", "   ", "\n\t " }) {
			OrganizationData.Settings reseed = patch();
			reseed.setFdaRiskTransferProcessRef("DHF-WRONG-9 rev A");
			organizationService.updateSettings(org.getUuid(), reseed, WhoUpdated.getTestWhoUpdated());

			OrganizationData.Settings clear = patch();
			clear.setFdaRiskTransferProcessRef(blank);
			organizationService.updateSettings(org.getUuid(), clear, WhoUpdated.getTestWhoUpdated());
			assertNull(settingsOf(org.getUuid()).getFdaRiskTransferProcessRef(),
					"blank must clear to null, not store whitespace: " + blank.length() + " chars");
		}
	}

	/** Clearing one slot must not disturb the other three. */
	@Test
	void clearingOneSlotLeavesTheOthers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings seed = patch();
		seed.setFdaAssessmentNarrative("narrative stays");
		seed.setFdaPatchesMayCeaseStatement("patches stays");
		seed.setFdaRiskTransferProcessRef("ref goes");
		seed.setFdaRiskIncreasesNotice("notice stays");
		organizationService.updateSettings(org.getUuid(), seed, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings clear = patch();
		clear.setFdaRiskTransferProcessRef("");
		organizationService.updateSettings(org.getUuid(), clear, WhoUpdated.getTestWhoUpdated());

		OrganizationData.Settings s = settingsOf(org.getUuid());
		assertNull(s.getFdaRiskTransferProcessRef());
		assertEquals("narrative stays", s.getFdaAssessmentNarrative());
		assertEquals("patches stays", s.getFdaPatchesMayCeaseStatement());
		assertEquals("notice stays", s.getFdaRiskIncreasesNotice());
	}

	@Test
	void proseIsTrimmedAndBounded() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings p = patch();
		p.setFdaRiskIncreasesNotice("   padded on both sides   ");
		organizationService.updateSettings(org.getUuid(), p, WhoUpdated.getTestWhoUpdated());
		assertEquals("padded on both sides", settingsOf(org.getUuid()).getFdaRiskIncreasesNotice());

		OrganizationData.Settings tooBig = patch();
		tooBig.setFdaAssessmentNarrative("x".repeat(
				OrganizationData.Settings.FDA_PROSE_MAX_LENGTH + 1));
		RelizaException e = assertThrows(RelizaException.class,
				() -> organizationService.updateSettings(org.getUuid(), tooBig, WhoUpdated.getTestWhoUpdated()));
		assertTrue(e.getMessage().contains("character limit"), e.getMessage());
	}

	/**
	 * Each field's error names THAT field.
	 *
	 * <p>applyProse pairs a setter reference with a field-name string by hand, four times.
	 * A copy-paste that hands field A's label to field B's setter compiles, passes every
	 * other test here, and shows an operator the wrong field name when their text is too
	 * long -- on a form with four textareas, which is precisely when naming the right one
	 * matters.
	 */
	@Test
	void anOverlongValueNamesTheFieldItCameFrom() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String tooBig = "x".repeat(OrganizationData.Settings.FDA_PROSE_MAX_LENGTH + 1);

		record Case(String field, java.util.function.BiConsumer<OrganizationData.Settings, String> set) {}
		var cases = java.util.List.of(
				new Case("fdaAssessmentNarrative", OrganizationData.Settings::setFdaAssessmentNarrative),
				new Case("fdaPatchesMayCeaseStatement", OrganizationData.Settings::setFdaPatchesMayCeaseStatement),
				new Case("fdaRiskTransferProcessRef", OrganizationData.Settings::setFdaRiskTransferProcessRef),
				new Case("fdaRiskIncreasesNotice", OrganizationData.Settings::setFdaRiskIncreasesNotice));

		for (Case c : cases) {
			OrganizationData.Settings p = patch();
			c.set().accept(p, tooBig);
			RelizaException e = assertThrows(RelizaException.class,
					() -> organizationService.updateSettings(org.getUuid(), p, WhoUpdated.getTestWhoUpdated()));
			assertTrue(e.getMessage().startsWith(c.field()),
					"the error must name the field it came from, not a sibling: expected "
							+ c.field() + ", got: " + e.getMessage());
		}
	}
}
