/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * How a split child's carried passes end (task TPF-161, D4, D5, D8; built by TPF-162): a reopen to a design role, the
 * child's own round of a carried document, and a review item about the design, which takes the child through its own
 * design stage and back to the checkpoint. Rows T-9 to T-11, T-23 and T-25 to T-27 of the TPF-161 architecture,
 * round 6 (TPF-162 architecture, round 1).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCarriedDesignRoutingIntegrationTest extends AgentCarriedDesignTestBase {

	/** The flagged child through the coder and the tester, completed (T-8). */
	AgentTaskData completed(Rig r, Carried c) throws RelizaException {
		AgentTaskData t = c.flagged();
		code(r, t);
		pass(r, t, r.tester());
		if (TaskStatus.COMPLETED != reload(t).getStatus()) agentTaskService.complete(t.getUuid(), "done", COORD, WU);
		assertEquals(TaskStatus.COMPLETED, reload(t).getStatus());
		return reload(t);
	}

	/** T-9 to T-11: a reopen to the architect ends its carry, its own round ends the reviewers', and the child stops. */
	@Test
	public void aReopenToTheArchitectTakesTheChildThroughItsOwnDesign() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "carried, reopened");
		AgentTaskData t = completed(r, c);

		// T-9
		agentTaskService.reopen(t.getUuid(), "architect", "the design misses a case", COORD, WU);
		AgentTaskData reopened = reload(t);
		assertFalse(carryLive(r, t, "architect"));
		assertTrue(carryLive(r, t, "functional_reviewer"));
		assertTrue(carryLive(r, t, "security_reviewer"));
		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		assertEquals("architect", reopened.getRole());
		assertFalse(checkpointStands(r, t));

		// T-10
		ReleaseData own = redesign(r, t, ARCH);
		assertFalse(carryLive(r, t, "functional_reviewer"));
		assertFalse(carryLive(r, t, "security_reviewer"));
		AgentTaskData atReviewer = reload(t);
		assertEquals(TaskStatus.QUEUED, atReviewer.getStatus(), atReviewer.getStatusHistory().toString());
		assertEquals("functional_reviewer", atReviewer.getRole());
		assertEquals(own.getUuid(), binding(r, t, "coder", ARCH), "the own round, not the carried one");
		assertEquals(c.design().get(1).getUuid(), binding(r, t, "coder", TM), "THREAT_MODEL is still the carried round");

		// T-11
		reviewsPass(r, t);
		AgentTaskData stopped = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), stopped.getStatusHistory().toString());
		assertTrue(stopped.checkpointPending());
		assertEquals(List.of(own.getUuid(), c.design().get(1).getUuid()), stopped.getDesignCheckpoint().design());
		assertEquals(1, checkpointAlerts(r, stopped).size());
	}

	/**
	 * T-23 (TPF board): an architect hop that leaves an own ARCHITECTURE draft ends the architect's carry only; the
	 * coder's review item round ends no reviewer's; the coder still binds the carried round and the checkpoint stands.
	 */
	@Test
	public void anOwnDraftEndsOnlyTheArchitectsCarry() throws RelizaException {
		Rig r = reviewedTpf();
		Carried c = coderHolds(r, "carried, a draft left behind");
		AgentTaskData t = c.flagged();
		coderRejects(r, t, ARCH);
		assertEquals("architect", reload(t).getRole());
		assign(r, t, r.architect());
		ReleaseData draft = publish(r.architect(), t, ARCH);
		assertEquals(ReleaseData.ReleaseLifecycle.DRAFT, release(draft.getUuid()).getLifecycle());
		agentTaskService.returnTask(t.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "not done", WU);
		authorize(r, t, "coder", COORD);

		assertFalse(carryLive(r, t, "architect"), "an own round of a carried document the architect produces");
		assertTrue(carryLive(r, t, "functional_reviewer"), "the coder's review items end no carry");
		assertTrue(carryLive(r, t, "security_reviewer"), "the coder's review items end no carry");
		assertTrue(checkpointStands(r, t), "a draft does not unsettle the checkpoint");
		assign(r, t, r.coder());
		List<UUID> bound = reload(t).getAssignment().resolvedInputs().stream().map(ri -> ri.release()).toList();
		assertTrue(bound.contains(c.design().get(0).getUuid()), "the carried ARCHITECTURE: " + bound);
		assertFalse(bound.contains(draft.getUuid()), "the draft is below the floor: " + bound);
	}

	/** T-25: a coder's review item about ARCHITECTURE runs the child's own design stage and stops it at the checkpoint. */
	@Test
	public void aCodersReviewItemAboutTheDesignRunsTheChildsOwnDesignStage() throws RelizaException {
		Rig r = reviewed();
		Carried c = coderHolds(r, "carried, the coder rejects");
		AgentTaskData t = c.flagged();
		coderRejects(r, t, ARCH);
		AgentTaskData toArchitect = reload(t);
		assertEquals(TaskStatus.QUEUED, toArchitect.getStatus());
		assertEquals("architect", toArchitect.getRole());
		assertTrue(toArchitect.getReopens().isEmpty(), "no reopen is written");

		ReleaseData own = redesign(r, t, ARCH);
		AgentTaskData toFunctional = reload(t);
		assertEquals("functional_reviewer", toFunctional.getRole());
		assertEquals(1, messages(r, BoardEventKind.INFO, "architect passed with a new round; to functional_reviewer")
				.stream().filter(m -> m.contains(toFunctional.label())).count());
		pass(r, t, r.functional());
		assertEquals("security_reviewer", reload(t).getRole());
		pass(r, t, r.security());
		AgentTaskData stopped = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), stopped.getStatusHistory().toString());
		assertTrue(stopped.checkpointPending());
		assertEquals(List.of(own.getUuid(), c.design().get(1).getUuid()), stopped.getDesignCheckpoint().design());
		for (String role : DESIGN_ROLES) assertFalse(carryLive(r, t, role), role);
		assertFalse(routingService.carryStanding(stopped));
	}

	/** T-26: a tester's review item about ARCHITECTURE does the same; the coder's stale pass then runs it again. */
	@Test
	public void aTestersReviewItemAboutTheDesignRunsItAndTheCoderAgain() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "carried, the tester rejects");
		AgentTaskData t = c.flagged();
		code(r, t);
		testRejects(r, t, ARCH, "T-1");
		assertEquals("architect", reload(t).getRole());
		redesign(r, t, ARCH);
		reviewsPass(r, t);
		AgentTaskData stopped = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), stopped.getStatusHistory().toString());
		assertTrue(stopped.checkpointPending());

		AgentTaskData toCoder = authorize(r, t, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, toCoder.getStatus());
		assertEquals("coder", toCoder.getRole());
		assertEquals(SignOffOutcome.PASSED, toCoder.lastSignOffForRole(null, "coder").outcome(),
				"the coder's last pass is stale, not absent");
		code(r, t);
		assertEquals("tester", reload(t).getRole());
	}

	/**
	 * T-27 (TPF board): a THREAT_MODEL round of its own goes to security_reviewer only; functional_reviewer, which reads
	 * ARCHITECTURE, keeps its carry, and the checkpoint stands, so the child goes back to the coder.
	 */
	@Test
	public void anOwnThreatModelRoundEndsOnlyTheCarriesThatReadIt() throws RelizaException {
		Rig r = reviewedTpf();
		Carried c = coderHolds(r, "carried, the threat model");
		AgentTaskData t = c.flagged();
		coderRejects(r, t, TM);
		assertEquals("architect", reload(t).getRole());
		redesign(r, t, TM);
		assertEquals("security_reviewer", reload(t).getRole(), "builderAfter skips functional_reviewer");
		pass(r, t, r.security());
		AgentTaskData toCoder = reload(t);
		assertEquals(TaskStatus.QUEUED, toCoder.getStatus(), toCoder.getStatusHistory().toString());
		assertEquals("coder", toCoder.getRole());
		assertTrue(checkpointStands(r, t), "no ARCHITECTURE round was published");
		assertNull(toCoder.lastSignOffForRole(null, "functional_reviewer"), "functional_reviewer never ran");
		assertTrue(carryLive(r, t, "functional_reviewer"));
		assertFalse(carryLive(r, t, "architect"));
		assertFalse(carryLive(r, t, "security_reviewer"));
	}

	/** T-9's record half: the checkpoint the split wrote survives until the design changes under it. */
	@Test
	public void theSplitsCheckpointRecordStandsUntilTheDesignChanges() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "carried, the record stands");
		DesignCheckpoint written = c.flagged().getDesignCheckpoint();
		code(r, c.flagged());
		assertEquals(written, reload(c.flagged()).getDesignCheckpoint());
		assertTrue(checkpointStands(r, c.flagged()));
	}
}
