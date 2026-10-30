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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.common.oss.LicensingConstants;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.Organization;
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
@SpringBootTest(classes = {App.class})
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

	private record Minted(Organization org, ApiKey key, String keyId, String teaOrg, String secret) {}

	private Minted mint() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = externalApiKeyService.create(org.getUuid(), "Acme", null, WU);
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
