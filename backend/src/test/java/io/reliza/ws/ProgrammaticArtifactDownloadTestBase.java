/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.CliSession;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ArtifactService;
import io.reliza.service.BranchService;
import io.reliza.service.CliSessionService;
import io.reliza.service.ComponentService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.RebomService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared fixture of the programmatic artifact download tests (task SCORE-17): a real server on a
 * random port, so every request crosses the real security chains, the async dispatch of the
 * {@code Mono} handlers and the servlet filters, with real keys, real access tokens from the token
 * endpoint and a real database. Rebom (which serves the stored BOM) and the IdP's JWT decoder are
 * replaced; the IdP decoder accepts exactly one token, {@link #idpToken}, as the person
 * {@link World#downloader}.
 *
 * <p>Split by topic, bearer tokens, Basic keys and the chains, over this base so the classes share
 * one Spring context. The world is built once per class.
 */
@SpringBootTest(classes = {App.class}, webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ProgrammaticArtifactDownloadTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	/** The start of the challenge the programmatic filter answers a bearer token it does not accept with. */
	protected static final String INVALID_TOKEN = "Bearer realm=\"rearm\", error=\"invalid_token\"";
	private static final AtomicInteger VERSION = new AtomicInteger();

	/** The stored BOM as rebom serves it processed (augmented download) and as uploaded (raw download). */
	protected static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"score17-lib\",\"version\":\"1.0\"}]}";
	protected static final String RAW_DOC = DOC.replace("\"1.0\"", "\"1.0-as-uploaded\"");

	@MockitoBean protected RebomService rebomService;
	@MockitoBean protected JwtDecoder jwtDecoder;
	@MockitoSpyBean protected UserService userService;

	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ComponentService componentService;
	@Autowired protected BranchService branchService;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected ArtifactService artifactService;
	@Autowired protected ApiKeyService apiKeyService;
	@Autowired protected ApiTokenService apiTokenService;
	@Autowired protected CliSessionService cliSessionService;
	@Autowired protected FederatedTrustRuleService federatedTrustRuleService;
	@Autowired protected JdbcTemplate jdbcTemplate;
	@Autowired private LicenseStatus licenseStatus;

	@LocalServerPort protected int port;

	protected final HttpClient http = HttpClient.newHttpClient();
	protected World w;
	protected String idpToken;
	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void serverReady() throws Exception {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree(DOC));
		when(rebomService.findBomByVersion(any(), any(), anyInt())).thenAnswer(inv -> Utils.OM.readTree(DOC));
		when(rebomService.findRawBomById(any(), any())).thenAnswer(inv -> Utils.OM.readTree(RAW_DOC));
		when(rebomService.findRawBomByVersion(any(), any(), anyInt())).thenAnswer(inv -> Utils.OM.readTree(RAW_DOC));
		if (null == w) {
			w = world();
			idpToken = idpSignedToken();
		}
		// the IdP knows one token, the downloader's; anything else fails to decode, as a ReARM token does there
		doThrow(new BadJwtException("not an IdP token")).when(jwtDecoder).decode(anyString());
		doReturn(Jwt.withTokenValue(idpToken).header("alg", "RS256").subject("score17-downloader")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build()).when(jwtDecoder).decode(idpToken);
		doReturn(Optional.of(w.downloader())).when(userService).getUserDataByAuth(any());
	}

	@AfterEach
	void restoreLicence() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	// ---- world -----------------------------------------------------------------------------------

	/** An API key row with the id and secret a client presents as Basic, or exchanges at the token endpoint. */
	protected record Key(ApiKey row, String id, String secret) {}

	/**
	 * Organization A with two components: {@code covered} (the component key's scope) holding
	 * {@code releaseArtifact} on a release, {@code uncovered} holding {@code otherArtifact};
	 * {@code orphanArtifact} on no release (an agent session's report). Organization B holds
	 * {@code foreignArtifact} on a release and {@code foreignOrphan}. Keys are Free Form keys of A
	 * unless named foreign.
	 */
	protected record World(Organization org, Organization foreignOrg, Component covered, Component uncovered, Component foreignComponent,
			UUID releaseArtifact, UUID otherArtifact, UUID orphanArtifact, UUID foreignArtifact, UUID foreignOrphan,
			UserData downloader, UserData reader,
			Key orgKey, Key componentKey, Key agentKey, Key adminKey, Key foreignAdminKey) {}

	private World world() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization foreign = testInitializer.obtainOrganization();
		Component covered = component(org);
		Component uncovered = component(org);
		Component foreignComponent = component(foreign);
		UUID releaseArtifact = bom(org.getUuid());
		release(org, covered, releaseArtifact);
		UUID otherArtifact = bom(org.getUuid());
		release(org, uncovered, otherArtifact);
		UUID foreignArtifact = bom(foreign.getUuid());
		release(foreign, foreignComponent, foreignArtifact);
		UserData downloader = user(org, List.of(PermissionFunction.RESOURCE, PermissionFunction.ARTIFACT_DOWNLOAD));
		UserData reader = user(org, List.of(PermissionFunction.RESOURCE));
		return new World(org, foreign, covered, uncovered, foreignComponent, releaseArtifact, otherArtifact, bom(org.getUuid()),
				foreignArtifact, bom(foreign.getUuid()), downloader, reader,
				freeform(org, orgWide(org, PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD)),
				freeform(org, new PermissionDto(org.getUuid(), PermissionScope.COMPONENT, covered.getUuid(),
						PermissionType.READ_ONLY, Set.of(PermissionFunction.ARTIFACT_DOWNLOAD), null)),
				freeform(org, orgWide(org, PermissionType.READ_ONLY, PermissionFunction.AGENT)),
				freeform(org, orgWide(org, PermissionType.ADMIN)),
				freeform(foreign, orgWide(foreign, PermissionType.ADMIN)));
	}

	protected Component component(Organization org) throws Exception {
		return componentService.createComponent("score17-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	protected void release(Organization org, Component c, UUID artifact) throws Exception {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("17.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(new ArrayList<>(List.of(artifact))).build(), WU);
	}

	/** A stored CycloneDX BOM artifact, as an upload leaves it. */
	protected UUID bom(UUID org) throws Exception {
		return artifactService.createArtifact(ArtifactDto.builder().org(org).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score17-" + UUID.randomUUID())
				.internalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE)).build(), WU).getUuid();
	}

	/** A member of {@code org} with READ_ONLY at organization scope and the given functions. */
	protected UserData user(Organization org, List<PermissionFunction> functions) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("S17 " + tag, "score17-" + tag + "@score.io", true, List.of(org.getUuid()),
				"score17-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, functions, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected static PermissionDto orgWide(Organization org, PermissionType type, PermissionFunction... functions) {
		return new PermissionDto(org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), type, Set.of(functions), null);
	}

	/** A Free Form key of {@code org} with exactly {@code grant}, and a secret. */
	protected Key freeform(Organization org, PermissionDto grant) throws Exception {
		ApiKey row = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "score17", WU);
		apiKeyService.setPermissionsOnApiKey(row.getUuid(), null, List.of(grant), WU);
		return withSecret(row);
	}

	/** A personal key of {@code owner} with exactly {@code grant}, and a secret. */
	protected Key personal(UserData owner, Organization org, PermissionDto grant) throws Exception {
		ApiKey row = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "score17", WU);
		apiKeyService.setPermissionsOnApiKey(row.getUuid(), null, List.of(grant), WU);
		return withSecret(row);
	}

	private Key withSecret(ApiKey row) throws Exception {
		String secret = apiKeyService.addApiKeySecret(row.getUuid(), null, WU);
		ApiKey stored = apiKeyService.getApiKey(row.getUuid()).orElseThrow();
		return new Key(stored, ApiKeyService.keyIdOf(stored), secret);
	}

	// ---- credentials -----------------------------------------------------------------------------

	protected static String basic(String id, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	protected static String basic(Key k) {
		return basic(k.id(), k.secret());
	}

	protected static String bearer(String token) {
		return "Bearer " + token;
	}

	/** The key exchanged at the token endpoint (client_credentials, the key as Basic), as a client does it. */
	protected String exchanged(Key k) throws Exception {
		HttpResponse<String> r = post(ProgrammaticTokenController.PATH, "grant_type=client_credentials", basic(k));
		assertEquals(200, r.statusCode(), r.body());
		return Utils.OM.readTree(r.body()).get("access_token").asText();
	}

	/** A CLI browser login: started, approved by {@code user} on {@code key}, and collected at the token endpoint. */
	protected CliLogin login(UserData user, ApiKey key) throws Exception {
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("score17-host", null, null, null, null));
		CliSession s = cliSessionService.approveWithKey(d.userCode(), user, key.getUuid());
		HttpResponse<String> r = post(ProgrammaticTokenController.PATH, "grant_type="
				+ URLEncoder.encode(ProgrammaticTokenController.DEVICE_CODE_GRANT, StandardCharsets.UTF_8)
				+ "&device_code=" + URLEncoder.encode(d.deviceCode(), StandardCharsets.UTF_8), null);
		assertEquals(200, r.statusCode(), r.body());
		return new CliLogin(s.getUuid(), Utils.OM.readTree(r.body()).get("access_token").asText());
	}

	protected record CliLogin(UUID session, String token) {}

	/** An RS256 token as an IdP mints it; only the mocked decoder of the browser chain accepts it. */
	private static String idpSignedToken() throws Exception {
		RSAKey rsa = new RSAKeyGenerator(2048).keyID("idp-key").generate();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("idp-key").build(),
				new JWTClaimsSet.Builder().issuer("https://idp.score17.invalid/realms/Reliza").subject("score17-downloader")
						.audience("account").claim("azp", "login-app").expirationTime(Date.from(Instant.now().plusSeconds(3600)))
						.issueTime(new Date()).build());
		jwt.sign(new RSASSASigner(rsa));
		return jwt.serialize();
	}

	// ---- HTTP ------------------------------------------------------------------------------------

	protected static String download(UUID artifact) {
		return "/api/programmatic/v1/artifact/" + artifact + "/download";
	}

	protected static String rawDownload(UUID artifact) {
		return "/api/programmatic/v1/artifact/" + artifact + "/rawdownload";
	}

	protected HttpResponse<String> get(String path, String authorization) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (null != authorization) b.header(HttpHeaders.AUTHORIZATION, authorization);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	protected HttpResponse<String> post(String path, String formOrJson, String authorization) throws Exception {
		boolean json = formOrJson.startsWith("{");
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header(HttpHeaders.CONTENT_TYPE, json ? "application/json" : "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formOrJson));
		if (null != authorization) b.header(HttpHeaders.AUTHORIZATION, authorization);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	protected static String header(HttpResponse<?> r, String name) {
		return r.headers().firstValue(name).orElse(null);
	}

	/** Asserts the status, with the body in the message when it differs. */
	protected static void assertStatus(int expected, HttpResponse<String> r) {
		assertStatus(expected, r, "");
	}

	protected static void assertStatus(int expected, HttpResponse<String> r, String what) {
		assertEquals(expected, r.statusCode(), () -> what + " " + r.request().uri().getPath() + " body: " + r.body()
				+ " headers: " + r.headers().map());
	}

	// ---- download log ----------------------------------------------------------------------------

	/** The download-log rows written for {@code artifact}, record data as maps. */
	protected List<Map<String, Object>> downloadLogs(UUID artifact) {
		return jdbcTemplate.queryForList("select record_data::text as rd from rearm.download_logs where record_data->>'subjectUuid' = ?",
				artifact.toString()).stream().map(row -> {
					try {
						@SuppressWarnings("unchecked")
						Map<String, Object> m = Utils.OM.readValue((String) row.get("rd"), Map.class);
						return m;
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}).toList();
	}
}
