/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData.ReturnTo;
import io.reliza.model.AgentTaskRoleConfigData.CommissionIntake;
import io.reliza.model.AgentTaskRoleConfigData.Commissions;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.service.AgentTaskService.CommissionRequest;

/**
 * The commission input and a role's commissions as the fetcher reads them (task RD4-12), and the gate of the
 * programmatic verb: a BOARD_READ floor both callers clear, the rest checked per caller.
 */
class InvestigationInputParsingTest {

	@Test
	void aCommissionInputReadsEveryField() throws RelizaException {
		UUID from = UUID.randomUUID();
		UUID in = UUID.randomUUID();
		Map<String, Object> input = new HashMap<>();
		input.put("role", "tester");
		input.put("title", "measure the UI");
		input.put("brief", "how slow is it");
		input.put("fromTask", from.toString());
		input.put("inputs", List.of(in.toString()));
		input.put("budgetMicros", 3_000_000);
		input.put("deadline", OffsetDateTime.of(2030, 1, 2, 3, 4, 0, 0, ZoneOffset.UTC));
		input.put("review", "lead");
		input.put("returnTo", "NONE");
		CommissionRequest req = AgentTaskDataFetcher.commissionFromInput(input);
		assertEquals("tester", req.role());
		assertEquals("how slow is it", req.brief());
		assertEquals(from, req.fromTask());
		assertEquals(List.of(in), req.inputs());
		assertEquals(3_000_000L, req.budgetMicros());
		assertEquals(2030, req.deadline().getYear());
		assertEquals("lead", req.review());
		assertEquals(ReturnTo.NONE, req.returnTo());

		input.put("deadline", "2030-01-02T03:04:00Z");
		assertEquals(3, AgentTaskDataFetcher.commissionFromInput(input).deadline().getHour(), "an RFC 3339 string too");
		input.put("deadline", "tomorrow");
		assertThrows(RelizaException.class, () -> AgentTaskDataFetcher.commissionFromInput(input));
	}

	@Test
	void commissionsLeftOutAreUnchangedAndSentAsNullAreNone() throws RelizaException {
		assertNull(AgentTaskDataFetcher.commissionsFromInput(Map.of("name", "architect")), "left out: unchanged");
		Map<String, Object> cleared = new HashMap<>();
		cleared.put("commissions", null);
		assertEquals(new Commissions(List.of(), null, null, null), AgentTaskDataFetcher.commissionsFromInput(cleared),
				"sent as null: an empty block, stored as none");
		Map<String, Object> set = Map.of("commissions", Map.of("roles", List.of("tester"), "intake", "COORDINATOR",
				"defaultBudgetMicros", 5, "review", "lead"));
		assertEquals(new Commissions(List.of("tester"), CommissionIntake.COORDINATOR, 5L, "lead"),
				AgentTaskDataFetcher.commissionsFromInput(set));
	}

	@Test
	void theProgrammaticVerbHasAReadFloorAndChecksTheCallerAfter() {
		AgentTaskDataFetcher.BoardGate gate = AgentTaskDataFetcher.PROGRAMMATIC_BOARD_GATES.get("agentTaskCommissionProgrammatic");
		assertEquals(Set.of(PermissionFunction.BOARD_READ), Set.copyOf(gate.functions()));
		assertEquals(CallType.ESSENTIAL_READ, gate.callType());
	}
}
