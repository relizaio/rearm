/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A ReARM-native typed identifier. The single identifier shape stored on the
 * {@code identifiers} JSONB list of components, releases, deliverables,
 * shipped products, and devices. {@link RearmIdentifierType} is a superset of
 * the code-generated TEA enum; TEA export filters to the TEA-representable
 * subset and converts to {@link io.reliza.model.tea.TeaIdentifier}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RearmIdentifier {

	@JsonProperty
	private RearmIdentifierType idType;

	@JsonProperty
	private String idValue;

	/**
	 * Rejects identifiers whose value must come from a published vocabulary. Only
	 * {@link RearmIdentifierType#SPECIFICATION} is constrained today; every other type
	 * carries a value from somebody else's namespace (a purl, a CPE, a UDI, a compliance
	 * document standard) and is taken as given.
	 *
	 * <p>Called on write, never on read: stored records are deserialized as they stand, so a
	 * vocabulary that changes later cannot make existing data unreadable.
	 *
	 * @param identifiers the list about to be stored, may be null
	 * @throws IllegalArgumentException naming the offending value and the allowed set
	 */
	public static void validateVocabulary(List<RearmIdentifier> identifiers) {
		if (null == identifiers) return;
		for (RearmIdentifier ri : identifiers) {
			if (null == ri || RearmIdentifierType.SPECIFICATION != ri.getIdType()) continue;
			if (null == RearmSpecificationType.fromValue(ri.getIdValue())) {
				throw new IllegalArgumentException("Unknown SPECIFICATION identifier value '" + ri.getIdValue()
						+ "'; expected one of " + Arrays.toString(RearmSpecificationType.values()));
			}
		}
	}
}
