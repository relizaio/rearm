/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.ComponentData.ComponentKind;

/**
 * The missing kind against the list GraphQL passes (TPF-100, mutation round 2, T-6): a mutable list, which
 * answers contains(null) with false instead of throwing, so a kind rule that stopped reading a missing kind as
 * GENERIC fails an assertion and not a NullPointerException.
 */
public class ComponentDataKindsMutableListTest {

	private static List<ComponentKind> mutable(ComponentKind... kinds) {
		return new ArrayList<>(List.of(kinds));
	}

	@Test
	public void aMissingKindIsGenericAgainstAMutableList() {
		assertTrue(ComponentData.isOfKinds((ComponentKind) null, mutable(ComponentKind.GENERIC)));
		assertFalse(ComponentData.isOfKinds((ComponentKind) null, mutable(ComponentKind.HELM)));
		assertTrue(ComponentData.isOfKinds((ComponentKind) null, mutable(ComponentKind.GENERIC, ComponentKind.HELM)));
	}

	@Test
	public void theDocumentFormReadsAMissingKindAsGenericToo() {
		ComponentData cd = new ComponentData();
		cd.setKind(null);
		assertTrue(ComponentData.isOfKinds(cd, mutable(ComponentKind.GENERIC)));
		assertFalse(ComponentData.isOfKinds(cd, mutable(ComponentKind.HELM)));
	}
}
