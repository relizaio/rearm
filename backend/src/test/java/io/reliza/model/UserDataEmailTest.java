/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;

/**
 * FDAW-8 T-12 (FDAW-1 F-26 item 2, F-28 item 1): {@code getEmail(org)} answers the address the user
 * holds for that organization, and {@code this.email} when none is tagged, whatever {@code isPrimary}
 * flags the other addresses carry. Built from a record, because no public method tags an address.
 */
class UserDataEmailTest {

	private static final UUID A = UUID.randomUUID();
	private static final UUID B = UUID.randomUUID();

	private static Map<String, Object> address(String email, boolean primary, UUID... orgs) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(CommonVariables.EMAIL_FIELD, email);
		m.put(CommonVariables.IS_PRIMARY_FIELD, primary);
		m.put(CommonVariables.IS_VERIFIED_FIELD, true);
		List<String> o = new ArrayList<>();
		for (UUID u : orgs) o.add(u.toString());
		m.put(CommonVariables.ORGANIZATION_FIELD, o);
		return m;
	}

	private static UserData user(String email, List<Map<String, Object>> allEmails) {
		Map<String, Object> rec = new LinkedHashMap<>();
		rec.put(CommonVariables.NAME_FIELD, "Writer One");
		rec.put(CommonVariables.EMAIL_FIELD, email);
		rec.put(CommonVariables.ALL_EMAILS_FIELD, allEmails);
		rec.put(CommonVariables.ORGANIZATION_FIELD, List.of(A.toString(), B.toString()));
		User u = new User();
		u.setUuid(UUID.randomUUID());
		u.setRecordData(rec);
		return UserData.dataFromRecord(u);
	}

	@Test
	void anAddressTaggedForAnOrganizationIsThatOrganizationsAndTheOtherGetsThePrimary() {
		UserData ud = user("primary@example.com", List.of(address("primary@example.com", true),
				address("regulatory@device.example", false, A)));
		assertEquals("regulatory@device.example", ud.getEmail(A));
		assertEquals("primary@example.com", ud.getEmail(B));
		assertTrue(ud.getEmailObject(B).isEmpty(), "no tagged object for B, and no primary stand-in");
	}

	@Test
	void withoutATaggedAddressTheAnswerIsThisEmailEvenWithTwoPrimaries() {
		UserData ud = user("current@example.com", List.of(address("old@example.com", true),
				address("current@example.com", true)));
		assertEquals("current@example.com", ud.getEmail(A));
		assertTrue(ud.getEmailObject(A).isEmpty());
	}

	@Test
	void eachOrganizationGetsItsOwnTaggedAddress() {
		UserData ud = user("primary@example.com", List.of(address("primary@example.com", true),
				address("a@device.example", false, A), address("b@device.example", false, B)));
		assertEquals("a@device.example", ud.getEmail(A));
		assertEquals("b@device.example", ud.getEmail(B));
	}
}
