/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseBranchScope;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.ws.App;

/**
 * TPF-135 architecture-2 section 4.1 test 5, and test 4 (d): through the GraphQL fetchers, a
 * document without branchScope gets the schema default ACTIVE_BRANCHES on both fields, and
 * ALL_BRANCHES lists the archived branch; the SBOM search's perspective filter composes with the
 * scope. The test installs its own security context (an org admin).
 */
@SpringBootTest(classes = {App.class})
public class ReleaseBranchScopeGraphqlIntegrationTest extends ReleaseBranchScopeTestBase {

	private static final String CVE_DEFAULT = "query($o: ID!, $c: String!) { searchReleasesByCveId(org: $o, cveId: $c)"
			+ " { totalReleases components { branches { releases { uuid } } } } }";
	private static final String CVE_ALL = "query($o: ID!, $c: String!) { searchReleasesByCveId(org: $o, cveId: $c,"
			+ " branchScope: ALL_BRANCHES) { totalReleases components { branches { releases { uuid } } } } }";
	private static final String SBOM_DEFAULT = "query($o: ID!, $s: [ID]!, $p: ID) { releasesBySbomComponents(orgUuid: $o,"
			+ " sbomComponentUuids: $s, perspectiveUuid: $p) { branches { releases { uuid } } } }";
	private static final String SBOM_ALL = "query($o: ID!, $s: [ID]!, $p: ID) { releasesBySbomComponents(orgUuid: $o,"
			+ " sbomComponentUuids: $s, perspectiveUuid: $p, branchScope: ALL_BRANCHES) { branches { releases { uuid } } } }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
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

	private void signInAsAdminOf(UUID org) throws RelizaException {
		String tag = suffix();
		User u = userService.createUser("Admin " + tag, "tpf135-" + tag + "@scope.io", true, List.of(org),
				"tpf135-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, PermissionScope.ORGANIZATION, org,
				PermissionType.ADMIN, List.of(), null, WU);
		UserData user = userService.getUserData(u.getUuid()).orElseThrow();
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf-135-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	private Object data(String query, Map<String, Object> vars, String field) {
		ExecutionResult r = dgsQueryExecutor.execute(query, vars);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return ((Map<?, ?>) r.getData()).get(field);
	}

	/** Every release uuid under a list of ComponentWithBranches maps. */
	private static Set<UUID> releaseUuids(Object components) {
		Set<UUID> out = new TreeSet<>();
		for (Object c : (List<?>) components) {
			for (Object b : (List<?>) ((Map<?, ?>) c).get("branches")) {
				for (Object r : (List<?>) ((Map<?, ?>) b).get("releases")) {
					out.add(UUID.fromString((String) ((Map<?, ?>) r).get("uuid")));
				}
			}
		}
		return out;
	}

	@Test
	public void theCveSearchDefaultsToActiveBranchesWhenTheArgumentIsAbsent() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String cve = cveId();
		ComponentAndBranch base = component(org, ComponentType.COMPONENT);
		UUID live = cveRelease(org, base, cve, 20);
		UUID archived = cveRelease(org, archivedBranch(base), cve, 10);
		signInAsAdminOf(org);
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("o", org.toString());
		vars.put("c", cve);

		Map<?, ?> byDefault = (Map<?, ?>) data(CVE_DEFAULT, vars, "searchReleasesByCveId");
		assertEquals(Set.of(live), releaseUuids(byDefault.get("components")));
		assertEquals(1, byDefault.get("totalReleases"));

		Map<?, ?> all = (Map<?, ?>) data(CVE_ALL, vars, "searchReleasesByCveId");
		assertEquals(Set.of(live, archived), releaseUuids(all.get("components")));
		assertEquals(2, all.get("totalReleases"));
	}

	@Test
	public void orDefaultResolvesNullToActiveBranches() {
		assertEquals(ReleaseBranchScope.ACTIVE_BRANCHES, ReleaseBranchScope.orDefault(null));
		assertEquals(ReleaseBranchScope.ALL_BRANCHES, ReleaseBranchScope.orDefault(ReleaseBranchScope.ALL_BRANCHES));
	}
}
