/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.ws.ProgrammaticTokenController;
import io.reliza.ws.RelizaConfigProps;

/**
 * TPF-142, architecture section 4 rows 2 to 6 and 17, and the publicUrl part of row 14: host-based organization
 * routing beside the uuid base, the host spellings, an unknown host, the organization boundary on a custom host,
 * the NONE default, and the custom-host gate through the container.
 */
public class TeaSurfaceHostRoutingIntegrationTest extends TeaHostRoutingIntegrationTestBase {

	private static final Logger log = LoggerFactory.getLogger(TeaSurfaceHostRoutingIntegrationTest.class);
	private static final String GATE_BODY = "{\"message\":\"Not Found\"}";

	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private RelizaConfigProps relizaConfigProps;

	private static String q(String name, String value) {
		return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private HttpResponse<String> onHost(String path, String host, String bearer) throws Exception {
		return get(root() + path, bearer, null == host ? null : on(host));
	}

	// row 2
	@Test
	@SuppressWarnings("unchecked")
	void bothShapesServeTheirOrganization() throws Exception {
		HostOrg a = hostOrg();
		serve(a.org(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		TeaWorld w = world();
		assign(B_HOST, org.getUuid());
		serve(org.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);

		HttpResponse<String> aList = get(a.uuidBase(root()) + "/components", null);
		assertEquals(200, aList.statusCode(), aList.body());
		assertEquals(List.of(a.pub().teaComponent().toString()), uuidsOf(aList), "A on the uuid base, the default host");

		HttpResponse<String> products = onHost(HOST_BASE + "/products", B_HOST, w.cover());
		assertEquals(200, products.statusCode(), products.body());
		assertConforms(products.body(), "paginated-product-response");
		assertEquals(List.of(w.prod().teaComponent().toString()), uuidsOf(products), "B's products only");
		HttpResponse<String> comps = onHost(HOST_BASE + "/components", B_HOST, null);
		assertEquals(List.of(w.pub().teaComponent().toString()), uuidsOf(comps), "B's PUBLIC component only, nothing of A");
		assertEquals(200, onHost(HOST_BASE + "/product/" + w.prod().teaComponent(), B_HOST, w.cover()).statusCode());
		HttpResponse<String> rel = onHost(HOST_BASE + "/componentRelease/" + w.pub().head(), B_HOST, null);
		assertEquals(200, rel.statusCode(), rel.body());

		HttpResponse<String> disc = onHost(HOST_BASE + "/discovery?" + q("purl", w.prod().purl()), B_HOST, w.cover());
		assertEquals(200, disc.statusCode(), disc.body());
		List<Map<String, Object>> infos = Utils.OM.readValue(disc.body(), List.class);
		Map<String, Object> server = ((List<Map<String, Object>>) infos.get(0).get("servers")).get(0);
		assertEquals("https://tea-b.test/tea", server.get("rootUrl"), "the discovery names the domain the client is on");

		// row 14, the publicUrl of a publication by mode
		TeaPublicationData head = teaPublicationService.getPublication(w.pub().head()).orElseThrow();
		assertEquals("https://tea-b.test/tea/v1.0.0/componentRelease/" + w.pub().head(),
				TeaPublicationService.publicUrl(teaPublicationService.apiBaseOf(org.getUuid()), head));
		TeaPublicationData aHead = teaPublicationService.getPublication(a.pub().head()).orElseThrow();
		assertEquals(a.uuidBase(trimmedBaseuri()) + "/componentRelease/" + a.pub().head(),
				TeaPublicationService.publicUrl(teaPublicationService.apiBaseOf(a.org()), aHead));
		serve(a.org(), TeaServing.NONE, TeaWellKnownHosting.NONE);
		assertNull(TeaPublicationService.publicUrl(teaPublicationService.apiBaseOf(a.org()), aHead), "NONE advertises no URL");
	}

	private String trimmedBaseuri() {
		return StringUtils.stripEnd(relizaConfigProps.getBaseuri(), "/");
	}

	// row 2: an artifact response's Content-Location names the domain
	@Test
	void anArtifactResponseNamesTheDomainInItsContentLocation() throws Exception {
		ArtWorld w = artifactWorld();
		assign(B_HOST, org.getUuid());
		serve(org.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);
		Art agg = w.pub().agg();
		String url = root() + HOST_BASE + "/artifact/" + agg.tea() + "/" + agg.revision() + "/download?mediaType=" + enc(agg.mediaType());
		HttpResponse<String> r = get(url, null, on(B_HOST));
		assertEquals(200, r.statusCode(), r.body());
		String location = header(r, "Content-Location");
		assertTrue(null != location && location.startsWith("https://tea-b.test/tea/v1.0.0/"), location);
	}

	// row 3
	@Test
	void theHostSpellingsServeTheSameOrganization() throws Exception {
		HostOrg b = hostOrg();
		assign(B_HOST, b.org());
		serve(b.org(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);
		List<String> expected = List.of(b.pub().teaComponent().toString());
		for (String host : List.of("TEA-B.test", "tea-b.test:443", "tea-b.test.")) {
			HttpResponse<String> r = onHost(HOST_BASE + "/components", host, null);
			assertEquals(200, r.statusCode(), host + ": " + r.body());
			assertEquals(expected, uuidsOf(r), host);
		}
		for (String host : List.of("TEA-B.test", "tea-b.test:443", "tea-b.test.", "tea-b.test:8443")) {
			Raw r = raw("GET", HOST_BASE + "/components", Map.of("Host", host), null);
			assertEquals(200, r.status(), host + ": " + r.body());
			assertTrue(r.body().contains(b.pub().teaComponent().toString()), host + ": " + r.body());
		}
		assertObjectUnknown(onHost(HOST_BASE + "/components", "tea-b.test, tea-a.test", null), "a comma list names no host");
	}

	// row 4
	@Test
	void anUnknownOrUnlistedHostBehavesAsToday() throws Exception {
		HostOrg a = hostOrg();
		serve(a.org(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		HttpResponse<String> uuidShape = get(a.uuidBase(root()) + "/components", null, on(X_HOST));
		assertEquals(200, uuidShape.statusCode(), uuidShape.body());
		assertEquals(List.of(a.pub().teaComponent().toString()), uuidsOf(uuidShape), "the uuid shape ignores an unlisted host");
		assertObjectUnknown(onHost(HOST_BASE + "/products", X_HOST, null), "the host shape on an unlisted host");
		for (String path : List.of("/graphql", "/")) {
			int today = get(root() + path, null).statusCode();
			HttpResponse<String> unlisted = onHost(path, X_HOST, null);
			assertEquals(today, unlisted.statusCode(), path + ": the gate is inert on an unlisted host");
			assertFalse(404 == unlisted.statusCode() && GATE_BODY.equals(unlisted.body()), path);
		}
	}

	// row 5
	@Test
	void aCustomHostReachesItsOwnOrganizationOnly() throws Exception {
		HostOrg a = hostOrg();
		HostOrg b = hostOrg();
		assign(A_HOST, a.org());
		serve(a.org(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);
		serve(b.org(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		String bToken = accessToken(tokenRequest("/tea/" + b.teaUuid() + "/v1.0.0/token", null, b.keyId(), b.secret()));

		assertObjectUnknown(onHost(HOST_BASE + "/component/" + b.pub().teaComponent(), A_HOST, null), "B's PUBLIC object on A's host");
		assertEquals(List.of(a.pub().teaComponent().toString()), uuidsOf(onHost(HOST_BASE + "/components", A_HOST, null)));
		assertEquals(200, onHost(HOST_BASE + "/component/" + a.pub().teaComponent(), A_HOST, bToken).statusCode());
		assertNoStore401(onHost(HOST_BASE + "/component/" + a.priv().teaComponent(), A_HOST, bToken), false,
				"B's token on A's host is the anonymous caller");
		assertObjectUnknown(onHost(HOST_BASE + "/components", X_HOST, null), "an unlisted host on the host shape");

		// the mirror (r3, F-7): B LEGACY_UUID, A DOMAIN on tea-a.test: B's uuid base is unknown on A's host
		for (String path : List.of("/componentReleases", "/products", "/components")) {
			assertObjectUnknown(get(b.uuidBase(root()) + path, null, on(A_HOST)), "B's uuid base on A's host " + path);
			assertEquals(200, get(b.uuidBase(root()) + path, null).statusCode(), "B's uuid base on the baseuri host " + path);
		}
		assertTokenObjectUnknown(tokenRequest("/tea/" + b.teaUuid() + "/v1.0.0/token", A_HOST, b.keyId(), b.secret()), "B's uuid token on A's host");
		assertEquals(200, tokenRequest("/tea/" + b.teaUuid() + "/v1.0.0/token", null, b.keyId(), b.secret()).statusCode());

		// A LEGACY_UUID, B DOMAIN on tea-b.test: A's uuid base is unknown on B's host and on a listed, unassigned host
		serve(a.org(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		assign(B_HOST, b.org());
		serve(b.org(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);
		for (String path : List.of("/componentReleases", "/products")) {
			assertObjectUnknown(get(a.uuidBase(root()) + path, null, on(B_HOST)), "A's uuid base on B's host " + path);
			assertObjectUnknown(get(a.uuidBase(root()) + path, null, on(C_HOST)), "A's uuid base on a listed, unassigned host " + path);
			assertEquals(200, get(a.uuidBase(root()) + path, null).statusCode(), "A's uuid base on the baseuri host " + path);
		}
		assertTokenObjectUnknown(tokenRequest("/tea/" + a.teaUuid() + "/v1.0.0/token", B_HOST, a.keyId(), a.secret()), "A's uuid token on B's host");
		assertTokenObjectUnknown(tokenRequest("/tea/" + a.teaUuid() + "/v1.0.0/token", C_HOST, a.keyId(), a.secret()), "A's uuid token on tea-c.test");
		assertEquals(200, tokenRequest("/tea/" + a.teaUuid() + "/v1.0.0/token", null, a.keyId(), a.secret()).statusCode());
	}

	private static void assertTokenObjectUnknown(HttpResponse<String> r, String what) {
		assertEquals(404, r.statusCode(), what + ": " + r.body());
		assertEquals("OBJECT_UNKNOWN", json(r).get("error"), what);
		assertFalse(json(r).containsKey("access_token"), what);
	}

	// row 6
	@Test
	void aNoneOrganizationIsServedOnNeitherShape() throws Exception {
		Organization c = testInitializer.obtainOrganization();
		String tea = organizationService.ensureTeaUuid(c.getUuid(), WU).toString();
		ApiKey key = externalApiKeyService.create(c.getUuid(), "tpf142-c-" + tag(), null, WU);
		String secret = apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		String keyId = ApiKeyService.keyIdOf(apiKeyService.getApiKey(key.getUuid()).orElseThrow());
		assign(C_HOST, c.getUuid());
		assertFalse(jdbcTemplate.queryForObject("SELECT jsonb_exists(record_data, 'teaServing') FROM rearm.organizations WHERE uuid = ?", Boolean.class,
				c.getUuid()), "no teaServing key on the stored record");
		assertNull(OrganizationData.orgDataFromDbRecord(organizationRepository.findById(c.getUuid()).orElseThrow()).getTeaServing());
		String uuidBase = root() + "/tea/" + tea + "/v1.0.0";
		String uuidToken = "/tea/" + tea + "/v1.0.0/token";
		String hostToken = HOST_BASE + "/token";

		assertObjectUnknown(get(uuidBase + "/products", null), "NONE on the uuid base");
		assertObjectUnknown(onHost(HOST_BASE + "/products", C_HOST, null), "NONE on its host");
		assertTokenObjectUnknown(tokenRequest(uuidToken, null, keyId, secret), "NONE uuid token");
		assertTokenObjectUnknown(tokenRequest(hostToken, C_HOST, keyId, secret), "NONE host token");

		serve(c.getUuid(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		assertEquals(200, get(uuidBase + "/products", null).statusCode());
		assertObjectUnknown(onHost(HOST_BASE + "/products", C_HOST, null), "LEGACY_UUID on its host");
		assertEquals(200, tokenRequest(uuidToken, null, keyId, secret).statusCode());
		assertTokenObjectUnknown(tokenRequest(hostToken, C_HOST, keyId, secret), "LEGACY_UUID host token");

		serve(c.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.NONE);
		assertObjectUnknown(get(uuidBase + "/products", null), "DOMAIN on the uuid base");
		assertEquals(200, onHost(HOST_BASE + "/products", C_HOST, null).statusCode());
		assertTokenObjectUnknown(tokenRequest(uuidToken, null, keyId, secret), "DOMAIN uuid token");
		String hostTokenValue = accessToken(tokenRequest(hostToken, C_HOST, keyId, secret));
		assertEquals(200, onHost(HOST_BASE + "/products", C_HOST, hostTokenValue).statusCode(), "the host token works on the host shape");
	}


	/** A FREEFORM key's programmatic bearer, exchanged at the programmatic token endpoint on the default host. */
	private String programmaticBearer(UUID orgUuid) throws Exception {
		ApiKey row = apiKeyService.createObjectApiKey(orgUuid, ApiTypeEnum.FREEFORM, orgUuid, UUID.randomUUID().toString(), "tpf142", WU);
		apiKeyService.setPermissionsOnApiKey(row.getUuid(), null, List.of(new PermissionDto(orgUuid, PermissionScope.ORGANIZATION, orgUuid,
				PermissionType.READ_ONLY, Set.of(PermissionFunction.RESOURCE), null)), WU);
		String secret = apiKeyService.addApiKeySecret(row.getUuid(), null, WU);
		String keyId = ApiKeyService.keyIdOf(apiKeyService.getApiKey(row.getUuid()).orElseThrow());
		String basic = "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(root() + ProgrammaticTokenController.PATH))
				.header("Authorization", basic).header("Content-Type", "application/x-www-form-urlencoded").header("X-Forwarded-For", randomIp())
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		return (String) json(r).get("access_token");
	}

	private HttpResponse<String> send(String method, String path, String host, String authorization, String body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(root() + path)).header("X-Forwarded-For", randomIp())
				.method(method, null == body ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (null != body) b.header("Content-Type", "application/json");
		if (null != host) b.header("X-Forwarded-Host", host);
		if (null != authorization) b.header("Authorization", authorization);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static void assertGate(int status, String body, Map<String, List<String>> headers, String what) {
		assertEquals(404, status, what + ": " + body);
		assertEquals(GATE_BODY, body, what);
		assertEquals(List.of("no-store"), headers.getOrDefault("cache-control", headers.get("Cache-Control")), what);
		assertNull(headers.get("set-cookie"), what + ": no cookie");
		assertNull(headers.get("www-authenticate"), what + ": no challenge");
	}

	// row 17
	@Test
	void aCustomHostRefusesEveryNonTeaPathThroughTheContainer() throws Exception {
		HostOrg a = hostOrg();
		assign(A_HOST, a.org());
		HostOrg b = hostOrg();
		serve(b.org(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE);
		String jwt = "tpf142-jwt-" + tag();
		when(jwtDecoder.decode(jwt)).thenReturn(Jwt.withTokenValue(jwt).header("alg", "RS256").subject("tpf142-user")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
		String programmatic = programmaticBearer(a.org());
		String query = "{\"query\":\"{ organizations { uuid } }\"}";
		record Call(String method, String path, String authorization, String body) {}
		List<Call> calls = List.of(new Call("GET", "/", null, null), new Call("GET", "/graphql", null, null),
				new Call("POST", "/graphql", "Bearer " + jwt, query),
				new Call("POST", "/api/programmatic/graphql", "Bearer " + programmatic, query),
				new Call("POST", "/api/programmatic/token", null, null), new Call("GET", "/v1/x", null, null),
				new Call("GET", "/.well-known/tea/x", null, null));
		Map<String, Integer> today = new LinkedHashMap<>();
		for (Call c : calls) {
			HttpResponse<String> gated = send(c.method(), c.path(), A_HOST, c.authorization(), c.body());
			assertGate(gated.statusCode(), gated.body(), lower(gated.headers().map()), c.method() + " " + c.path() + " on tea-a.test");
			HttpResponse<String> plain = send(c.method(), c.path(), null, c.authorization(), c.body());
			assertFalse(404 == plain.statusCode() && GATE_BODY.equals(plain.body()), c.method() + " " + c.path() + " without the host");
			today.put(c.method() + " " + c.path(), plain.statusCode());
		}
		// the baseuri host assigned to an organization does not trip the gate
		Organization other = testInitializer.obtainOrganization();
		assign("localhost", other.getUuid());
		HttpResponse<String> onBaseuri = send("GET", "/graphql", "localhost", null, null);
		assertEquals(today.get("GET /graphql"), onBaseuri.statusCode(), "the baseuri host is never gated");
		// the uuid base on a custom host: the resolution refuses it, with the TEA body, not the gate's
		assertObjectUnknown(get(b.uuidBase(root()) + "/products", null, on(A_HOST)), "B's uuid base on tea-a.test");

		// r5, F-11: dot-segment spellings, sent raw: the gate's 404 on the custom host, the firewall's 400 elsewhere
		List<Call> dotted = List.of(new Call("GET", "/tea/../graphql", null, null),
				new Call("POST", "/tea/%2e%2e/graphql", "Bearer " + jwt, query), new Call("GET", "/tea/..;/api/programmatic/token", null, null));
		for (Call c : dotted) {
			Map<String, String> h = new LinkedHashMap<>();
			if (null != c.authorization()) h.put("Authorization", c.authorization());
			if (null != c.body()) h.put("Content-Type", "application/json");
			Map<String, String> custom = new LinkedHashMap<>(h);
			custom.put("X-Forwarded-Host", A_HOST);
			Raw gated = raw(c.method(), c.path(), custom, c.body());
			assertEquals(404, gated.status(), c.path() + ": " + gated.body());
			assertEquals(GATE_BODY, gated.body(), c.path());
			assertEquals("no-store", gated.header("Cache-Control"), c.path());
			Raw firewall = raw(c.method(), c.path(), h, c.body());
			assertEquals(400, firewall.status(), c.path() + " without the host: " + firewall.body());
			assertEquals("{\"message\":\"Bad Request\"}", firewall.body(), c.path());
			assertEquals("no-store", firewall.header("Cache-Control"), c.path());
			assertFalse(firewall.body().contains("\"data\"") || firewall.body().contains("access_token"), c.path());
		}
		log.info("TPF-142 row 17, statuses without the custom host: {}", today);
	}

	private static Map<String, List<String>> lower(Map<String, List<String>> headers) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		headers.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
		return out;
	}
}
