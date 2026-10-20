/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.ws.saas.SaasComponentDataFetcher;

/**
 * Shared fixture of the TPF-14 tests: the four mutations that create a component inside a
 * perspective named by uuid (user-lane createComponentInPerspective, programmatic
 * createComponentInPerspectiveProgrammatic, and the perspective field of getNewVersionProgrammatic
 * and addReleaseProgrammatic with createComponentIfMissing), called through the real resolvers
 * with the principal the programmatic filter leaves on the request, the CLI's Basic header for a
 * legacy key, or a signed-in user.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
abstract class PerspectiveCreatePathsTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	static final String NOT_FOUND = "Perspective not found: ";

	@Autowired TestInitializer testInitializer;
	@Autowired SaasComponentDataFetcher saasComponentDataFetcher;
	@Autowired ComponentDataFetcher componentDataFetcher;
	@Autowired ReleaseDatafetcher releaseDatafetcher;
	@Autowired PerspectiveService perspectiveService;
	@Autowired ComponentService componentService;
	@Autowired GetComponentService getComponentService;
	@Autowired VcsRepositoryService vcsRepositoryService;
	@Autowired ApiKeyService apiKeyService;
	@Autowired LicenseStatus licenseStatus;
	@Autowired ApplicationContext applicationContext;
	@MockitoSpyBean UserService userService;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
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

	/**
	 * Two organizations with a live perspective each, an archived perspective of A, A's FREEFORM key
	 * with org-wide READ_WRITE RESOURCE, A's legacy ORGANIZATION_RW secret, and an admin of A.
	 */
	record World(UUID orgA, UUID orgB, UUID pA, UUID pB, UUID archivedA, ApiKey freeformA, String orgSecretA,
			UserData adminA) {}

	World world() throws RelizaException {
		UUID a = testInitializer.obtainOrganization().getUuid();
		UUID b = testInitializer.obtainOrganization().getUuid();
		UUID pA = perspectiveService.createPerspective(a, "tpf14-a-" + tag(), WU).getUuid();
		UUID pB = perspectiveService.createPerspective(b, "tpf14-b-" + tag(), WU).getUuid();
		UUID archivedA = perspectiveService.createPerspective(a, "tpf14-gone-" + tag(), WU).getUuid();
		perspectiveService.deletePerspective(archivedA, WU);
		ApiKey freeformA = apiKeyService.createObjectApiKey(a, ApiTypeEnum.FREEFORM, a,
				UUID.randomUUID().toString(), "tpf14 freeform key", WU);
		apiKeyService.setPermissionsOnApiKey(freeformA.getUuid(), null, List.of(new PermissionDto(a,
				PermissionScope.ORGANIZATION, a, PermissionType.READ_WRITE, Set.of(PermissionFunction.RESOURCE),
				null)), WU);
		apiKeyService.createObjectApiKey(a, ApiTypeEnum.ORGANIZATION_RW, a, null, "tpf14 org key", WU);
		String orgSecretA = apiKeyService.setObjectApiKey(a, ApiTypeEnum.ORGANIZATION_RW, a, null, "tpf14 org key", false, WU);
		String t = tag();
		User u = userService.createUser("TPF-14 " + t, "tpf14-" + t + "@perspective.io", true, List.of(a),
				"tpf14-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), a, PermissionScope.ORGANIZATION, a, PermissionType.ADMIN,
				List.of(), null, WU);
		return new World(a, b, pA, pB, archivedA, freeformA, orgSecretA,
				userService.getUserData(u.getUuid()).orElseThrow());
	}

	static String tag() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	// ---------- principals ----------

	void signInAs(UserData user) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf14-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	/** A key the programmatic filter verified. */
	DgsDataFetchingEnvironment as(ApiKey key, String argument, Map<String, Object> input) {
		return asPrincipal(AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.14"), argument, input);
	}

	DgsDataFetchingEnvironment asPrincipal(AuthHeaderParse principal, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return env(request, headers, argument, input);
	}

	/** The CLI's path for A's legacy ORGANIZATION_RW key: a Basic header the resolver verifies itself. */
	DgsDataFetchingEnvironment basicOrg(World w, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
				(ApiTypeEnum.ORGANIZATION_RW.name() + "__" + w.orgA() + ":" + w.orgSecretA()).getBytes(StandardCharsets.UTF_8)));
		return env(request, headers, argument, input);
	}

	/** The user lane reads only the arguments. */
	DgsDataFetchingEnvironment userEnv(Map<String, Object> component) {
		return env(new MockHttpServletRequest("POST", "/graphql"), new HttpHeaders(), "component", component);
	}

	private DgsDataFetchingEnvironment env(MockHttpServletRequest request, HttpHeaders headers, String argument,
			Map<String, Object> input) {
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	// ---------- inputs ----------

	static String vcsUri() {
		return "https://github.com/tpf14/repo-" + UUID.randomUUID();
	}

	/** createComponentInPerspective[Programmatic]: the user lane names the organization, the key lane does not. */
	static Map<String, Object> componentInput(String name, UUID organization, String vcsRepositoryUri) {
		Map<String, Object> c = new HashMap<>();
		c.put("name", name);
		c.put("type", ComponentType.COMPONENT.name());
		c.put("versionSchema", "semver");
		c.put(CommonVariables.FEATURE_BRANCH_VERSION_FIELD, "Branch.Micro");
		if (null != organization) c.put(CommonVariables.ORGANIZATION_FIELD, organization.toString());
		if (null != vcsRepositoryUri) c.put("vcsRepository", new HashMap<>(Map.of("uri", vcsRepositoryUri)));
		return c;
	}

	/** getNewVersionProgrammatic: the component is NOT_FOUND by vcsUri, so it is created in the perspective. */
	static Map<String, Object> newVersionInput(String vcsUri, UUID perspective) {
		Map<String, Object> in = new HashMap<>();
		in.put("vcsUri", vcsUri);
		in.put("repoPath", "svc");
		in.put(CommonVariables.BRANCH_FIELD, "main");
		in.put("createComponentIfMissing", true);
		in.put("createComponentVersionSchema", "semver");
		in.put("createComponentFeatureBranchVersionSchema", "Branch.Micro");
		in.put("perspective", perspective.toString());
		return in;
	}

	/** addReleaseProgrammatic: as above, with the release's version and lifecycle. */
	static Map<String, Object> releaseInput(String vcsUri, UUID perspective) {
		Map<String, Object> in = newVersionInput(vcsUri, perspective);
		in.put(CommonVariables.VERSION_FIELD, "1.0.0");
		in.put(CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());
		return in;
	}

	// ---------- the four calls ----------

	ComponentData userCreate(World w, UUID organization, UUID perspective, String name) throws Exception {
		signInAs(w.adminA());
		return saasComponentDataFetcher.createComponentInPerspective(userEnv(componentInput(name, organization, null)),
				perspective);
	}

	ComponentData progCreate(DgsDataFetchingEnvironment dfe, UUID perspective) throws Exception {
		return saasComponentDataFetcher.createComponentInPerspectiveProgrammatic(dfe, perspective);
	}

	void getNewVersion(DgsDataFetchingEnvironment dfe) throws Exception {
		componentDataFetcher.getNewVersionProgrammatic(dfe);
	}

	void addRelease(DgsDataFetchingEnvironment dfe) throws Exception {
		releaseDatafetcher.addReleaseProgrammatic(dfe);
	}

	// ---------- what a refusal must not have written ----------

	/** Component uuids of both organizations and the members of every perspective of the world. */
	record Snapshot(Set<UUID> componentsA, Set<UUID> componentsB, Set<UUID> membersPA, Set<UUID> membersPB) {}

	Snapshot snapshot(World w) {
		return new Snapshot(components(w.orgA()), components(w.orgB()), members(w.pA()), members(w.pB()));
	}

	Set<UUID> components(UUID org) {
		return componentService.listComponentDataByOrganization(org, ComponentType.COMPONENT, ComponentType.PRODUCT)
				.stream().map(ComponentData::getUuid).collect(Collectors.toSet());
	}

	Set<UUID> members(UUID perspective) {
		return getComponentService.listComponentsByPerspective(perspective).stream().map(ComponentData::getUuid)
				.collect(Collectors.toSet());
	}

	void assertNothingWritten(World w, Snapshot before) {
		assertEquals(before, snapshot(w), "a refused call created a component or a perspective member");
	}

	/** The one component of {@code org} created since {@code before}. */
	ComponentData createdSince(UUID org, Set<UUID> before) {
		Set<UUID> now = components(org);
		now.removeAll(before);
		assertEquals(1, now.size(), "exactly one component created");
		return getComponentService.getComponentData(now.iterator().next()).orElseThrow();
	}
}
