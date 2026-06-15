/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;

/**
 * Guards the superset contract between {@link RearmIdentifierType} and the
 * OpenAPI-generated {@link TeaIdentifierType}: every TEA type must exist
 * (same name) in the ReARM enum, otherwise a TEA spec regen would silently
 * break the boundary filter in {@link TeaTransformerService#toTeaIdentifiers}.
 */
class TeaIdentifierMappingTest {

	@Test
	void rearmEnumIsSupersetOfTeaEnum() {
		for (TeaIdentifierType teaType : TeaIdentifierType.values()) {
			RearmIdentifierType.valueOf(teaType.name()); // throws if missing
		}
	}

	@Test
	void boundaryFilterKeepsTeaTypesAndDropsInternalOnes() {
		List<RearmIdentifier> mixed = List.of(
				new RearmIdentifier(RearmIdentifierType.PURL, "pkg:generic/acme/widget@1.0.0"),
				new RearmIdentifier(RearmIdentifierType.TEI, "urn:tei:uuid:example.com:1234"),
				new RearmIdentifier(RearmIdentifierType.CPE, "cpe:2.3:a:acme:widget:1.0.0:*:*:*:*:*:*:*"),
				new RearmIdentifier(RearmIdentifierType.UDI, "(01)00844588003288(11)141231"),
				new RearmIdentifier(RearmIdentifierType.UDI_DI, "00844588003288"),
				new RearmIdentifier(RearmIdentifierType.SERIAL, "SN-0001"),
				new RearmIdentifier(RearmIdentifierType.LOT, "LOT-42"),
				new RearmIdentifier(null, "typeless"),
				new RearmIdentifier(RearmIdentifierType.PURL, null));
		List<TeaIdentifier> tea = TeaTransformerService.toTeaIdentifiers(mixed);
		assertEquals(3, tea.size());
		assertTrue(tea.stream().anyMatch(ti -> ti.getIdType() == TeaIdentifierType.PURL));
		assertTrue(tea.stream().anyMatch(ti -> ti.getIdType() == TeaIdentifierType.TEI));
		assertTrue(tea.stream().anyMatch(ti -> ti.getIdType() == TeaIdentifierType.CPE));
	}

	@Test
	void boundaryFilterHandlesNullList() {
		assertTrue(TeaTransformerService.toTeaIdentifiers(null).isEmpty());
	}
}
