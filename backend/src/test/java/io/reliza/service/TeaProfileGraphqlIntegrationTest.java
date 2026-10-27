/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.ws.App;
import io.reliza.ws.RelizaConfigProps;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task TEA-2, design 4.6: TEA profiles through the user GraphQL API against the test database --
 * CRUD, the editor view, the NULLS NOT DISTINCT key, the TEA id mints, authorization and
 * discovery. The PERSPECTIVE cases run in ReARM Pro only and go through the API, so the class
 * compiles in CE too. The TEA-9 gates are in TeaProfileGatesIntegrationTest.
 */
public class TeaProfileGraphqlIntegrationTest extends TeaProfileGraphqlIntegrationTestBase {

	// ---- cases ----

	@Test
	public void crudAndTheEditorView() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);

		Map<String, Object> o = ok(save(admin, org, "ORGANIZATION", null, profile()), "saveTeaProfile");
		assertEquals("ORGANIZATION", o.get("scope"));
		Map<String, Object> read = ok(exec(admin, PROFILE, "o", org.getUuid().toString(), "s", "ORGANIZATION"),
				"teaProfile");
		assertEquals(o.get("uuid"), read.get("uuid"));

		Map<String, Object> fresh = view(admin, org, "COMPONENT", c.getUuid());
		assertNull(fresh.get("stored"));
		assertEquals("ORGANIZATION", at(fresh, "effective.source"));
		assertEquals("ORGANIZATION", at(fresh, "parent.source"));
		assertEquals(0, fresh.get("publishedReleases"));
		assertEquals("DISABLED", fresh.get("supportInjection"));

		Map<String, Object> co = ok(save(admin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE")),
				"saveTeaProfile");
		assertEquals(List.of("default"), co.get("profiles"));
		Map<String, Object> withRow = view(admin, org, "COMPONENT", c.getUuid());
		assertEquals(co.get("uuid"), at(withRow, "stored.uuid"));
		assertEquals("COMPONENT", at(withRow, "effective.source"));
		assertEquals(c.getUuid().toString(), at(withRow, "effective.sourceObject"));

		List<Map<String, Object>> rows = ok(exec(admin, LIST, "o", org.getUuid().toString()), "teaProfilesOfOrg");
		assertEquals(Set.of(o.get("uuid"), co.get("uuid")), Set.copyOf(rows.stream().map(r -> r.get("uuid")).toList()));

		Object[] del = { "o", org.getUuid().toString(), "s", "COMPONENT", "ob", c.getUuid().toString() };
		assertEquals(Boolean.TRUE, ok(exec(admin, DELETE, del), "deleteTeaProfile"));
		assertEquals(Boolean.FALSE, ok(exec(admin, DELETE, del), "deleteTeaProfile"));
		failsWith(exec(admin, DELETE, "o", org.getUuid().toString(), "s", "ORGANIZATION"), "cannot be deleted");
	}

	@Test
	public void theOrganizationRowIsUniqueWithItsNullObject() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		teaProfileRepository.save(row(org));
		assertThrows(DataIntegrityViolationException.class, () -> teaProfileRepository.save(row(org)));
	}

	private static TeaProfile row(UUID org) {
		TeaProfile p = new TeaProfile();
		p.setOrg(org);
		p.setScope(TeaProfileScope.ORGANIZATION);
		p.setRecordData(TeaProfileData.builtInDefault(org).toRecordData());
		return p;
	}

	@Test
	public void theFirstEnabledSaveMintsTheOrgTeaIdOnce() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		ok(save(admin, org, "ORGANIZATION", null, profile()), "saveTeaProfile");
		assertNull(orgTeaUuid(admin, org), "a DISABLED save mints nothing");

		ok(save(admin, org, "ORGANIZATION", null, profile("publishing", "ENABLED")), "saveTeaProfile");
		String minted = orgTeaUuid(admin, org);
		assertNotNull(minted);
		ok(save(admin, org, "ORGANIZATION", null, profile("publishing", "ENABLED", "rawArtifacts", "NON_BOM")),
				"saveTeaProfile");
		assertEquals(minted, orgTeaUuid(admin, org), "a second ENABLED save keeps it");
		assertEquals(org.getUuid(), organizationRepository.findByTeaUuid(minted).orElseThrow().getUuid());

		Organization racing = testInitializer.obtainOrganization();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		Set<UUID> received = ConcurrentHashMap.newKeySet();
		try {
			List<Future<UUID>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return organizationService.ensureTeaUuid(racing.getUuid(), WU);
				}));
			}
			start.countDown();
			for (Future<UUID> f : futures) received.add(f.get(60, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, received.size(), "both callers receive the one id: " + received);
		assertEquals(received.iterator().next().toString(), orgTeaUuid(admin(racing), racing));
	}

	@Test
	public void aComponentTeaIdIsMintedOnce() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UUID minted = componentService.ensureTeaUuid(c.getUuid(), WU);
		assertEquals(minted, componentService.ensureTeaUuid(c.getUuid(), WU));
		List<Map<String, Object>> comps = ok(exec(admin, "query($o: ID!) { components(orgUuid: $o, componentType: COMPONENT)"
				+ " { uuid teaUuid } }", "o", org.getUuid().toString()), "components");
		assertEquals(minted.toString(), comps.stream().filter(x -> c.getUuid().toString().equals(x.get("uuid")))
				.findFirst().orElseThrow().get("teaUuid"));
		assertEquals(c.getUuid(), componentRepository.findByTeaUuid(minted.toString()).orElseThrow().getUuid());
	}

	@Test
	public void publicNeedsAnOrganizationAdmin() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UserData componentAdmin = user(org, PermissionScope.COMPONENT, c.getUuid());

		ok(save(componentAdmin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE")), "saveTeaProfile");
		failsWith(save(componentAdmin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "visibility", "PUBLIC")),
				"organization admin");
		ok(save(admin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "visibility", "PUBLIC")), "saveTeaProfile");
		failsWith(save(componentAdmin, org, "COMPONENT", c.getUuid(),
				profile("mode", "OVERRIDE", "visibility", "PUBLIC", "rawArtifacts", "NON_BOM")), "organization admin");
		ok(save(componentAdmin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "visibility", "PRIVATE")),
				"saveTeaProfile");

		Organization other = testInitializer.obtainOrganization();
		Component foreign = component(other);
		failsWith(save(admin, org, "COMPONENT", foreign.getUuid(), profile("mode", "OVERRIDE")), "not found");
	}

	// ---- TEA-27, design 4.11 (threat model M4): the names of the TEA settings resolve in the catalog ----

	private static final String SAVE_SBOM = "mutation($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!,"
			+ " $p: SbomProfileInput!) { saveSbomProfile(org: $o, scope: $s, object: $ob, name: $n, profile: $p)"
			+ " { uuid name } }";
	private static final String DELETE_SBOM = "mutation($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!) {"
			+ " deleteSbomProfile(org: $o, scope: $s, object: $ob, name: $n) }";

	private static Map<String, Object> recipe() {
		Map<String, Object> p = new java.util.LinkedHashMap<>();
		p.put("mode", "OVERRIDE");
		p.put("dependencyDepth", "TOP_LEVEL_ONLY");
		p.put("optionalDependencies", "INCLUDE");
		p.put("structure", "FLAT");
		p.put("sources", List.of("DELIVERABLE", "RELEASE", "SOURCE_CODE"));
		p.put("excludedCoverage", List.of());
		p.put("supportMetadata", "EXCLUDE");
		p.put("internalMetadata", "EXCLUDE");
		p.put("vulnerabilityDocuments", "NONE");
		return p;
	}

	@Test
	public void anEnabledRowNamingAProfileThatDoesNotResolveIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		String name = io.reliza.model.ComponentData.dataFromRecord(c).getName();

		failsWith(save(admin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "publishing", "ENABLED",
				"profiles", List.of("nope"))), "SBOM profile nope does not resolve at component " + name);
		// F-4: the same row DISABLED generates nothing and is accepted.
		Map<String, Object> disabled = ok(save(admin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE",
				"profiles", List.of("nope"))), "saveTeaProfile");
		assertEquals(List.of("nope"), disabled.get("profiles"));

		// A scope-local name defined at the component is accepted on the component's own ENABLED row.
		ok(exec(admin, SAVE_SBOM, "o", org.getUuid().toString(), "s", "COMPONENT", "ob", c.getUuid().toString(), "n",
				"premarket", "p", recipe()), "saveSbomProfile");
		Map<String, Object> enabled = ok(save(admin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE",
				"publishing", "ENABLED", "profiles", List.of("premarket"))), "saveTeaProfile");
		assertEquals(List.of("premarket"), enabled.get("profiles"));

		// The catalog's delete guard: the component's TEA publishing settings name it and nothing else defines it.
		failsWith(exec(admin, DELETE_SBOM, "o", org.getUuid().toString(), "s", "COMPONENT", "ob", c.getUuid().toString(),
				"n", "premarket"), "the TEA publishing settings of component " + name + " name it");
		// Pointed at default, the profile can go.
		ok(save(admin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "publishing", "ENABLED")),
				"saveTeaProfile");
		assertEquals(Boolean.TRUE, ok(exec(admin, DELETE_SBOM, "o", org.getUuid().toString(), "s", "COMPONENT", "ob",
				c.getUuid().toString(), "n", "premarket"), "deleteSbomProfile"));
	}

	@Test
	public void aFollowRowSendsNoProfiles() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UUID p = perspective(admin, org, "names");
		perspectivesOf(admin, c, p);
		Map<String, Object> follow = profile("mode", "FOLLOW_PERSPECTIVE", "followedPerspective", p.toString());
		failsWith(save(admin, org, "COMPONENT", c.getUuid(), follow), "profiles must be empty when mode is FOLLOW_PERSPECTIVE");
		follow.remove("profiles");
		Map<String, Object> saved = ok(save(admin, org, "COMPONENT", c.getUuid(), follow), "saveTeaProfile");
		assertNull(saved.get("profiles"));
	}

	@Test
	public void discoveryAppearsWithTheMint() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Map<String, Object> before = ok(exec(admin, DISCOVERY, "o", org.getUuid().toString()), "teaOrgDiscovery");
		assertNull(before.get("teaUuid"));
		assertNull(before.get("apiBase"));
		assertNull(before.get("wellKnownDocument"));

		ok(save(admin, org, "ORGANIZATION", null, profile("publishing", "ENABLED")), "saveTeaProfile");
		Map<String, Object> after = ok(exec(admin, DISCOVERY, "o", org.getUuid().toString()), "teaOrgDiscovery");
		String base = StringUtils.stripEnd(relizaConfigProps.getBaseuri(), "/") + "/tea/" + after.get("teaUuid");
		assertEquals(base, after.get("apiBase"));
		assertEquals(Utils.OM.readTree("{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"" + base
				+ "\",\"versions\":[\"1.0.0\"],\"priority\":1}]}"), Utils.OM.readTree((String) after.get("wellKnownDocument")));
	}

	@Test
	public void perspectiveProfilesConflictsAndFollow() throws RelizaException {
		if (LicensingConstants.isOssEdition()) {
			Organization org = testInitializer.obtainOrganization();
			failsWith(save(admin(org), org, "PERSPECTIVE", UUID.randomUUID(), profile()), "ReARM Pro");
			return;
		}
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		String o = org.getUuid().toString();
		String create = "mutation($o: ID!, $n: String!) { createPerspective(org: $o, name: $n) { uuid } }";
		UUID p1 = UUID.fromString((String) ((Map<?, ?>) ok(exec(admin, create, "o", o, "n", "tea2-alpha-" + UUID.randomUUID()),
				"createPerspective")).get("uuid"));
		UUID p2 = UUID.fromString((String) ((Map<?, ?>) ok(exec(admin, create, "o", o, "n", "tea2-beta-" + UUID.randomUUID()),
				"createPerspective")).get("uuid"));
		UUID lonely = UUID.fromString((String) ((Map<?, ?>) ok(exec(admin, create, "o", o, "n", "tea2-gone-" + UUID.randomUUID()),
				"createPerspective")).get("uuid"));
		Component c = component(org);
		ok(exec(admin, "mutation($c: ID!, $p: [ID!]!) { setPerspectivesOnComponent(componentUuid: $c, perspectiveUuids: $p)"
				+ " { uuid } }", "c", c.getUuid().toString(), "p", List.of(p1.toString(), p2.toString())),
				"setPerspectivesOnComponent");

		ok(save(admin, org, "PERSPECTIVE", p1, profile("publishing", "ENABLED")), "saveTeaProfile");
		assertEquals("PERSPECTIVE", at(view(admin, org, "COMPONENT", c.getUuid()), "effective.source"));

		ok(save(admin, org, "PERSPECTIVE", p2, profile()), "saveTeaProfile");
		Map<String, Object> conflicted = view(admin, org, "COMPONENT", c.getUuid());
		assertEquals("CONFLICT", at(conflicted, "effective.status"));
		assertEquals(2, ((List<?>) at(conflicted, "effective.conflictingPerspectives")).size());
		assertEquals("CONFLICT", at(conflicted, "parent.status"));

		Map<String, Object> follow = ok(save(admin, org, "COMPONENT", c.getUuid(),
				Map.of("mode", "FOLLOW_PERSPECTIVE", "followedPerspective", p2.toString())), "saveTeaProfile");
		assertNull(follow.get("publishing"));
		Map<String, Object> followed = view(admin, org, "COMPONENT", c.getUuid());
		assertEquals("FOLLOWED_PERSPECTIVE", at(followed, "effective.source"));
		assertEquals(p2.toString(), at(followed, "effective.sourceObject"));

		ok(save(admin, org, "PERSPECTIVE", lonely, profile()), "saveTeaProfile");
		ok(exec(admin, "mutation($u: ID!) { deletePerspective(uuid: $u) { uuid } }", "u", lonely.toString()),
				"deletePerspective");
		List<Map<String, Object>> rows = ok(exec(admin, LIST, "o", o), "teaProfilesOfOrg");
		assertTrue(rows.stream().noneMatch(r -> lonely.toString().equals(r.get("object"))), rows.toString());
		assertEquals(3, rows.size(), "the two perspective rows and the follow row stay");

		// The deleted (ARCHIVED) perspective cannot get its row back, PUBLIC or not (TEA-2 run 2, T-3).
		failsWith(save(admin, org, "PERSPECTIVE", lonely, profile("publishing", "ENABLED", "visibility", "PUBLIC")),
				"Perspective not found");
		failsWith(save(admin, org, "PERSPECTIVE", lonely, profile()), "Perspective not found");
		assertEquals(3, ((List<?>) ok(exec(admin, LIST, "o", o), "teaProfilesOfOrg")).size());
	}
}
