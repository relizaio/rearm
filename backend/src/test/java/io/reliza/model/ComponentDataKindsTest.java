/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.ComponentData.ComponentKind;

/**
 * TPF-100 (architecture round 1 section 9.2, test 6): the kinds rule on a kind alone, and the
 * ComponentData form delegating to it.
 */
public class ComponentDataKindsTest {

	private static List<ComponentKind> kindsAndNull() {
		List<ComponentKind> all = new ArrayList<>(Arrays.asList(ComponentKind.values()));
		all.add(null);
		return all;
	}

	private static List<List<ComponentKind>> kindsLists() {
		return Arrays.asList(null, List.of(), List.of(ComponentKind.GENERIC), List.of(ComponentKind.HELM),
				List.of(ComponentKind.GENERIC, ComponentKind.HELM), List.of(ComponentKind.BOARD_DOCUMENT));
	}

	@Test
	public void noKindsNamedMatchesEveryKind() {
		for (ComponentKind k : kindsAndNull()) {
			assertTrue(ComponentData.isOfKinds(k, null), String.valueOf(k));
			assertTrue(ComponentData.isOfKinds(k, List.of()), String.valueOf(k));
		}
	}

	@Test
	public void aMissingKindIsGeneric() {
		assertTrue(ComponentData.isOfKinds((ComponentKind) null, List.of(ComponentKind.GENERIC)));
		assertFalse(ComponentData.isOfKinds((ComponentKind) null, List.of(ComponentKind.HELM)));
	}

	@Test
	public void theBoardDocumentKindIsOnlyItself() {
		assertFalse(ComponentData.isOfKinds(ComponentKind.BOARD_DOCUMENT, List.of(ComponentKind.GENERIC, ComponentKind.HELM)));
		assertTrue(ComponentData.isOfKinds(ComponentKind.BOARD_DOCUMENT, List.of(ComponentKind.BOARD_DOCUMENT)));
	}

	@Test
	public void theDocumentFormAnswersAsTheKindForm() {
		for (ComponentKind k : kindsAndNull()) {
			ComponentData cd = new ComponentData();
			cd.setKind(k);
			for (List<ComponentKind> kinds : kindsLists()) {
				assertEquals(ComponentData.isOfKinds(k, kinds), ComponentData.isOfKinds(cd, kinds), k + " " + kinds);
			}
		}
	}
}
