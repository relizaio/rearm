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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.Link.ContentEnum;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.BranchService;
import io.reliza.service.CliSessionService;
import io.reliza.service.ComponentService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-16: {@code release(releaseUuid, orgUuid)} is declared on the programmatic root, and its one
 * fetcher used to answer only a signed-in user. It is now dual-lane: a FREEFORM key holding READ on
 * the release reads it (with the field resolvers), a key of another organization, a key without
 * READ, a missing uuid and a mismatched {@code orgUuid} all get the same "Not authorized", the
 * system state is checked before the lookup, bearer and Basic answer alike, and the user lane is
 * unchanged. Calls go through the DGS executor so the Release field resolvers run.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticReleaseReadTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String NOT_AUTHORIZED = "Not authorized";
	private static final String QUERY = "query($r: ID!, $o: ID) { release(releaseUuid: $r, orgUuid: $o) { uuid version"
			+ " lifecycle org artifacts artifactDetails { uuid } parentReleases { release } componentDetails { uuid }"
			+ " branchDetails { uuid } sourceCodeEntryDetails { uuid } } }";

	@Autowired private TestInitializer testInitializer;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private ApiTokenService apiTokenService;
	@Autowired private CliSessionService cliSessionService;
	@Autowired private FederatedTrustRuleService federatedTrustRuleService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;
	@MockitoSpyBean private UserService userService;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the lane under test refuses both first. */
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

	// ---------- fixture ----------

	/**
	 * An org with one component; on its base branch a parent release and the release under test,
	 * which carries a source code entry, the parent and one externally stored artifact. {@code key}
	 * is a FREEFORM key with READ_ONLY RESOURCE at ORGANIZATION scope and {@code secret} its secret.
	 */
	private record Tenant(UUID org, Component component, Branch branch, UUID sce, UUID parent, UUID release,
			UUID artifact, ApiKey key, String secret) {}

	private Tenant tenant() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Component component = component(org);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org,
				"github.com/example/prr-" + UUID.randomUUID(), VcsType.GIT, WU);
		UUID sce = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(branch.getUuid()).vcs(vcs).organizationUuid(org)
				.commit(UUID.randomUUID().toString().replace("-", "") + "cccccccc").build(), WU).getUuid();
		UUID parent = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("0.9.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").sourceCodeEntry(sce)
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(parent, null)))
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();

		// setup only: a READ_WRITE key attaches the artifact through the real resolver
		ApiKey writer = freeformKey(org, new PermissionDto(org, PermissionScope.ORGANIZATION, org,
				PermissionType.READ_WRITE, Set.of(PermissionFunction.RESOURCE), null));
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.RELEASE_FIELD, release.toString());
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		releaseDatafetcher.addArtifactProg(asEnv(writer, "artifactInput", input));
		List<UUID> artifacts = sharedReleaseService.getReleaseData(release).orElseThrow().getArtifacts();
		assertEquals(1, artifacts.size(), "setup: the artifact is attached");

		ApiKey key = freeformKey(org, new PermissionDto(org, PermissionScope.ORGANIZATION, org,
				PermissionType.READ_ONLY, Set.of(PermissionFunction.RESOURCE), null));
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		return new Tenant(org, component, branch, sce, parent, release, artifacts.get(0),
				apiKeyService.getApiKey(key.getUuid()).orElseThrow(), secret);
	}

	private Component component(UUID org) throws RelizaException {
		return componentService.createComponent("prr_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	/** A FREEFORM key in {@code org} holding {@code permissions} (none when empty). */
	private ApiKey freeformKey(UUID org, PermissionDto... permissions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "release read test key", WU);
		if (permissions.length > 0) {
			apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(permissions), WU);
		}
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	private static Map<String, Object> externalArtifact() {
		String name = "prr-doc-" + UUID.randomUUID();
		Map<String, Object> link = new HashMap<>();
		link.put("uri", "https://example.com/" + name);
		link.put("content", ContentEnum.OCTET_STREAM.name());
		Map<String, Object> a = new HashMap<>();
		a.put("type", ArtifactType.USER_DOCUMENT.name());
		a.put("displayIdentifier", name);
		a.put("storedIn", StoredIn.EXTERNALLY.name());
		a.put("downloadLinks", new ArrayList<>(List.of(link)));
		a.put("file", new MockMultipartFile("file", name + ".txt", "text/plain", "doc".getBytes(StandardCharsets.UTF_8)));
		return a;
	}

	/** A resolver environment carrying the principal the programmatic filter leaves on the request. */
	private DgsDataFetchingEnvironment asEnv(ApiKey key, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.16"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	// ---------- calls ----------

	private static Map<String, Object> vars(UUID release, UUID org) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("r", release.toString());
		if (null != org) m.put("o", org.toString());
		return m;
	}

	private ExecutionResult execute(MockHttpServletRequest request, HttpHeaders headers, UUID release, UUID org) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(QUERY, vars(release, org), Map.of(), headers, null, new ServletWebRequest(request));
	}

	/** Verified-principal form: what the filter leaves after it checked a bearer token. */
	private ExecutionResult asKey(ApiKey key, UUID release, UUID org) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.16"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return execute(request, headers, release, org);
	}

	/** Basic form: the resolver verifies the key id and secret itself. */
	private ExecutionResult asBasic(Tenant t, UUID release, UUID org) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		String id = ApiTypeEnum.FREEFORM.name() + "__" + t.org() + "__ord__" + t.key().getKeyOrder();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((id + ":" + t.secret()).getBytes(StandardCharsets.UTF_8)));
		return execute(request, headers, release, org);
	}

	private ExecutionResult asPerson(UserData user, UUID release) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("release-read-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(QUERY, vars(release, null));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> release(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("release");
	}

	private static String refusal(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "expected a refusal, got " + String.valueOf((Object) r.getData()));
		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		assertNull(null == data ? null : data.get("release"), "a refused read returns no release");
		return r.getErrors().get(0).getMessage();
	}

	@SuppressWarnings("unchecked")
	private static void assertFullRelease(Tenant t, Map<String, Object> rel) {
		assertNotNull(rel, "release");
		assertEquals(t.release().toString(), rel.get("uuid"));
		assertEquals("1.0.0", rel.get("version"));
		assertEquals(ReleaseLifecycle.DRAFT.name(), rel.get("lifecycle"));
		assertEquals(t.org().toString(), rel.get("org"));
		assertEquals(List.of(t.artifact().toString()), rel.get("artifacts"));
		List<Map<String, Object>> details = (List<Map<String, Object>>) rel.get("artifactDetails");
		assertEquals(List.of(t.artifact().toString()), details.stream().map(d -> d.get("uuid")).toList());
		List<Map<String, Object>> parents = (List<Map<String, Object>>) rel.get("parentReleases");
		assertEquals(List.of(t.parent().toString()), parents.stream().map(p -> p.get("release")).toList());
		assertEquals(t.component().getUuid().toString(), ((Map<String, Object>) rel.get("componentDetails")).get("uuid"));
		assertEquals(t.branch().getUuid().toString(), ((Map<String, Object>) rel.get("branchDetails")).get("uuid"));
		assertEquals(t.sce().toString(), ((Map<String, Object>) rel.get("sourceCodeEntryDetails")).get("uuid"));
	}

	// ---------- cases ----------

	@Test
	public void aKeyWithReadReadsTheReleaseWithItsFields() throws RelizaException {
		Tenant a = tenant();
		assertFullRelease(a, release(asKey(a.key(), a.release(), null)));
		assertFullRelease(a, release(asKey(a.key(), a.release(), a.org())));
	}

	@Test
	public void aKeyOfAnotherOrgIsNotAuthorized() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		assertEquals(NOT_AUTHORIZED, refusal(asKey(b.key(), a.release(), null)));
		assertEquals(NOT_AUTHORIZED, refusal(asKey(b.key(), a.release(), a.org())));
		assertEquals(NOT_AUTHORIZED, refusal(asKey(a.key(), a.release(), b.org())),
				"the orgUuid argument has to agree with the key's org");
	}

	@Test
	public void aKeyWithoutReadIsNotAuthorizedAndTheScopeClimbs() throws RelizaException {
		Tenant a = tenant();
		ApiKey noGrant = freeformKey(a.org());
		Component sibling = component(a.org());
		ApiKey siblingGrant = freeformKey(a.org(), new PermissionDto(a.org(), PermissionScope.COMPONENT,
				sibling.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.RESOURCE), null));
		ApiKey ownComponentGrant = freeformKey(a.org(), new PermissionDto(a.org(), PermissionScope.COMPONENT,
				a.component().getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.RESOURCE), null));

		assertEquals(NOT_AUTHORIZED, refusal(asKey(noGrant, a.release(), null)));
		assertEquals(NOT_AUTHORIZED, refusal(asKey(siblingGrant, a.release(), null)));
		assertFullRelease(a, release(asKey(ownComponentGrant, a.release(), null)));
	}

	@Test
	public void missingAndForeignAnswerAlikeAndTheSealedSentenceComesFirst() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		String missing = refusal(asKey(a.key(), UUID.randomUUID(), null));
		String foreign = refusal(asKey(a.key(), b.release(), null));
		assertEquals(NOT_AUTHORIZED, missing);
		assertEquals(missing, foreign);

		licenseStatus.setSystemSealed(true);
		String sealedExisting = refusal(asKey(a.key(), a.release(), null));
		String sealedMissing = refusal(asKey(a.key(), UUID.randomUUID(), null));
		assertEquals(sealedExisting, sealedMissing);
		assertTrue(sealedExisting.contains("sealed"), sealedExisting);
	}

	@Test
	public void aBearerTokenAnswersLikeBasic() throws Exception {
		Tenant a = tenant();
		ApiKey ak = a.key();
		ApiKeySecret secret = ApiKeyData.dataFromRecord(ak).effectiveSecrets(ak.getApiKey()).stream()
				.filter(ApiKeySecret::isUsable).findFirst().orElseThrow();
		String token = apiTokenService.issue(ak, secret, ApiTokenService.AUD_PROGRAMMATIC);

		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setRequestURI(ProgrammaticGraphQlConfig.PATH);
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		MockHttpServletResponse response = new MockHttpServletResponse();
		new ProgrammaticAuthenticationFilter(apiTokenService, apiKeyService, apiKeyAccessService, cliSessionService, federatedTrustRuleService)
				.doFilter(request, response, new MockFilterChain());
		assertEquals(200, response.getStatus(), response.getContentAsString());
		assertTrue(request.getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE) instanceof AuthHeaderParse,
				"the filter verified the token and left the principal");
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		Map<String, Object> viaBearer = release(execute(request, headers, a.release(), a.org()));
		Map<String, Object> viaBasic = release(asBasic(a, a.release(), a.org()));

		assertFullRelease(a, viaBearer);
		assertFullRelease(a, viaBasic);
		assertEquals(viaBasic.get("uuid"), viaBearer.get("uuid"));
		assertEquals(viaBasic.get("version"), viaBearer.get("version"));
	}

	@Test
	public void theUserLaneIsUnchanged() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User reader = userService.createUser("Prr " + tag, "prr-" + tag + "@release-read.io", true, List.of(a.org()),
				"prr-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(reader.getUuid(), a.org(), PermissionScope.ORGANIZATION, a.org(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null, WU);
		User outsider = userService.createUser("Prr out " + tag, "prr-out-" + tag + "@release-read.io", true,
				List.of(b.org()), "prr-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);

		assertFullRelease(a, release(asPerson(userService.getUserData(reader.getUuid()).orElseThrow(), a.release())));
		assertEquals(NOT_AUTHORIZED, refusal(asPerson(userService.getUserData(outsider.getUuid()).orElseThrow(), a.release())));
	}

	@Test
	public void anAnonymousCallIsStillRefused() throws RelizaException {
		Tenant a = tenant();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/graphql");
		assertEquals(NOT_AUTHORIZED, refusal(execute(request, new HttpHeaders(), a.release(), null)));
	}
}
