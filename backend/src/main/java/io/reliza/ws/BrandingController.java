/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.reliza.model.BrandingPreset;
import io.reliza.model.BrandingPreset.BrandingAssetKind;
import io.reliza.model.BrandingPreset.BrandingField;
import io.reliza.service.BrandingService;
import io.reliza.service.BrandingService.BrandingAsset;
import io.reliza.service.BrandingService.ResolvedBranding;

/**
 * The deployment's branding, for the UI: public, unauthenticated, GET only.
 *
 * <p>{@code /settings} answers the resolved values (fallback applied) and the URL of each asset;
 * {@code /asset/{kind}} serves an asset the active preset supplies. An asset URL is {@code null}
 * when a non-default preset supplies none and the kind is not inherited from the default branding
 * (the sign-up background): the key stays, so the body always carries the same keys. The JSON keys of
 * {@code /settings} are the contract the UI builds against: do not rename one. The UI falls back to
 * its own defaults on any non-200, so a backend without this endpoint shows the Reliza branding.
 */
@RestController
@RequestMapping(BrandingController.PATH)
public class BrandingController {

	public static final String PATH = BrandingService.API_PATH;

	static final String ASSET_CACHE_CONTROL = "public, max-age=86400";
	static final String SVG_CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; img-src data:";

	private final BrandingService brandingService;

	public BrandingController(BrandingService brandingService) {
		this.brandingService = brandingService;
	}

	@GetMapping("/settings")
	public ResponseEntity<Map<String, Object>> settings() {
		ResolvedBranding b = brandingService.current();
		BrandingPreset v = b.values();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("preset", b.preset());
		body.put("isDefault", b.isDefault());
		for (BrandingField field : BrandingField.values()) {
			if (field.servedToUi()) body.put(field.key(), field.read(v));
		}
		body.put("logoUrl", b.assetUrl(BrandingAssetKind.LOGO));
		body.put("faviconUrl", b.assetUrl(BrandingAssetKind.FAVICON));
		body.put("signUpBackgroundUrl", b.assetUrl(BrandingAssetKind.SIGN_UP_BACKGROUND));
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noCache())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body);
	}

	/**
	 * The asset's bytes; 404 for a kind the active preset does not supply (every kind on
	 * {@code default}) and for an unknown kind. The {@code v} query parameter is cache-busting only.
	 */
	@GetMapping("/asset/{kind}")
	public ResponseEntity<byte[]> asset(@PathVariable("kind") String kind) {
		Optional<BrandingAsset> asset = BrandingAssetKind.fromWireName(kind)
				.flatMap(k -> brandingService.current().asset(k));
		if (asset.isEmpty()) return ResponseEntity.notFound().build();
		BrandingAsset a = asset.get();
		ResponseEntity.BodyBuilder response = ResponseEntity.ok()
				.header(HttpHeaders.CACHE_CONTROL, ASSET_CACHE_CONTROL)
				.contentType(MediaType.parseMediaType(a.contentType()))
				.header("X-Content-Type-Options", "nosniff");
		if (BrandingService.SVG_CONTENT_TYPE.equals(a.contentType())) {
			response.header("Content-Security-Policy", SVG_CONTENT_SECURITY_POLICY);
		}
		return response.body(a.bytes());
	}
}
