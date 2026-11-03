/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.TeaProfileGraphqlIntegrationTestBase;

/**
 * TPF-142, architecture section 4 rows 11 and 12: the global-administrator query and mutations of the TEA host
 * assignment, their gate, the allow-list they offer (the base's relizaprops.teaCustomDomains fixture plus the
 * baseuri host, localhost in the test context) and the refusal texts as GraphQL errors.
 */
public class TeaHostAssignmentGraphqlIntegrationTest extends TeaProfileGraphqlIntegrationTestBase {

	static final String LIST_HOSTS = "{ teaHostAssignments { host org orgName isBaseuriHost inAllowList teaServing } }";
	static final String ASSIGN = "mutation($h: String!, $o: ID!) { assignTeaHost(host: $h, org: $o) {"
			+ " host org orgName isBaseuriHost inAllowList teaServing } }";
	static final String UNASSIGN = "mutation($h: String!) { unassignTeaHost(host: $h) }";

	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	@AfterEach
	void clearHostMap() {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	private UserData globalAdmin() throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Ga " + tag, "tpf142-ga-" + tag + "@tea.io", true, List.of(), "tpf142-" + UUID.randomUUID(),
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		jdbcTemplate.update("UPDATE rearm.users SET record_data = jsonb_set(record_data, '{" + CommonVariables.GLOBAL_ADMIN_FIELD
				+ "}', 'true'::jsonb) WHERE uuid = ?", u.getUuid());
		UserData g = userService.getUserData(u.getUuid()).orElseThrow();
		assertTrue(g.isGlobalAdmin());
		return g;
	}

	private UserData member(Organization org, PermissionType type) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Mem " + tag, "tpf142-m-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tpf142-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), type, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult anonymous(String query, Object... vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		Map<String, Object> v = new LinkedHashMap<>();
		for (int i = 0; i < vars.length; i += 2) v.put((String) vars[i], vars[i + 1]);
		return dgsQueryExecutor.execute(query, v);
	}

	private static String nameOf(Organization o) {
		return OrganizationData.orgDataFromDbRecord(o).getName();
	}

	private static String message(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "expected a GraphQL error");
		return r.getErrors().get(0).getMessage();
	}

	private Map<String, Object> rowOf(UserData ga, String host) {
		List<Map<String, Object>> rows = ok(exec(ga, LIST_HOSTS), "teaHostAssignments");
		return rows.stream().filter(m -> host.equals(m.get("host"))).findFirst().orElseThrow();
	}

	// row 11
	@Test
	void theAssignmentIsGlobalAdministratorsOnly() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		UserData ga = globalAdmin();
		List<UserData> refused = List.of(admin(a), member(a, PermissionType.READ_WRITE), member(a, PermissionType.READ_ONLY));
		for (UserData u : refused) {
			failsWith(exec(u, LIST_HOSTS), "Not authorized");
			failsWith(exec(u, ASSIGN, "h", "tea-a.test", "o", a.getUuid().toString()), "Not authorized");
			assertNull(rowOf(ga, "tea-a.test").get("org"), "nothing written");
		}
		assertFalse(anonymous(LIST_HOSTS).getErrors().isEmpty(), "anonymous");
		assertFalse(anonymous(ASSIGN, "h", "tea-a.test", "o", a.getUuid().toString()).getErrors().isEmpty(), "anonymous");
		assertNull(rowOf(ga, "tea-a.test").get("org"), "nothing written by an anonymous caller");

		ok(exec(ga, ASSIGN, "h", "tea-a.test", "o", a.getUuid().toString()), "assignTeaHost");
		for (UserData u : refused) {
			failsWith(exec(u, UNASSIGN, "h", "tea-a.test"), "Not authorized");
		}
		assertFalse(anonymous(UNASSIGN, "h", "tea-a.test").getErrors().isEmpty(), "anonymous");
		assertEquals(a.getUuid().toString(), rowOf(ga, "tea-a.test").get("org"), "still assigned");
		assertEquals(Boolean.TRUE, ok(exec(ga, UNASSIGN, "h", "tea-a.test"), "unassignTeaHost"));
	}

	// row 12
	@Test
	void theListOffersTheAllowListAndTheBaseuriHostAndTheRefusalsReachTheClient() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		Organization b = testInitializer.obtainOrganization();
		UserData ga = globalAdmin();
		List<Map<String, Object>> rows = ok(exec(ga, LIST_HOSTS), "teaHostAssignments");
		assertEquals(List.of("tea-a.test", "tea-b.test", "tea-c.test", "localhost"), rows.stream().map(m -> m.get("host")).toList());
		for (Map<String, Object> m : rows.subList(0, 3)) {
			assertNull(m.get("org"), m.toString());
			assertNull(m.get("orgName"), m.toString());
			assertNull(m.get("teaServing"), m.toString());
			assertEquals(Boolean.FALSE, m.get("isBaseuriHost"), m.toString());
			assertEquals(Boolean.TRUE, m.get("inAllowList"), m.toString());
		}
		assertEquals(Boolean.TRUE, rows.get(3).get("isBaseuriHost"));

		Map<String, Object> assigned = ok(exec(ga, ASSIGN, "h", "Tea-A.test", "o", a.getUuid().toString()), "assignTeaHost");
		assertEquals("tea-a.test", assigned.get("host"));
		Map<String, Object> row = rowOf(ga, "tea-a.test");
		assertEquals(a.getUuid().toString(), row.get("org"));
		assertEquals(nameOf(a), row.get("orgName"));
		assertEquals("NONE", row.get("teaServing"));

		// every refusal text of row 10, as the GraphQL error message
		assertEquals("Host tea-x.test is not in the TEA custom domain allow-list (relizaprops.teaCustomDomains) and is not the instance host",
				message(exec(ga, ASSIGN, "h", "tea-x.test", "o", b.getUuid().toString())));
		assertEquals("Host tea-a.test is already assigned to organization " + nameOf(a),
				message(exec(ga, ASSIGN, "h", "tea-a.test", "o", b.getUuid().toString())));
		assertEquals("Organization " + nameOf(a) + " already holds host tea-a.test; unassign it first",
				message(exec(ga, ASSIGN, "h", "tea-b.test", "o", a.getUuid().toString())));
		assertEquals("Organization not found", message(exec(ga, ASSIGN, "h", "tea-b.test", "o", UUID.randomUUID().toString())));
		assertEquals("host must be a bare lowercase DNS name", message(exec(ga, ASSIGN, "h", "tea}", "o", b.getUuid().toString())));
		assertEquals("Host tea-b.test is not assigned", message(exec(ga, UNASSIGN, "h", "tea-b.test")));
		organizationService.setTeaServing(a.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
		assertEquals("Organization " + nameOf(a) + " serves TEA on tea-a.test; set its TEA serving to NONE or LEGACY_UUID first",
				message(exec(ga, UNASSIGN, "h", "tea-a.test")));
		organizationService.setTeaServing(a.getUuid(), TeaServing.NONE, TeaWellKnownHosting.NONE, WU);
		assertEquals(Boolean.TRUE, ok(exec(ga, UNASSIGN, "h", "tea-a.test"), "unassignTeaHost"));
		assertNull(rowOf(ga, "tea-a.test").get("org"), "unassigned again");

		// the baseuri host can be assigned and unassigned too
		Map<String, Object> base = ok(exec(ga, ASSIGN, "h", "localhost", "o", b.getUuid().toString()), "assignTeaHost");
		assertEquals(Boolean.TRUE, base.get("isBaseuriHost"));
		assertEquals(b.getUuid().toString(), rowOf(ga, "localhost").get("org"));
		assertEquals(Boolean.TRUE, ok(exec(ga, UNASSIGN, "h", "localhost"), "unassignTeaHost"));
		assertNull(rowOf(ga, "localhost").get("org"));
	}
}
