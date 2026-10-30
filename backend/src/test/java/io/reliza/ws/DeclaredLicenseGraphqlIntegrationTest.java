/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ComponentDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SharedReleaseService;

/**
 * SCORE-39 T-17 (ADR-1, ADR-3, ADR-8), the GraphQL half: the declared licence at the three levels and
 * the component supplier through the user and programmatic mutations, read back through the schema
 * fields, with the permission each write needs (organization ADMIN for the default, component write
 * for the component, release write for the override) and the validator message on a refusal. The
 * merge requests the declarations lead to are {@link DeclaredLicenseMergeRequestIntegrationTest}.
 */
class DeclaredLicenseGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final String ACME_URL = "https://acme.example/licence";
	private static final String ORG_URL = "https://org.example/licence";

	private static final String UPDATE_SETTINGS = "mutation($o: ID!, $s: SettingsInput!) {"
			+ " updateOrganizationSettings(orgUuid: $o, settings: $s) { uuid } }";
	private static final String ORGANIZATIONS = "query { organizations { uuid settings { defaultLicenseExpression"
			+ " defaultLicenseUrl } } }";
	private static final String UPDATE_COMPONENT = "mutation($c: UpdateComponentInput!) { updateComponent(component: $c)"
			+ " { uuid } }";
	private static final String COMPONENT = "query($c: ID!) { component(componentUuid: $c) { licenseExpression licenseUrl"
			+ " supplierName } }";
	private static final String CREATE_COMPONENT = "mutation($c: CreateComponentInput!) { createComponent(component: $c)"
			+ " { uuid licenseExpression licenseUrl supplierName } }";
	private static final String UPDATE_RELEASE = "mutation($r: ReleaseInput!) { updateRelease(release: $r) {"
			+ " licenseExpression effectiveLicense { expression url level } } }";
	private static final String RELEASE = "query($r: ID!) { release(releaseUuid: $r) { licenseExpression"
			+ " effectiveLicense { expression url level } } }";

	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ComponentDataFetcher componentDataFetcher;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private ApplicationContext applicationContext;

	// ---- fixture -------------------------------------------------------------------------------

	private UserData member(Organization org, PermissionScope scope, UUID object, PermissionType type)
			throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Licence " + tag, "score39-" + tag + "@score.io", true, List.of(org.getUuid()),
				"score39-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private UserData orgMember(Organization org, PermissionType type) throws RelizaException {
		return member(org, PermissionScope.ORGANIZATION, org.getUuid(), type);
	}

	private Component component(Organization org) throws RelizaException {
		return componentService.createComponent("score39-" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU);
	}

	private static Map<String, Object> vars(Object... pairs) {
		Map<String, Object> vars = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) vars.put((String) pairs[i], pairs[i + 1]);
		return vars;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (Map<String, Object>) ((Map<String, Object>) r.getData()).get(field);
	}

	private static void refused(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "expected a refusal");
		assertTrue(r.getErrors().get(0).getMessage().contains("Not authorized")
				|| r.getErrors().get(0).getMessage().contains("Access Denied")
				|| r.getErrors().get(0).getMessage().toLowerCase().contains("forbidden"), r.getErrors().toString());
	}

	private OrganizationData.Settings settings(Organization org) {
		OrganizationData.Settings s = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getSettings();
		return null == s ? new OrganizationData.Settings() : s;
	}

	private ExecutionResult setDefault(UserData user, Organization org, String expression, String url) {
		Map<String, Object> s = new HashMap<>();
		s.put("defaultLicenseExpression", expression);
		s.put("defaultLicenseUrl", url);
		return as(user, UPDATE_SETTINGS, vars("o", org.getUuid().toString(), "s", s));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> settingsRead(UserData user, Organization org) {
		ExecutionResult r = as(user, ORGANIZATIONS, Map.of());
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<Map<String, Object>> orgs = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("organizations");
		return (Map<String, Object>) orgs.stream().filter(o -> org.getUuid().toString().equals(o.get("uuid")))
				.findFirst().orElseThrow().get("settings");
	}

	private ExecutionResult updateComponent(UserData user, Component c, String expression, String url, String supplier) {
		Map<String, Object> input = new HashMap<>();
		input.put("uuid", c.getUuid().toString());
		input.put("name", getComponentService.getComponentData(c.getUuid()).orElseThrow().getName());
		input.put("licenseExpression", expression);
		input.put("licenseUrl", url);
		input.put("supplierName", supplier);
		return as(user, UPDATE_COMPONENT, vars("c", input));
	}

	private ExecutionResult updateRelease(UserData user, Organization org, UUID release, String expression) {
		Map<String, Object> input = new HashMap<>();
		input.put("uuid", release.toString());
		input.put("org", org.getUuid().toString());
		input.put("licenseExpression", expression);
		return as(user, UPDATE_RELEASE, vars("r", input));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> effective(UserData user, UUID release) {
		return (Map<String, Object>) object(as(user, RELEASE, vars("r", release.toString())), "release")
				.get("effectiveLicense");
	}

	// ---- organization default ------------------------------------------------------------------

	@Test
	void anAdminSetsTheDefaultAndReadsItBack() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = orgMember(org, PermissionType.ADMIN);

		object(setDefault(admin, org, "agpl-3.0-only", ORG_URL), "updateOrganizationSettings");

		Map<String, Object> read = settingsRead(admin, org);
		assertEquals("AGPL-3.0-only", read.get("defaultLicenseExpression"));
		assertEquals(ORG_URL, read.get("defaultLicenseUrl"));
	}

	@Test
	void aReadWriteMemberCannotSetTheDefault() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		OrganizationData.Settings before = settings(org);

		refused(setDefault(orgMember(org, PermissionType.READ_WRITE), org, "MIT", null));

		assertEquals(before.getDefaultLicenseExpression(), settings(org).getDefaultLicenseExpression());
	}

	@Test
	void anInvalidDefaultAnswersOneErrorWithTheValidatorMessage() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = orgMember(org, PermissionType.ADMIN);

		String message = errorMessage(setDefault(admin, org, "MIT", "javascript:alert(1)"));
		assertTrue(message.contains("defaultLicenseUrl invalid: scheme must be http or https"), message);
		message = errorMessage(setDefault(admin, org, "Apache-2", null));
		assertTrue(message.contains("defaultLicenseExpression invalid: unknown licence id \"Apache-2\""), message);
	}

	// ---- component -----------------------------------------------------------------------------

	@Test
	void aComponentWriterSetsTheThreeFieldsAndReadsThemBack() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org);
		UserData writer = member(org, PermissionScope.COMPONENT, c.getUuid(), PermissionType.READ_WRITE);

		object(updateComponent(writer, c, "LicenseRef-Acme-Commercial", ACME_URL, " Acme Corp "), "updateComponent");

		Map<String, Object> read = object(as(writer, COMPONENT, vars("c", c.getUuid().toString())), "component");
		assertEquals("LicenseRef-Acme-Commercial", read.get("licenseExpression"));
		assertEquals(ACME_URL, read.get("licenseUrl"));
		assertEquals("Acme Corp", read.get("supplierName"));
	}

	@Test
	void aReadOnlyUserCannotSetTheComponentFields() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org);

		refused(updateComponent(member(org, PermissionScope.COMPONENT, c.getUuid(), PermissionType.READ_ONLY), c, "MIT",
				null, "Acme Corp"));

		ComponentData cd = getComponentService.getComponentData(c.getUuid()).orElseThrow();
		assertNull(cd.getLicenseExpression());
		assertNull(cd.getSupplierName());
	}

	@Test
	void aComponentFromAnotherOrganizationIsRefused() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		Component theirs = component(other);

		refused(updateComponent(orgMember(org, PermissionType.ADMIN), theirs, "MIT", null, "Acme Corp"));

		assertNull(getComponentService.getComponentData(theirs.getUuid()).orElseThrow().getLicenseExpression());
	}

	@Test
	void createComponentStoresTheThreeFields() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData writer = orgMember(org, PermissionType.READ_WRITE);
		Map<String, Object> input = new HashMap<>();
		input.put("org", org.getUuid().toString());
		input.put("name", "score39-" + UUID.randomUUID());
		input.put("type", "COMPONENT");
		input.put("versionSchema", "semver");
		input.put("featureBranchVersioning", "Branch.Micro");
		input.put("licenseExpression", "mit OR apache-2.0");
		input.put("licenseUrl", ACME_URL);
		input.put("supplierName", "Acme Corp");

		Map<String, Object> created = object(as(writer, CREATE_COMPONENT, vars("c", input)), "createComponent");

		assertEquals("MIT OR Apache-2.0", created.get("licenseExpression"));
		assertEquals(ACME_URL, created.get("licenseUrl"));
		assertEquals("Acme Corp", created.get("supplierName"));
		ComponentData cd = getComponentService.getComponentData(UUID.fromString((String) created.get("uuid"))).orElseThrow();
		assertEquals("MIT OR Apache-2.0", cd.getLicenseExpression());
	}

	// ---- release override and the effective licence -------------------------------------------

	@Test
	void theEffectiveLicenceFollowsTheLevelsAsEachIsCleared() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = orgMember(org, PermissionType.ADMIN);
		Component c = component(org);
		UUID release = release(org, c, List.of());
		object(setDefault(admin, org, "AGPL-3.0-only", ORG_URL), "updateOrganizationSettings");
		object(updateComponent(admin, c, "LicenseRef-Acme-Commercial", ACME_URL, null), "updateComponent");

		Map<String, Object> updated = object(updateRelease(admin, org, release, "mit"), "updateRelease");
		assertEquals("MIT", updated.get("licenseExpression"));
		Map<String, Object> override = new HashMap<>();
		override.put("expression", "MIT");
		override.put("url", null);
		override.put("level", "RELEASE");
		assertEquals(override, updated.get("effectiveLicense"));

		object(updateRelease(admin, org, release, ""), "updateRelease");
		assertEquals(Map.of("expression", "LicenseRef-Acme-Commercial", "url", ACME_URL, "level", "COMPONENT"),
				effective(admin, release));
		assertNull(object(as(admin, RELEASE, vars("r", release.toString())), "release").get("licenseExpression"));

		object(updateComponent(admin, c, "", null, null), "updateComponent");
		assertEquals(Map.of("expression", "AGPL-3.0-only", "url", ORG_URL, "level", "ORGANIZATION"),
				effective(admin, release));

		object(setDefault(admin, org, "", null), "updateOrganizationSettings");
		assertNull(effective(admin, release));
	}

	@Test
	@SuppressWarnings("unchecked")
	void aReleaseWithAnOverrideEventStillSerialises() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = orgMember(org, PermissionType.ADMIN);
		UUID release = release(org, component(org), List.of());
		object(updateRelease(admin, org, release, "MIT"), "updateRelease");

		Map<String, Object> read = object(as(admin, "query($r: ID!) { release(releaseUuid: $r) { updateEvents { rus rua"
				+ " oldValue newValue } } }", vars("r", release.toString())), "release");

		List<Map<String, Object>> events = (List<Map<String, Object>>) read.get("updateEvents");
		assertTrue(events.stream().anyMatch(e -> "DECLARED_LICENSE".equals(e.get("rus")) && "ADDED".equals(e.get("rua"))
				&& "MIT".equals(e.get("newValue"))), events.toString());
	}

	@Test
	void aReadOnlyUserCannotSetTheOverride() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID release = release(org, component(org), List.of());

		refused(updateRelease(orgMember(org, PermissionType.READ_ONLY), org, release, "MIT"));

		assertNull(sharedReleaseService.getReleaseData(release).orElseThrow().getLicenseExpression());
	}

	@Test
	void aReleaseOfAnotherOrganizationIsRefused() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		UUID theirs = release(other, component(other), List.of());

		refused(updateRelease(orgMember(org, PermissionType.ADMIN), org, theirs, "MIT"));

		assertNull(sharedReleaseService.getReleaseData(theirs).orElseThrow().getLicenseExpression());
	}

	@Test
	void anInvalidOverrideAnswersTheValidatorMessage() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UUID release = release(org, component(org), List.of());

		String message = errorMessage(updateRelease(orgMember(org, PermissionType.ADMIN), org, release, "NONE"));

		assertTrue(message.contains("licenseExpression invalid: NOASSERTION and NONE"), message);
	}

	// ---- programmatic --------------------------------------------------------------------------

	private DgsDataFetchingEnvironment asKey(ApiKey key, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.39"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	@Test
	void createComponentProgrammaticStoresTheThreeFields() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey orgKey = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.ORGANIZATION_RW, org.getUuid(), null,
				"score39 t17 organization key", WU);
		Map<String, Object> input = new HashMap<>();
		input.put("name", "score39-prog-" + UUID.randomUUID());
		input.put("type", "COMPONENT");
		input.put("versionSchema", "semver");
		input.put("featureBranchVersioning", "Branch.Micro");
		input.put("licenseExpression", "LicenseRef-Acme-Commercial");
		input.put("licenseUrl", ACME_URL);
		input.put("supplierName", "Acme Corp");

		ComponentDto created = componentDataFetcher.createComponentProgrammatic(asKey(orgKey, "component", input));

		ComponentData cd = getComponentService.getComponentData(created.getUuid()).orElseThrow();
		assertEquals("LicenseRef-Acme-Commercial", cd.getLicenseExpression());
		assertEquals(ACME_URL, cd.getLicenseUrl());
		assertEquals("Acme Corp", cd.getSupplierName());
		assertEquals("LicenseRef-Acme-Commercial", created.getLicenseExpression());
	}

	@Test
	void addReleaseProgrammaticStoresTheOverride() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org);
		ApiKey componentKey = apiKeyService.createObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, org.getUuid(), null,
				"score39 t17 component key", WU);
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.COMPONENT_FIELD, c.getUuid().toString());
		input.put(CommonVariables.BRANCH_FIELD, branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow()
				.getUuid().toString());
		input.put(CommonVariables.VERSION_FIELD, "39.17.1");
		input.put(CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());
		input.put("licenseExpression", "gpl-2.0-only WITH Classpath-exception-2.0");

		ReleaseData created = releaseDatafetcher.addReleaseProgrammatic(asKey(componentKey, "release", input));

		assertEquals("GPL-2.0-only WITH Classpath-exception-2.0",
				sharedReleaseService.getReleaseData(created.getUuid()).orElseThrow().getLicenseExpression());
	}

	@Test
	void addReleaseProgrammaticRefusesAnInvalidOverride() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component c = component(org);
		ApiKey componentKey = apiKeyService.createObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, org.getUuid(), null,
				"score39 t17 component key", WU);
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.COMPONENT_FIELD, c.getUuid().toString());
		input.put(CommonVariables.BRANCH_FIELD, branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow()
				.getUuid().toString());
		input.put(CommonVariables.VERSION_FIELD, "39.17.2");
		input.put("licenseExpression", "NOASSERTION");

		Exception e = assertThrows(Exception.class,
				() -> releaseDatafetcher.addReleaseProgrammatic(asKey(componentKey, "release", input)));

		assertTrue(String.valueOf(e.getMessage()).contains("licenseExpression invalid"), String.valueOf(e.getMessage()));
		assertTrue(sharedReleaseService.listReleaseDataOfBranch(branchService.getBaseBranchOfComponent(c.getUuid())
				.orElseThrow().getUuid()).isEmpty(), "no release was created");
	}
}
