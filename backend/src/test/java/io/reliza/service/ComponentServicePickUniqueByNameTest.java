/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;

/**
 * Pins the by-name resolution used when a CLI caller passes a component or
 * product name instead of a uuid: case-insensitive, whitespace-trimmed, active
 * only, and exactly one hit -- unknown or ambiguous names are errors.
 */
public class ComponentServicePickUniqueByNameTest {

	private static ComponentData comp(String name, StatusEnum status) {
		ComponentData cd = mock(ComponentData.class);
		when(cd.getUuid()).thenReturn(UUID.randomUUID());
		when(cd.getName()).thenReturn(name);
		when(cd.getStatus()).thenReturn(status);
		return cd;
	}

	@Test
	void exactlyOneCaseInsensitiveHitWins() throws RelizaException {
		ComponentData cd = comp("Reliza CD", StatusEnum.ACTIVE);
		List<ComponentData> all = List.of(comp("Reliza", StatusEnum.ACTIVE), cd, comp("Reliza Harbor", StatusEnum.ACTIVE));
		assertEquals(cd.getUuid(), ComponentService.pickUniqueByName(all, "reliza cd").getUuid());
		assertEquals(cd.getUuid(), ComponentService.pickUniqueByName(all, "  Reliza CD ").getUuid());
	}

	@Test
	void archivedNamesakesDoNotCount() throws RelizaException {
		ComponentData live = comp("card-shuffle", StatusEnum.ACTIVE);
		List<ComponentData> all = List.of(comp("card-shuffle", StatusEnum.ARCHIVED), live);
		assertEquals(live.getUuid(), ComponentService.pickUniqueByName(all, "card-shuffle").getUuid());
	}

	@Test
	void unknownAndAmbiguousNamesError() {
		List<ComponentData> all = List.of(comp("dup", StatusEnum.ACTIVE), comp("dup", StatusEnum.ACTIVE), comp("other", StatusEnum.ACTIVE));
		RelizaException none = assertThrows(RelizaException.class, () -> ComponentService.pickUniqueByName(all, "missing"));
		assertTrue(none.getMessage().contains("No active component or product named 'missing'"));
		RelizaException many = assertThrows(RelizaException.class, () -> ComponentService.pickUniqueByName(all, "DUP"));
		assertTrue(many.getMessage().contains("ambiguous"));
		assertThrows(RelizaException.class, () -> ComponentService.pickUniqueByName(all, "  "));
	}
}
