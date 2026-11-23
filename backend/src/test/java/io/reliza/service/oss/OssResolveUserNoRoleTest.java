/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.SsoUsersWithoutRole;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.UserRepository;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.SystemInfoService;
import io.reliza.service.UserService;
import io.reliza.ws.App;
import io.reliza.ws.RelizaConfigProps;
import io.reliza.ws.oss.TestInitializer;

/**
 * On this edition's installation type (OSS), a first SSO sign-in whose groups claim matches no connected
 * group still creates the account, in the user organization and with no role, whatever the "SSO users
 * without a role" setting says: it is set to REFUSE on both the user organization and the default
 * organization here. A seed user makes the sign-in not the first one (the first becomes ADMIN). The
 * installation type, the default organization and the user organization's setting are restored; a
 * setting that was never stored is restored as its default, REFUSE.
 */
@SpringBootTest(classes = {App.class})
class OssResolveUserNoRoleTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private UserService userService;
	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private RelizaConfigProps relizaConfigProps;
	@Autowired private UserRepository userRepository;
	@Autowired private TestInitializer testInitializer;

	private UUID previousDefaultOrg;
	private String previousInstallationType;
	private SsoUsersWithoutRole previousUserOrgSetting;

	@BeforeEach
	void up() throws Exception {
		previousDefaultOrg = systemInfoService.getDefaultOrg();
		previousInstallationType = relizaConfigProps.getInstallationType();
		previousUserOrgSetting = getOrganizationService.getOrganizationData(UserService.USER_ORG).orElseThrow()
				.getSettingsWithDefaults().getSsoUsersWithoutRoleOrDefault();

		relizaConfigProps.setInstallationType("OSS");
		String seed = tag();
		userService.createUser("Seed " + seed, "oss-no-role-seed-" + seed + "@example.com", true,
				List.of(UserService.USER_ORG), "oss-no-role-seed-" + seed, OauthType.RELIZA_KEYCLOAK_OWN, WU);
		refuse(UserService.USER_ORG);
	}

	@AfterEach
	void down() throws Exception {
		systemInfoService.setDefaultOrg(previousDefaultOrg);
		relizaConfigProps.setInstallationType(previousInstallationType);
		OrganizationData.Settings patch = new OrganizationData.Settings();
		patch.setSsoUsersWithoutRole(previousUserOrgSetting);
		organizationService.updateSettings(UserService.USER_ORG, patch, WU);
	}

	private void refuse(UUID org) throws Exception {
		OrganizationData.Settings patch = new OrganizationData.Settings();
		patch.setSsoUsersWithoutRole(SsoUsersWithoutRole.REFUSE);
		organizationService.updateSettings(org, patch, WU);
	}

	private static String tag() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static String emailOf(String tag) {
		return "oss-no-role-" + tag + "@example.com";
	}

	private static JwtAuthenticationToken jwt(String tag) {
		Jwt j = Jwt.withTokenValue("t").header("alg", "none").subject("oss-no-role-sub-" + tag)
				.claim("sub", "oss-no-role-sub-" + tag).claim("email", emailOf(tag)).claim("name", "Oss No Role " + tag)
				.claim("groups", List.of("unmatched-" + tag)).build();
		return new JwtAuthenticationToken(j, List.of());
	}

	/** Signs in, then checks the one account it made: in the user organization only, with an explicit NONE role there. */
	private UserData signedInWithNoRole() throws Exception {
		String t = tag();
		UserData ud = userService.resolveUser(jwt(t));
		assertEquals(1, userRepository.findAnyUserByEmail(emailOf(t)).size(), "one user row");
		UserData stored = userService.getUserData(ud.getUuid()).orElseThrow();
		assertEquals(Set.of(UserService.USER_ORG), Set.copyOf(stored.getOrganizations()));
		assertEquals(PermissionType.NONE, stored.getPermission(UserService.USER_ORG, PermissionScope.ORGANIZATION,
				UserService.USER_ORG).orElseThrow().getType());
		return stored;
	}

	@Test
	void createdWithNoRoleWithoutADefaultOrganization() throws Exception {
		systemInfoService.setDefaultOrg(null);
		signedInWithNoRole();
	}

	@Test
	void createdWithNoRoleInTheUserOrganizationWhenTheDefaultOrganizationRefuses() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		refuse(org.getUuid());
		systemInfoService.setDefaultOrg(org.getUuid());
		UserData stored = signedInWithNoRole();
		assertFalse(stored.getOrganizations().contains(org.getUuid()), "not in the default organization");
	}
}
