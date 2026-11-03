/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

/**
 * The file name a TEA artifact revision is downloaded under: {@code <teaUuid>-r<revision>} and the
 * extension of the format's media type, for example
 * {@code 3f2c...-r4.cdx.json}. The name never comes from the artifact's own name or display
 * identifier, so it is stable, unique per revision and free of user text.
 *
 * <p>The UI's download of a collection entry (ui/src/utils/teaPublication.ts, teaEntryFileName)
 * applies the same rule; change both together.
 */
public final class TeaFileNames {

	private TeaFileNames() {}

	/** Extensions by media type (type/subtype, lowercase, no parameters). */
	private static final Map<String, String> EXTENSIONS = Map.ofEntries(
			Map.entry("application/vnd.cyclonedx+json", ".cdx.json"),
			Map.entry("application/vnd.cyclonedx+xml", ".cdx.xml"),
			Map.entry("application/spdx+json", ".spdx.json"),
			Map.entry("application/spdx+xml", ".spdx.xml"),
			Map.entry("application/spdx+yaml", ".spdx.yaml"),
			Map.entry("text/spdx", ".spdx"),
			Map.entry("application/vnd.in-toto+json", ".intoto.json"),
			Map.entry("application/json", ".json"),
			Map.entry("application/xml", ".xml"),
			Map.entry("text/xml", ".xml"),
			Map.entry("application/yaml", ".yaml"),
			Map.entry("text/yaml", ".yaml"),
			Map.entry("application/pdf", ".pdf"),
			Map.entry("text/plain", ".txt"),
			Map.entry("text/markdown", ".md"),
			Map.entry("text/html", ".html"),
			Map.entry("text/csv", ".csv"),
			Map.entry("application/zip", ".zip"),
			Map.entry("application/gzip", ".gz"),
			Map.entry("application/x-tar", ".tar"),
			Map.entry("application/pgp-signature", ".asc"));

	/** {@code <teaUuid>-r<revision>} and the extension of {@code mediaType}. */
	public static String artifactFileName(UUID teaUuid, int revision, String mediaType) {
		return teaUuid + "-r" + revision + extensionOf(mediaType);
	}

	/**
	 * The extension of a media type: a known type's, else {@code .json} or {@code .xml} for a
	 * {@code +json} or {@code +xml} structured suffix, else none (octet-stream, unknown or absent).
	 */
	public static String extensionOf(String mediaType) {
		if (StringUtils.isBlank(mediaType)) return "";
		String base = StringUtils.substringBefore(mediaType, ";").strip().toLowerCase(Locale.ROOT);
		String known = EXTENSIONS.get(base);
		if (null != known) return known;
		if (base.endsWith("+json")) return ".json";
		if (base.endsWith("+xml")) return ".xml";
		return "";
	}
}
