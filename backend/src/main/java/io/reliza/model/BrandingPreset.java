/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The text values of a branding preset: what a deployment shows in place of the Reliza branding.
 *
 * <p>{@link #DEFAULT} is compiled in and is exactly what ReARM shows without a preset, so a
 * deployment on {@code default} is unchanged. A bundled preset (Pro only, under
 * {@code branding/saas/<name>/preset.yaml}) sets any of these fields; a field it leaves out takes
 * the {@code DEFAULT} value. Assets (logo, favicon, sign-up background) are carried beside this
 * record by {@code BrandingService.ResolvedBranding}.
 */
public record BrandingPreset(
		String titleText,
		String organizationText,
		String navProductName,
		String supportName,
		String supportEmail,
		String documentationUrl,
		String termsOfServiceUrl,
		String privacyPolicyUrl,
		String legalUrl,
		BrandingMarketingConsent marketingConsent,
		String marketingConsentText,
		String emailSenderName) {

	/** The name of the compiled-in preset; reserved, never read from disk. */
	public static final String DEFAULT_NAME = "default";

	public static final BrandingPreset DEFAULT = new BrandingPreset(
			"ReARM",
			"Reliza Incorporated",
			"ReARM",
			"Reliza Support",
			"info@reliza.io",
			"https://docs.rearmhq.com",
			"https://rearmhq.com/tos.html",
			"https://rearmhq.com/privacy.html",
			"https://rearmhq.com/tos.html",
			BrandingMarketingConsent.SHOWN,
			"Agree to receive news and promotions from Reliza by email (Optional).",
			"ReARM - Do Not Reply");

	/**
	 * The fields of a preset by their {@code preset.yaml} key, which is also their key in the
	 * public settings JSON. Each says how a value is checked at load and how to read it.
	 */
	public enum BrandingField {
		TITLE_TEXT("titleText", BrandingFieldCheck.TEXT, BrandingPreset::titleText),
		ORGANIZATION_TEXT("organizationText", BrandingFieldCheck.TEXT, BrandingPreset::organizationText),
		NAV_PRODUCT_NAME("navProductName", BrandingFieldCheck.TEXT, BrandingPreset::navProductName),
		SUPPORT_NAME("supportName", BrandingFieldCheck.TEXT, BrandingPreset::supportName),
		SUPPORT_EMAIL("supportEmail", BrandingFieldCheck.EMAIL, BrandingPreset::supportEmail),
		DOCUMENTATION_URL("documentationUrl", BrandingFieldCheck.URL, BrandingPreset::documentationUrl),
		TERMS_OF_SERVICE_URL("termsOfServiceUrl", BrandingFieldCheck.URL, BrandingPreset::termsOfServiceUrl),
		PRIVACY_POLICY_URL("privacyPolicyUrl", BrandingFieldCheck.URL, BrandingPreset::privacyPolicyUrl),
		LEGAL_URL("legalUrl", BrandingFieldCheck.URL, BrandingPreset::legalUrl),
		MARKETING_CONSENT("marketingConsent", BrandingFieldCheck.MARKETING_CONSENT, p -> p.marketingConsent().name()),
		MARKETING_CONSENT_TEXT("marketingConsentText", BrandingFieldCheck.TEXT, BrandingPreset::marketingConsentText),
		/** Backend only: the SMTP sender name, not served to the UI. */
		EMAIL_SENDER_NAME("emailSenderName", BrandingFieldCheck.TEXT, BrandingPreset::emailSenderName);

		private final String key;
		private final BrandingFieldCheck check;
		private final Function<BrandingPreset, String> reader;

		BrandingField(String key, BrandingFieldCheck check, Function<BrandingPreset, String> reader) {
			this.key = key;
			this.check = check;
			this.reader = reader;
		}

		public String key() {
			return key;
		}

		public BrandingFieldCheck check() {
			return check;
		}

		/** The field's value in {@code preset}, the enum's name for {@link #MARKETING_CONSENT}. */
		public String read(BrandingPreset preset) {
			return reader.apply(preset);
		}

		/** Whether the public settings JSON carries the field. */
		public boolean servedToUi() {
			return this != EMAIL_SENDER_NAME;
		}

		/** Exact match on the key; empty on anything else. */
		public static Optional<BrandingField> fromKey(String raw) {
			for (BrandingField f : values()) {
				if (f.key.equals(raw)) return Optional.of(f);
			}
			return Optional.empty();
		}
	}

	/** What a field's value must be, beyond a non-blank string of at most 200 characters. */
	public enum BrandingFieldCheck {
		TEXT,
		URL,
		EMAIL,
		MARKETING_CONSENT
	}

	/** Whether the sign-up "promotions" checkbox and the profile toggle are shown. */
	public enum BrandingMarketingConsent {
		SHOWN,
		HIDDEN;

		/** Case-insensitive; empty on anything else. */
		public static Optional<BrandingMarketingConsent> parse(String raw) {
			if (raw == null) return Optional.empty();
			try {
				return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				return Optional.empty();
			}
		}
	}

	/** What a non-default preset that does not supply an asset of a kind shows instead. */
	public enum BrandingAssetFallback {
		/** The UI's own static file, the one the default branding shows. */
		UI_DEFAULT,
		/** Nothing: the default branding's asset is not inherited, the UI shows its plain look. */
		NONE
	}

	/**
	 * The images a preset may supply. {@code wireName} is the key in {@code preset.yaml} and the
	 * {@code {kind}} of {@code /api/branding/v1/asset/{kind}}; {@code defaultStaticPath} is the UI's
	 * own static file the default branding shows; {@code fallback} says whether a non-default preset
	 * that does not supply the asset inherits that file. The Reliza sign-up background is shown on
	 * the default branding only.
	 */
	public enum BrandingAssetKind {
		LOGO("logo", "/logo_svg_no_tag_3.svg", BrandingAssetFallback.UI_DEFAULT),
		FAVICON("favicon", "/favicon.ico", BrandingAssetFallback.UI_DEFAULT),
		SIGN_UP_BACKGROUND("signUpBackground", "/reliza_in_sand_right_corner.jpg", BrandingAssetFallback.NONE);

		private final String wireName;
		private final String defaultStaticPath;
		private final BrandingAssetFallback fallback;

		BrandingAssetKind(String wireName, String defaultStaticPath, BrandingAssetFallback fallback) {
			this.wireName = wireName;
			this.defaultStaticPath = defaultStaticPath;
			this.fallback = fallback;
		}

		public String wireName() {
			return wireName;
		}

		public String defaultStaticPath() {
			return defaultStaticPath;
		}

		public BrandingAssetFallback fallback() {
			return fallback;
		}

		/** Exact match on the wire name; empty on anything else. */
		public static Optional<BrandingAssetKind> fromWireName(String raw) {
			for (BrandingAssetKind k : values()) {
				if (k.wireName.equals(raw)) return Optional.of(k);
			}
			return Optional.empty();
		}
	}
}
