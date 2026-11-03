/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.LicenseStatus;
import io.reliza.service.OrgTotalsCacheService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-139 (architecture round 2, 6.7): the JSON the Statistics page receives from
 * {@code Query.totalsAnalytics} after the cache: exactly twelve keys, the four cached ones and
 * counted_at present and null while pending, numbers and an ISO instant once counted, and the
 * organization read check in front of it unchanged. The contract of section 10 for TPF-140.
 */
@SpringBootTest(classes = {App.class})
class TotalsAnalyticsContractTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String TOTALS = "query($o: ID!) { totalsAnalytics(orgUuid: $o) }";
	private static final List<String> NUMBER_KEYS = List.of("components", "products", "vcs", "branches",
			"feature_sets", "admin_and_write_users", "read_only_users");
	private static final List<String> CACHED_KEYS = List.of("releases", "artifacts", "commits", "deliverables");

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private OrgTotalsCacheService orgTotalsCacheService;
	@Autowired private JdbcTemplate jdbc;
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

	private UserData admin(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Admin " + tag, "tpf139-" + tag + "@totals.io", true, List.of(org.getUuid()),
				"tpf139-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult totalsAs(UserData user, Organization org) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf-139-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(TOTALS, Map.of("o", org.getUuid().toString()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> totals(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("totalsAnalytics");
	}

	private static void assertNumberKeys(Map<String, Object> m) {
		for (String key : NUMBER_KEYS) {
			assertInstanceOf(Number.class, m.get(key), key + " in " + m);
		}
	}

	/** 6.7.1 */
	@Test
	void pendingCountsAreNullAndTheMapHasTwelveKeys() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Map<String, Object> m = totals(totalsAs(admin(org), org));
		Set<String> keys = Set.of("components", "products", "vcs", "branches", "feature_sets", "admin_and_write_users",
				"read_only_users", "releases", "artifacts", "commits", "deliverables", "counted_at");
		assertEquals(keys, m.keySet());
		assertNumberKeys(m);
		for (String key : CACHED_KEYS) {
			assertTrue(m.containsKey(key), key);
			assertNull(m.get(key), key);
		}
		assertTrue(m.containsKey("counted_at"));
		assertNull(m.get("counted_at"));
	}

	/** 6.7.2 */
	@Test
	void countedValuesAreNumbersAndCountedAtIsAnInstant() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = UUID.randomUUID();
		jdbc.update("INSERT INTO rearm.releases (uuid, record_data) VALUES (?, cast(? as jsonb))", release,
				"{\"org\":\"" + org.getUuid() + "\",\"status\":\"ACTIVE\"}");
		try {
			assertEquals(List.of(), orgTotalsCacheService.countAndStampOrg(org.getUuid()));
			Map<String, Object> m = totals(totalsAs(admin(org), org));
			assertEquals(12, m.size(), m.toString());
			assertNumberKeys(m);
			assertEquals(1L, ((Number) m.get("releases")).longValue());
			for (String key : List.of("artifacts", "commits", "deliverables")) {
				assertEquals(0L, ((Number) m.get(key)).longValue(), key);
			}
			String countedAt = assertInstanceOf(String.class, m.get("counted_at"));
			assertTrue(countedAt.endsWith("Z"), countedAt);
			Instant.parse(countedAt);
		} finally {
			jdbc.update("DELETE FROM rearm.releases WHERE uuid = ?", release);
		}
	}

	/** 6.7.3 */
	@Test
	void anAdminOfAnotherOrganizationIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		ExecutionResult r = totalsAs(admin(other), org);
		assertFalse(r.getErrors().isEmpty(), "a foreign admin must be refused");
		Map<?, ?> data = (Map<?, ?>) r.getData();
		assertTrue(null == data || null == data.get("totalsAnalytics"), String.valueOf(data));
	}
}
