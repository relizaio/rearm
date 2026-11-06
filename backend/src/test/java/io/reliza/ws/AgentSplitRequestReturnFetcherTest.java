/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.UserPermission.PermissionFunction;

/**
 * T-3, the fetcher's side (task SPLIT-9, D9; built by SPLIT-11): {@code agentTaskReturnProgrammatic} takes a proposal
 * only with reason SPLIT and refuses it before the service; the system-generated reasons stay refused; a SPLIT
 * return with a proposal goes through and is recorded. The rig is {@link AgentSelfHoldTestBase}.
 */
public class AgentSplitRequestReturnFetcherTest extends AgentSelfHoldTestBase {

	private static final String RETURN = "mutation($t: ID!, $s: ID!, $r: AgentTaskReturnReason!, $d: String,"
			+ " $p: [AgentTaskSplitChildInput!]) { agentTaskReturnProgrammatic(taskUuid: $t, sessionUuid: $s,"
			+ " reason: $r, description: $d, proposedChildren: $p) { status splitRequest { state role description"
			+ " proposedChildren { title dependsOnSiblingIndexes } } } }";

	private static final List<Map<String, Object>> TWO = List.of(Map.of("title", "backend"),
			Map.of("title", "ui", "dependsOnSiblingIndexes", List.of(0)));

	private ExecutionResult returnAs(Caller holder, AgentTaskData t, String reason, Object proposal) {
		Map<String, Object> vars = new HashMap<>(Map.of("t", t.getUuid().toString(),
				"s", holder.session().getUuid().toString(), "r", reason, "d", "too big"));
		if (null != proposal) vars.put("p", proposal);
		return asKey(holder.key(), RETURN, vars);
	}

	private void assertRefusedAndUntouched(ExecutionResult er, AgentTaskData t, Caller holder, String containing) {
		assertTrue(er.getErrors().toString().contains(containing), er.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.ASSIGNED, after.getStatus());
		assertEquals(holder.session().getUuid(), after.getAssignment().session());
		assertTrue(after.getReturns().isEmpty());
		assertNull(after.getSplitRequest());
	}

	@Test
	public void aProposalNeedsReasonSplit() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertRefusedAndUntouched(returnAs(holder, t, "OTHER", TWO), t, holder, "proposedChildren needs reason SPLIT");
		assertRefusedAndUntouched(returnAs(holder, t, "OTHER", List.of()), t, holder,
				"proposedChildren needs reason SPLIT");
	}

	@Test
	public void theSystemReasonsAreStillRefused() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertRefusedAndUntouched(returnAs(holder, t, "SESSION_CLOSED", null), t, holder,
				"SESSION_CLOSED returns are system-generated only");
		assertRefusedAndUntouched(returnAs(holder, t, "PARKED_FOR_OPERATOR", null), t, holder,
				"PARKED_FOR_OPERATOR returns are system-generated only");
	}

	@Test
	public void aSplitReturnWithAProposalIsRecorded() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		ExecutionResult er = returnAs(holder, t, "SPLIT", TWO);
		assertTrue(er.getErrors().isEmpty(), er.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertEquals(SplitRequestState.REQUESTED, after.getSplitRequest().state());
		assertEquals(List.of("backend", "ui"), after.getSplitRequest().proposedChildren().stream()
				.map(c -> c.title()).toList());
		assertEquals(List.of(0), after.getSplitRequest().proposedChildren().get(1).dependsOnSiblingIndexes());
		Map<String, Object> data = er.getData();
		@SuppressWarnings("unchecked")
		Map<String, Object> returned = (Map<String, Object>) data.get("agentTaskReturnProgrammatic");
		@SuppressWarnings("unchecked")
		Map<String, Object> sr = (Map<String, Object>) returned.get("splitRequest");
		assertEquals("REQUESTED", sr.get("state"));
		assertEquals("too big", sr.get("description"));
	}

	@Test
	public void aSplitReturnWithoutADescriptionIsRefused() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		ExecutionResult er = asKey(holder.key(), RETURN, Map.of("t", t.getUuid().toString(),
				"s", holder.session().getUuid().toString(), "r", "SPLIT"));
		assertRefusedAndUntouched(er, t, holder, "Return reason SPLIT requires a description");
	}
}
