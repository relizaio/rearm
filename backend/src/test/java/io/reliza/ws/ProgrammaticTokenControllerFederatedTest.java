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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.common.LogSafe;
import io.reliza.common.OAuthErrors;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.FederatedAssertionService;
import io.reliza.service.FederatedAssertionService.AssertionException;
import io.reliza.service.FederatedAssertionService.Refusal;
import io.reliza.service.FederatedAssertionService.VerifiedAssertion;
import io.reliza.service.FederatedMatching;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.saas.SaasOrganizationService;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The jwt-bearer grant answers every refusal before a trust rule admits the verified identity with one wire answer,
 * so an unauthenticated caller cannot tell an issuer no rule names from a trusted issuer whose assertion did not
 * verify (task TPF-31, architecture-2 4.2). The real controller and exchange over a standalone MockMvc; the assertion
 * service is a spy, so {@code peekIssuer} runs real and {@code verify} is stubbed per row (no network). Its own class
 * so the spy does not re-key the shared context of {@link ProgrammaticTokenControllerTest}.
 */
@SpringBootTest(classes = {App.class})
public class ProgrammaticTokenControllerFederatedTest {

	@Autowired private ProgrammaticTokenController tokenController;
	@Autowired private TestInitializer testInitializer;
	@Autowired private FederatedTrustRuleService federatedTrustRuleService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private SaasOrganizationService saasOrganizationService;
	@MockitoSpyBean private FederatedAssertionService assertionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String OWNER = "tpf31";
	private static final String ADDRESS = "198.51.100.31";
	private static KeyPair throwaway;

	private MockMvc mvc;
	private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
	private AbstractAppender appender;

	@BeforeAll
	static void keys() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		throwaway = g.generateKeyPair();
	}

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(tokenController).build();
		appender = new AbstractAppender("tpf31-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				lines.add(event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		controllerLogger().addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		controllerLogger().removeAppender(appender);
		appender.stop();
	}

	private static org.apache.logging.log4j.core.Logger controllerLogger() {
		return (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ProgrammaticTokenController.class);
	}

	// ---- rig ---------------------------------------------------------------------------------

	/** An issuer of the form the design gives, unique per call so no other test's rule names it. */
	private static String issuer() {
		return "https://issuer-" + UUID.randomUUID().toString().substring(0, 8) + ".tpf31.invalid";
	}

	private FederatedTrustRule rule(UUID org, String issuer) throws Exception {
		FederatedMatcher matcher = new FederatedMatcher();
		matcher.setOwner(OWNER);
		FederatedGrant grant = new FederatedGrant();
		grant.setOrgPermission(PermissionType.READ_ONLY);
		return federatedTrustRuleService.create(org, "tpf31-rule-" + UUID.randomUUID(), Provider.GITHUB_ACTIONS, issuer, matcher, grant, null, WU);
	}

	private UUID org() {
		return testInitializer.obtainOrganization().getUuid();
	}

	/** An RS256 assertion naming {@code iss}, signed by a throwaway key (the real verify would refuse it; rows stub verify). */
	private static String assertion(String iss) throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(iss).subject("repo:" + OWNER + "/app:ref:refs/heads/main")
				.audience("https://rearm.example.com").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.jwtID(UUID.randomUUID().toString()).claim("repository", OWNER + "/app").claim("repository_owner", OWNER).build();
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("tpf31").build(), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) throwaway.getPrivate()));
		return jwt.serialize();
	}

	/** What a successful verify returns for {@code owner/name}: ids set, a fresh jti, five minutes left. */
	private static VerifiedAssertion verified(String iss, String owner, String repository) {
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("sub", "repo:" + repository + ":ref:refs/heads/main");
		claims.put("repository", repository);
		claims.put("repository_owner", owner);
		claims.put("repository_id", "3101");
		claims.put("repository_owner_id", "3100");
		claims.put("ref", "refs/heads/main");
		claims.put("sha", "abc31");
		claims.put("run_id", "31");
		String jti = UUID.randomUUID().toString();
		claims.put("jti", jti);
		return new VerifiedAssertion(FederatedMatching.fromGitHub(iss, claims), claims, Instant.now().plusSeconds(300), jti);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static MockHttpServletRequestBuilder form(String body) {
		MockHttpServletRequestBuilder b = post(ProgrammaticTokenController.PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body);
		b.with(r -> { r.setRemoteAddr(ADDRESS); return r; });
		return b;
	}

	private static MockHttpServletRequestBuilder grant(String assertion, String clientId) {
		return form("grant_type=" + enc(OAuthErrors.GRANT_JWT_BEARER) + "&assertion=" + enc(assertion)
				+ (clientId == null ? "" : "&client_id=" + enc(clientId)));
	}

	/** Everything on the wire: status, raw body, parsed body, and every header but Date. */
	private record Answer(int status, String raw, Map<String, Object> body, Map<String, List<String>> headers) {
		String cacheControl() {
			List<String> v = headers.get(HttpHeaders.CACHE_CONTROL);
			return v == null ? null : String.join(",", v);
		}
		List<Object> tuple() {
			return List.of(status, String.valueOf(body.get("error")), String.valueOf(body.get("error_description")), String.valueOf(cacheControl()));
		}
	}

	@SuppressWarnings("unchecked")
	private Answer send(MockHttpServletRequestBuilder b) throws Exception {
		MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
		String raw = r.getContentAsString(StandardCharsets.UTF_8);
		Map<String, Object> body = raw.isEmpty() ? Map.of() : JSON.readValue(raw, Map.class);
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : r.getHeaderNames()) {
			if (!HttpHeaders.DATE.equalsIgnoreCase(name)) headers.put(name, r.getHeaders(name));
		}
		return new Answer(r.getStatus(), raw, body, headers);
	}

	private static void assertNotAccepted(Answer a) {
		assertEquals(200, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_GRANT, a.body().get("error"), a.raw());
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, a.body().get("error_description"), a.raw());
		assertEquals("no-store", a.cacheControl(), a.raw());
		assertFalse(a.headers().containsKey(HttpHeaders.WWW_AUTHENTICATE), a.headers().toString());
		assertEquals(2, a.body().size(), a.raw());
	}

	/** The one refusal line this request wrote (lines of other threads cannot match: they carry other issuers). */
	private String refusalLine(String iss) {
		List<String> mine;
		synchronized (lines) {
			mine = lines.stream().filter(l -> l.startsWith("SECURITY: federated identity exchange refused from ")
					&& (iss == null || l.contains("(issuer " + iss + ","))).toList();
		}
		assertEquals(1, mine.size(), "one refusal line: " + mine);
		return mine.get(0);
	}

	// ---- rows 1-6: the refusals before admission answer identically ----------------------------

	private Answer unknownIssuer() throws Exception {
		String iss = issuer();
		Answer a = send(grant(assertion(iss), null));
		verify(assertionService, never()).verify(anyString(), anyString(), any());
		assertEquals("SECURITY: federated identity exchange refused from " + ADDRESS + ": UNTRUSTED_ISSUER (issuer " + iss + ", identity -)",
				refusalLine(iss));
		return a;
	}

	private Answer knownIssuerVerifyThrows(Refusal reason, String message) throws Exception {
		String iss = issuer();
		rule(org(), iss);
		doThrow(new AssertionException(reason, message)).when(assertionService).verify(anyString(), anyString(), any());
		Answer a = send(grant(assertion(iss), null));
		assertEquals("SECURITY: federated identity exchange refused from " + ADDRESS + ": " + reason + " (issuer " + iss + ", identity -)",
				refusalLine(iss));
		assertFalse(refusalLine(iss).contains(message), "the service's message stays off the line too");
		return a;
	}

	private Answer knownIssuerIdentityUnmatched() throws Exception {
		String iss = issuer();
		rule(org(), iss);
		doReturn(verified(iss, "other", "other/repo")).when(assertionService).verify(anyString(), anyString(), any());
		Answer a = send(grant(assertion(iss), null));
		assertEquals("SECURITY: federated identity exchange refused from " + ADDRESS + ": IDENTITY_UNMATCHED (issuer " + iss + ", identity other/repo)",
				refusalLine(iss));
		return a;
	}

	@Test
	public void row1UnknownIssuer() throws Exception {
		assertNotAccepted(unknownIssuer());
	}

	@Test
	public void row2KnownIssuerKeysUnavailable() throws Exception {
		assertNotAccepted(knownIssuerVerifyThrows(Refusal.KEYS_UNAVAILABLE, "issuer keys unavailable"));
	}

	@Test
	public void row3KnownIssuerNotVerified() throws Exception {
		assertNotAccepted(knownIssuerVerifyThrows(Refusal.NOT_VERIFIED, "assertion rejected: signature, key or format not accepted"));
	}

	@Test
	public void row4KnownIssuerAudience() throws Exception {
		assertNotAccepted(knownIssuerVerifyThrows(Refusal.AUDIENCE, "audience must be https://rearm.example.com"));
	}

	@Test
	public void row5KnownIssuerIdentityUnmatched() throws Exception {
		assertNotAccepted(knownIssuerIdentityUnmatched());
	}

	@Test
	public void row6TheFiveRefusalsAreOneAnswer() throws Exception {
		List<Answer> answers = new ArrayList<>();
		answers.add(unknownIssuer());
		lines.clear();
		answers.add(knownIssuerVerifyThrows(Refusal.KEYS_UNAVAILABLE, "issuer keys unavailable"));
		lines.clear();
		answers.add(knownIssuerVerifyThrows(Refusal.NOT_VERIFIED, "assertion rejected: signature, key or format not accepted"));
		lines.clear();
		answers.add(knownIssuerVerifyThrows(Refusal.AUDIENCE, "audience must be https://rearm.example.com"));
		lines.clear();
		answers.add(knownIssuerIdentityUnmatched());
		Answer first = answers.get(0);
		assertNotAccepted(first);
		for (Answer a : answers) {
			assertEquals(first.tuple(), a.tuple());
			assertEquals(first.raw(), a.raw());
			// the whole header set, Date aside (security review round 3, observation 3)
			assertEquals(first.headers(), a.headers());
		}
	}

	// ---- row 7: the pre-lookup answers are unchanged ---------------------------------------------

	@Test
	public void row7MalformedAndEmptyAssertionsAreUnchanged() throws Exception {
		Answer bad = send(grant("not-a-jwt", null));
		assertEquals(200, bad.status(), bad.raw());
		assertEquals(OAuthErrors.INVALID_GRANT, bad.body().get("error"), bad.raw());
		assertEquals("malformed assertion", bad.body().get("error_description"), bad.raw());
		assertEquals("SECURITY: federated identity exchange refused from " + ADDRESS + ": MALFORMED (issuer -, identity -)", refusalLine(null));
		Answer missing = send(form("grant_type=" + enc(OAuthErrors.GRANT_JWT_BEARER)));
		assertEquals(200, missing.status(), missing.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, missing.body().get("error"));
		assertEquals("assertion is required", missing.body().get("error_description"));
		verify(assertionService, never()).verify(anyString(), anyString(), any());
	}

	// ---- row 9: the address on the refusal line is rendered through LogSafe.text ------------------

	/**
	 * Under {@code forward-headers-strategy: native} Tomcat's RemoteIpValve makes {@code getRemoteAddr()} the
	 * X-Forwarded-For entry whenever the socket peer is in the internal-proxy range, unvalidated. The standalone
	 * MockMvc runs no valve, so a second {@code setRemoteAddr} post-processor stands in for what it would resolve:
	 * a tab, a non-ASCII unit, text mimicking the line, and more than 256 units.
	 */
	@Test
	public void row9TheAddressIsRenderedThroughLogSafe() throws Exception {
		String forged = "198.51.100.31\t: UNTRUSTED_ISSUER (issuer \u00e9" + "x".repeat(300);
		Answer bad = send(grant("not-a-jwt", null).with(r -> { r.setRemoteAddr(forged); return r; }));
		assertEquals(200, bad.status(), bad.raw());
		assertEquals("malformed assertion", bad.body().get("error_description"), bad.raw());
		String prefix = "SECURITY: federated identity exchange refused from ";
		String suffix = ": MALFORMED (issuer -, identity -)";
		String line = refusalLine(null);
		assertEquals(prefix + LogSafe.text(forged) + suffix, line);
		assertFalse(line.contains("\t"), line);
		assertTrue(line.chars().allMatch(c -> c >= 0x20 && c <= 0x7E), line);
		assertTrue(line.startsWith(prefix) && line.endsWith(suffix), line);
		String field = line.substring(prefix.length(), line.length() - suffix.length());
		assertEquals(256 + 3, field.length(), field);
		assertTrue(field.endsWith("..."), field);
		assertEquals(forged.substring(0, 256).replace('\t', '?').replace('\u00e9', '?'), field.substring(0, 256));
	}

	// ---- row 8: a valid assertion from a trusted issuer still exchanges ---------------------------

	@Test
	public void row8AnAdmittedIdentityStillExchanges() throws Exception {
		String iss = issuer();
		UUID org = org();
		rule(org, iss);
		doReturn(verified(iss, OWNER, OWNER + "/app")).when(assertionService).verify(anyString(), anyString(), any());
		Answer a = send(grant(assertion(iss), null));
		assertEquals(200, a.status(), a.raw());
		assertNull(a.body().get("error"), a.raw());
		assertNotNull(a.body().get("access_token"), a.raw());
		assertEquals("Bearer", a.body().get("token_type"));
		assertNotNull(a.body().get("api_key_uuid"));
		assertNotNull(a.body().get("api_key_id"));
		assertNotNull(a.body().get("expires_in"));
		assertEquals(org.toString(), a.body().get("org"));
		assertEquals(OWNER + "/app", a.body().get("identity"));
		assertEquals(List.of("access_token", "token_type", "expires_in", "api_key_id", "api_key_uuid", "org", "identity"),
				List.copyOf(a.body().keySet()), "the success body is unchanged, member order included");
		assertEquals("no-store", a.cacheControl());
		assertTrue(apiKeyService.getFederatedIdentity(org, identity(OWNER + "/app")).isPresent());
		synchronized (lines) {
			assertTrue(lines.stream().noneMatch(l -> l.contains("(issuer " + iss + ",")), "no refusal line: " + lines);
		}
	}

	private static FederatedIdentity identity(String repository) {
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(Provider.GITHUB_ACTIONS);
		fi.setRepository(repository);
		return fi;
	}

	// ---- organisation selection (security review round 3, observation 3) ------------------------

	@Test
	public void clientIdSelectsAmongTheTrustingOrganizationsOnly() throws Exception {
		String iss = issuer();
		UUID orgA = org();
		UUID orgB = saasOrganizationService.createOrganization("tpf31-b-" + UUID.randomUUID(), WU).getUuid();
		UUID orgC = saasOrganizationService.createOrganization("tpf31-c-" + UUID.randomUUID(), WU).getUuid();
		rule(orgA, iss);
		rule(orgB, iss);
		String repository = OWNER + "/two-orgs-" + UUID.randomUUID().toString().substring(0, 8);

		doReturn(verified(iss, OWNER, repository)).when(assertionService).verify(anyString(), anyString(), any());
		Answer several = send(grant(assertion(iss), null));
		assertEquals(200, several.status(), several.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, several.body().get("error"), several.raw());
		assertEquals("this identity is trusted by several organizations; pass client_id=<organization uuid>", several.body().get("error_description"));

		doReturn(verified(iss, OWNER, repository)).when(assertionService).verify(anyString(), anyString(), any());
		Answer named = send(grant(assertion(iss), orgA.toString()));
		assertEquals(200, named.status(), named.raw());
		assertEquals(orgA.toString(), named.body().get("org"), named.raw());
		assertNotNull(named.body().get("access_token"));

		lines.clear();
		doReturn(verified(iss, OWNER, repository)).when(assertionService).verify(anyString(), anyString(), any());
		Answer third = send(grant(assertion(iss), orgC.toString()));
		assertNotAccepted(third);
		assertEquals("SECURITY: federated identity exchange refused from " + ADDRESS + ": ORG_NOT_NAMED (issuer " + iss + ", identity " + repository + ")",
				refusalLine(iss));
		assertTrue(apiKeyService.getFederatedIdentity(orgC, identity(repository)).isEmpty(), "nothing written for the third organization");
		assertTrue(apiKeyService.getFederatedIdentity(orgB, identity(repository)).isEmpty(), "nor for the one not named");
	}
}
