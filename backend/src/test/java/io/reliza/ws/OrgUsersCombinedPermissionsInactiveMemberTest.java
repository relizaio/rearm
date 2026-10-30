/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

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
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-104: the org Users query with combinedUserOrgPermissions selected must not fail because one member is
 * INACTIVE. The INACTIVE row answers an empty set (its stored grants stay in the row's permissions field),
 * active rows are unchanged, a caller without ORGANIZATION ADMIN is still refused the field, and the
 * active-member gate getUserDataWithOrg still answers empty for the inactive member.
 */
@SpringBootTest(classes = {App.class})
public class OrgUsersCombinedPermissionsInactiveMemberTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String USERS = "query($o: ID!) { users(orgUuid: $o, includeInactive: true) { uuid status "
			+ "permissions { permissions { scope object type } } "
			+ "combinedUserOrgPermissions { permissions { org scope object type functions } } } }";
	private boolean wasSealed;
	private boolean wasLicensed;

	/** The org with admin A, active member M and inactive member I (I was READ_WRITE before softDeleteUser). */
	private record Fixture(UUID org, UserData admin, UserData active, UUID inactive) {}

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		SecurityContextHolder.clearContext();
	}

	private UserData member(UUID org, PermissionType type) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("T " + tag, "t-" + tag + "@tpf104-inactive.io", true, List.of(org),
				"t-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, PermissionScope.ORGANIZATION, org, type, null, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private Fixture fixture() throws Exception {
		Organization o = testInitializer.obtainOrganization();
		UUID org = o.getUuid();
		UserData admin = member(org, PermissionType.ADMIN);
		UserData active = member(org, PermissionType.READ_WRITE);
		UserData inactive = member(org, PermissionType.READ_WRITE);
		assertTrue(userService.getUserDataWithOrg(inactive.getUuid(), org).isPresent(),
				"the member resolves through the active-member gate before deactivation");
		userService.softDeleteUser(inactive.getUuid(), WU);
		return new Fixture(org, admin, active, inactive.getUuid());
	}

	private ExecutionResult asPerson(UserData user, UUID org) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf104-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(USERS, Map.of("o", org.toString()));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(ExecutionResult r) {
		Map<String, Object> data = r.getData();
		assertNotNull(data, "data is served");
		List<Map<String, Object>> users = (List<Map<String, Object>>) data.get("users");
		assertNotNull(users, "the users list is served");
		return users;
	}

	private static Map<String, Object> row(List<Map<String, Object>> rows, UUID uuid) {
		return rows.stream().filter(m -> uuid.toString().equals(m.get("uuid"))).findFirst()
				.orElseThrow(() -> new AssertionError("no row for " + uuid));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> combined(Map<String, Object> row) {
		Map<String, Object> c = (Map<String, Object>) row.get("combinedUserOrgPermissions");
		assertNotNull(c, "combinedUserOrgPermissions is served for " + row.get("uuid"));
		return (List<Map<String, Object>>) c.get("permissions");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> stored(Map<String, Object> row) {
		Map<String, Object> p = (Map<String, Object>) row.get("permissions");
		assertNotNull(p, "permissions is served for " + row.get("uuid"));
		return (List<Map<String, Object>>) p.get("permissions");
	}

	private static boolean hasOrgGrant(List<Map<String, Object>> perms, UUID org, String type) {
		return perms != null && perms.stream().anyMatch(p -> "ORGANIZATION".equals(p.get("scope"))
				&& org.toString().equals(p.get("object")) && type.equals(p.get("type")));
	}

	/** Tests 1 and 2: every member listed, no error, inactive row empty with its stored grant kept, active rows unchanged. */
	@Test
	public void adminSeesEveryRowAndTheInactiveRowHasNoCombinedPermissions() throws Exception {
		Fixture f = fixture();
		ExecutionResult r = asPerson(f.admin(), f.org());
		assertTrue(r.getErrors().isEmpty(), "no error entry: " + r.getErrors());
		List<Map<String, Object>> rows = rows(r);

		Map<String, Object> inactive = row(rows, f.inactive());
		assertEquals("INACTIVE", inactive.get("status"));
		List<Map<String, Object>> inactiveCombined = combined(inactive);
		assertNotNull(inactiveCombined, "an empty list, not null");
		assertTrue(inactiveCombined.isEmpty(), "an inactive member has no effective permissions: " + inactiveCombined);
		assertTrue(hasOrgGrant(stored(inactive), f.org(), "READ_WRITE"), "the stored grant stays on the row");

		Map<String, Object> active = row(rows, f.active().getUuid());
		assertEquals("ACTIVE", active.get("status"));
		assertTrue(hasOrgGrant(combined(active), f.org(), "READ_WRITE"), "the active member keeps READ_WRITE");

		Map<String, Object> admin = row(rows, f.admin().getUuid());
		assertTrue(hasOrgGrant(combined(admin), f.org(), "ADMIN"), "the admin keeps ADMIN");
	}

	/** Test 3: a caller without ORGANIZATION ADMIN is still refused the field on every row, inactive included. */
	@Test
	public void nonAdminIsStillRefusedTheField() throws Exception {
		Fixture f = fixture();
		ExecutionResult r = asPerson(f.active(), f.org());
		List<GraphQLError> errors = r.getErrors();
		assertFalse(errors.isEmpty(), "the field is refused");
		assertTrue(errors.stream().anyMatch(e -> "Not authorized".equals(e.getMessage())), "refused as Not authorized: " + errors);
		List<Map<String, Object>> rows = rows(r);
		row(rows, f.inactive());
		row(rows, f.active().getUuid());
		for (Map<String, Object> m : rows) {
			assertNull(m.get("combinedUserOrgPermissions"), "no combined permissions for " + m.get("uuid"));
		}
	}

	/** Test 4: the active-member gate the other callers rely on still answers empty for the inactive member. */
	@Test
	public void activeMemberGateStillExcludesTheInactiveMember() throws Exception {
		Fixture f = fixture();
		assertTrue(userService.getUserDataWithOrg(f.inactive(), f.org()).isEmpty(),
				"getUserDataWithOrg is empty after softDeleteUser");
		assertTrue(userService.getUserDataWithOrg(f.active().getUuid(), f.org()).isPresent(),
				"an active member still resolves");
	}
}
