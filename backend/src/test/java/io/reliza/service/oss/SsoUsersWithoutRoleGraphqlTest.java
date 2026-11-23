/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The GraphQL documents the UI sends for the "SSO users without a role" setting and the Statistics user
 * figures are answered by this edition's backend: the organizations query that selects
 * {@code settings.ssoUsersWithoutRole}, the settings mutation that patches it, and the totals that carry
 * {@code users_without_role}. The query and the totals are the UI's documents (organizationsQuery,
 * AnalyticsOfOrg); the mutation is OrgSettings' with its selection trimmed to the setting.
 */
@SpringBootTest(classes = {App.class})
class SsoUsersWithoutRoleGraphqlTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	/** organizationsQuery's ORGANIZATIONS_CORE, assembled. */
	private static final String ORGANIZATIONS = "query organizations { organizations { uuid name type"
			+ " approvalRoles { id displayView } terminology { featureSetLabel }"
			+ " ignoreViolation { licenseViolationRegexIgnore securityViolationRegexIgnore operationalViolationRegexIgnore }"
			+ " settings { justificationMandatory reviewItemPriorityLevels agentSessionIdleCloseHours branchSuffixMode"
			+ " vexComplianceFramework sidPurlMode sidAuthoritySegments fdaAssessmentNarrative fdaPatchesMayCeaseStatement"
			+ " fdaRiskTransferProcessRef fdaRiskIncreasesNotice ssoUsersWithoutRole } } }";
	private static final String UPDATE = "mutation updateOrganizationSettings($orgUuid: ID!, $settings: SettingsInput!) {"
			+ " updateOrganizationSettings(orgUuid: $orgUuid, settings: $settings) { uuid settings { ssoUsersWithoutRole } } }";
	private static final String TOTALS = "query totalsAnalytics($orgUuid: ID!) { totalsAnalytics(orgUuid: $orgUuid) }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	@Test
	void theSettingIsReadAndPatchedThroughTheUisDocuments() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		settingOf(admin, org);

		assertEquals("CREATE_WITHOUT_ROLE", patched(admin, org, "CREATE_WITHOUT_ROLE"));
		assertEquals("CREATE_WITHOUT_ROLE", settingOf(admin, org), "read back through the organizations query");
		assertEquals("REFUSE", patched(admin, org, "REFUSE"));
		assertEquals("REFUSE", settingOf(admin, org));
	}

	@Test
	void theStatisticsTotalsCarryTheUsersWithoutARole() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Map<String, Object> totals = ok(exec(admin(org), TOTALS, "orgUuid", org.getUuid().toString()), "totalsAnalytics");
		assertTrue(totals.containsKey("users_without_role"), totals.keySet().toString());
	}

	/** The organization's setting as the organizations query returns it; null while it has no settings (the UI reads REFUSE). */
	@SuppressWarnings("unchecked")
	private Object settingOf(UserData user, Organization org) {
		List<Map<String, Object>> orgs = ok(exec(user, ORGANIZATIONS), "organizations");
		Map<String, Object> mine = orgs.stream().filter(o -> org.getUuid().toString().equals(o.get("uuid"))).findFirst()
				.orElseThrow();
		Map<String, Object> settings = (Map<String, Object>) mine.get("settings");
		return null == settings ? null : settings.get("ssoUsersWithoutRole");
	}

	@SuppressWarnings("unchecked")
	private String patched(UserData admin, Organization org, String value) {
		Map<String, Object> o = ok(exec(admin, UPDATE, "orgUuid", org.getUuid().toString(),
				"settings", Map.of("ssoUsersWithoutRole", value)), "updateOrganizationSettings");
		return (String) ((Map<String, Object>) o.get("settings")).get("ssoUsersWithoutRole");
	}

	private UserData admin(Organization org) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Sso " + tag, "sso-no-role-" + tag + "@sso.io", true, List.of(org.getUuid()),
				"sso-no-role-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult exec(UserData user, String query, Object... vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sso-no-role-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		Map<String, Object> v = new LinkedHashMap<>();
		for (int i = 0; i < vars.length; i += 2) v.put((String) vars[i], vars[i + 1]);
		return dgsQueryExecutor.execute(query, v);
	}

	@SuppressWarnings("unchecked")
	private static <T> T ok(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}
}
