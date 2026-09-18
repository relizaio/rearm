/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;

import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.common.Utils;

/**
 * Every user_groups row written before the team fields were removed still
 * carries {@code memberRoles}, {@code externalMembers} and
 * {@code notificationChannels} in its {@code record_data} -- the deletion moved
 * those concepts to Team and deliberately did NOT migrate the stored JSON.
 *
 * <p>So the read path has to tolerate them, and the blast radius if it stops is
 * not one screen: group reads feed permission resolution, SSO sync and the
 * ownership candidate walk.
 *
 * <p><b>What actually provides that tolerance is the shared mapper, not the
 * class.</b> {@code Utils.OM} is Jackson 3, where unknown properties are
 * ignored by DEFAULT. Verified by mutation while writing this test: removing
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} from
 * {@link UserGroupData} does NOT break it, and neither does setting
 * {@code ignoreUnknown = false} -- that annotation only declines to suppress
 * failure, it never enables it. So the annotation is belt-and-braces and this
 * test is the real guard: it fails the day someone hardens the shared mapper
 * with FAIL_ON_UNKNOWN_PROPERTIES, which is an entirely reasonable-looking
 * change to make and would take every legacy group row down with it.
 *
 * <p>The fixture is real shape, copied from a live row.
 */
class UserGroupLegacyRecordDataTest {

	private static final String LEGACY_ROW = """
			{
			  "name": "Docs Team",
			  "org": "%s",
			  "status": "ACTIVE",
			  "manualUsers": ["%s"],
			  "connectedSsoGroups": ["docs-sso"],
			  "memberRoles": [
			    {"userRef": "%s", "role": "SECURITY_SPECIALIST", "customRole": null}],
			  "externalMembers": [
			    {"name": "Vendor Ops", "contact": "ops@vendor.example",
			     "role": "CUSTOM", "customRole": "On-call"}],
			  "notificationChannels": ["%s"]
			}
			""";

	@Test
	void aRowStillCarryingTheRemovedTeamFieldsStillParses() {
		UUID org = UUID.randomUUID();
		UUID member = UUID.randomUUID();
		UUID channel = UUID.randomUUID();
		Map<String, Object> recordData = Utils.OM.readValue(
				LEGACY_ROW.formatted(org, member, member, channel),
				new TypeReference<Map<String, Object>>() {});

		UserGroupData ugd = Utils.OM.convertValue(recordData, UserGroupData.class);

		assertEquals("Docs Team", ugd.getName());
		assertEquals(org, ugd.getOrg());
		assertEquals(UserGroupStatus.ACTIVE, ugd.getStatus());
		assertTrue(ugd.getManualUsers().contains(member), "the surviving roster must come through intact");
		assertTrue(ugd.getConnectedSsoGroups().contains("docs-sso"),
				"SSO backing must survive -- ownership durability reads it through contained groups");
	}
}
