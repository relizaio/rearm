/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.tea.TeaIdentifierType;

public class RearmIdentifierVocabularyTest {

	private static RearmIdentifier id(RearmIdentifierType t, String v) {
		return new RearmIdentifier(t, v);
	}

	@Test
	void specificationValuesMustComeFromTheVocabulary() {
		assertDoesNotThrow(() -> RearmIdentifier.validateVocabulary(
				List.of(id(RearmIdentifierType.SPECIFICATION, "REQUIREMENTS"))));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> RearmIdentifier.validateVocabulary(List.of(id(RearmIdentifierType.SPECIFICATION, "requirements"))));
		assertTrue(e.getMessage().contains("requirements"), "the message names the offending value");
		assertTrue(e.getMessage().contains("REQUIREMENTS"), "the message names the allowed set");
	}

	@Test
	void everyOtherTypeCarriesSomebodyElsesNamespace() {
		// purls, CPEs, UDIs and compliance standards are not ours to enumerate
		assertDoesNotThrow(() -> RearmIdentifier.validateVocabulary(List.of(
				id(RearmIdentifierType.PURL, "pkg:npm/left-pad@1.0.0"),
				id(RearmIdentifierType.COMPLIANCE_DOCUMENT, "SOC_2_TYPE_II"),
				id(RearmIdentifierType.UDI, "(01)00819320081011"),
				id(RearmIdentifierType.CPE, "cpe:2.3:a:vendor:product:1.0"))));
		assertDoesNotThrow(() -> RearmIdentifier.validateVocabulary(null));
	}

	@Test
	void specificationIsInternalToRearm() {
		// the TEA boundary keeps only types the generated TEA enum knows; SPECIFICATION is ours
		// until TEA standardises a document identifier of its own
		assertTrue(Arrays.stream(TeaIdentifierType.values())
				.noneMatch(t -> "SPECIFICATION".equals(t.name())));
	}

	@Test
	void fromValueMatchesExactlyAndReportsUnknown() {
		assertEquals(RearmSpecificationType.CONOPS, RearmSpecificationType.fromValue("CONOPS"));
		assertNull(RearmSpecificationType.fromValue("Conops"));
		assertNull(RearmSpecificationType.fromValue(null));
	}
}
