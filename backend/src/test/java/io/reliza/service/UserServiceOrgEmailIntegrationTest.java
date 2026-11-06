/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.OrganizationData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.OrganizationService.GlobalUserCounts;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * FDAW-8 T-13 (FDAW-1 F-28 item 2): once a member holds an address tagged for an organization, the
 * callers of {@code getEmail(org)} answer it there: the action-email recipients, the org user list, and
 * the org user records, whose global merge counts a user once by uuid however many addresses they hold.
 * No public path tags an address, so it is written into the user record directly. Shared: the CE
 * mirror runs it too.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
class UserServiceOrgEmailIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private UserService userService;
	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private JdbcTemplate jdbc;

	private UserData member(List<UUID> orgs) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Fdaw8 " + tag, "fdaw8-" + tag + "@primary.example", true, orgs,
				"fdaw8-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		for (UUID o : orgs) {
			userService.setUserPermission(u.getUuid(), o, PermissionScope.ORGANIZATION, o, PermissionType.READ_WRITE,
					List.of(), null, WU);
		}
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private void tagAddress(UUID user, String address, UUID o) {
		jdbc.update("UPDATE rearm.users SET record_data = jsonb_set(record_data, '{" + CommonVariables.ALL_EMAILS_FIELD
				+ "}', (record_data->'" + CommonVariables.ALL_EMAILS_FIELD + "') || jsonb_build_array(jsonb_build_object('"
				+ CommonVariables.EMAIL_FIELD + "', ?::text, '" + CommonVariables.IS_PRIMARY_FIELD + "', false, '"
				+ CommonVariables.IS_VERIFIED_FIELD + "', true, '" + CommonVariables.ORGANIZATION_FIELD
				+ "', jsonb_build_array(?::text)))) WHERE uuid = ?", address, o.toString(), user);
	}

	private long usersIn(UUID o) {
		OrganizationData od = getOrganizationService.getOrganizationData(o).orElseThrow();
		Map<String, Object> analytics = organizationService.getNumericAnalytics(od);
		return ((BigInteger) analytics.get("admin_and_write_users")).longValue()
				+ ((BigInteger) analytics.get("read_only_users")).longValue();
	}

	@Test
	void theRecipientsAndTheOrgUserListAnswerTheTaggedAddress() throws Exception {
		UUID o = testInitializer.obtainOrganization().getUuid();
		UserData ud = member(List.of(o));
		String tagged = "regulatory-" + UUID.randomUUID().toString().substring(0, 8) + "@device.example";
		tagAddress(ud.getUuid(), tagged, o);

		assertEquals(Set.of(tagged), userService.activeRecipientEmails(o, List.of(ud.getUuid())));
		UserData fresh = userService.getUserData(ud.getUuid()).orElseThrow();
		assertEquals(tagged, UserData.convertUserDataToOrgUserData(fresh, o).getEmail());
	}

	@Test
	void aUserWithATaggedAddressInEachOfTwoOrganizationsCountsOnce() throws Exception {
		UUID a = testInitializer.obtainOrganization().getUuid();
		UUID b = testInitializer.obtainOrganization().getUuid();
		UserData ud = member(List.of(a, b));
		GlobalUserCounts before = organizationService.getGlobalUserCounts();
		long inA = usersIn(a);
		long inB = usersIn(b);
		tagAddress(ud.getUuid(), "a-" + UUID.randomUUID().toString().substring(0, 8) + "@device.example", a);
		tagAddress(ud.getUuid(), "b-" + UUID.randomUUID().toString().substring(0, 8) + "@device.example", b);

		GlobalUserCounts after = organizationService.getGlobalUserCounts();
		assertEquals(before.totalUsers(), after.totalUsers(), "the user counts once, not once per address");
		assertEquals(before.writeUsers(), after.writeUsers());
		assertEquals(before.readUsers(), after.readUsers());
		assertEquals(inA, usersIn(a), "each organization counts the user once");
		assertEquals(inB, usersIn(b));
	}
}
