/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import io.reliza.service.BrandingService.ResolvedBranding;
import io.reliza.ws.RelizaConfigProps;

/**
 * Branding for tests that must also run in CE: built in memory, never read from
 * {@code branding/saas/} on the classpath (CE has no such directory).
 *
 * <p>{@link #EXAMPLE} is the test-only {@code example} preset, the same content as
 * {@code src/test/resources/branding/saas/example/}; {@code BundledBrandingPresetsTest} (Pro only)
 * keeps the file and this fixture equal.
 */
public final class BrandingTestFixtures {

	private BrandingTestFixtures() {}

	public static final String EXAMPLE_NAME = "example";

	public static final String EXAMPLE_YAML = """
			titleText: Example Release Hub
			organizationText: Example Organization
			navProductName: Example Hub
			supportName: Example Support
			supportEmail: support@example.com
			documentationUrl: https://docs.example.com
			termsOfServiceUrl: https://example.com/terms
			legalUrl: https://example.com/legal
			marketingConsent: HIDDEN
			emailSenderName: Example Release Hub - Do Not Reply
			assets:
			  logo: logo.svg
			  favicon: favicon.png
			""";

	public static final byte[] EXAMPLE_LOGO_SVG = ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 120 40\">"
			+ "<rect width=\"120\" height=\"40\" fill=\"#1e6feb\"/><text x=\"60\" y=\"26\" font-size=\"16\""
			+ " text-anchor=\"middle\" fill=\"#ffffff\">Example</text></svg>\n").getBytes(StandardCharsets.UTF_8);

	/** A 32x32 single-colour PNG. */
	public static final byte[] EXAMPLE_FAVICON_PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR42mOQy39NU8QwasGoBaMWjFowasGoBaMWjFowasGoBaMWDBULAO5E4EydU0CxAAAAAElFTkSuQmCC");

	public static final ResolvedBranding DEFAULT = ResolvedBranding.DEFAULT;

	public static final ResolvedBranding EXAMPLE = BrandingService.resolve(EXAMPLE_NAME, false, loader(exampleFiles()));

	/** The example preset's files, keyed by path relative to {@code branding/saas/}; mutable copy. */
	public static Map<String, byte[]> exampleFiles() {
		Map<String, byte[]> files = new HashMap<>();
		files.put(EXAMPLE_NAME + "/preset.yaml", EXAMPLE_YAML.getBytes(StandardCharsets.UTF_8));
		files.put(EXAMPLE_NAME + "/logo.svg", EXAMPLE_LOGO_SVG);
		files.put(EXAMPLE_NAME + "/favicon.png", EXAMPLE_FAVICON_PNG);
		return files;
	}

	public static final String FULL_NAME = "full";

	/** A preset that sets every field and supplies every asset, the sign-up background included. */
	public static final String FULL_YAML = """
			titleText: Full Hub
			organizationText: Full Org
			navProductName: Full Nav
			supportName: Full Support
			supportEmail: help@full.example.com
			documentationUrl: https://docs.full.example.com
			termsOfServiceUrl: https://full.example.com/terms
			privacyPolicyUrl: http://full.example.com/privacy
			legalUrl: https://full.example.com/legal
			marketingConsent: SHOWN
			marketingConsentText: Send me Full news.
			emailSenderName: Full Hub Mailer
			assets:
			  logo: logo.svg
			  favicon: icon.ico
			  signUpBackground: back.webp
			""";

	public static final byte[] FULL_BACKGROUND_WEBP = "RIFF0000WEBP".getBytes(StandardCharsets.UTF_8);

	public static final ResolvedBranding FULL = BrandingService.resolve(FULL_NAME, false, loader(fullFiles()));

	/** The full preset's files, keyed by path relative to {@code branding/saas/}; mutable copy. */
	public static Map<String, byte[]> fullFiles() {
		Map<String, byte[]> files = new HashMap<>();
		files.put(FULL_NAME + "/preset.yaml", FULL_YAML.getBytes(StandardCharsets.UTF_8));
		files.put(FULL_NAME + "/logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));
		files.put(FULL_NAME + "/icon.ico", new byte[] {0, 0, 1, 0});
		files.put(FULL_NAME + "/back.webp", FULL_BACKGROUND_WEBP);
		return files;
	}

	/**
	 * Resolves a preset with the bean's own classpath loader. Only Pro tests call this: CE has no
	 * bundled presets, so there it would always answer default.
	 */
	public static ResolvedBranding resolveFromClasspath(String name) {
		return BrandingService.resolve(name, false, BrandingService::classpathResource);
	}

	/** A BrandingService that answers {@code branding}, for code under test that takes the service. */
	public static BrandingService serviceOver(ResolvedBranding branding) {
		return new BrandingService(new RelizaConfigProps()) {
			@Override
			public ResolvedBranding current() {
				return branding;
			}
		};
	}

	/** A preset loader over in-memory files; null for a path it does not hold, like the classpath. */
	public static Function<String, InputStream> loader(Map<String, byte[]> files) {
		return path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null;
	}
}
