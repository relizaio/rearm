/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.cyclonedx.Version;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.util.serializer.LicenseChoiceSerializer;

/**
 * Converts between the on-disk CycloneDX license shape (the raw {@code licenses}
 * array, stored as {@code List<Map<String,Object>>}) and the typed
 * {@link LicenseChoice} used at the parse and BOM-emit boundaries.
 *
 * <p>Why not just type the stored field as {@code LicenseChoice}: {@code LicenseChoice}
 * carries a class-level Jackson <em>deserializer</em> but no class-level serializer, so
 * default Jackson (what the JSONB JsonBinaryType uses) writes a Java-bean shape
 * ({@code {"licenses":[{"id":...}]}}) that the deserializer then reads back lossily.
 * We keep storage as the valid-CDX array and only materialize a {@code LicenseChoice}
 * here, where we can apply the library's {@link LicenseChoiceSerializer} (array shape)
 * and the class-level deserializer.
 */
public final class CdxLicenseUtil {

	private static final TypeReference<List<Map<String, Object>>> ARRAY_TYPE =
			new TypeReference<>() {};

	// A mapper that serializes LicenseChoice via the CDX serializer so the output is
	// the wire array ([{"license":{...}}, {"expression":...}]), not the default bean.
	private static final ObjectMapper CDX_MAPPER;
	static {
		CDX_MAPPER = new ObjectMapper();
		SimpleModule m = new SimpleModule();
		m.addSerializer(LicenseChoice.class, new LicenseChoiceSerializer(false, Version.VERSION_16));
		CDX_MAPPER.registerModule(m);
	}

	// The authoritative SPDX license id set, loaded from the CycloneDX-bundled
	// spdx.schema.json enum — the SAME list Dependency-Track validates license.id
	// against. SPDX_BY_LOWER maps a lowercased id to its canonical casing so a
	// mis-cased-but-valid id (e.g. "apache-2.0") is emitted correctly rather than
	// demoted. Deprecated-but-valid ids (e.g. "GPL-3.0") are members and kept.
	private static final Set<String> SPDX_IDS = new HashSet<>();
	private static final Map<String, String> SPDX_BY_LOWER = new HashMap<>();
	static {
		try (InputStream is = CdxLicenseUtil.class.getResourceAsStream("/spdx.schema.json")) {
			if (is != null) {
				JsonNode enumNode = new ObjectMapper().readTree(is).get("enum");
				if (enumNode != null) {
					for (JsonNode n : enumNode) {
						String id = n.asText();
						SPDX_IDS.add(id);
						SPDX_BY_LOWER.put(id.toLowerCase(Locale.ROOT), id);
					}
				}
			}
		} catch (Exception ignore) {
			// If the list can't be loaded, sanitize falls back to emitting every id
			// as a name — DTrack still accepts the BOM, we just lose SPDX semantics.
		}
	}

	private CdxLicenseUtil() {}

	/** CDX licenses array (stored shape) -> LicenseChoice; null/empty -> null. */
	public static LicenseChoice toLicenseChoice(List<Map<String, Object>> cdxArray) {
		if (cdxArray == null || cdxArray.isEmpty()) return null;
		LicenseChoice lc = CDX_MAPPER.convertValue(cdxArray, LicenseChoice.class);
		sanitizeSpdxIds(lc);
		return lc;
	}

	/**
	 * Dependency-Track strict-validates {@code license.id} against the CycloneDX
	 * SPDX enumeration; a source SBOM that puts a non-SPDX string in {@code id}
	 * (e.g. "LGPL", "Apache 2.0", a proprietary name) makes the whole BOM upload
	 * fail schema validation. For each license we keep {@code id} only when it is a
	 * member of the SPDX enum (canonical casing, fixing only letter case — never a
	 * semantic remap); otherwise we move the value to the freeform {@code name}
	 * field, which is unconstrained and always valid. Expressions are untouched.
	 */
	private static void sanitizeSpdxIds(LicenseChoice lc) {
		if (lc == null || lc.getLicenses() == null) return;
		for (License l : lc.getLicenses()) {
			String id = l.getId();
			if (id == null || id.isBlank()) continue;
			String canonical = SPDX_IDS.contains(id) ? id : SPDX_BY_LOWER.get(id.toLowerCase(Locale.ROOT));
			if (canonical != null) {
				l.setId(canonical);
			} else {
				if (l.getName() == null || l.getName().isBlank()) l.setName(id);
				l.setId(null);
			}
		}
	}

	/** LicenseChoice -> CDX licenses array (stored shape); null -> null. */
	public static List<Map<String, Object>> toCdxArray(LicenseChoice licenseChoice) {
		if (licenseChoice == null) return null;
		return CDX_MAPPER.convertValue(licenseChoice, ARRAY_TYPE);
	}
}
