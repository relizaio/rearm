/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.reliza.common.Utils;
import io.reliza.model.BrandingPreset;
import io.reliza.model.BrandingPreset.BrandingAssetKind;
import io.reliza.service.BrandingService.ResolvedBranding;
import io.reliza.service.BrandingTestFixtures;

/**
 * The public branding REST contract (WL-2 design 4.4, tests 13-15), the controller called
 * directly over a fixed branding. The JSON keys are what the UI builds against.
 */
class BrandingControllerTest {

	private static final List<String> SETTINGS_KEYS = List.of("preset", "isDefault", "titleText",
			"organizationText", "navProductName", "supportName", "supportEmail", "documentationUrl",
			"termsOfServiceUrl", "privacyPolicyUrl", "legalUrl", "marketingConsent", "marketingConsentText",
			"logoUrl", "faviconUrl", "signUpBackgroundUrl");

	/**
	 * The default branding's settings body as the base tip of WL-6 serialized it: WL-6 must leave
	 * the default output byte for byte as it was.
	 */
	private static final String DEFAULT_SETTINGS_JSON = "{\"preset\":\"default\",\"isDefault\":true,"
			+ "\"titleText\":\"ReARM\",\"organizationText\":\"Reliza Incorporated\",\"navProductName\":\"ReARM\","
			+ "\"supportName\":\"Reliza Support\",\"supportEmail\":\"info@reliza.io\","
			+ "\"documentationUrl\":\"https://docs.rearmhq.com\",\"termsOfServiceUrl\":\"https://rearmhq.com/tos.html\","
			+ "\"privacyPolicyUrl\":\"https://rearmhq.com/privacy.html\",\"legalUrl\":\"https://rearmhq.com/tos.html\","
			+ "\"marketingConsent\":\"SHOWN\","
			+ "\"marketingConsentText\":\"Agree to receive news and promotions from Reliza by email (Optional).\","
			+ "\"logoUrl\":\"/logo_svg_no_tag_3.svg\",\"faviconUrl\":\"/favicon.ico\","
			+ "\"signUpBackgroundUrl\":\"/reliza_in_sand_right_corner.jpg\"}";

	private static BrandingController controllerOver(ResolvedBranding branding) {
		return new BrandingController(BrandingTestFixtures.serviceOver(branding));
	}

	// 13.

	@Test
	void settingsOnDefaultAreTheDefaultValues() {
		ResponseEntity<Map<String, Object>> r = controllerOver(BrandingTestFixtures.DEFAULT).settings();
		assertEquals(HttpStatus.OK, r.getStatusCode());
		assertEquals("no-cache", r.getHeaders().getCacheControl());
		assertEquals(MediaType.APPLICATION_JSON, r.getHeaders().getContentType());
		Map<String, Object> body = r.getBody();
		assertEquals(SETTINGS_KEYS, List.copyOf(body.keySet()));
		BrandingPreset d = BrandingPreset.DEFAULT;
		assertEquals("default", body.get("preset"));
		assertEquals(true, body.get("isDefault"));
		assertEquals(d.titleText(), body.get("titleText"));
		assertEquals(d.organizationText(), body.get("organizationText"));
		assertEquals(d.navProductName(), body.get("navProductName"));
		assertEquals(d.supportName(), body.get("supportName"));
		assertEquals(d.supportEmail(), body.get("supportEmail"));
		assertEquals(d.documentationUrl(), body.get("documentationUrl"));
		assertEquals(d.termsOfServiceUrl(), body.get("termsOfServiceUrl"));
		assertEquals(d.privacyPolicyUrl(), body.get("privacyPolicyUrl"));
		assertEquals(d.legalUrl(), body.get("legalUrl"));
		assertEquals("SHOWN", body.get("marketingConsent"));
		assertEquals(d.marketingConsentText(), body.get("marketingConsentText"));
		assertEquals("/logo_svg_no_tag_3.svg", body.get("logoUrl"));
		assertEquals("/favicon.ico", body.get("faviconUrl"));
		assertEquals("/reliza_in_sand_right_corner.jpg", body.get("signUpBackgroundUrl"));
		assertEquals(DEFAULT_SETTINGS_JSON, Utils.OM.writeValueAsString(body));
	}

	// 14.

	@Test
	void settingsOnExampleAreItsValuesWithTheRestFallingBack() {
		Map<String, Object> body = controllerOver(BrandingTestFixtures.EXAMPLE).settings().getBody();
		assertEquals(SETTINGS_KEYS, List.copyOf(body.keySet()));
		assertEquals("example", body.get("preset"));
		assertEquals(false, body.get("isDefault"));
		assertEquals("Example Release Hub", body.get("titleText"));
		assertEquals("Example Organization", body.get("organizationText"));
		assertEquals("Example Hub", body.get("navProductName"));
		assertEquals("Example Support", body.get("supportName"));
		assertEquals("support@example.com", body.get("supportEmail"));
		assertEquals("https://docs.example.com", body.get("documentationUrl"));
		assertEquals("https://example.com/terms", body.get("termsOfServiceUrl"));
		assertEquals("https://example.com/legal", body.get("legalUrl"));
		assertEquals("HIDDEN", body.get("marketingConsent"));
		// not set by example: the default's
		assertEquals(BrandingPreset.DEFAULT.privacyPolicyUrl(), body.get("privacyPolicyUrl"));
		assertEquals(BrandingPreset.DEFAULT.marketingConsentText(), body.get("marketingConsentText"));
		// the sign-up background is not inherited from default: the key stays, the value is null
		assertTrue(body.containsKey("signUpBackgroundUrl"));
		assertNull(body.get("signUpBackgroundUrl"));
		assertTrue(Utils.OM.writeValueAsString(body).contains("\"signUpBackgroundUrl\":null"));
		assertTrue(((String) body.get("logoUrl")).matches("^/api/branding/v1/asset/logo\\?v=[0-9a-f]{12}$"),
				(String) body.get("logoUrl"));
		assertTrue(((String) body.get("faviconUrl")).matches("^/api/branding/v1/asset/favicon\\?v=[0-9a-f]{12}$"),
				(String) body.get("faviconUrl"));
	}

	@Test
	void settingsOnAPresetWithABackgroundCarryItsUrl() {
		BrandingController full = controllerOver(BrandingTestFixtures.FULL);
		Map<String, Object> body = full.settings().getBody();
		assertEquals(SETTINGS_KEYS, List.copyOf(body.keySet()));
		assertEquals("full", body.get("preset"));
		String hash = BrandingTestFixtures.FULL.asset(BrandingAssetKind.SIGN_UP_BACKGROUND).orElseThrow().hash();
		assertEquals("/api/branding/v1/asset/signUpBackground?v=" + hash, body.get("signUpBackgroundUrl"));

		ResponseEntity<byte[]> r = full.asset("signUpBackground");
		assertEquals(HttpStatus.OK, r.getStatusCode());
		assertEquals(MediaType.parseMediaType("image/webp"), r.getHeaders().getContentType());
		assertEquals("public, max-age=86400", r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
		assertArrayEquals(BrandingTestFixtures.FULL_BACKGROUND_WEBP, r.getBody());
	}

	// 15.

	@Test
	void svgLogoIsServedWithItsContentTypeAndALockedDownPolicy() {
		ResponseEntity<byte[]> r = controllerOver(BrandingTestFixtures.EXAMPLE).asset("logo");
		assertEquals(HttpStatus.OK, r.getStatusCode());
		assertEquals(MediaType.parseMediaType("image/svg+xml"), r.getHeaders().getContentType());
		assertEquals("default-src 'none'; style-src 'unsafe-inline'; img-src data:",
				r.getHeaders().getFirst("Content-Security-Policy"));
		assertEquals("nosniff", r.getHeaders().getFirst("X-Content-Type-Options"));
		assertTrue(r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL).contains("max-age=86400"));
		assertArrayEquals(BrandingTestFixtures.EXAMPLE_LOGO_SVG, r.getBody());
	}

	@Test
	void pngFaviconIsServedWithoutTheSvgPolicy() {
		ResponseEntity<byte[]> r = controllerOver(BrandingTestFixtures.EXAMPLE).asset("favicon");
		assertEquals(HttpStatus.OK, r.getStatusCode());
		assertEquals(MediaType.IMAGE_PNG, r.getHeaders().getContentType());
		assertNull(r.getHeaders().getFirst("Content-Security-Policy"));
		assertEquals("nosniff", r.getHeaders().getFirst("X-Content-Type-Options"));
		assertArrayEquals(BrandingTestFixtures.EXAMPLE_FAVICON_PNG, r.getBody());
	}

	@Test
	void anAssetThePresetLeavesOutOrAnUnknownKindIsNotFound() {
		BrandingController example = controllerOver(BrandingTestFixtures.EXAMPLE);
		assertEquals(HttpStatus.NOT_FOUND, example.asset("signUpBackground").getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, example.asset("nope").getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, example.asset("LOGO").getStatusCode());
	}

	@Test
	void everyAssetOnDefaultIsNotFound() {
		BrandingController def = controllerOver(BrandingTestFixtures.DEFAULT);
		for (String kind : List.of("logo", "favicon", "signUpBackground")) {
			assertEquals(HttpStatus.NOT_FOUND, def.asset(kind).getStatusCode(), kind);
		}
	}
}
