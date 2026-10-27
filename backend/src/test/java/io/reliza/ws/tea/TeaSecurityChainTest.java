/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import io.reliza.common.OAuthQueryGuard;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Link;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.ExternalApiKeyService;
import io.reliza.service.GetOrganizationService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task TEA-3, design 4.8 cases 46 to 48: the TEA security chain over real HTTP -- the only tests that prove the
 * chain itself: no CSRF and no session on the token endpoint, the resource server never sees a TEA token on
 * /tea/**, the boundary on the other surfaces, the removed 0.4.0 surface (task TEA-6), and (round 2, case 64) the body-only
 * token request.
 */
@SpringBootTest(classes = {App.class}, webEnvironment = WebEnvironment.RANDOM_PORT)
public class TeaSecurityChainTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Value("${local.server.port}") private int port;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ExternalApiKeyService externalApiKeyService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private ArtifactService artifactService;
	@Autowired private io.reliza.service.LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@org.junit.jupiter.api.BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@org.junit.jupiter.api.AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private final HttpClient http = HttpClient.newHttpClient();

	private String base() {
		return "http://localhost:" + port;
	}

	private record Minted(Organization org, String keyId, String secret, String teaOrg) {}

	private Minted mint() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ak = externalApiKeyService.create(org.getUuid(), "Acme", null, WU);
		String secret = apiKeyService.addApiKeySecret(ak.getUuid(), null, WU);
		String tea = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getTeaUuid().toString();
		return new Minted(org, ApiKeyService.keyIdOf(apiKeyService.getApiKey(ak.getUuid()).orElseThrow()), secret, tea);
	}

	private static String basic(String id, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> tokenRequest(Minted m) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(base() + "/tea/" + m.teaOrg() + "/v1.0.0/token"))
				.header("Authorization", basic(m.keyId(), m.secret()))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(), HttpResponse.BodyHandlers.ofString());
	}

	@SuppressWarnings("unchecked")
	private String token(Minted m) throws Exception {
		HttpResponse<String> r = tokenRequest(m);
		assertEquals(200, r.statusCode(), r.body());
		return (String) Utils.OM.readValue(r.body(), Map.class).get("access_token");
	}

	// 46
	@Test
	public void theTokenEndpointNeedsNoCsrfAndCreatesNoSession() throws Exception {
		Minted m = mint();
		HttpResponse<String> r = tokenRequest(m);
		assertEquals(200, r.statusCode(), r.body());
		assertTrue(r.headers().firstValue("Set-Cookie").isEmpty(), "no session, no CSRF cookie");
		assertTrue(r.headers().firstValue("X-Rate-Limit-Remaining").isPresent(), "the limiter runs on the TEA chain");
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
		HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base() + "/tea/" + m.teaOrg() + "/v1.0.0/token"))
				.header("Authorization", basic(m.keyId(), m.secret() + "x"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(401, bad.statusCode());
		assertEquals("Basic realm=\"tea\"", bad.headers().firstValue("WWW-Authenticate").orElse(null));
	}

	// 47
	@Test
	public void aTeaTokenReachesTeaAndNothingElse() throws Exception {
		Minted m = mint();
		String t = token(m);
		HttpResponse<String> tea = http.send(HttpRequest.newBuilder(URI.create(base() + "/tea/" + m.teaOrg() + "/v1.0.0/product/" + UUID.randomUUID()))
				.header("Authorization", "Bearer " + t).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertNotEquals(401, tea.statusCode(), "no resource server on the TEA chain");
		assertEquals(404, tea.statusCode(), "an unknown product of the base (task TEA-6)");
		assertEquals("{\"error\":\"OBJECT_UNKNOWN\"}", tea.body());

		HttpResponse<String> prog = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/programmatic/graphql"))
				.header("Authorization", "Bearer " + t).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(401, prog.statusCode());
		assertTrue(prog.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_token"));

		HttpResponse<String> graphql = http.send(HttpRequest.newBuilder(URI.create(base() + "/graphql"))
				.header("Authorization", "Bearer " + t).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ organizations { uuid } }\"}")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(401, graphql.statusCode(), "the resource server refuses it on /graphql");

		Link link = new Link();
		link.setUri("https://example.com/tea3-sbom.json");
		link.setContent(Link.ContentEnum.PLAIN_JSON);
		ArtifactDto dto = ArtifactDto.builder().org(m.org().getUuid()).type(ArtifactType.BOM).bomFormat(BomFormat.CYCLONEDX)
				.displayIdentifier("tea3-sbom").storedIn(StoredIn.EXTERNALLY).downloadLinks(List.of(link)).build();
		UUID artifact = artifactService.createArtifact(dto, WU).getUuid();
		HttpResponse<String> download = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/programmatic/v1/artifact/" + artifact + "/download"))
				.header("Authorization", basic(m.keyId(), m.secret())).GET().build(), HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> unknownKey = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/programmatic/v1/artifact/" + artifact + "/download"))
				.header("Authorization", basic("FREEFORM__" + m.org().getUuid() + "__ord__" + UUID.randomUUID(), m.secret())).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(unknownKey.statusCode(), download.statusCode(), "an external key is refused as an unknown key is");
		// The design expected 403; an unknown key gets 401 there today (the refusal is thrown and rendered through
		// /error on the browser chain), and the external key gets exactly what an unknown key gets.
		assertEquals(401, download.statusCode(), download.body());
	}

	private HttpResponse<String> postToken(Minted m, String query, String body) throws Exception {
		String q = query == null ? "" : "?" + query;
		return http.send(HttpRequest.newBuilder(URI.create(base() + "/tea/" + m.teaOrg() + "/v1.0.0/token" + q))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String form(String... pairs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pairs.length; i += 2) {
			if (sb.length() > 0) sb.append('&');
			sb.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	// 64 (design round 2, T-1): only the running server merges the query string into the request parameters
	@Test
	@SuppressWarnings("unchecked")
	public void tokenRequestParametersInTheQueryStringAreInvalidRequest() throws Exception {
		Minted m = mint();
		HttpResponse<String> allInQuery = postToken(m, form("grant_type", "client_credentials", "client_id", m.keyId(), "client_secret", m.secret()), "");
		assertEquals(400, allInQuery.statusCode(), allInQuery.body());
		Map<String, Object> b = Utils.OM.readValue(allInQuery.body(), Map.class);
		assertEquals("invalid_request", b.get("error"));
		assertFalse(b.containsKey("access_token"));
		// TEA-24 design 4.2 row 1: the guard's text, the names in the order of the form helper; the secret is not echoed
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: grant_type, client_id, client_secret", b.get("error_description"));
		assertFalse(allInQuery.body().contains(m.secret()), allInQuery.body());
		HttpResponse<String> secretInQuery = postToken(m, form("client_secret", m.secret()),
				form("grant_type", "client_credentials", "client_id", m.keyId()));
		assertEquals(400, secretInQuery.statusCode(), secretInQuery.body());
		assertFalse(secretInQuery.body().contains("access_token"));
		HttpResponse<String> allInBody = postToken(m, null,
				form("grant_type", "client_credentials", "client_id", m.keyId(), "client_secret", m.secret()));
		assertEquals(200, allInBody.statusCode(), allInBody.body());
		assertNotNull(Utils.OM.readValue(allInBody.body(), Map.class).get("access_token"));
	}

	// TEA-24 design 4.2 row 2: a repeated parameter, parsed from the form body by the running container
	@Test
	@SuppressWarnings("unchecked")
	public void aRepeatedTokenParameterIsInvalidRequestThroughTheContainer() throws Exception {
		Minted m = mint();
		HttpResponse<String> r = postToken(m, null,
				form("grant_type", "client_credentials", "client_id", m.keyId(), "client_id", m.keyId(), "client_secret", m.secret()));
		assertEquals(400, r.statusCode(), r.body());
		Map<String, Object> b = Utils.OM.readValue(r.body(), Map.class);
		assertEquals("invalid_request", b.get("error"));
		assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: client_id", b.get("error_description"));
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), String.valueOf(r.headers().map()));
		assertFalse(b.containsKey("access_token"));
		assertFalse(r.body().contains(m.secret()), r.body());
	}

	// TEA-24 design 4.2 row 3: decorated names are read by nobody, so Spring's [] binder fallback cannot serve them
	@Test
	@SuppressWarnings("unchecked")
	public void decoratedTokenParameterNamesAreUnreadThroughTheContainer() throws Exception {
		Minted m = mint();
		HttpResponse<String> r = postToken(m, null, form("grant_type", "client_credentials", "client_id[]", m.keyId(), "client_secret[]", m.secret())
				.replace("client_id[]", "client_id%5B%5D").replace("client_secret[]", "client_secret%5B%5D"));
		assertEquals(401, r.statusCode(), r.body());
		Map<String, Object> b = Utils.OM.readValue(r.body(), Map.class);
		assertEquals("invalid_client", b.get("error"));
		assertEquals("Basic realm=\"tea\"", r.headers().firstValue("WWW-Authenticate").orElse(null));
		assertFalse(b.containsKey("access_token"));
	}

	// 48, amended by task TEA-6: the 0.4.0 surface and its beta flag are gone; nothing is mounted there
	@Test
	public void theZeroFourSurfaceIsNotMounted() throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/tea/v0.4.0/product/" + UUID.randomUUID())).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(404, r.statusCode());
		assertTrue(r.headers().firstValue("WWW-Authenticate").isEmpty(), "no challenge for a path that is not mounted");
		// task TEA-6 round 2 (decision 16): the answer of every unmounted TEA path, not a TEA error-response
		assertEquals("{\"message\":\"Not Found\"}", r.body());
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
	}
}
