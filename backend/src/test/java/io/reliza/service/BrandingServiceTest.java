/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.model.BrandingPreset;
import io.reliza.model.BrandingPreset.BrandingAssetFallback;
import io.reliza.model.BrandingPreset.BrandingAssetKind;
import io.reliza.model.BrandingPreset.BrandingMarketingConsent;
import io.reliza.service.BrandingService.BrandingAsset;
import io.reliza.service.BrandingService.ResolvedBranding;

/**
 * Preset resolution (WL-2 design 4.1): the {@code default} values, field-by-field fallback, the
 * edition rule, and every way a preset is invalid. In-memory presets only, so this runs in CE too.
 */
class BrandingServiceTest {

	private static ResolvedBranding resolve(String name, Map<String, byte[]> files) {
		return BrandingService.resolve(name, false, BrandingTestFixtures.loader(files));
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	// 1. The default preset, and every way of asking for it.

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  ", "default", "DEFAULT"})
	void blankOrDefaultNameResolvesToDefault(String name) {
		ResolvedBranding b = resolve(name, BrandingTestFixtures.exampleFiles());
		assertSame(ResolvedBranding.DEFAULT, b);
		assertTrue(b.isDefault());
		assertEquals("default", b.preset());
		assertEquals(BrandingPreset.DEFAULT, b.values());
		assertTrue(b.assets().isEmpty());
	}

	@Test
	void defaultValuesAreTodaysRelizaBranding() {
		BrandingPreset d = BrandingPreset.DEFAULT;
		assertEquals("ReARM", d.titleText());
		assertEquals("Reliza Incorporated", d.organizationText());
		assertEquals("ReARM", d.navProductName());
		assertEquals("Reliza Support", d.supportName());
		assertEquals("info@reliza.io", d.supportEmail());
		assertEquals("https://docs.rearmhq.com", d.documentationUrl());
		assertEquals("https://rearmhq.com/tos.html", d.termsOfServiceUrl());
		assertEquals("https://rearmhq.com/privacy.html", d.privacyPolicyUrl());
		assertEquals("https://rearmhq.com/tos.html", d.legalUrl());
		assertEquals(BrandingMarketingConsent.SHOWN, d.marketingConsent());
		assertEquals("Agree to receive news and promotions from Reliza by email (Optional).", d.marketingConsentText());
		assertEquals("ReARM - Do Not Reply", d.emailSenderName());
	}

	// 2. A preset that sets everything.

	@Test
	void fullPresetSetsEveryFieldAndAsset() {
		Map<String, byte[]> files = BrandingTestFixtures.fullFiles();
		ResolvedBranding b = resolve(BrandingTestFixtures.FULL_NAME, files);

		assertEquals("full", b.preset());
		assertFalse(b.isDefault());
		assertEquals(new BrandingPreset("Full Hub", "Full Org", "Full Nav", "Full Support", "help@full.example.com",
				"https://docs.full.example.com", "https://full.example.com/terms", "http://full.example.com/privacy",
				"https://full.example.com/legal", BrandingMarketingConsent.SHOWN, "Send me Full news.",
				"Full Hub Mailer"), b.values());
		assertAsset(b, BrandingAssetKind.LOGO, "image/svg+xml", files.get("full/logo.svg"));
		assertAsset(b, BrandingAssetKind.FAVICON, "image/x-icon", files.get("full/icon.ico"));
		assertAsset(b, BrandingAssetKind.SIGN_UP_BACKGROUND, "image/webp", files.get("full/back.webp"));
	}

	private static void assertAsset(ResolvedBranding b, BrandingAssetKind kind, String contentType, byte[] bytes) {
		BrandingAsset a = b.asset(kind).orElseThrow();
		assertEquals(contentType, a.contentType());
		assertArrayEquals(bytes, a.bytes());
		assertTrue(a.hash().matches("^[0-9a-f]{12}$"), a.hash());
	}

	// 3. A partial preset (the example shape) falls back field by field.

	@Test
	void partialPresetFallsBackPerField() {
		ResolvedBranding b = BrandingTestFixtures.EXAMPLE;
		BrandingPreset d = BrandingPreset.DEFAULT;
		assertEquals("example", b.preset());
		assertFalse(b.isDefault());
		assertEquals("Example Release Hub", b.values().titleText());
		assertEquals("Example Organization", b.values().organizationText());
		assertEquals("Example Hub", b.values().navProductName());
		assertEquals("Example Support", b.values().supportName());
		assertEquals("support@example.com", b.values().supportEmail());
		assertEquals("https://docs.example.com", b.values().documentationUrl());
		assertEquals("https://example.com/terms", b.values().termsOfServiceUrl());
		assertEquals("https://example.com/legal", b.values().legalUrl());
		assertEquals(BrandingMarketingConsent.HIDDEN, b.values().marketingConsent());
		assertEquals("Example Release Hub - Do Not Reply", b.values().emailSenderName());
		// left out by the preset
		assertEquals(d.privacyPolicyUrl(), b.values().privacyPolicyUrl());
		assertEquals(d.marketingConsentText(), b.values().marketingConsentText());
		assertTrue(b.asset(BrandingAssetKind.SIGN_UP_BACKGROUND).isEmpty());
		assertAsset(b, BrandingAssetKind.LOGO, "image/svg+xml", BrandingTestFixtures.EXAMPLE_LOGO_SVG);
		assertAsset(b, BrandingAssetKind.FAVICON, "image/png", BrandingTestFixtures.EXAMPLE_FAVICON_PNG);
	}

	// 4. The edition decides: CE never applies a preset.

	@Test
	void ossEditionIgnoresAValidPreset() {
		ResolvedBranding b = BrandingService.resolve("example", true,
				BrandingTestFixtures.loader(BrandingTestFixtures.exampleFiles()));
		assertSame(ResolvedBranding.DEFAULT, b);
	}

	// 5. An invalid preset is default as a whole.

	@ParameterizedTest
	@ValueSource(strings = {"../x", "x/../example", "Example", "-example", "exa mple",
			"a2345678901234567890123456789012345678901" /* 41 characters */})
	void badNameResolvesToDefault(String name) {
		// The loader holds a complete, valid preset under exactly this name, so only the name rule can
		// turn it away; a loader that lacked the directory would pass without the rule.
		Map<String, byte[]> files = filesUnder(name);
		assertTrue(files.containsKey(name + "/preset.yaml"));
		assertSame(ResolvedBranding.DEFAULT, resolve(name, files));
	}

	@Test
	void theExampleFilesUnderAGoodNameAreValid() {
		// The control for badNameResolvesToDefault: the same files under a name the rule accepts.
		String name = "a234567890123456789012345678901234567890"; // 40 characters, the longest allowed
		ResolvedBranding b = resolve(name, filesUnder(name));
		assertFalse(b.isDefault());
		assertEquals(name, b.preset());
	}

	/** The example preset's files, moved under {@code name}. */
	private static Map<String, byte[]> filesUnder(String name) {
		Map<String, byte[]> files = new HashMap<>();
		BrandingTestFixtures.exampleFiles().forEach((path, bytes) ->
				files.put(name + path.substring(BrandingTestFixtures.EXAMPLE_NAME.length()), bytes));
		return files;
	}

	static Stream<Arguments> invalidPresets() {
		return Stream.of(
				invalid("missing preset.yaml", f -> f.remove("example/preset.yaml")),
				invalid("unknown key titelText", f -> yaml(f, "titelText: Typo\n")),
				invalid("unknown asset key banner", f -> yaml(f, "  banner: logo.svg\n")),
				invalid("blank titleText", f -> replaceYaml(f, "titleText: Example Release Hub", "titleText: '  '")),
				invalid("201-character titleText", f -> replaceYaml(f, "titleText: Example Release Hub",
						"titleText: " + "t".repeat(201))),
				invalid("documentationUrl without a scheme", f -> replaceYaml(f,
						"documentationUrl: https://docs.example.com", "documentationUrl: docs.example.com")),
				invalid("supportEmail not an address", f -> replaceYaml(f,
						"supportEmail: support@example.com", "supportEmail: nope")),
				invalid("marketingConsent MAYBE", f -> replaceYaml(f, "marketingConsent: HIDDEN", "marketingConsent: MAYBE")),
				invalid("asset .gif", f -> {
					replaceYaml(f, "logo: logo.svg", "logo: logo.gif");
					f.put("example/logo.gif", new byte[] {1});
				}),
				invalid("asset file missing", f -> f.remove("example/favicon.png")),
				invalid("asset over 2 MiB", f -> f.put("example/favicon.png", new byte[2 * 1024 * 1024 + 1])),
				invalid("malformed YAML", f -> f.put("example/preset.yaml", utf8("titleText: [unclosed\n"))));
	}

	private static Arguments invalid(String name, Consumer<Map<String, byte[]>> breakIt) {
		return Arguments.of(Named.of(name, breakIt));
	}

	private static void yaml(Map<String, byte[]> files, String append) {
		files.put("example/preset.yaml", utf8(BrandingTestFixtures.EXAMPLE_YAML + append));
	}

	private static void replaceYaml(Map<String, byte[]> files, String from, String to) {
		String yaml = new String(files.get("example/preset.yaml"), StandardCharsets.UTF_8);
		assertTrue(yaml.contains(from), from);
		files.put("example/preset.yaml", utf8(yaml.replace(from, to)));
	}

	@ParameterizedTest
	@MethodSource("invalidPresets")
	void invalidPresetResolvesToDefault(Consumer<Map<String, byte[]>> breakIt) {
		Map<String, byte[]> files = BrandingTestFixtures.exampleFiles();
		breakIt.accept(files);
		assertSame(ResolvedBranding.DEFAULT, resolve("example", files));
	}

	@Test
	void theUnbrokenExampleIsValid() {
		// The control for invalidPresetResolvesToDefault: each case breaks exactly one thing.
		assertFalse(resolve("example", BrandingTestFixtures.exampleFiles()).isDefault());
	}

	@Test
	void anInvalidPresetLogsOneErrorNamingItAndAValidOneNone() {
		List<String> errors = new CopyOnWriteArrayList<>();
		Logger logger = (Logger) LogManager.getLogger(BrandingService.class);
		Appender appender = new AbstractAppender("brandingServiceTestErrors", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (event.getLevel() == Level.ERROR) errors.add(event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		logger.addAppender(appender);
		try {
			resolve("example", BrandingTestFixtures.exampleFiles());
			assertEquals(List.of(), errors);

			resolve("nosuch", BrandingTestFixtures.exampleFiles());
			assertEquals(List.of("Branding preset 'nosuch' cannot be used (no bundled preset.yaml); using default"),
					errors);
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
	}

	// 6. Asset URLs.

	@Test
	void suppliedAssetIsServedVersionedAndAMissingOneIsTheUiDefault() {
		ResolvedBranding b = BrandingTestFixtures.EXAMPLE;
		String hash = b.asset(BrandingAssetKind.LOGO).orElseThrow().hash();
		assertEquals("/api/branding/v1/asset/logo?v=" + hash, b.assetUrl(BrandingAssetKind.LOGO));
		assertTrue(b.assetUrl(BrandingAssetKind.FAVICON).matches("^/api/branding/v1/asset/favicon\\?v=[0-9a-f]{12}$"));

		ResolvedBranding d = ResolvedBranding.DEFAULT;
		assertEquals("/logo_svg_no_tag_3.svg", d.assetUrl(BrandingAssetKind.LOGO));
		assertEquals("/favicon.ico", d.assetUrl(BrandingAssetKind.FAVICON));
	}

	@Test
	void aMissingLogoOrFaviconOnAPresetIsTheUiDefault() {
		Map<String, byte[]> files = BrandingTestFixtures.exampleFiles();
		files.put("example/preset.yaml", utf8("titleText: No Images\n"));
		ResolvedBranding b = resolve("example", files);
		assertEquals("example", b.preset());
		assertFalse(b.isDefault());
		assertEquals("/logo_svg_no_tag_3.svg", b.assetUrl(BrandingAssetKind.LOGO));
		assertEquals("/favicon.ico", b.assetUrl(BrandingAssetKind.FAVICON));
	}

	// 7. The sign-up background is not inherited from default (WL-6 design 3.2).

	@Test
	void aMissingSignUpBackgroundIsNullOnAPresetAndTheRelizaPhotoOnDefault() throws NoSuchAlgorithmException {
		assertNull(BrandingTestFixtures.EXAMPLE.assetUrl(BrandingAssetKind.SIGN_UP_BACKGROUND),
				"a preset without a sign-up background inherits none");
		assertEquals("/reliza_in_sand_right_corner.jpg",
				ResolvedBranding.DEFAULT.assetUrl(BrandingAssetKind.SIGN_UP_BACKGROUND));

		ResolvedBranding full = BrandingTestFixtures.FULL;
		String backHash = sha256Prefix(BrandingTestFixtures.FULL_BACKGROUND_WEBP);
		assertEquals("/api/branding/v1/asset/signUpBackground?v=" + backHash,
				full.assetUrl(BrandingAssetKind.SIGN_UP_BACKGROUND));
	}

	// Computed here, not read from the resolved asset, so the URL is checked against the file's own digest.
	private static String sha256Prefix(byte[] bytes) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
	}

	@Test
	void everyAssetKindDeclaresItsFallback() {
		assertEquals(BrandingAssetFallback.UI_DEFAULT, BrandingAssetKind.LOGO.fallback());
		assertEquals(BrandingAssetFallback.UI_DEFAULT, BrandingAssetKind.FAVICON.fallback());
		assertEquals(BrandingAssetFallback.NONE, BrandingAssetKind.SIGN_UP_BACKGROUND.fallback());
	}
}
