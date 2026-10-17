/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.context.request.ServletWebRequest;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.OAuthErrors;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.CliSessionRepository;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.CliSessionService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;
import jakarta.servlet.http.HttpServletRequest;

/**
 * TPF-13: the programmatic artifact downloads ({@link ArtifactWs#PROGRAMMATIC_ARTIFACT_PATTERN}) are on
 * the programmatic chain, and its {@link ProgrammaticAuthenticationFilter} accepts a ReARM bearer access
 * token there exactly as on the GraphQL endpoint. Driven with the real chains and the real filter instance
 * of the application context over mock requests (architecture tests 4 to 10); the handler that consumes
 * the principal is pinned by {@code ArtifactWsAuthenticationOrderTest}, and the served bytes by the harness.
 */
@SpringBootTest(classes = {App.class})
public class ProgrammaticArtifactDownloadChainTest {

	@Autowired @Qualifier("programmaticFilterChain") private SecurityFilterChain programmaticFilterChain;
	@Autowired @Qualifier("filterChain") private SecurityFilterChain filterChain;
	@Autowired private ProgrammaticTokenController tokenController;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiTokenService apiTokenService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private CliSessionService cliSessionService;
	@Autowired private CliSessionRepository cliSessionRepository;
	@Autowired private UserService userService;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String REMOTE = "198.51.100.41";
	private static final Supplier<Authentication> ANONYMOUS = () -> new AnonymousAuthenticationToken("key",
			"anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void setUp() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private static String download(UUID artifact) {
		return "/api/programmatic/v1/artifact/" + artifact + "/download";
	}

	private static String rawDownload(UUID artifact) {
		return "/api/programmatic/v1/artifact/" + artifact + "/rawdownload";
	}

	/** Both programmatic download paths, for a fresh artifact uuid (the filter never reads it). */
	private static List<String> artifactPaths() {
		UUID artifact = UUID.randomUUID();
		return List.of(download(artifact), rawDownload(artifact));
	}

	private static MockHttpServletRequest request(String method, String path, String query) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		req.setRequestURI(path);
		req.setServletPath(path);
		req.setQueryString(query);
		req.setRemoteAddr(REMOTE);
		return req;
	}

	private static MockHttpServletRequest withAuthorization(String path, String authorization) {
		MockHttpServletRequest req = request(path.endsWith("graphql") ? "POST" : "GET", path, null);
		if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return req;
	}

	private ProgrammaticAuthenticationFilter filter() {
		List<ProgrammaticAuthenticationFilter> found = programmaticFilterChain.getFilters().stream()
				.filter(ProgrammaticAuthenticationFilter.class::isInstance).map(ProgrammaticAuthenticationFilter.class::cast)
				.toList();
		assertEquals(1, found.size(), "the programmatic chain carries exactly one programmatic authentication filter");
		return found.get(0);
	}

	private record Run(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
		boolean reachedChain() {
			return chain.getRequest() != null;
		}

		Object principal() {
			return request.getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
		}
	}

	private Run run(MockHttpServletRequest req) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter().doFilter(req, resp, chain);
		return new Run(req, resp, chain);
	}

	private Run bearer(String path, String token) throws Exception {
		return run(withAuthorization(path, "Bearer " + token));
	}

	private static void assertInvalidToken(Run r, String what) throws Exception {
		assertEquals(401, r.response().getStatus(), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, r.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertEquals("no-store", r.response().getHeader(HttpHeaders.CACHE_CONTROL), what);
		assertFalse(r.reachedChain(), what + ": the handler must not run");
		assertNull(r.principal(), what + ": no principal");
	}

	private static AuthHeaderParse assertAdmitted(Run r, String what) {
		assertTrue(r.reachedChain(), what + ": admitted, " + r.response().getStatus());
		assertEquals(200, r.response().getStatus(), what);
		return assertInstanceOf(AuthHeaderParse.class, r.principal(), what);
	}

	private static HttpHeaders headersOf(MockHttpServletRequest req) {
		HttpHeaders h = new HttpHeaders();
		String a = req.getHeader(HttpHeaders.AUTHORIZATION);
		if (a != null) h.set(HttpHeaders.AUTHORIZATION, a);
		return h;
	}

	/** The seam the download handler's keyGate consumes: authenticateProgrammatic, then verifyProgrammaticKey. */
	private VerifiedProgrammaticKey handlerSeam(MockHttpServletRequest req) {
		AuthHeaderParse ahp = authorizationService.authenticateProgrammatic(headersOf(req), new ServletWebRequest(req));
		assertNotNull(ahp, "a credential is present");
		return authorizationService.verifyProgrammaticKey(ahp);
	}

	// ---- fixtures

	private record Key(ApiKey key, String id, String secret) {
		String basic() {
			return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
		}

		UUID uuid() {
			return key.getUuid();
		}
	}

	private Key freeformKey() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "TPF-13 download bearer test", WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		return new Key(apiKeyService.getApiKey(key.getUuid()).orElseThrow(), ApiKeyService.keyIdOf(key), secret);
	}

	private ApiKeySecret firstSecret(UUID keyUuid) {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).orElseThrow();
		return ApiKeyData.dataFromRecord(ak).effectiveSecrets(ak.getApiKey()).get(0);
	}

	private String mint(UUID keyUuid, String audience) {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).orElseThrow();
		return apiTokenService.issue(ak, firstSecret(keyUuid), audience);
	}

	/** A bearer from the token endpoint itself: client_credentials with the key as HTTP Basic. */
	private String tokenFromEndpoint(Key k) {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticTokenController.PATH);
		req.setRemoteAddr(REMOTE);
		req.addHeader(HttpHeaders.AUTHORIZATION, k.basic());
		req.setParameter("grant_type", OAuthErrors.GRANT_CLIENT_CREDENTIALS);
		Map<String, Object> body = tokenController.token(req).getBody();
		assertNotNull(body);
		String token = (String) body.get("access_token");
		assertNotNull(token, String.valueOf(body));
		return token;
	}

	private record Login(UserData owner, ApiKey key, CliSession session, String accessToken) {}

	/** A delivered device login on a member's personal key: start, approve, collect at the token endpoint (device_code grant). */
	private Login cliLogin() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("D " + tag, "d-" + tag + "@tpf13download.io", true, List.of(org.getUuid()),
				"d-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, null, null, WU);
		UserData owner = userService.getUserData(u.getUuid()).orElseThrow();
		ApiKey key = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "TPF-13 download session test", WU);
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("tpf13-host", null, null, null, null));
		cliSessionService.approveWithKey(d.userCode(), owner, key.getUuid());
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticTokenController.PATH);
		req.setRemoteAddr(REMOTE);
		req.setParameter("grant_type", ProgrammaticTokenController.DEVICE_CODE_GRANT);
		req.setParameter("device_code", d.deviceCode());
		Map<String, Object> body = tokenController.token(req).getBody();
		assertNotNull(body);
		assertNull(body.get("error"), String.valueOf(body));
		CliSession s = cliSessionRepository.findById(UUID.fromString((String) body.get("session"))).orElseThrow();
		return new Login(owner, key, s, (String) body.get("access_token"));
	}

	// ---- test 4: chain selection

	@Test
	void theDownloadPathsAreOnTheProgrammaticChainAndTheManualPairIsNot() {
		UUID artifact = UUID.randomUUID();
		for (String path : List.of(download(artifact), rawDownload(artifact))) {
			for (String query : new String[] {null, "version=2"}) {
				assertTrue(programmaticFilterChain.matches(request("GET", path, query)), path + "?" + query + " is on the programmatic chain");
			}
		}
		String manual = "/api/manual/v1/artifact/" + artifact + "/download";
		for (String path : List.of(manual, "/graphql", "/api/healthCheck")) {
			assertFalse(programmaticFilterChain.matches(request("GET", path, null)), path + " is not on the programmatic chain");
		}
		assertTrue(filterChain.matches(request("GET", manual, null)), "the manual pair stays on the browser chain");
		filter(); // exactly one programmatic authentication filter on the chain

		AuthorizationManager<HttpServletRequest> authz = filterChain.getFilters().stream()
				.filter(AuthorizationFilter.class::isInstance).map(AuthorizationFilter.class::cast)
				.findFirst().orElseThrow().getAuthorizationManager();
		assertFalse(authz.authorize(ANONYMOUS, request("GET", "/api/manual/v1/artifact/x/download", null)).isGranted(),
				"the browser chain still refuses an anonymous caller on the manual pair");
	}

	// ---- test 5: a bearer from the token endpoint is admitted on both paths and accepted by the handler's seam

	@Test
	void aTokenEndpointBearerIsAdmittedOnBothDownloadPaths() throws Exception {
		Key k = freeformKey();
		String token = tokenFromEndpoint(k);
		for (String path : artifactPaths()) {
			Run r = bearer(path, token);
			AuthHeaderParse p = assertAdmitted(r, path);
			assertEquals(k.uuid(), p.getVerifiedKeyUuid(), path);
			assertTrue(p.isRbacKey(), path);
			assertNull(r.response().getHeader("Deprecation"), path + ": a bearer is not deprecated");

			AuthHeaderParse seen = authorizationService.authenticateProgrammatic(headersOf(r.request()), new ServletWebRequest(r.request()));
			assertSame(p, seen, path + ": the handler takes the filter's principal");
			VerifiedProgrammaticKey vk = authorizationService.verifyProgrammaticKey(seen);
			assertSame(p, vk.principal(), path);
			assertEquals(k.key().getOrg(), vk.org(), path);
		}
	}

	// ---- test 6: a CLI session bearer is admitted, and refused once the session is revoked

	@Test
	void aCliSessionBearerIsAdmittedUntilTheSessionIsRevoked() throws Exception {
		Login l = cliLogin();
		for (String path : artifactPaths()) {
			Run r = bearer(path, l.accessToken());
			AuthHeaderParse p = assertAdmitted(r, path);
			assertEquals(l.key().getUuid(), p.getVerifiedKeyUuid(), path + ": the approving key");
			assertEquals(l.owner().getUuid(), p.getActorUser(), path + ": the user is the actor");
			assertEquals(l.session().getUuid(), p.getCliSession(), path);
			VerifiedProgrammaticKey vk = authorizationService.verifyProgrammaticKey(
					authorizationService.authenticateProgrammatic(headersOf(r.request()), new ServletWebRequest(r.request())));
			assertSame(p, vk.principal(), path);
			assertEquals(l.key().getOrg(), vk.org(), path);
		}
		cliSessionService.revoke(cliSessionRepository.findById(l.session().getUuid()).orElseThrow(), WU);
		for (String path : artifactPaths()) {
			assertInvalidToken(bearer(path, l.accessToken()), path + " after revoke");
		}
	}

	// ---- test 7: expired and revoked bearers get the RFC 6750 invalid_token challenge

	@Test
	void aBornExpiredBearerIsRefused() throws Exception {
		Key k = freeformKey();
		apiKeyService.setApiKeySecretExpiry(k.uuid(), firstSecret(k.uuid()).getSlot(), ZonedDateTime.now().minusHours(2), WU);
		String expired = mint(k.uuid(), ApiTokenService.AUD_PROGRAMMATIC); // issue clips exp to the secret's expiry
		for (String path : artifactPaths()) {
			assertInvalidToken(bearer(path, expired), path + " expired");
		}
	}

	@Test
	void aBearerFollowsItsSecretOffAndOnAgain() throws Exception {
		Key k = freeformKey();
		int slot = firstSecret(k.uuid()).getSlot();
		String token = mint(k.uuid(), ApiTokenService.AUD_PROGRAMMATIC);
		for (String path : artifactPaths()) assertAdmitted(bearer(path, token), path + " before retiring");
		apiKeyService.setApiKeySecretActive(k.uuid(), slot, false, WU);
		for (String path : artifactPaths()) assertInvalidToken(bearer(path, token), path + " secret retired");
		apiKeyService.setApiKeySecretActive(k.uuid(), slot, true, WU);
		for (String path : artifactPaths()) assertAdmitted(bearer(path, token), path + " secret re-enabled");
	}

	@Test
	void aBearerOfADeactivatedKeyIsRefused() throws Exception {
		Key k = freeformKey();
		String token = mint(k.uuid(), ApiTokenService.AUD_PROGRAMMATIC);
		apiKeyService.setApiKeyStatus(k.uuid(), ApiKeyStatus.INACTIVE, false, WU);
		for (String path : artifactPaths()) assertInvalidToken(bearer(path, token), path + " key inactive");
	}

	@Test
	void aTeaAudienceTokenOrGarbageIsRefused() throws Exception {
		Key k = freeformKey();
		String tea = mint(k.uuid(), ApiTokenService.AUD_TEA);
		for (String path : artifactPaths()) {
			assertInvalidToken(bearer(path, tea), path + " tea audience");
			assertInvalidToken(bearer(path, "not-a-token"), path + " garbage");
		}
	}

	// ---- test 8: an IdP user JWT is not a programmatic credential

	@Test
	void anIdpUserJwtIsRefusedByTheFilter() throws Exception {
		RSAKey rsa = new RSAKeyGenerator(2048).keyID("idp-" + UUID.randomUUID()).generate();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer("https://idp.example.test/realms/Reliza")
				.subject(UUID.randomUUID().toString())
				.claim("azp", "login-app")
				.issueTime(new Date())
				.expirationTime(Date.from(Instant.now().plusSeconds(600)))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(), claims);
		jwt.sign(new RSASSASigner(rsa));
		String userJwt = jwt.serialize();
		for (String path : artifactPaths()) {
			assertInvalidToken(bearer(path, userJwt), path + " IdP user JWT");
		}
	}

	// ---- test 9: the GraphQL endpoint behaves as before for a bearer and no credential; since TPF-27 it verifies Basic

	@Test
	void theGraphQlEndpointVerifiesBasicAndKeepsTheRest() throws Exception {
		Key k = freeformKey();
		String path = ProgrammaticGraphQlConfig.PATH;

		Run valid = bearer(path, tokenFromEndpoint(k));
		assertEquals(k.uuid(), assertAdmitted(valid, "valid bearer").getVerifiedKeyUuid());

		Key expiring = freeformKey();
		apiKeyService.setApiKeySecretExpiry(expiring.uuid(), firstSecret(expiring.uuid()).getSlot(), ZonedDateTime.now().minusHours(2), WU);
		assertInvalidToken(bearer(path, mint(expiring.uuid(), ApiTokenService.AUD_PROGRAMMATIC)), "expired bearer on GraphQL");

		Run basic = run(withAuthorization(path, k.basic()));
		AuthHeaderParse verified = assertAdmitted(basic, "valid Basic");
		assertEquals(k.uuid(), verified.getVerifiedKeyUuid(), "the filter verified the Basic key");
		assertNull(verified.getApiKey(), "the secret does not travel past the filter");
		assertEquals("true", basic.response().getHeader("Deprecation"));
		assertNotNull(basic.response().getHeader("Link"));
		assertNotNull(basic.response().getHeader("X-ReARM-Deprecation"));

		Run none = run(withAuthorization(path, null));
		assertEquals(401, none.response().getStatus());
		assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, none.response().getHeader(HttpHeaders.WWW_AUTHENTICATE));
		assertEquals("no-store", none.response().getHeader(HttpHeaders.CACHE_CONTROL));
		assertFalse(none.reachedChain());
	}

	// ---- test 10: Basic on the download paths, verified by the filter (TPF-27), the handler's seam takes its principal

	@Test
	void basicIsVerifiedByTheFilterAndTheHandlerTakesItsPrincipal() throws Exception {
		Key k = freeformKey();
		for (String path : artifactPaths()) {
			Run r = run(withAuthorization(path, k.basic()));
			AuthHeaderParse p = assertAdmitted(r, path);
			assertEquals(k.uuid(), p.getVerifiedKeyUuid(), path);
			assertNull(p.getApiKey(), path);
			assertEquals("true", r.response().getHeader("Deprecation"), path);
			assertNotNull(r.response().getHeader("Link"), path);
			assertNotNull(r.response().getHeader("X-ReARM-Deprecation"), path);
			VerifiedProgrammaticKey vk = handlerSeam(r.request());
			assertSame(p, vk.principal(), path + ": the handler takes the filter's principal");
			assertEquals(k.key().getOrg(), vk.org(), path);

			Key wrong = new Key(k.key(), k.id(), k.secret() + "x");
			assertInvalidToken(run(withAuthorization(path, wrong.basic())), path + ": the filter refuses a wrong secret");
		}
	}
}
