/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * {@code reviewItemPriorityLevels} through the settings service, against the database.
 *
 * <p>Two things are pinned. The value is PERSISTED: updateSettings used to drop it on the floor,
 * so the setting was accepted over GraphQL and then never stored, and every board read the
 * default. And the 1..10 bound lives in the service, so every write path validates rather than
 * only the GraphQL one.
 */
@SpringBootTest(classes = { App.class })
class ReviewItemPriorityLevelsSettingTest {

	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TestInitializer testInitializer;

	private OrganizationData.Settings levels(int n) {
		OrganizationData.Settings p = new OrganizationData.Settings();
		p.setReviewItemPriorityLevels(n);
		return p;
	}

	private Integer storedLevels(UUID orgUuid) {
		OrganizationData.Settings s = getOrganizationService.getOrganizationData(orgUuid).orElseThrow().getSettings();
		return null == s ? null : s.getReviewItemPriorityLevels();
	}

	@Test
	void savesAndReadsBack() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData returned = organizationService.updateSettings(org.getUuid(), levels(5),
				WhoUpdated.getTestWhoUpdated());
		assertEquals(Integer.valueOf(5), returned.getSettings().getReviewItemPriorityLevels());
		assertEquals(Integer.valueOf(5), storedLevels(org.getUuid()),
				"the value must survive a fresh read from the database");
	}

	/** Both ends of 1..10 are legal and stored. */
	@Test
	void theBoundsThemselvesAreAccepted() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		organizationService.updateSettings(org.getUuid(), levels(1), WhoUpdated.getTestWhoUpdated());
		assertEquals(Integer.valueOf(1), storedLevels(org.getUuid()));
		organizationService.updateSettings(org.getUuid(), levels(OrganizationData.MAX_REVIEW_ITEM_PRIORITY_LEVELS),
				WhoUpdated.getTestWhoUpdated());
		assertEquals(Integer.valueOf(10), storedLevels(org.getUuid()));
	}

	@Test
	void refusesZeroAndElevenAndKeepsTheStoredValue() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		organizationService.updateSettings(org.getUuid(), levels(4), WhoUpdated.getTestWhoUpdated());
		for (int bad : new int[] { 0, 11 }) {
			RelizaException e = assertThrows(RelizaException.class,
					() -> organizationService.updateSettings(org.getUuid(), levels(bad), WhoUpdated.getTestWhoUpdated()));
			assertTrue(e.getMessage().contains("reviewItemPriorityLevels") && e.getMessage().contains("10"),
					"the refusal must name the setting and its bound; got: " + e.getMessage());
			assertEquals(Integer.valueOf(4), storedLevels(org.getUuid()),
					bad + " was refused, so the stored value must be unchanged");
		}
	}

	/** An unrelated patch leaves the stored count alone. */
	@Test
	void omittingItLeavesItUnchanged() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		organizationService.updateSettings(org.getUuid(), levels(7), WhoUpdated.getTestWhoUpdated());
		OrganizationData.Settings other = new OrganizationData.Settings();
		other.setJustificationMandatory(true);
		organizationService.updateSettings(org.getUuid(), other, WhoUpdated.getTestWhoUpdated());
		assertEquals(Integer.valueOf(7), storedLevels(org.getUuid()));
	}
}
