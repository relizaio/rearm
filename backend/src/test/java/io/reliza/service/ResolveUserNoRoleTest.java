/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.exceptions.AccountRefusedException;
import io.reliza.exceptions.AccountRefusedException.AccountRefusalKind;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.SsoUsersWithoutRole;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.UserRepository;
import io.reliza.ws.App;
import io.reliza.ws.RelizaConfigProps;
import io.reliza.ws.oss.TestInitializer;

/**
 * A first SSO sign-in by a user with no role in the default organization, on MANAGED_SERVICE: refused
 * when the organization's ssoUsersWithoutRole is REFUSE, created with no role when it is
 * CREATE_WITHOUT_ROLE. Each test sets the setting, so the tests do not depend on its default, and they
 * hold with any seat gate. The installation type and the default org are switched on the shared beans and
 * restored.
 */
@SpringBootTest(classes = {App.class})
public class ResolveUserNoRoleTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private UserService userService;
	@Autowired private OrganizationService organizationService;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private RelizaConfigProps relizaConfigProps;
	@Autowired private UserRepository userRepository;
	@Autowired private TestInitializer testInitializer;

	private UUID previousDefaultOrg;
	private String previousInstallationType;

	@BeforeEach
	void up() {
		previousDefaultOrg = systemInfoService.getDefaultOrg();
		previousInstallationType = relizaConfigProps.getInstallationType();
	}

	@AfterEach
	void down() {
		systemInfoService.setDefaultOrg(previousDefaultOrg);
		relizaConfigProps.setInstallationType(previousInstallationType);
	}

	private static String tag() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static String emailOf(String tag) {
		return "no-role-" + tag + "@sso.io";
	}

	private static JwtAuthenticationToken jwt(String tag) {
		Jwt j = Jwt.withTokenValue("t").header("alg", "none").subject("no-role-sub-" + tag)
				.claim("sub", "no-role-sub-" + tag).claim("email", emailOf(tag)).claim("name", "No Role " + tag)
				.claim("groups", List.of("unmatched-" + tag)).build();
		return new JwtAuthenticationToken(j, List.of());
	}

	/** A new default organization on MANAGED_SERVICE with ssoUsersWithoutRole set to the given value. */
	private Organization managedServiceDefaultOrg(SsoUsersWithoutRole setting) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings patch = new OrganizationData.Settings();
		patch.setSsoUsersWithoutRole(setting);
		organizationService.updateSettings(org.getUuid(), patch, WU);
		relizaConfigProps.setInstallationType("MANAGED_SERVICE");
		systemInfoService.setDefaultOrg(org.getUuid());
		return org;
	}

	@Test
	public void refusedWhenTheSettingIsRefuse() throws Exception {
		managedServiceDefaultOrg(SsoUsersWithoutRole.REFUSE);
		String t = tag();
		AccountRefusedException e = assertThrows(AccountRefusedException.class, () -> userService.resolveUser(jwt(t)));
		assertEquals("LICENSE_SEAT_REFUSED", e.getErrorCode());
		assertEquals(AccountRefusalKind.NO_ROLE, e.getReason());
		assertEquals(AccountRefusedException.NO_ROLE_TEXT, e.getMessage());
		assertTrue(userRepository.findAnyUserByEmail(emailOf(t)).isEmpty(), "no user row");
	}

	@Test
	public void createdWithNoRoleWhenTheSettingIsCreateWithoutRole() throws Exception {
		Organization org = managedServiceDefaultOrg(SsoUsersWithoutRole.CREATE_WITHOUT_ROLE);
		String t = tag();
		UserData ud = userService.resolveUser(jwt(t));
		assertEquals(1, userRepository.findAnyUserByEmail(emailOf(t)).size(), "one user row");
		UserData stored = userService.getUserData(ud.getUuid()).orElseThrow();
		assertTrue(stored.getOrganizations().contains(org.getUuid()), stored.getOrganizations().toString());
		PermissionType type = stored.getPermission(org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid())
				.map(p -> p.getType()).orElse(PermissionType.NONE);
		assertEquals(PermissionType.NONE, type);
	}
}
