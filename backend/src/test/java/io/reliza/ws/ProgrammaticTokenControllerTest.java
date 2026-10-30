/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.CliSession;
import io.reliza.model.CliSession.Status;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.CliSessionRepository;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.CliSessionService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The token and revoke endpoints take their OAuth parameters from the form body (and the client
 * credentials from HTTP Basic) only; the same parameters in the URL query string are refused with
 * 400 invalid_request before anything else is read (task TPF-3). Driven through Spring's real
 * request binding with a standalone MockMvc, because the defect was in the binding: a
 * {@code @RequestParam} reads the query string as well as the body.
 */
@SpringBootTest(classes = {App.class})
public class ProgrammaticTokenControllerTest {

	@Autowired private ProgrammaticTokenController tokenController;
	@Autowired private DeviceAuthorizationController deviceAuthorizationController;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private ApiTokenService apiTokenService;
	@Autowired private CliSessionService cliSessionService;
	@Autowired private CliSessionRepository cliSessionRepository;
	@Autowired private UserService userService;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Never a real secret: a secret must not go into a URI, not even in a test, and the refusal comes before any lookup. */
	private static final String DUMMY_SECRET = "dummy-secret-" + UUID.randomUUID();

	private MockMvc mvc;
	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(tokenController, deviceAuthorizationController).build();
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

	private record Key(String id, String secret) {
		String basic() {
			return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
		}
	}

	private Key freeformKey() throws Exception {
		return freeformKey(testInitializer.obtainOrganization().getUuid());
	}

	/** A FREEFORM key on {@code org}: two calls on one org give it two keys. */
	private Key freeformKey(UUID org) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "TPF-3 token query test", WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		return new Key(ApiKeyService.keyIdOf(key), secret);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** A form POST whose body is {@code form} and whose URL carries {@code query}, if any, both already encoded: a URI, not a template, so token%5B%5D arrives as token[] (TPF-52 T-1). */
	private static MockHttpServletRequestBuilder form(String path, String query, String form) {
		MockHttpServletRequestBuilder b = post(URI.create(query == null ? path : path + "?" + query))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content(form == null ? "" : form);
		b.with(r -> { r.setRemoteAddr("198.51.100.23"); return r; });
		return b;
	}

	private record Answer(int status, String raw, Map<String, Object> body, String cacheControl) {}

	@SuppressWarnings("unchecked")
	private Answer send(MockHttpServletRequestBuilder b) throws Exception {
		MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
		String raw = r.getContentAsString(StandardCharsets.UTF_8);
		Map<String, Object> body = raw.isEmpty() ? Map.of() : JSON.readValue(raw, Map.class);
		return new Answer(r.getStatus(), raw, body, r.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	private static void assertRefused(Answer a, String... names) {
		assertEquals(400, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, a.body().get("error"), a.raw());
		String description = (String) a.body().get("error_description");
		assertNotNull(description, a.raw());
		assertTrue(description.startsWith(OAuthQueryGuard.BODY_ONLY_DESCRIPTION), "the description names the rule: " + description);
		for (String n : names) assertTrue(description.contains(n), "the description names " + n + ": " + description);
		assertFalse(a.body().containsKey("access_token"), "no token: " + a.raw());
		assertEquals("no-store", a.cacheControl());
	}

	private void assertTokenIssued(Answer a) {
		assertEquals(200, a.status(), a.raw());
		assertTrue(apiTokenService.verify((String) a.body().get("access_token"), ApiTokenService.AUD_PROGRAMMATIC).isPresent(),
				"a verifiable access token: " + a.raw());
		assertEquals("Bearer", a.body().get("token_type"));
		assertEquals(3600L, ((Number) a.body().get("expires_in")).longValue());
	}

	// 1. the defect: client credentials in the query string, empty body
	@Test
	public void queryStringCredentialsAreRefused() throws Exception {
		Key k = freeformKey();
		Answer a = send(form(ProgrammaticTokenController.PATH,
				"grant_type=client_credentials&client_id=" + enc(k.id()) + "&client_secret=" + enc(DUMMY_SECRET), null));
		assertRefused(a, "grant_type", "client_id", "client_secret");
		assertFalse(a.raw().contains(DUMMY_SECRET), "the answer never repeats the secret sent in the URI");
		assertFalse(a.raw().contains(k.id()), "the answer never repeats a value sent in the URI");
	}

	// 2. mixed sources: Basic plus query credentials, and Basic plus grant_type in the query (the TEA-3 repro variant)
	@Test
	public void basicPlusQueryParametersIsRefused() throws Exception {
		Key k = freeformKey();
		assertRefused(send(form(ProgrammaticTokenController.PATH, "client_id=" + enc(k.id()) + "&client_secret=x",
				"grant_type=client_credentials").header(HttpHeaders.AUTHORIZATION, k.basic())), "client_id", "client_secret");
		assertRefused(send(form(ProgrammaticTokenController.PATH, "grant_type=client_credentials", null)
				.header(HttpHeaders.AUTHORIZATION, k.basic())), "grant_type");
	}

	// 3. intended source: the credentials as form fields in the body, empty query string
	@Test
	public void formBodyCredentialsStillWork() throws Exception {
		Key k = freeformKey();
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null,
				"grant_type=client_credentials&client_id=" + enc(k.id()) + "&client_secret=" + enc(k.secret()))));
	}

	// 4. intended source: HTTP Basic with grant_type in the body (the Go client's exact shape)
	@Test
	public void httpBasicStillWorks() throws Exception {
		Key k = freeformKey();
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, k.basic())));
	}

	// ---- TPF-51 4.2: an orderless id of an ordered type is the invalid-client 401, never a server error; header alone
	// or form alone in every row, as TPF-50's guard answers a request sending both with its 400 before the parse

	private static void assertInvalidClient(Answer a, String description) {
		assertEquals(401, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_CLIENT, a.body().get("error"), a.raw());
		assertEquals(description, a.body().get("error_description"), a.raw());
		assertFalse(a.body().containsKey("access_token"), "no token: " + a.raw());
		assertEquals("no-store", a.cacheControl());
	}

	private static final String UNKNOWN_KEY = "unknown API key or wrong secret";

	private Answer clientCredentialsBasic(String id, String secret) throws Exception {
		return send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, new Key(id, secret).basic()));
	}

	// rows 1 to 4: an org with two FREEFORM keys
	@Test
	public void anOrderlessFreeformIdIsTheInvalidClient401() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Key first = freeformKey(org);
		Key second = freeformKey(org);
		// row 1: header alone (the defect: the ambiguous lookup's exception left the controller)
		assertInvalidClient(clientCredentialsBasic("FREEFORM__" + org, first.secret()), UNKNOWN_KEY);
		// row 2: form alone
		assertInvalidClient(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id="
				+ enc("FREEFORM__" + org) + "&client_secret=" + enc(first.secret()))), UNKNOWN_KEY);
		// row 3: the empty order
		assertInvalidClient(clientCredentialsBasic("FREEFORM__" + org + "__ord__", first.secret()), UNKNOWN_KEY);
		// row 4: each key by its own id is unchanged
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, first.basic())));
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, second.basic())));
	}

	// row 5: an org with exactly one FREEFORM key, the orderless id with its real secret (was a token)
	@Test
	public void anOrderlessFreeformIdOnAOneKeyOrgIsRefused() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Key only = freeformKey(org);
		assertInvalidClient(clientCredentialsBasic("FREEFORM__" + org, only.secret()), UNKNOWN_KEY);
	}

	/** The exchange verified the key's secret, so it bumps the key's last access (task t20261013-072815-2705). */
	@Test
	public void aTokenExchangeBumpsTheKeysLastAccess() throws Exception {
		Key k = freeformKey();
		UUID keyUuid = apiKeyService.resolveApiKeyReference(k.id()).orElseThrow().getUuid();
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, k.basic())));
		apiKeyAccessService.flushPendingLastAccessTouches();
		assertNotNull(apiKeyService.getApiKey(keyUuid).orElseThrow().getLastAccessDate());
		assertNotNull(apiKeyService.getApiKeyDto(keyUuid).orElseThrow().getAccessDate(), "and the DTO carries it");
	}

	// unrelated query parameters (a cache-buster) are ignored as before
	@Test
	public void unrelatedQueryParametersAreIgnored() throws Exception {
		Key k = freeformKey();
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, "scope=x&cb=1", "grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, k.basic())));
	}

	// 5. the other grants' parameters in the query: real 400s, not the 200 soft errors of those grants
	@Test
	public void otherGrantParametersInTheQueryAreRefused() throws Exception {
		assertRefused(send(form(ProgrammaticTokenController.PATH,
				"grant_type=" + enc(OAuthErrors.GRANT_DEVICE_CODE) + "&device_code=x", null)), "grant_type", "device_code");
		assertRefused(send(form(ProgrammaticTokenController.PATH,
				"grant_type=refresh_token&refresh_token=x", null)), "grant_type", "refresh_token");
		assertRefused(send(form(ProgrammaticTokenController.PATH,
				"grant_type=" + enc(OAuthErrors.GRANT_JWT_BEARER) + "&assertion=x", null)), "grant_type", "assertion");
		assertRefused(send(form(ProgrammaticTokenController.PATH, "device_code=x",
				"grant_type=" + enc(OAuthErrors.GRANT_DEVICE_CODE))), "device_code");
	}

	private record Live(CliSession session, String refreshToken) {}

	/** A delivered device-login session on a member's personal key: start, approve, collect through the token endpoint's body. */
	private Live liveSession() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("T " + tag, "t-" + tag + "@tokenquery.io", true, List.of(org.getUuid()),
				"t-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, null, null, WU);
		UserData owner = userService.getUserData(u.getUuid()).orElseThrow();
		ApiKey key = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "TPF-3 revoke test", WU);
		CliSessionService.DeviceAuthorization d = cliSessionService.start(new CliSessionService.DeviceInfo("tpf3-host", null, null, null, null));
		cliSessionService.approveWithKey(d.userCode(), owner, key.getUuid());
		Answer a = send(form(ProgrammaticTokenController.PATH, null,
				"grant_type=" + enc(OAuthErrors.GRANT_DEVICE_CODE) + "&device_code=" + enc(d.deviceCode())));
		assertEquals(200, a.status(), a.raw());
		String refreshToken = (String) a.body().get("refresh_token");
		assertNotNull(refreshToken, a.raw());
		CliSession s = cliSessionRepository.findById(UUID.fromString((String) a.body().get("session"))).orElseThrow();
		return new Live(s, refreshToken);
	}

	private Status statusOf(CliSession s) {
		return cliSessionRepository.findById(s.getUuid()).orElseThrow().getStatus();
	}

	// 6. revoke: the refresh token in the query is refused and the session lives; in the body it ends the session
	@Test
	public void revokeTakesTheTokenFromTheBodyOnly() throws Exception {
		Live live = liveSession();
		assertEquals(Status.ACTIVE, statusOf(live.session()));

		Answer refused = send(form(DeviceAuthorizationController.REVOKE_PATH, "token=" + enc(live.refreshToken()), null));
		assertRefused(refused, "token");
		assertFalse(refused.raw().contains(live.refreshToken()), "the answer never repeats the token sent in the URI");
		assertEquals(Status.ACTIVE, statusOf(live.session()), "a token in the URL does not end the session");

		Answer revoked = send(form(DeviceAuthorizationController.REVOKE_PATH, null, "token=" + enc(live.refreshToken())));
		assertEquals(200, revoked.status(), revoked.raw());
		assertEquals("", revoked.raw(), "success has no body");
		assertEquals("no-store", revoked.cacheControl());
		assertEquals(Status.REVOKED, statusOf(live.session()), "the body token ends the session");
	}

	// revoke keeps answering 200 for an unknown token in the body (RFC 7009: no probing)
	@Test
	public void revokeOfAnUnknownBodyTokenIsStill200() throws Exception {
		Answer a = send(form(DeviceAuthorizationController.REVOKE_PATH, null, "token=not-a-live-token"));
		assertEquals(200, a.status(), a.raw());
		assertEquals("", a.raw());
	}

	// ---- TPF-52 4.2: revoke refuses a repeated token and reads it by exact name only, through the real binding
	private Answer revoke(String query, String body) throws Exception { return send(form(DeviceAuthorizationController.REVOKE_PATH, query, body)); }
	private static void assertRevokeAnswered(Answer a) {
		assertEquals(200, a.status(), a.raw());
		assertEquals("", a.raw(), "success has no body");
		assertEquals("no-store", a.cacheControl());
	}

	// row 1: a repeated token is refused and the session lives; the same token once ends it
	@Test
	public void revokeRefusesARepeatedToken() throws Exception {
		Live live = liveSession();
		String t = enc(live.refreshToken());
		Answer a = revoke(null, "token=" + t + "&token=" + t);
		assertRefusedRepeated(a, "token");
		assertFalse(a.raw().contains(live.refreshToken()), "the answer never repeats the token");
		assertEquals(Status.ACTIVE, statusOf(live.session()));
		assertRevokeAnswered(revoke(null, "token=" + t));
		assertEquals(Status.REVOKED, statusOf(live.session()));
	}

	// row 2: a token under a decorated or case-changed name is not read (the binder read token[] when token was absent)
	@Test
	public void revokeNeverReadsADecoratedTokenName() throws Exception {
		Live live = liveSession();
		String t = enc(live.refreshToken());
		for (String name : List.of("token%5B%5D", "token%5B0%5D", "token.value", "Token", "TOKEN")) {
			assertRevokeAnswered(revoke(null, name + "=" + t));
			assertEquals(Status.ACTIVE, statusOf(live.session()), name + " is not read");
		}
		assertRevokeAnswered(revoke(null, "token=" + t));
		assertEquals(Status.REVOKED, statusOf(live.session()));
	}

	// rows 3 to 5: a decorated query name is neither refused nor read; no token answers 200; a query token is refused first
	@Test
	public void revokeIgnoresADecoratedQueryNameAndRefusesAQueryTokenFirst() throws Exception {
		Live live = liveSession();
		assertRevokeAnswered(revoke("token%5B%5D=" + enc(live.refreshToken()), null)); // an empty body: the token is not echoed
		assertEquals(Status.ACTIVE, statusOf(live.session()));
		assertRevokeAnswered(revoke(null, null));
		assertRefused(revoke("token=" + enc(live.refreshToken()), "token=a&token=b"), "token");
		assertEquals(Status.ACTIVE, statusOf(live.session()));
	}

	// ---- TPF-26 4.3: a repeated parameter is refused before any parse; the parameters are read by exact name only

	private static void assertRefusedRepeated(Answer a, String... names) {
		assertEquals(400, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, a.body().get("error"), a.raw());
		String description = (String) a.body().get("error_description");
		assertNotNull(description, a.raw());
		assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: " + String.join(", ", names), description);
		assertFalse(a.body().containsKey("access_token"), "no token: " + a.raw());
		assertEquals("no-store", a.cacheControl());
	}

	private static void assertNoCredential(Answer a) {
		assertEquals(401, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_CLIENT, a.body().get("error"), a.raw());
		assertTrue(((String) a.body().get("error_description")).startsWith("client authentication required"), a.raw());
		assertFalse(a.body().containsKey("access_token"), "no token: " + a.raw());
		assertEquals("no-store", a.cacheControl());
	}

	private Answer sendRaw(MockHttpServletRequestBuilder b, String[] wwwAuthenticate) throws Exception {
		MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
		wwwAuthenticate[0] = r.getHeader(HttpHeaders.WWW_AUTHENTICATE);
		String raw = r.getContentAsString(StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = raw.isEmpty() ? Map.of() : JSON.readValue(raw, Map.class);
		return new Answer(r.getStatus(), raw, body, r.getHeader(HttpHeaders.CACHE_CONTROL));
	}

	/** The object uuid and key order of a key id; a FREEFORM key of an org has the org uuid as its object. */
	private record Parts(String object, String order) {}

	private static Parts partsOf(Key k) {
		String[] ord = k.id().split("__ord__", 2);
		return new Parts(ord[0].split("__")[1], ord[1]);
	}

	// row 1: the F-4 shape, a repeated client_id whose join would name E
	@Test
	public void aRepeatedClientIdIsRefusedAndTheKeyIsUntouched() throws Exception {
		Key e = freeformKey();
		Parts p = partsOf(e);
		Answer a = send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id=" + enc("FREEFORM__" + p.object() + "__ORGANIZATION")
				+ "&client_id=" + enc("__" + p.object() + "__ord__" + p.order()) + "&client_secret=" + enc(e.secret())));
		assertRefusedRepeated(a, "client_id");
		assertFalse(a.raw().contains(p.object()), "no value is repeated");
		assertFalse(a.raw().contains(e.secret()), "the secret is not repeated");
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null,
				"grant_type=client_credentials&client_id=" + enc(e.id()) + "&client_secret=" + enc(e.secret()))));
	}

	// row 2
	@Test
	public void eachRepeatedParameterIsNamedSorted() throws Exception {
		Key k = freeformKey();
		assertRefusedRepeated(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id=" + enc(k.id())
				+ "&client_secret=" + enc(k.secret()) + "&client_secret=x")), "client_secret");
		assertRefusedRepeated(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&grant_type=client_credentials")
				.header(HttpHeaders.AUTHORIZATION, k.basic())), "grant_type");
		assertRefusedRepeated(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_secret=a&client_id=" + enc(k.id())
				+ "&client_secret=b&client_id=x")), "client_id", "client_secret");
	}

	// row 3: the other grants get the 400 too, not their 200 soft error
	@Test
	public void aRepeatedParameterOfTheOtherGrantsIsRefused() throws Exception {
		assertRefusedRepeated(send(form(ProgrammaticTokenController.PATH, null,
				"grant_type=" + enc(OAuthErrors.GRANT_DEVICE_CODE) + "&device_code=a&device_code=b")), "device_code");
		assertRefusedRepeated(send(form(ProgrammaticTokenController.PATH, null, "grant_type=refresh_token&refresh_token=a&refresh_token=b")),
				"refresh_token");
	}

	// row 4: the query-string guard runs first
	@Test
	public void theQueryStringRefusalPrecedesTheRepeatRefusal() throws Exception {
		Key k = freeformKey();
		assertRefused(send(form(ProgrammaticTokenController.PATH, "client_id=" + enc(k.id()),
				"grant_type=client_credentials&client_id=" + enc(k.id()) + "&client_secret=x")), "client_id");
	}

	// row 5: a repeated name outside the six is ignored
	@Test
	public void aRepeatedUnrelatedParameterIsIgnored() throws Exception {
		Key k = freeformKey();
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&scope=a&scope=b")
				.header(HttpHeaders.AUTHORIZATION, k.basic())));
	}

	// row 7: the binder spellings are read by nobody (the F-5 shape)
	@Test
	public void binderSpellingsCarryNoCredential() throws Exception {
		Key e = freeformKey();
		String[] www = new String[1];
		Answer a = sendRaw(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id%5B%5D=" + enc(e.id())
				+ "&client_secret%5B%5D=" + enc(e.secret())), www);
		assertNoCredential(a);
		assertEquals("Basic realm=\"rearm\"", www[0]);
		assertFalse(a.raw().contains(e.secret()));
		assertFalse(a.raw().contains(e.id()));
		assertNoCredential(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id%5B0%5D=" + enc(e.id())
				+ "&client_secret=" + enc(e.secret()))));
		assertNoCredential(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id.value=" + enc(e.id())
				+ "&client_secret=" + enc(e.secret()))));
		assertNoCredential(send(form(ProgrammaticTokenController.PATH, null, "grant_type=client_credentials&client_id=" + enc(e.id())
				+ "&client_secret%5B%5D=" + enc(e.secret()))));
		assertTokenIssued(send(form(ProgrammaticTokenController.PATH, null,
				"grant_type=client_credentials&client_id=" + enc(e.id()) + "&client_secret=" + enc(e.secret()))));
	}

	// row 8: client_secret[] in the query string never authenticates
	@Test
	public void suffixedNamesInTheQueryStringNeverAuthenticate() throws Exception {
		Key e = freeformKey();
		Answer a = send(form(ProgrammaticTokenController.PATH, "client_secret%5B%5D=" + enc(DUMMY_SECRET),
				"grant_type=client_credentials&client_id=" + enc(e.id())));
		assertNoCredential(a);
		assertFalse(a.raw().contains(DUMMY_SECRET));
		Answer b = send(form(ProgrammaticTokenController.PATH, "client_id%5B%5D=" + enc(e.id()) + "&client_secret%5B%5D=" + enc(DUMMY_SECRET),
				"grant_type=client_credentials"));
		assertNoCredential(b);
		assertFalse(b.raw().contains(DUMMY_SECRET));
	}

	// row 10: the other grants still bind through the exact-name read
	@Test
	public void theOtherGrantsStillReadTheirParameters() throws Exception {
		Answer missing = send(form(ProgrammaticTokenController.PATH, null, "grant_type=" + enc(OAuthErrors.GRANT_JWT_BEARER)));
		assertEquals(200, missing.status(), missing.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, missing.body().get("error"));
		assertEquals("assertion is required", missing.body().get("error_description"));
		Answer bad = send(form(ProgrammaticTokenController.PATH, null, "grant_type=" + enc(OAuthErrors.GRANT_JWT_BEARER) + "&assertion=not-a-jwt"));
		assertEquals(200, bad.status(), bad.raw());
		assertEquals(OAuthErrors.INVALID_GRANT, bad.body().get("error"), bad.raw());
		assertFalse("assertion is required".equals(bad.body().get("error_description")), "the assertion reached the exchange");
		Answer refresh = send(form(ProgrammaticTokenController.PATH, null, "grant_type=refresh_token&refresh_token=not-a-live-token"));
		assertEquals(200, refresh.status(), refresh.raw());
		assertEquals(OAuthErrors.INVALID_GRANT, refresh.body().get("error"), refresh.raw());
	}
}
