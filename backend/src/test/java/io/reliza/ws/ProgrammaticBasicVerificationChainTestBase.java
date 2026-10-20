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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.OAuthErrors;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.common.CommonVariables.InstanceType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ComponentData;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.saas.Instance;
import io.reliza.repositories.ApiKeyAccessRepository;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.UserService;
import io.reliza.service.saas.InstanceService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;
import jakarta.servlet.http.HttpServlet;
import tools.jackson.databind.json.JsonMapper;


/**
 * The shared rig of the programmatic chain's Basic tests: the real chains of the application context, the
 * limiter's clock paused per case, fresh addresses and keys per count, a spied {@link ApiKeyService} and
 * {@link ApiKeyAccessService}, and one key fixture per Basic key type. TPF-26's limiter cases are in
 * {@link ProgrammaticBasicVerificationChainTest}; TPF-27's filter cases in
 * {@link ProgrammaticBasicFilterVerificationChainTest}; TPF-29's /graphql filter cases (browser chain) in
 * {@link LegacyGraphqlBasicChainTest}. All share one cached context through this base, with the assertion helpers and
 * the refusal maps below.
 */
@SpringBootTest(classes = {App.class})
public abstract class ProgrammaticBasicVerificationChainTestBase {

	@Autowired @Qualifier("programmaticFilterChain") protected SecurityFilterChain programmaticFilterChain;
	@Autowired @Qualifier("filterChain") protected SecurityFilterChain filterChain;
	@Autowired protected RateLimitingFilter rateLimitingFilter;
	/** Every chain in the order FilterChainProxy tries them (by @Order): the first that matches serves the request. */
	@Autowired protected List<SecurityFilterChain> chains;
	@Autowired protected ProgrammaticTokenController tokenController;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ApiTokenService apiTokenService;
	@Autowired protected LicenseStatus licenseStatus;
	@Autowired protected AuthorizationService authorizationService;
	@Autowired protected UserService userService;
	@Autowired protected InstanceService instanceService;
	@Autowired protected ComponentService componentService;
	@Autowired protected ApiKeyAccessRepository apiKeyAccessRepository;
	@Autowired protected PlatformTransactionManager txManager;
	@MockitoSpyBean protected ApiKeyService apiKeyService;
	@MockitoSpyBean protected ApiKeyAccessService apiKeyAccessService;

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final JsonMapper JSON = JsonMapper.builder().build();
	protected static final String REMAINING = "X-Rate-Limit-Remaining";
	protected static final String LIMITED_BODY = "{\"error\":\"Rate limit exceeded\"}";
	/** Fresh addresses from 198.18.0.0/15 (benchmarking range), never an address another test class counts with. */
	private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void setUp() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
		rateLimitingFilter.clock.pause();
	}

	@AfterEach
	void restore() {
		rateLimitingFilter.clock.resume();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/**
	 * A fresh address per call. The counter is static in this base, so the two subclasses never hand out the
	 * same address while they share the one limiter bean of the cached context.
	 */
	protected static String freshAddress() {
		int n = ADDRESSES.getAndIncrement();
		return "198." + (18 + (n >> 16 & 1)) + "." + (n >> 8 & 255) + "." + (n & 255);
	}

	protected static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	protected static String basic(String id, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	protected static MockHttpServletRequest request(String method, String path, String address) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		req.setRequestURI(path);
		req.setServletPath(path);
		req.setRemoteAddr(address);
		return req;
	}

	/**
	 * A request as the container hands a percent-encoded spelling to a filter: the request URI as sent (what the
	 * path matchers parse) and the servlet path decoded (what Tomcat sets).
	 */
	protected static MockHttpServletRequest request(String method, String requestUri, String servletPath, String address) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, requestUri);
		req.setRequestURI(requestUri);
		req.setServletPath(servletPath);
		req.setRemoteAddr(address);
		return req;
	}

	/** The spelling form of {@link #request(String, String, String, String)}, the servlet path decoded from the URI. */
	protected static MockHttpServletRequest spelled(String method, String requestUri, String address, String authorization) {
		MockHttpServletRequest req = request(method, requestUri, URLDecoder.decode(requestUri, StandardCharsets.UTF_8), address);
		if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return req;
	}

	protected static MockHttpServletRequest withAuthorization(String method, String path, String address, String authorization) {
		MockHttpServletRequest req = request(method, path, address);
		if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return req;
	}

	/** The chain that serves a request: the first of {@link #chains} (in @Order) whose matcher claims it. */
	protected SecurityFilterChain servedBy(MockHttpServletRequest req) {
		return chains.stream().filter(c -> c.matches(req)).findFirst().orElseThrow();
	}

	protected static HttpHeaders headersOf(MockHttpServletRequest req) {
		HttpHeaders h = new HttpHeaders();
		String a = req.getHeader(HttpHeaders.AUTHORIZATION);
		if (a != null) h.set(HttpHeaders.AUTHORIZATION, a);
		return h;
	}

	protected record Run(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
		boolean reached() {
			return chain.getRequest() != null;
		}

		String remaining() {
			return response.getHeader(REMAINING);
		}

		Object principal() {
			return request.getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
		}
	}

	protected Run through(SecurityFilterChain chain, MockHttpServletRequest req, MockFilterChain after) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		new FilterChainProxy(chain).doFilter(req, resp, after);
		return new Run(req, resp, after);
	}

	protected Run programmatic(MockHttpServletRequest req) throws Exception {
		return through(programmaticFilterChain, req, new MockFilterChain());
	}

	protected static void assertLimited(Run r, String what) throws Exception {
		assertEquals(429, r.response().getStatus(), what);
		assertTrue(Long.parseLong(r.response().getHeader("Retry-After")) >= 1, what);
		assertEquals(LIMITED_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertFalse(r.reached(), what + ": the chain is not reached");
		assertNull(r.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), what + ": no challenge");
		assertNull(r.response().getHeader("Deprecation"), what + ": the authentication filter did not run");
		assertNull(r.principal(), what);
	}

	protected static void assertInvalidToken(Run r, String remaining, String what) throws Exception {
		assertEquals(401, r.response().getStatus(), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertEquals(remaining, r.remaining(), what + ": counted by the limiter first");
		assertFalse(r.reached(), what);
	}

	// ---- the Basic filters' answers (moved up from ProgrammaticBasicFilterVerificationChainTest for TPF-29)

	protected static void assertRefused(Run r, String what) throws Exception {
		assertEquals(401, r.response().getStatus(), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, r.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertTrue(String.join(", ", r.response().getHeaders(HttpHeaders.CACHE_CONTROL)).contains("no-store"), what);
		assertTrue(r.response().getContentType().startsWith("application/json"), what + ": " + r.response().getContentType());
		assertNotNull(r.remaining(), what + ": counted by the limiter first");
		assertNull(r.response().getHeader("Deprecation"), what + ": no deprecation header on a refusal");
		assertNull(r.response().getHeader("Link"), what);
		assertNull(r.response().getHeader("X-ReARM-Deprecation"), what);
		assertFalse(r.reached(), what + ": no resolver or handler runs");
		assertNull(r.principal(), what + ": no principal");
	}

	protected static AuthHeaderParse assertAdmittedBasic(Run r, Key k, String address, String what) {
		assertTrue(r.reached(), what + ": admitted, " + r.response().getStatus());
		assertEquals(200, r.response().getStatus(), what);
		AuthHeaderParse p = assertInstanceOf(AuthHeaderParse.class, r.principal(), what);
		assertEquals(k.uuid(), p.getVerifiedKeyUuid(), what);
		assertEquals(k.type(), p.getType(), what);
		assertEquals(k.object(), p.getObjUuid(), what);
		assertNull(p.getApiKey(), what + ": the secret does not travel past the filter");
		boolean orgKey = k.type() == ApiTypeEnum.ORGANIZATION || k.type() == ApiTypeEnum.ORGANIZATION_RW;
		assertEquals(orgKey ? k.org() : null, p.getOrgUuid(), what + ": the row org for the organization keys only");
		assertEquals(ApiKeyService.keyIdOf(k.key()), p.getApiKeyId(), what);
		assertNotNull(p.getMatchedSecretSlot(), what);
		assertEquals(address, p.getRemoteIp(), what);
		assertEquals("true", r.response().getHeader("Deprecation"), what);
		assertNotNull(r.response().getHeader("Link"), what);
		assertNotNull(r.response().getHeader("X-ReARM-Deprecation"), what);
		assertNotNull(r.remaining(), what);
		return p;
	}

	/** A refusal as the wire sees it, minus what differs per request (Date, the bucket count): every header, the status, the body. */
	protected static Map<String, Object> answerOf(Run r) throws Exception {
		Map<String, Object> m = new TreeMap<>();
		for (String name : r.response().getHeaderNames()) {
			if (!name.equalsIgnoreCase("Date") && !name.equalsIgnoreCase(REMAINING)) m.put(name, r.response().getHeaders(name));
		}
		m.put("status", r.response().getStatus());
		m.put("body", r.response().getContentAsString(StandardCharsets.UTF_8));
		return m;
	}

	// ---- the refusal kinds, each on every path the caller names, sent by the caller's sender

	/** How a case sends one request: the method and path, and the {@code Authorization} value. */
	@FunctionalInterface
	protected interface Send {
		Run send(String[] path, String authorization) throws Exception;
	}

	protected Map<String, Run> wrongSecrets(List<Key> keys, List<String[]> paths, Send send) throws Exception {
		Map<String, Run> out = new LinkedHashMap<>();
		for (Key k : keys) {
			for (String[] path : paths) out.put("wrong secret " + k.type() + " on " + path[1], send.send(path, basic(k.id(), k.secret() + "x")));
		}
		return out;
	}

	protected Map<String, Run> unknownIds(List<String[]> paths, Send send) throws Exception {
		Map<String, Run> out = new LinkedHashMap<>();
		for (ApiTypeEnum type : List.of(ApiTypeEnum.FREEFORM, ApiTypeEnum.ORGANIZATION_RW, ApiTypeEnum.ORGANIZATION, ApiTypeEnum.USER,
				ApiTypeEnum.INSTANCE, ApiTypeEnum.CLUSTER, ApiTypeEnum.COMPONENT, ApiTypeEnum.APPROVAL)) {
			String id = type + "__" + UUID.randomUUID();
			if (type == ApiTypeEnum.FREEFORM || type == ApiTypeEnum.USER) id = id + "__ord__" + UUID.randomUUID();
			for (String[] path : paths) out.put("unknown " + id + " on " + path[1], send.send(path, basic(id, "any")));
		}
		return out;
	}

	protected static String b64(String s) {
		return "Basic " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	protected Map<String, Run> malformedAndForeign(List<String[]> paths, Send send) throws Exception {
		UUID u = UUID.randomUUID();
		Map<String, Run> out = new LinkedHashMap<>();
		for (String authorization : List.of("Basic !!!notbase64", b64("nocolon"), b64(":x"), b64("FREEFORM__" + u + "__ord__1:"),
				b64("FOO__" + u + ":x"), b64("FREEFORM__notauuid__ord__1:x"), "Basic", "Digest abc", "Token abc")) {
			for (String[] path : paths) out.put(authorization + " on " + path[1], send.send(path, authorization));
		}
		return out;
	}

	// ---- fixtures

	protected record Key(ApiKey key, String id, String secret) {
		UUID uuid() {
			return key.getUuid();
		}

		UUID object() {
			return key.getObjectUuid();
		}

		UUID org() {
			return key.getOrg();
		}

		String order() {
			return key.getKeyOrder();
		}

		ApiTypeEnum type() {
			return key.getObjectType();
		}
	}

	protected Key freeformKey() throws Exception {
		return freeformKey(testInitializer.obtainOrganization().getUuid());
	}

	protected Key freeformKey(UUID org) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "TPF-26 limiter chain test", WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		return new Key(apiKeyService.getApiKey(key.getUuid()).orElseThrow(), ApiKeyService.keyIdOf(key), secret);
	}

	/** A key minted with setObjectApiKey (slot 1) on an object of {@code org}: the legacy org keys, instance, cluster, component. */
	protected Key objectKey(UUID object, ApiTypeEnum type, UUID org) throws Exception {
		String secret = apiKeyService.setObjectApiKey(object, type, org, null, "TPF-27 filter chain test", false, WU);
		ApiKey key = apiKeyService.getApiKeyByObjUuidTypeOrder(object, type, null, org).orElseThrow();
		return new Key(key, ApiKeyService.keyIdOf(key), secret);
	}

	protected Key organizationRwKey() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		return objectKey(org, ApiTypeEnum.ORGANIZATION_RW, org);
	}

	protected Key organizationKey() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		return objectKey(org, ApiTypeEnum.ORGANIZATION, org);
	}

	/** A member's personal key, as rearm login mints it, with a secret added. */
	protected Key userKey() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("P " + tag, "p-" + tag + "@tpf27chain.io", true, List.of(org.getUuid()),
				"p-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, null, null, WU);
		ApiKey key = apiKeyService.createObjectApiKey(u.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "TPF-27 filter chain test", WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		return new Key(apiKeyService.getApiKey(key.getUuid()).orElseThrow(), ApiKeyService.keyIdOf(key), secret);
	}

	protected Key instanceKey() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// createInstance runs @Modifying updates that need a surrounding transaction
		Instance inst = new TransactionTemplate(txManager).execute(status -> {
			try {
				return instanceService.createInstance("tpf27-" + UUID.randomUUID() + ".example.com", org, null, WU);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		return objectKey(inst.getUuid(), ApiTypeEnum.INSTANCE, org);
	}

	protected Key clusterKey() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Instance cluster = new TransactionTemplate(txManager).execute(status -> {
			try {
				String tag = UUID.randomUUID().toString();
				return instanceService.createInstance("tpf27-cluster-" + tag + ".example.com", "tpf27-cluster-" + tag, InstanceType.CLUSTER,
						null, org, null, null, WU);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		return objectKey(cluster.getUuid(), ApiTypeEnum.CLUSTER, org);
	}

	/** A component bound to a VCS repository, so its main branch exists. */
	protected Key componentKey() throws Exception {
		return componentKey(testInitializer.obtainOrganization().getUuid());
	}

	protected Key componentKey(UUID org) throws Exception {
		ComponentData component = componentService.createComponentFromVcsUri(org, "https://github.com/tpf27/repo-" + UUID.randomUUID(),
				null, null, null, "semver", "Branch.Micro", WU);
		return objectKey(component.getUuid(), ApiTypeEnum.COMPONENT, org);
	}

	/** One fresh key per Basic key type the filter verifies, each on a fresh organisation. */
	protected List<Key> keyPerType() throws Exception {
		return List.of(freeformKey(), organizationRwKey(), organizationKey(), userKey(), instanceKey(), clusterKey(), componentKey());
	}

	protected ApiKeySecret firstSecret(UUID keyUuid) {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).orElseThrow();
		return ApiKeyData.dataFromRecord(ak).effectiveSecrets(ak.getApiKey()).get(0);
	}

	/** A bearer minted in process, so no exchange is counted for it. */
	protected String mint(UUID keyUuid) {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).orElseThrow();
		return apiTokenService.issue(ak, firstSecret(keyUuid), ApiTokenService.AUD_PROGRAMMATIC);
	}

	protected int keyAccessCount(UUID key) {
		return apiKeyAccessRepository.countKeyAccessByKeyId(key);
	}

	/**
	 * A signed, IdP-shaped JWT. Programmatic chain only: never send it through the browser chain ({@code filterChain}),
	 * whose resource server would fetch the JWK set of application.yaml to check it; nothing serves that set in the test
	 * JVM, and the failure propagates out of {@link #through} as an AuthenticationServiceException (TPF-29 architecture-3
	 * 4.1). Use {@link #unsignedToken()} for a JWT-shaped bearer on the browser chain.
	 */
	protected static String idpShapedToken() throws Exception {
		RSAKey rsa = new RSAKeyGenerator(2048).keyID("idp-" + UUID.randomUUID()).generate();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).build(),
				new JWTClaimsSet.Builder().subject("someone").issuer("https://idp.example").audience("account")
						.expirationTime(new Date(System.currentTimeMillis() + 600_000)).build());
		jwt.sign(new RSASSASigner(rsa));
		return jwt.serialize();
	}

	/** A JWT with {@code alg: none}: the browser chain's decoder refuses it (BadJwtException) before any key fetch. */
	protected static String unsignedToken() {
		return new PlainJWT(new JWTClaimsSet.Builder().subject("someone").issuer("https://idp.example")
				.expirationTime(new Date(System.currentTimeMillis() + 600_000)).build()).serialize();
	}
}
