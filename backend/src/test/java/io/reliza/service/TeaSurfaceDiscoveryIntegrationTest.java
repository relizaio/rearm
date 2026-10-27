/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static io.reliza.service.tea.TeaOpenApiSchemas.assertWellKnownConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.common.Utils;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-6, design 4.8 cases 30 (the stamped TEI filter), 31 and 32: discovery by TEI and by
 * PURL within the organization of the base called, malformed input as 400, and the instance
 * /.well-known/tea document.
 */
public class TeaSurfaceDiscoveryIntegrationTest extends TeaSurfaceIntegrationTestBase {

	private static final String DOMAIN = "products.example.com";

	private HttpResponse<String> discover(TeaWorld w, String query, String bearer) throws Exception {
		return get(w.base() + "/discovery?" + query, bearer);
	}

	private static String q(String name, String value) {
		return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	// 31
	@Test
	@SuppressWarnings("unchecked")
	void discoveryResolvesPublishedProductReleasesOfTheBaseOrganization() throws Exception {
		TeaWorld w = world();
		saveOrgProfileTei(TeaVisibility.PUBLIC, TeaTei.UUID, DOMAIN);
		publish(w.prod().release(), TeaPublishMode.REPUBLISH);
		String stamped = teaPublicationService.getPublication(w.prod().head()).orElseThrow().getTei();
		assertEquals("tei://" + DOMAIN + "/uuid/" + w.prod().head(), stamped);

		HttpResponse<String> byTei = discover(w, q("tei", stamped), null);
		assertEquals(200, byTei.statusCode(), byTei.body());
		List<Map<String, Object>> infos = Utils.OM.readValue(byTei.body(), List.class);
		assertEquals(1, infos.size());
		assertEquals(w.prod().head().toString(), infos.get(0).get("productReleaseUuid"));
		Map<String, Object> server = ((List<Map<String, Object>>) infos.get(0).get("servers")).get(0);
		assertEquals(w.apiBase(), server.get("rootUrl"));
		assertFalse(((String) server.get("rootUrl")).endsWith("/"));
		assertEquals(List.of("1.0.0"), server.get("versions"));
		assertEquals(1.0, ((Number) server.get("priority")).doubleValue());
		JsonNode arr = Utils.OM.readTree(byTei.body());
		for (JsonNode n : arr) assertConforms(n.toString(), "discovery-info");

		// 30: the stamped TEI is a served identifier
		HttpResponse<String> filtered = get(w.base() + "/productReleases?" + q("idValue", stamped), null);
		assertEquals(List.of(w.prod().head().toString()), uuidsOf(filtered));

		assertEquals(200, discover(w, q("tei", "tei://Products.Example.com/uuid/" + w.prod().head()), null).statusCode(),
				"step 3 compares the DNS name case-insensitively");
		assertObjectUnknown(discover(w, q("tei", "tei://other.example.com/uuid/" + w.prod().head()), null), "other domain");
		assertObjectUnknown(discover(w, q("tei", "tei://" + DOMAIN + "/uuid/" + w.pub().head()), null), "a component release");
		HttpResponse<String> byPurl = discover(w, q("purl", w.prod().purl()), null);
		assertEquals(200, byPurl.statusCode(), byPurl.body());
		assertTrue(byPurl.body().contains(w.prod().head().toString()));
		String b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(w.prod().purl().getBytes(StandardCharsets.UTF_8));
		assertEquals(200, discover(w, q("tei", "tei://" + DOMAIN + "/purl/" + b64), null).statusCode(), "a purl TEI");
		assertObjectUnknown(discover(w, q("tei", "tei://other.example.com/purl/" + b64), null), "a purl TEI of another domain");

		// stamping off: the domain is not checked
		saveOrgProfileTei(TeaVisibility.PUBLIC, TeaTei.DISABLED, null);
		publish(w.prod().release(), TeaPublishMode.REPUBLISH);
		assertEquals(200, discover(w, q("tei", "tei://other.example.com/uuid/" + w.prod().head()), null).statusCode());

		for (String bad : List.of(q("tei", "urn:tei:uuid:x:y"), q("tei", "tei://a.b/uuid/not-a-uuid"), q("tei", "tei://a.b/purl/%%%"),
				"tei=&purl=", "", q("tei", stamped) + "&" + q("purl", w.prod().purl()), "tei=")) {
			HttpResponse<String> r = discover(w, bad, null);
			assertEquals(400, r.statusCode(), bad + ": " + r.body());
			assertEquals("INVALID_REQUEST", Utils.OM.readTree(r.body()).get("error").asString(), bad);
			assertConforms(r.body(), "error-response");
		}

		// a PRIVATE-only match
		saveOrgProfileTei(TeaVisibility.PRIVATE, TeaTei.DISABLED, null);
		HttpResponse<String> anon = discover(w, q("purl", w.prod().purl()), null);
		assertNoStore401(anon, false, "PRIVATE-only discovery");
		assertEquals(200, discover(w, q("purl", w.prod().purl()), w.cover()).statusCode());
		assertObjectUnknown(discover(w, q("purl", w.prod().purl()), w.other()), "non-covering key");
	}

	// 32
	@Test
	void theInstanceDocumentIsServedOnOssOnly() throws Exception {
		TeaWorld w = world();
		doReturn(InstallationType.OSS).when(userService).getInstallationType();
		for (String bearer : java.util.Arrays.asList(null, "garbage", w.cover())) {
			HttpResponse<String> r = get(root() + "/.well-known/tea", bearer);
			assertEquals(200, r.statusCode(), bearer + ": " + r.body());
			assertWellKnownConforms(r.body());
			assertTrue(r.body().contains("\"url\":\"" + w.apiBase() + "\""), r.body());
			assertTrue(r.body().contains("\"versions\":[\"1.0.0\"]"), r.body());
			assertEquals("public, max-age=300", r.headers().firstValue("Cache-Control").orElse(null));
			assertTrue(r.headers().firstValue("Set-Cookie").isEmpty(), "no session, no CSRF cookie on the TEA chain");
		}
		doReturn(InstallationType.SAAS).when(userService).getInstallationType();
		HttpResponse<String> saas = get(root() + "/.well-known/tea", null);
		assertEquals(404, saas.statusCode(), saas.body());
		assertEquals("NOT_IMPLEMENTED", Utils.OM.readTree(saas.body()).get("error").asString());
		assertEquals("no-store", saas.headers().firstValue("Cache-Control").orElse(null));
		assertConforms(saas.body(), "error-response");
		assertTrue(saas.headers().firstValue("Set-Cookie").isEmpty());
	}
}
