/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.common.oss.LicensingConstants;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.model.UserData;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.repositories.ApiKeyRepository;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.ws.App;
import io.reliza.ws.tea.TeaAuthenticationFilter;
import io.reliza.ws.tea.TeaTokenController;

/**
 * Task TEA-3, design 4.8 cases 41 to 45: the TEA token endpoint and the TEA filter with the real beans against the
 * test database, and the per-object check on a real perspective (Pro).
 */
// TPF-142 row 21: the allow-list of the host token path cases (the other cases send no host)
@SpringBootTest(classes = {App.class}, properties = "relizaprops.teaCustomDomains=tea-a.test,tea-b.test,Tea-C.test.")
public class TeaTokenEndpointIntegrationTest extends ExternalKeyIntegrationTestBase {

	@Autowired private TeaTokenController tokenController;
	@Autowired private ExternalApiKeyService externalApiKeyService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiTokenService apiTokenService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private ApiKeyRepository apiKeyRepository;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TeaAuthorizationService teaAuthorizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private OrganizationService organizationService;
	@Autowired private TeaHostAssignmentService teaHostAssignmentService;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	@AfterEach
	void clearHostMap() {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	/** The token endpoint on {@code host}: the uuid path when {@code teaOrg} is set, else the host path. */
	private ResponseEntity<?> exchangeOn(String teaOrg, String host, String keyId, String secret, String query) {
		String path = null == teaOrg ? TeaTokenController.HOST_TOKEN_PATH_TEMPLATE : "/tea/" + teaOrg + "/v1.0.0/token";
		MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
		req.setServerName(host);
		req.setRemoteAddr("192.0.2.34");
		if (null != query) req.setQueryString(query);
		req.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		req.setParameter("grant_type", "client_credentials");
		return tokenController.token(req, teaOrg);
	}

	private TeaCaller filterOnHost(String token) throws Exception {
		TeaAuthenticationFilter f = new TeaAuthenticationFilter(apiTokenService, apiKeyService);
		String uri = "/tea/v1.0.0/product/" + UUID.randomUUID();
		MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
		req.setRequestURI(uri);
		req.setServerName("tea-b.test");
		req.addHeader("Authorization", "Bearer " + token);
		f.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
		return (TeaCaller) req.getAttribute(TeaAuthenticationFilter.CALLER_ATTRIBUTE);
	}

	private static void assertObjectUnknown(ResponseEntity<?> r, String what) {
		assertEquals(404, r.getStatusCode().value(), what);
		assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, ((TeaErrorResponse) r.getBody()).getError(), what);
	}

	@SuppressWarnings("unchecked")
	private static String error(ResponseEntity<?> r) {
		return (String) ((Map<String, Object>) r.getBody()).get("error");
	}

	// TPF-142 row 21: the host token path, and the uuid token path on a custom host
	@Test
	public void theHostTokenPathServesTheHostsOrganizationOnly() throws Exception {
		Minted a = mint();
		Minted b = mint();
		// A LEGACY_UUID: its uuid token path is unknown on a custom host and served on the baseuri host
		teaHostAssignmentService.assign("tea-b.test", b.org().getUuid(), null);
		organizationService.setTeaServing(b.org().getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
		assertObjectUnknown(exchangeOn(a.teaOrg(), "tea-b.test", a.keyId(), a.secret(), null), "A's uuid token on B's host");
		token(exchangeOn(a.teaOrg(), "localhost", a.keyId(), a.secret(), null));

		String t = token(exchangeOn(null, "tea-b.test", b.keyId(), b.secret(), null));
		TeaCaller onB = filterOnHost(t);
		assertEquals(TeaAuthState.AUTHENTICATED, onB.state(), "the token works on B's host shape");
		assertEquals(b.org().getUuid(), onB.principal().org());

		teaHostAssignmentService.assign("tea-a.test", a.org().getUuid(), null);
		organizationService.setTeaServing(a.org().getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
		ResponseEntity<?> foreign = exchangeOn(null, "tea-a.test", b.keyId(), b.secret(), null);
		assertEquals(401, foreign.getStatusCode().value(), "B's key on A's host");
		assertEquals("invalid_client", error(foreign));
		assertObjectUnknown(exchangeOn(null, "tea-c.test", b.keyId(), b.secret(), null), "a listed, unassigned host");
		ResponseEntity<?> inQuery = exchangeOn(null, "tea-b.test", b.keyId(), b.secret(), "client_secret=" + b.secret());
		assertEquals(400, inQuery.getStatusCode().value(), "the body-only rule holds on the host path");
		assertEquals("invalid_request", error(inQuery));
	}

	// TPF-142 row 21: the uuid token path of a NONE and a DOMAIN organization, on the baseuri host
	@Test
	public void theUuidTokenPathServesLegacyUuidOrganizationsOnly() throws Exception {
		Minted dark = mint();
		organizationService.setTeaServing(dark.org().getUuid(), TeaServing.NONE, TeaWellKnownHosting.NONE, WU);
		assertObjectUnknown(exchangeOn(dark.teaOrg(), "localhost", dark.keyId(), dark.secret(), null), "NONE");
		Minted domain = mint();
		teaHostAssignmentService.assign("tea-a.test", domain.org().getUuid(), null);
		organizationService.setTeaServing(domain.org().getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE, WU);
		assertObjectUnknown(exchangeOn(domain.teaOrg(), "localhost", domain.keyId(), domain.secret(), null), "DOMAIN");
	}

	private record Minted(Organization org, ApiKey key, String keyId, String teaOrg, String secret) {}

	private Minted mint() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = externalApiKeyService.create(org.getUuid(), "Acme", null, WU);
		// TPF-142 (F-6, F-8): the key creation minted the TEA id; the uuid base serves LEGACY_UUID organizations only
		organizationService.setTeaServing(org.getUuid(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE, WU);
		String secret = apiKeyService.addApiKeySecret(ak.getUuid(), null, WU);
		ApiKey stored = apiKeyService.getApiKey(ak.getUuid()).orElseThrow();
		String tea = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid().toString();
		return new Minted(org, stored, ApiKeyService.keyIdOf(stored), tea, secret);
	}

	private ResponseEntity<?> exchange(String teaOrg, String keyId, String secret) {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tea/" + teaOrg + "/v1.0.0/token");
		req.setRemoteAddr("192.0.2.33");
		req.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		req.setParameter("grant_type", "client_credentials");
		return tokenController.token(req, teaOrg);
	}

	@SuppressWarnings("unchecked")
	private static String token(ResponseEntity<?> r) {
		assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
		return (String) ((Map<String, Object>) r.getBody()).get("access_token");
	}

	private TeaCaller filter(String token, String teaOrg) throws Exception {
		TeaAuthenticationFilter f = new TeaAuthenticationFilter(apiTokenService, apiKeyService);
		String uri = "/tea/" + teaOrg + "/v1.0.0/product/" + UUID.randomUUID();
		MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
		req.setRequestURI(uri);
		req.addHeader("Authorization", "Bearer " + token);
		f.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
		return (TeaCaller) req.getAttribute(TeaAuthenticationFilter.CALLER_ATTRIBUTE);
	}

	// 41
	@Test
	public void aGoodExchange() throws Exception {
		Minted m = mint();
		String t = token(exchange(m.teaOrg(), m.keyId(), m.secret()));
		assertTrue(apiTokenService.verify(t, ApiTokenService.AUD_TEA).isPresent());
		assertFalse(apiTokenService.verify(t, ApiTokenService.AUD_PROGRAMMATIC).isPresent());
		apiKeyAccessService.flushPendingLastAccessTouches();
		assertNotNull(apiKeyRepository.findByUUID(m.key().getUuid()).orElseThrow().getLastAccessDate(), "the key's access date is set");
	}

	// 42
	@Test
	public void wrongSecretInactiveKeyAndTheOtherSlot() throws Exception {
		Minted m = mint();
		assertEquals(401, exchange(m.teaOrg(), m.keyId(), m.secret() + "x").getStatusCode().value());
		apiKeyService.setApiKeyStatus(m.key().getUuid(), ApiKeyStatus.INACTIVE, true, WU);
		assertEquals(401, exchange(m.teaOrg(), m.keyId(), m.secret()).getStatusCode().value());
		apiKeyService.setApiKeyStatus(m.key().getUuid(), ApiKeyStatus.ACTIVE, true, WU);
		token(exchange(m.teaOrg(), m.keyId(), m.secret()));
		String second = apiKeyService.addApiKeySecret(m.key().getUuid(), null, WU);
		apiKeyService.setApiKeySecretActive(m.key().getUuid(), 1, false, WU);
		assertEquals(401, exchange(m.teaOrg(), m.keyId(), m.secret()).getStatusCode().value(), "slot 1 retired");
		token(exchange(m.teaOrg(), m.keyId(), second));
		apiKeyService.deleteApiKey(m.key().getUuid(), WU);
		assertEquals(401, exchange(m.teaOrg(), m.keyId(), second).getStatusCode().value(), "a deleted key");
	}

	// 43
	@Test
	public void regenerationKillsTheTokensOfTheOldSecret() throws Exception {
		Minted m = mint();
		String before = token(exchange(m.teaOrg(), m.keyId(), m.secret()));
		assertEquals(TeaAuthState.AUTHENTICATED, filter(before, m.teaOrg()).state());
		String regenerated = apiKeyService.regenerateApiKeySecret(m.key().getUuid(), 1, null, WU);
		TeaCaller stale = filter(before, m.teaOrg());
		assertEquals(TeaAuthState.INVALID_TOKEN, stale.state());
		String after = token(exchange(m.teaOrg(), m.keyId(), regenerated));
		TeaCaller fresh = filter(after, m.teaOrg());
		assertEquals(TeaAuthState.AUTHENTICATED, fresh.state());
		assertEquals(m.keyId(), fresh.principal().keyId());
		apiKeyService.setApiKeyStatus(m.key().getUuid(), ApiKeyStatus.INACTIVE, true, WU);
		assertEquals(TeaAuthState.INVALID_TOKEN, filter(after, m.teaOrg()).state(), "a disabled key's token dies at once");
	}

	// 44
	@Test
	public void theBasePathMustBeTheKeysOrganization() throws Exception {
		Minted m = mint();
		Minted other = mint();
		assertEquals(401, exchange(other.teaOrg(), m.keyId(), m.secret()).getStatusCode().value());
		ResponseEntity<?> unknown = exchange(UUID.randomUUID().toString(), m.keyId(), m.secret());
		assertEquals(404, unknown.getStatusCode().value());
		assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, ((TeaErrorResponse) unknown.getBody()).getError());
	}

	// 45
	@Test
	public void aPerspectiveGrantFollowsMembershipAndAPublicProfileServesAnyone() throws Exception {
		if (LicensingConstants.isOssEdition()) return; // perspectives are Pro
		Organization org = testInitializer.obtainOrganization();
		UserData admin = admin(org);
		String o = org.getUuid().toString();
		UUID perspective = UUID.fromString((String) ((Map<?, ?>) ok(exec(admin, "mutation($o: ID!, $n: String!) { createPerspective(org: $o, name: $n) { uuid } }",
				"o", o, "n", "tea3-" + UUID.randomUUID()), "createPerspective")).get("uuid"));
		Component c = component(org);
		String setPerspectives = "mutation($c: ID!, $p: [ID!]!) { setPerspectivesOnComponent(componentUuid: $c, perspectiveUuids: $p) { uuid } }";
		ok(exec(admin, setPerspectives, "c", c.getUuid().toString(), "p", List.of(perspective.toString())), "setPerspectivesOnComponent");
		Map<String, Object> k = createKey(admin, org, "Acme");
		ok(exec(admin, SET_GRANTS, "k", k.get("uuid"), "t", null, "p", List.of(grant("PERSPECTIVE", perspective, "READ_ONLY", List.of()))),
				"setPermissionsOnFreeformApiKey");
		TeaPrincipal p = new TeaPrincipal(UUID.fromString((String) k.get("uuid")), org.getUuid(), (String) k.get("keyId"), 1, "192.0.2.1");
		assertTrue(teaAuthorizationService.covers(p, org.getUuid(), c.getUuid()));
		ok(exec(admin, setPerspectives, "c", c.getUuid().toString(), "p", List.of()), "setPerspectivesOnComponent");
		assertFalse(teaAuthorizationService.covers(p, org.getUuid(), c.getUuid()), "the component left the perspective");

		ComponentData cd = getComponentService.getComponentData(c.getUuid()).orElseThrow();
		assertEquals(TeaAccess.CONCEAL, teaAuthorizationService.accessToComponent(TeaCaller.anonymous(), org.getUuid(), cd),
				"the built-in default publishes nothing");
		String save = "mutation($o: ID!, $s: TeaProfileScope!, $ob: ID, $p: TeaProfileInput!) {"
				+ " saveTeaProfile(org: $o, scope: $s, object: $ob, profile: $p) { uuid } }";
		Map<String, Object> profile = new java.util.LinkedHashMap<>();
		profile.put("publishing", "ENABLED");
		profile.put("visibility", "PUBLIC");
		profile.put("rawArtifacts", "NONE");
		profile.put("productComponents", "PUBLISH_WITH_PRODUCT");
		profile.put("minimumLifecycle", "ASSEMBLED");
		profile.put("tei", "DISABLED");
		profile.put("profiles", List.of("default"));
		ok(exec(admin, save, "o", o, "s", "ORGANIZATION", "ob", null, "p", profile), "saveTeaProfile");
		assertEquals(TeaAccess.SERVE, teaAuthorizationService.accessToComponent(TeaCaller.anonymous(), org.getUuid(), cd));
	}
}
