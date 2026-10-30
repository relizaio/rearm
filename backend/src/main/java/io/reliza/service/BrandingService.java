/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import io.reliza.common.oss.LicensingConstants;
import io.reliza.model.BrandingPreset;
import io.reliza.model.BrandingPreset.BrandingAssetFallback;
import io.reliza.model.BrandingPreset.BrandingAssetKind;
import io.reliza.model.BrandingPreset.BrandingField;
import io.reliza.model.BrandingPreset.BrandingMarketingConsent;
import io.reliza.ws.RelizaConfigProps;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The deployment's branding: which preset is active and every value it resolves to.
 *
 * <p>The preset is named by {@code relizaprops.brandingPreset} and resolved once at startup.
 * Presets are files bundled with ReARM Pro under {@code branding/saas/<name>/} on the classpath
 * ({@code preset.yaml} and its assets); CE carries none and always answers {@code default}. A field
 * or asset the preset leaves out takes the {@link BrandingPreset#DEFAULT} value. A branding typo
 * must not take the system down, so an unknown or invalid preset logs an ERROR and resolves to
 * {@code default}.
 *
 * <p>The edition decides, not the license's validity: an expired license must not flip a
 * white-label deployment back to the Reliza branding.
 */
@Slf4j
@Service
public class BrandingService {

	/** Classpath root of the bundled presets, relative paths below it are {@code <name>/<file>}. */
	static final String PRESET_ROOT = "/branding/saas/";
	static final String PRESET_FILE = "preset.yaml";
	static final String ASSETS_KEY = "assets";
	/** The public branding REST API; {@code BrandingController} serves it. */
	public static final String API_PATH = "/api/branding/v1";
	public static final String ASSET_URL_PREFIX = API_PATH + "/asset/";
	public static final String SVG_CONTENT_TYPE = "image/svg+xml";
	static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
	static final int MAX_TEXT_LENGTH = 200;
	static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
	static final int ASSET_HASH_LENGTH = 12;

	private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
			"svg", SVG_CONTENT_TYPE,
			"png", "image/png",
			"ico", "image/x-icon",
			"jpg", "image/jpeg",
			"jpeg", "image/jpeg",
			"webp", "image/webp");

	/** One image a preset supplies: its bytes, content type, and the first 12 hex of its SHA-256. */
	public record BrandingAsset(byte[] bytes, String contentType, String hash) {}

	/**
	 * The active branding with every fallback applied.
	 *
	 * @param preset the active preset's name, {@code default} when none applies
	 * @param isDefault true when no preset applies
	 * @param values every text value, the preset's over {@code DEFAULT}'s
	 * @param assets only the assets the preset supplies; a missing kind falls back as {@link #assetUrl} says
	 */
	public record ResolvedBranding(String preset, boolean isDefault, BrandingPreset values,
			Map<BrandingAssetKind, BrandingAsset> assets) {

		public static final ResolvedBranding DEFAULT = new ResolvedBranding(BrandingPreset.DEFAULT_NAME, true,
				BrandingPreset.DEFAULT, Map.of());

		public Optional<BrandingAsset> asset(BrandingAssetKind kind) {
			return Optional.ofNullable(assets.get(kind));
		}

		/**
		 * Where the UI loads the asset from: the branding endpoint, versioned by the content hash,
		 * when the preset supplies it; otherwise the UI's own static file, on the default branding
		 * and for a kind a preset inherits from it ({@link BrandingAssetFallback#UI_DEFAULT}).
		 *
		 * @return {@code null} when the active preset is not the default and the kind is not
		 *         inherited from it: the UI shows its plain background
		 */
		@Nullable
		public String assetUrl(BrandingAssetKind kind) {
			Optional<BrandingAsset> supplied = asset(kind);
			if (supplied.isPresent()) {
				return ASSET_URL_PREFIX + kind.wireName() + "?v=" + supplied.get().hash();
			}
			if (isDefault || kind.fallback() == BrandingAssetFallback.UI_DEFAULT) {
				return kind.defaultStaticPath();
			}
			return null;
		}
	}

	private final RelizaConfigProps relizaConfigProps;
	private volatile ResolvedBranding current = ResolvedBranding.DEFAULT;

	public BrandingService(RelizaConfigProps relizaConfigProps) {
		this.relizaConfigProps = relizaConfigProps;
	}

	@PostConstruct
	void load() {
		current = resolve(relizaConfigProps.getBrandingPreset(), LicensingConstants.isOssEdition(),
				BrandingService::classpathResource);
		log.info("Branding preset in use: {}", current.preset());
	}

	public ResolvedBranding current() {
		return current;
	}

	/** The bean's preset loader: the bundled presets on the classpath. */
	static InputStream classpathResource(String relativePath) {
		return BrandingService.class.getResourceAsStream(PRESET_ROOT + relativePath);
	}

	/**
	 * Resolves a preset name to the branding it stands for.
	 *
	 * @param name the configured name; blank, absent or {@code default} (any case) is {@code default}
	 * @param ossEdition true on CE, where presets are not a feature and {@code default} always applies
	 * @param loader opens a path relative to {@code branding/saas/}, null when it does not exist
	 */
	static ResolvedBranding resolve(String name, boolean ossEdition, Function<String, InputStream> loader) {
		if (StringUtils.isBlank(name) || BrandingPreset.DEFAULT_NAME.equalsIgnoreCase(name.trim())) {
			return ResolvedBranding.DEFAULT;
		}
		if (ossEdition) {
			log.warn("Branding preset '{}' is set, but branding presets are a ReARM Pro feature; using default", name);
			return ResolvedBranding.DEFAULT;
		}
		try {
			return load(name, loader);
		} catch (InvalidPresetException e) {
			log.error("Branding preset '{}' cannot be used ({}); using default", name, e.getMessage());
			return ResolvedBranding.DEFAULT;
		}
	}

	private static ResolvedBranding load(String name, Function<String, InputStream> loader)
			throws InvalidPresetException {
		if (!NAME_PATTERN.matcher(name).matches()) {
			throw new InvalidPresetException("the name must match " + NAME_PATTERN.pattern());
		}
		Map<String, Object> yaml = readYaml(name, loader);
		Map<BrandingField, String> set = new EnumMap<>(BrandingField.class);
		for (Map.Entry<String, Object> e : yaml.entrySet()) {
			if (ASSETS_KEY.equals(e.getKey())) continue;
			BrandingField field = BrandingField.fromKey(e.getKey())
					.orElseThrow(() -> new InvalidPresetException("unknown key '" + e.getKey() + "'"));
			set.put(field, readValue(field, e.getValue()));
		}
		Function<BrandingField, String> value = f -> set.getOrDefault(f, f.read(BrandingPreset.DEFAULT));
		BrandingPreset values = new BrandingPreset(
				value.apply(BrandingField.TITLE_TEXT),
				value.apply(BrandingField.ORGANIZATION_TEXT),
				value.apply(BrandingField.NAV_PRODUCT_NAME),
				value.apply(BrandingField.SUPPORT_NAME),
				value.apply(BrandingField.SUPPORT_EMAIL),
				value.apply(BrandingField.DOCUMENTATION_URL),
				value.apply(BrandingField.TERMS_OF_SERVICE_URL),
				value.apply(BrandingField.PRIVACY_POLICY_URL),
				value.apply(BrandingField.LEGAL_URL),
				BrandingMarketingConsent.parse(value.apply(BrandingField.MARKETING_CONSENT)).orElseThrow(),
				value.apply(BrandingField.MARKETING_CONSENT_TEXT),
				value.apply(BrandingField.EMAIL_SENDER_NAME));
		return new ResolvedBranding(name, false, values, readAssets(name, yaml.get(ASSETS_KEY), loader));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readYaml(String name, Function<String, InputStream> loader)
			throws InvalidPresetException {
		try (InputStream in = loader.apply(name + "/" + PRESET_FILE)) {
			if (in == null) throw new InvalidPresetException("no bundled " + PRESET_FILE);
			Object root = new Yaml().load(in);
			if (!(root instanceof Map)) throw new InvalidPresetException(PRESET_FILE + " is not a mapping");
			return (Map<String, Object>) root;
		} catch (IOException | RuntimeException e) {
			// RuntimeException: snakeyaml reports malformed YAML as YAMLException.
			throw new InvalidPresetException(PRESET_FILE + " cannot be read: " + e.getMessage());
		}
	}

	private static String readValue(BrandingField field, Object raw) throws InvalidPresetException {
		String key = field.key();
		if (!(raw instanceof String s) || StringUtils.isBlank(s)) {
			throw new InvalidPresetException(key + " must be a non-blank string");
		}
		if (s.length() > MAX_TEXT_LENGTH) {
			throw new InvalidPresetException(key + " is longer than " + MAX_TEXT_LENGTH + " characters");
		}
		switch (field.check()) {
			case URL -> {
				if (!isAbsoluteHttpUrl(s)) throw new InvalidPresetException(key + " must be an absolute http(s) URL");
			}
			case EMAIL -> {
				if (!EmailValidator.getInstance().isValid(s)) {
					throw new InvalidPresetException(key + " is not a valid email address");
				}
			}
			case MARKETING_CONSENT -> {
				// Stored as the enum's name, so the merge above parses one spelling only.
				return BrandingMarketingConsent.parse(s).orElseThrow(
						() -> new InvalidPresetException(key + " must be SHOWN or HIDDEN")).name();
			}
			case TEXT -> { }
		}
		return s;
	}

	private static Map<BrandingAssetKind, BrandingAsset> readAssets(String name, Object raw,
			Function<String, InputStream> loader) throws InvalidPresetException {
		if (raw == null) return Map.of();
		if (!(raw instanceof Map<?, ?> declared)) throw new InvalidPresetException(ASSETS_KEY + " is not a mapping");
		Map<BrandingAssetKind, BrandingAsset> assets = new EnumMap<>(BrandingAssetKind.class);
		for (Map.Entry<?, ?> e : declared.entrySet()) {
			BrandingAssetKind kind = BrandingAssetKind.fromWireName(String.valueOf(e.getKey())).orElseThrow(
					() -> new InvalidPresetException("unknown asset '" + e.getKey() + "'"));
			assets.put(kind, readAsset(name, kind, e.getValue(), loader));
		}
		return Collections.unmodifiableMap(assets);
	}

	private static BrandingAsset readAsset(String name, BrandingAssetKind kind, Object rawFile,
			Function<String, InputStream> loader) throws InvalidPresetException {
		String asset = "asset " + kind.wireName();
		if (!(rawFile instanceof String file) || StringUtils.isBlank(file)
				|| file.contains("/") || file.contains("\\") || file.startsWith(".")) {
			throw new InvalidPresetException(asset + " must name a file in the preset's directory");
		}
		String contentType = CONTENT_TYPE_BY_EXTENSION.get(
				StringUtils.substringAfterLast(file, ".").toLowerCase(Locale.ROOT));
		if (contentType == null) {
			throw new InvalidPresetException(asset + " must be one of " + new TreeSet<>(CONTENT_TYPE_BY_EXTENSION.keySet()));
		}
		byte[] bytes;
		try (InputStream in = loader.apply(name + "/" + file)) {
			if (in == null) throw new InvalidPresetException(asset + ": file '" + file + "' is missing");
			bytes = in.readNBytes(MAX_ASSET_BYTES + 1);
		} catch (IOException e) {
			throw new InvalidPresetException(asset + ": file '" + file + "' cannot be read: " + e.getMessage());
		}
		if (bytes.length > MAX_ASSET_BYTES) {
			throw new InvalidPresetException(asset + ": file '" + file + "' is larger than 2 MiB");
		}
		return new BrandingAsset(bytes, contentType, sha256Prefix(bytes));
	}

	private static boolean isAbsoluteHttpUrl(String value) {
		try {
			URI uri = new URI(value);
			String scheme = uri.getScheme();
			return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
					&& StringUtils.isNotEmpty(uri.getHost());
		} catch (URISyntaxException e) {
			return false;
		}
	}

	private static String sha256Prefix(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			return HexFormat.of().formatHex(digest).substring(0, ASSET_HASH_LENGTH);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	/** Why a preset cannot be used; the message is logged with the preset's name. */
	private static final class InvalidPresetException extends Exception {
		private static final long serialVersionUID = 1L;

		InvalidPresetException(String reason) {
			super(reason);
		}
	}
}
