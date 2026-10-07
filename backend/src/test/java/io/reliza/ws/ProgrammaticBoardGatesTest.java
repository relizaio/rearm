/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.netflix.graphql.dgs.DgsData;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.UserPermission.PermissionFunction;

/**
 * Every programmatic field of the agent board fetcher has a board permission class (architecture
 * d8e7bd7e §3.1). A new field without one fails here, not in production, where it would be refused.
 */
class ProgrammaticBoardGatesTest {

	private static Set<String> programmaticFields() {
		Set<String> out = new TreeSet<>();
		for (Method m : AgentTaskDataFetcher.class.getDeclaredMethods()) {
			DgsData d = m.getAnnotation(DgsData.class);
			if (null != d && d.field().endsWith("Programmatic")) out.add(d.field());
		}
		return out;
	}

	@Test
	void everyProgrammaticFieldIsClassifiedAndNothingElseIs() {
		Set<String> fields = programmaticFields();
		assertEquals(44, fields.size(), fields.toString());
		assertEquals(fields, new TreeSet<>(AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.keySet()),
				"the gate map and the fetcher's programmatic fields must be the same set");
	}

	@Test
	void theTiersAreTheirFloors() {
		AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.forEach((field, gate) -> {
			boolean writes = gate.functions().contains(PermissionFunction.BOARD_WRITE);
			assertEquals(writes ? CallType.WRITE : CallType.ESSENTIAL_READ, gate.callType(), field);
			assertTrue(gate.functions().stream().anyMatch(fn -> fn.name().startsWith("BOARD_")),
					field + " names a board function");
		});
		assertEquals(Set.of(PermissionFunction.CONFIGURATION_WRITE, PermissionFunction.BOARD_WRITE),
				Set.copyOf(AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.get("agentTaskRoleConfigSetProgrammatic").functions()));
		assertEquals(Set.of(PermissionFunction.BOARD_AGENT),
				Set.copyOf(AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.get("agentDocumentPublishProgrammatic").functions()));
	}
}
