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
 * compiles in CE too.
 */
@SpringBootTest(classes = {App.class})
public class TeaProfileGraphqlIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private static final String SAVE = "mutation($o: ID!, $s: TeaProfileScope!, $ob: ID, $p: TeaProfileInput!) {"
			+ " saveTeaProfile(org: $o, scope: $s, object: $ob, profile: $p) { uuid scope object mode followedPerspective"
			+ " publishing visibility supportMetadata sources excludedCoverage minimumLifecycle revision } }";
	private static final String PROFILE = "query($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " teaProfile(org: $o, scope: $s, object: $ob) { uuid scope publishing } }";
	private static final String VIEW = "query($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " teaProfileEditorView(org: $o, scope: $s, object: $ob) { stored { uuid supportMetadata visibility }"
			+ " effective { status source sourceObject profile { uuid supportMetadata visibility }"
			+ " conflictingPerspectives { uuid name type } }"
			+ " parent { status source conflictingPerspectives { name } } publishedReleases supportInjection } }";
	private static final String LIST = "query($o: ID!) { teaProfilesOfOrg(org: $o) { uuid scope object } }";
	private static final String DELETE = "mutation($o: ID!, $s: TeaProfileScope!, $ob: ID) {"
			+ " deleteTeaProfile(org: $o, scope: $s, object: $ob) }";
	private static final String DISCOVERY = "query($o: ID!) { teaOrgDiscovery(org: $o) { org teaUuid apiBase wellKnownDocument } }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private OrganizationService organizationService;
	@Autowired private TeaProfileRepository teaProfileRepository;
	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private ComponentRepository componentRepository;
	@Autowired private RelizaConfigProps relizaConfigProps;
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

	// ---- fixtures ----

	private UserData user(Organization org, PermissionScope scope, UUID object) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea " + tag, "tea2-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tea2-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private UserData admin(Organization org) throws RelizaException {
		return user(org, PermissionScope.ORGANIZATION, org.getUuid());
	}

	private Component component(Organization org) throws RelizaException {
		return componentService.createComponent("tea2-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
	}

	/** The built-in defaults as an input, with the named changes. */
	private static Map<String, Object> profile(Object... changes) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("publishing", "DISABLED");
		p.put("visibility", "PRIVATE");
		p.put("dependencyDepth", "FULL");
		p.put("optionalDependencies", "INCLUDE");
		p.put("structure", "FLAT");
		p.put("sources", List.of("DELIVERABLE", "RELEASE", "SOURCE_CODE"));
		p.put("excludedCoverage", List.of("DEV", "TEST"));
		p.put("supportMetadata", "EXCLUDE");
		p.put("internalMetadata", "EXCLUDE");
		p.put("rawArtifacts", "NONE");
		p.put("productComponents", "PUBLISH_WITH_PRODUCT");
		p.put("minimumLifecycle", "ASSEMBLED");
		p.put("tei", "DISABLED");
		p.put("vulnerabilityDocuments", "NONE");
		for (int i = 0; i < changes.length; i += 2) p.put((String) changes[i], changes[i + 1]);
		return p;
	}

	private ExecutionResult exec(UserData user, String query, Object... vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-2-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		Map<String, Object> v = new LinkedHashMap<>();
		for (int i = 0; i < vars.length; i += 2) v.put((String) vars[i], vars[i + 1]);
		return dgsQueryExecutor.execute(query, v);
	}

	@SuppressWarnings("unchecked")
	private <T> T ok(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	private static void failsWith(ExecutionResult r, String fragment) {
		assertFalse(r.getErrors().isEmpty(), "expected an error containing " + fragment);
		assertTrue(r.getErrors().toString().contains(fragment), r.getErrors().toString());
	}

	private ExecutionResult save(UserData u, Organization org, String scope, UUID object, Map<String, Object> p) {
		return exec(u, SAVE, "o", org.getUuid().toString(), "s", scope, "ob", null == object ? null : object.toString(),
				"p", p);
	}

	private Map<String, Object> view(UserData u, Organization org, String scope, UUID object) {
		return ok(exec(u, VIEW, "o", org.getUuid().toString(), "s", scope, "ob",
				null == object ? null : object.toString()), "teaProfileEditorView");
	}

	@SuppressWarnings("unchecked")
	private static Object at(Map<String, Object> m, String path) {
		Object cur = m;
		for (String k : path.split("\\.")) cur = null == cur ? null : ((Map<String, Object>) cur).get(k);
		return cur;
	}

	private String orgTeaUuid(UserData u, Organization org) {
		List<Map<String, Object>> orgs = ok(exec(u, "{ organizations { uuid teaUuid } }"), "organizations");
		return (String) orgs.stream().filter(o -> org.getUuid().toString().equals(o.get("uuid"))).findFirst()
				.orElseThrow().get("teaUuid");
	}

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
		assertEquals(List.of("DELIVERABLE", "RELEASE", "SOURCE_CODE"), co.get("sources"));
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
		p.setScope("ORGANIZATION");
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
		ok(save(admin, org, "ORGANIZATION", null, profile("publishing", "ENABLED", "dependencyDepth", "TOP_LEVEL_ONLY")),
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
				profile("mode", "OVERRIDE", "visibility", "PUBLIC", "dependencyDepth", "TOP_LEVEL_ONLY")), "organization admin");
		ok(save(componentAdmin, org, "COMPONENT", c.getUuid(), profile("mode", "OVERRIDE", "visibility", "PRIVATE")),
				"saveTeaProfile");

		Organization other = testInitializer.obtainOrganization();
		Component foreign = component(other);
		failsWith(save(admin, org, "COMPONENT", foreign.getUuid(), profile("mode", "OVERRIDE")), "not found");
	}

	@Test
	public void supportMetadataFollowsTheOrgInjectionSwitch() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		String injection = "mutation($o: ID!, $v: SupportInjectionSetting) {"
				+ " updateOrganizationSettings(orgUuid: $o, settings: { supportInjection: $v }) { uuid } }";

		failsWith(save(admin, org, "ORGANIZATION", null, profile("supportMetadata", "INCLUDE")), "supportInjection");
		ok(exec(admin, injection, "o", org.getUuid().toString(), "v", "ENABLED"), "updateOrganizationSettings");
		ok(save(admin, org, "ORGANIZATION", null, profile("supportMetadata", "INCLUDE")), "saveTeaProfile");
		ok(exec(admin, injection, "o", org.getUuid().toString(), "v", "DISABLED"), "updateOrganizationSettings");

		Map<String, Object> v = view(admin, org, "ORGANIZATION", null);
		assertEquals("EXCLUDE", at(v, "effective.profile.supportMetadata"));
		assertEquals("INCLUDE", at(v, "stored.supportMetadata"));
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
	}
}
