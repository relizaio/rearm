/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;

/**
 * Task TEA-2, design 4.3: the TEA profile data class, its defaults and record round trip; trimmed by
 * task TEA-27 to the channel fields and the profiles list (the adapters moved to SbomProfileDataTest).
 */
class TeaProfileDataTest {

	@Test
	void builtInDefaultCarriesEveryDecidedDefault() {
		UUID org = UUID.randomUUID();
		TeaProfileData d = TeaProfileData.builtInDefault(org);
		assertEquals(org, d.getOrg());
		assertEquals(TeaProfileScope.ORGANIZATION, d.getScope());
		assertNull(d.getUuid());
		assertNull(d.getObject());
		assertNull(d.getMode());
		assertNull(d.getFollowedPerspective());
		assertEquals(TeaPublishing.DISABLED, d.getPublishing());
		assertEquals(TeaVisibility.PRIVATE, d.getVisibility());
		assertEquals(TeaRawArtifacts.NONE, d.getRawArtifacts());
		assertEquals(TeaProductComponents.PUBLISH_WITH_PRODUCT, d.getProductComponents());
		assertEquals(ReleaseLifecycle.ASSEMBLED, d.getMinimumLifecycle());
		assertEquals(TeaTei.DISABLED, d.getTei());
		assertNull(d.getTeiDomain());
		assertEquals(List.of(SbomProfileData.DEFAULT_NAME), d.getProfiles(), "TEA-27: the built-in names default");
	}

	private static TeaProfileData everyFieldSet() {
		TeaProfileData d = new TeaProfileData();
		d.setOrg(UUID.randomUUID());
		d.setScope(TeaProfileScope.COMPONENT);
		d.setObject(UUID.randomUUID());
		d.setMode(TeaComponentProfileMode.OVERRIDE);
		d.setFollowedPerspective(UUID.randomUUID());
		d.setPublishing(TeaPublishing.ENABLED);
		d.setVisibility(TeaVisibility.PUBLIC);
		d.setRawArtifacts(TeaRawArtifacts.NON_BOM);
		d.setProductComponents(TeaProductComponents.PRODUCT_ONLY);
		d.setMinimumLifecycle(ReleaseLifecycle.GENERAL_AVAILABILITY);
		d.setTei(TeaTei.UUID);
		d.setTeiDomain("products.example.com");
		d.setProfiles(List.of("default", "fda-premarket.v2"));
		d.setLastUpdatedBy(UUID.randomUUID());
		return d;
	}

	@Test
	void recordRoundTripKeepsEveryField() {
		TeaProfileData d = everyFieldSet();
		TeaProfile e = new TeaProfile();
		e.setRevision(4);
		e.setCreatedDate(ZonedDateTime.now().minusDays(1));
		e.setRecordData(d.toRecordData());

		TeaProfileData back = TeaProfileData.dataFromRecord(e);
		assertEquals(e.getUuid(), back.getUuid(), "the entity uuid wins");
		assertEquals(4, back.getRevision());
		assertEquals(e.getCreatedDate(), back.getCreatedDate());
		assertEquals(d.getLastUpdatedBy(), back.getLastUpdatedBy(), "RelizaDataParent fields survive");
		d.setUuid(e.getUuid());
		d.setRevision(4);
		d.setCreatedDate(e.getCreatedDate());
		assertEquals(d, back);
		assertEquals(List.of("default", "fda-premarket.v2"), back.getProfiles(), "the names read back in the order given");
		assertFalse(e.getRecordData().containsKey("revision"), "the revision is the entity's, not the record's");
	}

	@Test
	void aRecordWithAnUnknownKeyStillReads() {
		TeaProfileData d = everyFieldSet();
		Map<String, Object> record = d.toRecordData();
		record.put("somethingFromALaterVersion", Map.of("x", 1));
		TeaProfile e = new TeaProfile();
		e.setRecordData(record);
		assertEquals(TeaPublishing.ENABLED, TeaProfileData.dataFromRecord(e).getPublishing());
	}

	@Test
	void theRecipeKeysOfALegacyRowAreIgnored() {
		// A row carrying the pre-TEA-27 recipe keys would still read: they are unknown to the trimmed class (TEA-27 3.7).
		Map<String, Object> record = everyFieldSet().toRecordData();
		record.put("dependencyDepth", "TOP_LEVEL_ONLY");
		record.put("sources", List.of("DELIVERABLE"));
		TeaProfile e = new TeaProfile();
		e.setRecordData(record);
		assertEquals(TeaVisibility.PUBLIC, TeaProfileData.dataFromRecord(e).getVisibility());
	}

	@Test
	void theProfilesListIsDetached() {
		List<String> names = new ArrayList<>(List.of("a"));
		TeaProfileData d = new TeaProfileData();
		d.setProfiles(names);
		names.add("b");
		assertEquals(List.of("a"), d.getProfiles());
	}

	@Test
	void copyIsDetached() {
		TeaProfileData d = everyFieldSet();
		d.setRevision(2);
		TeaProfileData c = d.copy();
		assertEquals(d, c);
		c.getProfiles().add("other");
		c.setVisibility(TeaVisibility.PRIVATE);
		assertEquals(TeaVisibility.PUBLIC, d.getVisibility());
		assertEquals(List.of("default", "fda-premarket.v2"), d.getProfiles());
	}
}
