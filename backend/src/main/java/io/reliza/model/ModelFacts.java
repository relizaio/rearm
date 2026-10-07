/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What is true of a model regardless of who serves it.
 *
 * <p>A record rather than a map, because the key set has never actually been open: the bundled
 * catalogue writes exactly these, the UI renders exactly these, and a routing floor that reads
 * {@code contextWindow} needs it to be a number rather than whatever a caller happened to put
 * there. A map made every reader do its own casting and let a typo in a key become a fact nobody
 * would ever read.
 *
 * <p>Unknown properties are ignored, so a bundle written by a later version still loads rather than
 * failing the whole catalogue over a field this build does not know.
 *
 * @param contextWindow total tokens the model accepts in one request
 * @param maxOutputTokens most it will generate in one response
 * @param modalities what it can read and write, e.g. text, image
 * @param hostingKind how it is served
 * @param releaseDate when the model became available
 * @param deprecatedAt when the provider announced its retirement, if it has
 * @param knowledgeCutoff the end of its training data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelFacts(
		@JsonProperty("contextWindow") Long contextWindow,
		@JsonProperty("maxOutputTokens") Long maxOutputTokens,
		@JsonProperty("modalities") List<Modality> modalities,
		@JsonProperty("hostingKind") HostingKind hostingKind,
		@JsonProperty("releaseDate") LocalDate releaseDate,
		@JsonProperty("deprecatedAt") LocalDate deprecatedAt,
		@JsonProperty("knowledgeCutoff") LocalDate knowledgeCutoff) implements Serializable {

	private static final long serialVersionUID = 20260920L;

	/** Never null, so a caller can iterate without a guard. */
	public ModelFacts {
		if (null == modalities) modalities = List.of();
	}

	/**
	 * What a model can read and write.
	 *
	 * <p>Parsed case-insensitively. The bundled catalogue is a hand-edited YAML file, and a
	 * lowercase {@code text} there used to throw during load -- which the loader catches and logs,
	 * so the whole catalogue silently became empty and every model resolved as unresolved. One
	 * mis-cased letter should not cost the entire bundle.
	 */
	public enum Modality {
		TEXT, IMAGE, AUDIO, VIDEO;

		@com.fasterxml.jackson.annotation.JsonCreator
		public static Modality fromValue(String v) {
			return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
		}
	}

	/**
	 * How a model is served.
	 *
	 * <p>Distinct from {@link SessionUsageHosting}, which says where ONE request went. This says
	 * what kind of thing the model is: a hosted API, something you run yourself, or a model
	 * embedded in a product.
	 */
	public enum HostingKind {
		API, SELF_HOSTED, EMBEDDED;

		/** Case-insensitive for the same reason as {@link Modality}. */
		@com.fasterxml.jackson.annotation.JsonCreator
		public static HostingKind fromValue(String v) {
			return null == v ? null : valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
		}
	}
}
