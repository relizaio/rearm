/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.repositories.SbomProfileRepository;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;

/**
 * Task TEA-27, design 4.10 (threat model M3 and M5): the SBOM profile catalog through GraphQL. Reads
 * need READ on the scope's object and writes ADMIN, with no organization-admin gate; an object of
 * another organization answers "not found" for every operation; the two programmatic reads take the
 * organization from the key. Design 4.13: a perspective's rows go with the perspective.
 */
public class SbomProfileGraphqlIntegrationTest extends TeaProfileGraphqlIntegrationTestBase {

	private static final String SAVE_SBOM = "mutation($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!,"
			+ " $p: SbomProfileInput!) { saveSbomProfile(org: $o, scope: $s, object: $ob, name: $n, profile: $p)"
			+ " { uuid org scope object name mode description dependencyDepth supportMetadata revision } }";
	private static final String LIST_SBOM = "query($o: ID!, $s: SettingsScope!, $ob: ID) {"
			+ " sbomProfiles(org: $o, scope: $s, object: $ob) { uuid name scope object } }";
	private static final String ORG_SBOM = "query($o: ID!) { sbomProfilesOfOrg(org: $o) { uuid name scope } }";
	private static final String GET_SBOM = "query($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!) {"
			+ " sbomProfile(org: $o, scope: $s, object: $ob, name: $n) { uuid name dependencyDepth } }";
	private static final String EFFECTIVE_SBOM = "query($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!) {"
			+ " sbomProfileEffective(org: $o, scope: $s, object: $ob, name: $n) { status source sourceObject"
			+ " profile { uuid name dependencyDepth supportMetadata } conflictingPerspectives { name } } }";
	private static final String CATALOG_SBOM = "query($o: ID!, $s: SettingsScope!, $ob: ID) {"
			+ " sbomProfileCatalog(org: $o, scope: $s, object: $ob) { name resolution { status source } } }";
	private static final String VIEW_SBOM = "query($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!) {"
			+ " sbomProfileEditorView(org: $o, scope: $s, object: $ob, name: $n) { stored { uuid }"
			+ " effective { status source } parent { status source profile { uuid } } supportInjection } }";
	private static final String DELETE_SBOM = "mutation($o: ID!, $s: SettingsScope!, $ob: ID, $n: String!) {"
			+ " deleteSbomProfile(org: $o, scope: $s, object: $ob, name: $n) }";
	private static final String LIST_KEY = "query($s: SettingsScope!, $ob: ID) {"
			+ " sbomProfilesProgrammatic(scope: $s, object: $ob) { uuid name org } }";
	private static final String EFFECTIVE_KEY = "query($s: SettingsScope!, $ob: ID, $n: String!) {"
			+ " sbomProfileEffectiveProgrammatic(scope: $s, object: $ob, name: $n) { status source profile { uuid org } } }";

	@Autowired private ApiKeyService apiKeyService;
	@Autowired private SbomProfileRepository sbomProfileRepository;

	private static Map<String, Object> recipe(Object... changes) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("dependencyDepth", "FULL");
		p.put("optionalDependencies", "INCLUDE");
		p.put("structure", "FLAT");
		p.put("sources", List.of("DELIVERABLE", "RELEASE", "SOURCE_CODE"));
		p.put("excludedCoverage", List.of("DEV", "TEST"));
		p.put("supportMetadata", "EXCLUDE");
		p.put("internalMetadata", "EXCLUDE");
		p.put("vulnerabilityDocuments", "NONE");
		for (int i = 0; i < changes.length; i += 2) p.put((String) changes[i], changes[i + 1]);
		return p;
	}

	private static String s(UUID u) {
		return null == u ? null : u.toString();
	}

	private ExecutionResult saveSbom(UserData u, Organization org, String scope, UUID object, String name,
			Map<String, Object> p) {
		return exec(u, SAVE_SBOM, "o", s(org.getUuid()), "s", scope, "ob", s(object), "n", name, "p", p);
	}

	private ExecutionResult effective(UserData u, Organization org, String scope, UUID object, String name) {
		return exec(u, EFFECTIVE_SBOM, "o", s(org.getUuid()), "s", scope, "ob", s(object), "n", name);
	}

	private ExecutionResult deleteSbom(UserData u, Organization org, String scope, UUID object, String name) {
		return exec(u, DELETE_SBOM, "o", s(org.getUuid()), "s", scope, "ob", s(object), "n", name);
	}

	private UserData reader(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea27 " + tag, "tea27-" + tag + "@tea.io", true, List.of(org.getUuid()),
				"tea27-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	@Test
	public void saveListGetEffectiveAndDelete() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);

		Map<String, Object> o = ok(saveSbom(admin, org, "ORGANIZATION", null, "default",
				recipe("description", "Org shape")), "saveSbomProfile");
		assertEquals("ORGANIZATION", o.get("scope"));
		assertEquals("default", o.get("name"));
		assertEquals(org.getUuid().toString(), o.get("org"));
		assertNull(o.get("object"));
		assertEquals("Org shape", o.get("description"));
		assertEquals(0, o.get("revision"));

		List<Map<String, Object>> list = ok(exec(admin, LIST_SBOM, "o", s(org.getUuid()), "s", "ORGANIZATION"),
				"sbomProfiles");
		assertEquals(List.of(o.get("uuid")), list.stream().map(r -> r.get("uuid")).toList());
		Map<String, Object> got = ok(exec(admin, GET_SBOM, "o", s(org.getUuid()), "s", "ORGANIZATION", "n", "default"),
				"sbomProfile");
		assertEquals(o.get("uuid"), got.get("uuid"));

		Map<String, Object> fromOrg = ok(effective(admin, org, "COMPONENT", c.getUuid(), "default"), "sbomProfileEffective");
		assertEquals("RESOLVED", fromOrg.get("status"));
		assertEquals("ORGANIZATION", fromOrg.get("source"));
		assertEquals(o.get("uuid"), at(fromOrg, "profile.uuid"));

		Map<String, Object> own = ok(saveSbom(admin, org, "COMPONENT", c.getUuid(), "default",
				recipe("mode", "OVERRIDE", "dependencyDepth", "TOP_LEVEL_ONLY")), "saveSbomProfile");
		Map<String, Object> fromComponent = ok(effective(admin, org, "COMPONENT", c.getUuid(), "default"),
				"sbomProfileEffective");
		assertEquals("COMPONENT", fromComponent.get("source"));
		assertEquals(c.getUuid().toString(), fromComponent.get("sourceObject"));
		assertEquals(own.get("uuid"), at(fromComponent, "profile.uuid"));
		assertEquals("TOP_LEVEL_ONLY", at(fromComponent, "profile.dependencyDepth"));

		Map<String, Object> view = ok(exec(admin, VIEW_SBOM, "o", s(org.getUuid()), "s", "COMPONENT", "ob",
				s(c.getUuid()), "n", "default"), "sbomProfileEditorView");
		assertEquals(own.get("uuid"), at(view, "stored.uuid"));
		assertEquals("COMPONENT", at(view, "effective.source"));
		assertEquals("ORGANIZATION", at(view, "parent.source"));
		assertEquals(o.get("uuid"), at(view, "parent.profile.uuid"));
		assertEquals("DISABLED", view.get("supportInjection"));

		List<Map<String, Object>> all = ok(exec(admin, ORG_SBOM, "o", s(org.getUuid())), "sbomProfilesOfOrg");
		assertEquals(Set.of(o.get("uuid"), own.get("uuid")), Set.copyOf(all.stream().map(r -> r.get("uuid")).toList()));

		assertEquals(Boolean.TRUE, ok(deleteSbom(admin, org, "COMPONENT", c.getUuid(), "default"), "deleteSbomProfile"));
		assertEquals(Boolean.FALSE, ok(deleteSbom(admin, org, "COMPONENT", c.getUuid(), "default"), "deleteSbomProfile"));
		assertEquals("ORGANIZATION", at(ok(effective(admin, org, "COMPONENT", c.getUuid(), "default"),
				"sbomProfileEffective"), "source"));
		// The organization row of a name can go: default falls back to the built-in.
		assertEquals(Boolean.TRUE, ok(deleteSbom(admin, org, "ORGANIZATION", null, "default"), "deleteSbomProfile"));
		assertEquals("DEFAULT", at(ok(effective(admin, org, "ORGANIZATION", null, "default"), "sbomProfileEffective"),
				"source"));
	}

	@Test
	public void aScopeLocalNameAtAPerspectiveIsInTheMembersCatalogAndNotFoundAbove() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UUID p = perspective(admin, org, "tea27");
		perspectivesOf(admin, c, p);
		ok(saveSbom(admin, org, "PERSPECTIVE", p, "premarket", recipe()), "saveSbomProfile");

		List<Map<String, Object>> catalog = ok(exec(admin, CATALOG_SBOM, "o", s(org.getUuid()), "s", "COMPONENT", "ob",
				s(c.getUuid())), "sbomProfileCatalog");
		assertEquals(List.of("default", "premarket"), catalog.stream().map(e -> e.get("name")).toList());
		assertEquals("PERSPECTIVE", at(catalog.get(1), "resolution.source"));
		assertEquals("DEFAULT", at(catalog.get(0), "resolution.source"));

		Map<String, Object> above = ok(effective(admin, org, "ORGANIZATION", null, "premarket"), "sbomProfileEffective");
		assertEquals("NOT_FOUND", above.get("status"));
		assertNull(above.get("profile"));
		assertNull(above.get("source"));
		List<Map<String, Object>> orgCatalog = ok(exec(admin, CATALOG_SBOM, "o", s(org.getUuid()), "s", "ORGANIZATION"),
				"sbomProfileCatalog");
		assertEquals(List.of("default"), orgCatalog.stream().map(e -> e.get("name")).toList());
	}

	@Test
	public void permissionsAndTheOrganizationBoundary() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UserData reader = reader(org);
		UserData componentAdmin = user(org, PermissionScope.COMPONENT, c.getUuid());

		// A READ user reads and is refused both writes.
		ok(saveSbom(admin, org, "ORGANIZATION", null, "default", recipe()), "saveSbomProfile");
		ok(effective(reader, org, "COMPONENT", c.getUuid(), "default"), "sbomProfileEffective");
		ok(exec(reader, ORG_SBOM, "o", s(org.getUuid())), "sbomProfilesOfOrg");
		assertFalse(saveSbom(reader, org, "ORGANIZATION", null, "default", recipe()).getErrors().isEmpty());
		assertFalse(deleteSbom(reader, org, "ORGANIZATION", null, "default").getErrors().isEmpty());
		assertEquals(1, sbomProfileRepository.listByOrg(org.getUuid()).size(), "the reader changed nothing");

		// A component admin who is not an organization admin saves at COMPONENT scope (no org-admin gate, T3).
		Map<String, Object> mine = ok(saveSbom(componentAdmin, org, "COMPONENT", c.getUuid(), "default",
				recipe("mode", "OVERRIDE", "internalMetadata", "INCLUDE")), "saveSbomProfile");
		assertEquals("default", mine.get("name"));
		assertFalse(saveSbom(componentAdmin, org, "ORGANIZATION", null, "default", recipe()).getErrors().isEmpty(),
				"but not at the organization");

		// An object of another organization is "not found" for every operation.
		Organization other = testInitializer.obtainOrganization();
		Component foreign = component(other);
		ok(saveSbom(admin(other), other, "COMPONENT", foreign.getUuid(), "secret", recipe("mode", "OVERRIDE")),
				"saveSbomProfile");
		String o = s(org.getUuid());
		String f = s(foreign.getUuid());
		failsWith(saveSbom(admin, org, "COMPONENT", foreign.getUuid(), "secret", recipe("mode", "OVERRIDE")), "not found");
		failsWith(deleteSbom(admin, org, "COMPONENT", foreign.getUuid(), "secret"), "not found");
		failsWith(effective(admin, org, "COMPONENT", foreign.getUuid(), "secret"), "not found");
		failsWith(exec(admin, GET_SBOM, "o", o, "s", "COMPONENT", "ob", f, "n", "secret"), "not found");
		failsWith(exec(admin, LIST_SBOM, "o", o, "s", "COMPONENT", "ob", f), "not found");
		failsWith(exec(admin, CATALOG_SBOM, "o", o, "s", "COMPONENT", "ob", f), "not found");
		failsWith(exec(admin, VIEW_SBOM, "o", o, "s", "COMPONENT", "ob", f, "n", "secret"), "not found");
		assertEquals(1, sbomProfileRepository.listByOrg(other.getUuid()).size(), "the foreign row is untouched");

		// A name that is not stored: no row, NOT_FOUND, false.
		assertNull(ok(exec(admin, GET_SBOM, "o", o, "s", "ORGANIZATION", "n", "secret"), "sbomProfile"));
		assertEquals("NOT_FOUND", at(ok(effective(admin, org, "COMPONENT", c.getUuid(), "secret"),
				"sbomProfileEffective"), "status"));
		assertEquals(Boolean.FALSE, ok(deleteSbom(admin, org, "ORGANIZATION", null, "secret"), "deleteSbomProfile"));
	}

	@Test
	public void supportMetadataFollowsTheOrgInjectionSwitch() throws RelizaException {
		// Moved from TeaProfileGraphqlIntegrationTest with the field (TEA-2 rule, now on the catalog).
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		String injection = "mutation($o: ID!, $v: SupportInjectionSetting) {"
				+ " updateOrganizationSettings(orgUuid: $o, settings: { supportInjection: $v }) { uuid } }";

		failsWith(saveSbom(admin, org, "ORGANIZATION", null, "default", recipe("supportMetadata", "INCLUDE")),
				"supportInjection");
		ok(exec(admin, injection, "o", s(org.getUuid()), "v", "ENABLED"), "updateOrganizationSettings");
		ok(saveSbom(admin, org, "ORGANIZATION", null, "default", recipe("supportMetadata", "INCLUDE")), "saveSbomProfile");
		ok(exec(admin, injection, "o", s(org.getUuid()), "v", "DISABLED"), "updateOrganizationSettings");

		assertEquals("EXCLUDE", at(ok(effective(admin, org, "ORGANIZATION", null, "default"), "sbomProfileEffective"),
				"profile.supportMetadata"));
		assertEquals("INCLUDE", SbomProfileRepositoryReads.supportMetadataOf(sbomProfileRepository, org.getUuid()));
	}

	/** The stored value, read past the resolver. */
	private static final class SbomProfileRepositoryReads {
		static String supportMetadataOf(SbomProfileRepository r, UUID org) {
			return (String) r.findOrgProfile(org, "default").orElseThrow().getRecordData().get("supportMetadata");
		}
	}

	@Test
	public void deletingAPerspectiveRemovesItsSbomProfiles() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		UUID p = perspective(admin, org, "tea27-gone");
		ok(saveSbom(admin, org, "PERSPECTIVE", p, "default", recipe()), "saveSbomProfile");
		ok(saveSbom(admin, org, "PERSPECTIVE", p, "premarket", recipe()), "saveSbomProfile");
		assertEquals(2, sbomProfileRepository.listByObject(org.getUuid(), p).size());
		ok(exec(admin, "mutation($u: ID!) { deletePerspective(uuid: $u) { uuid } }", "u", s(p)), "deletePerspective");
		assertTrue(sbomProfileRepository.listByObject(org.getUuid(), p).isEmpty());
	}

	// ---------------------------------------------------------------- programmatic reads (M5)

	private ApiKey readKey(UUID org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"tea27 catalog reader", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY, Set.of(PermissionFunction.RESOURCE), null)),
				WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	/** Verified-principal form: what the programmatic filter leaves after it checked a bearer token. */
	private ExecutionResult asKey(ApiKey key, String op, Object... vars) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.27"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		Map<String, Object> v = new LinkedHashMap<>();
		for (int i = 0; i < vars.length; i += 2) v.put((String) vars[i], vars[i + 1]);
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(op, v, Map.of(), headers, null, new ServletWebRequest(request));
	}

	@Test
	public void theProgrammaticReadsTakeTheOrganizationFromTheKey() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		Component c = component(org);
		UUID p = perspective(admin, org, "tea27-key");
		perspectivesOf(admin, c, p);
		Map<String, Object> o = ok(saveSbom(admin, org, "ORGANIZATION", null, "default", recipe()), "saveSbomProfile");
		Map<String, Object> pr = ok(saveSbom(admin, org, "PERSPECTIVE", p, "default", recipe()), "saveSbomProfile");
		Map<String, Object> cr = ok(saveSbom(admin, org, "COMPONENT", c.getUuid(), "premarket",
				recipe("mode", "OVERRIDE")), "saveSbomProfile");
		ApiKey key = readKey(org.getUuid());

		List<Map<String, Object>> atOrg = ok(asKey(key, LIST_KEY, "s", "ORGANIZATION"), "sbomProfilesProgrammatic");
		assertEquals(List.of(o.get("uuid")), atOrg.stream().map(r -> r.get("uuid")).toList());
		List<Map<String, Object>> atP = ok(asKey(key, LIST_KEY, "s", "PERSPECTIVE", "ob", s(p)), "sbomProfilesProgrammatic");
		assertEquals(List.of(pr.get("uuid")), atP.stream().map(r -> r.get("uuid")).toList());
		List<Map<String, Object>> atC = ok(asKey(key, LIST_KEY, "s", "COMPONENT", "ob", s(c.getUuid())),
				"sbomProfilesProgrammatic");
		assertEquals(List.of(cr.get("uuid")), atC.stream().map(r -> r.get("uuid")).toList());
		Map<String, Object> eff = ok(asKey(key, EFFECTIVE_KEY, "s", "COMPONENT", "ob", s(c.getUuid()), "n", "default"),
				"sbomProfileEffectiveProgrammatic");
		assertEquals("PERSPECTIVE", eff.get("source"));
		assertEquals(pr.get("uuid"), at(eff, "profile.uuid"));
		assertEquals("RESOLVED", at(ok(asKey(key, EFFECTIVE_KEY, "s", "PERSPECTIVE", "ob", s(p), "n", "default"),
				"sbomProfileEffectiveProgrammatic"), "status"));
		assertEquals("ORGANIZATION", at(ok(asKey(key, EFFECTIVE_KEY, "s", "ORGANIZATION", "n", "default"),
				"sbomProfileEffectiveProgrammatic"), "source"));

		// A key of another organization: "not found" for the first organization's objects, its own empty org scope.
		Organization other = testInitializer.obtainOrganization();
		ApiKey foreignKey = readKey(other.getUuid());
		failsWith(asKey(foreignKey, LIST_KEY, "s", "COMPONENT", "ob", s(c.getUuid())), "not found");
		failsWith(asKey(foreignKey, LIST_KEY, "s", "PERSPECTIVE", "ob", s(p)), "not found");
		failsWith(asKey(foreignKey, EFFECTIVE_KEY, "s", "COMPONENT", "ob", s(c.getUuid()), "n", "default"), "not found");
		List<Map<String, Object>> foreignOrg = ok(asKey(foreignKey, LIST_KEY, "s", "ORGANIZATION"),
				"sbomProfilesProgrammatic");
		assertTrue(foreignOrg.isEmpty(), "never a row of the first organization");
		Map<String, Object> foreignDefault = ok(asKey(foreignKey, EFFECTIVE_KEY, "s", "ORGANIZATION", "n", "default"),
				"sbomProfileEffectiveProgrammatic");
		assertEquals("DEFAULT", foreignDefault.get("source"));
		assertEquals(other.getUuid().toString(), at(foreignDefault, "profile.org"));
	}
}
