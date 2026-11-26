/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpointWaiver;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.service.AgentBoardService.RoleConfigSpec;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.ws.App;

/**
 * TPF-152 mutation round: what the waiver classes leave open. The note is cut at its limit in the record and the INFO,
 * a false flag waives nothing, a waiver stands only on a board with a flagged role and no reopen newer than it, the
 * newer of two rounds declared in one hop is the waived one, another role's round cannot be waived, a rejected round
 * keeps the waiver, a role that is inactive does not make a board blind, and the declarative apply of the switch
 * refuses an actor that is no one.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointWaiverEdgesIntegrationTest extends AgentDesignCheckpointTestBase {

	static final String NOTE = "fits one coder pass";

	@Autowired AgentRoutingService agentRoutingService;

	/** A role spec that changes only the coordinator checkpoint flag of an existing role. */
	private static RoleConfigSpec flag(String name, Boolean checkpoint) {
		return new RoleConfigSpec(name, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, checkpoint);
	}

	@Test
	public void aLongNoteIsCutAtTheLimitInTheRecordAndTheInfoButKeptWholeOnTheWaiver() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "long note");
		String note = "n".repeat(150) + "m".repeat(150);
		designNoSplit(r, t, note);

		AgentTaskData after = reload(t);
		String cut = note.substring(0, AgentRoutingService.CHECKPOINT_NOTE_CHARS);
		assertEquals("waived by architect: " + cut, after.getDesignCheckpoint().note());
		assertEquals(List.of(waiverInfo(after, "ARCHITECTURE round 1", "ASSEMBLED", cut)), waivedInfos(r, after));
		assertEquals(note, after.getDesignCheckpointWaiver().note(), "the waiver keeps the whole note");
	}

	@Test
	public void aFalseNoSplitWaivesNothing() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "false flag");
		assign(r, t, r.architect());
		ReleaseData arch = publish(r.architect(), t, ARCH);
		ReleaseData tm = publish(r.architect(), t, TM);
		agentTaskService.signOff(t.getUuid(), r.architect().sessionUuid(), SignOffOutcome.PASSED, NOTE,
				List.of(arch.getUuid(), tm.getUuid()), null, null, null, Boolean.FALSE, WU);

		AgentTaskData after = reload(t);
		assertNull(after.getDesignCheckpointWaiver());
		assertNull(after.getSignOffs().get(after.getSignOffs().size() - 1).noSplit());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertEquals(1, checkpointAlerts(r, after).size());
		assertTrue(waivedInfos(r, after).isEmpty());
	}

	@Test
	public void aWaiverStandsOnlyOnABoardWithAFlaggedRole() throws RelizaException {
		Rig unflagged = rig(false, false, HumanGate.NONE);
		AgentTaskData t = task(unflagged, "no flagged role");
		designNoSplit(unflagged, t, NOTE);
		assertNotNull(waiver(t), "the flag is recorded whatever the board");
		assertFalse(agentRoutingService.waiverStands(reload(t), board(unflagged)), "no flagged role: nothing to waive");

		Rig flagged = plain();
		AgentTaskData t2 = task(flagged, "flagged role");
		designNoSplit(flagged, t2, NOTE);
		assertTrue(agentRoutingService.waiverStands(reload(t2), board(flagged)), "the control: it stands here");
		assertFalse(agentRoutingService.waiverStands(reload(t2), null), "no board: it does not");
	}

	@Test
	public void aReopenWhileNoRoleWasFlaggedLeavesAWaiverThatDoesNotStandOnceARoleIsFlaggedAgain() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "reopened while unflagged");
		designNoSplit(r, t, NOTE);
		code(r, t);
		assertEquals(TaskStatus.COMPLETED, pass(r, t, r.tester()).getStatus());
		DesignCheckpointWaiver w = waiver(t);
		assertTrue(agentRoutingService.waiverStands(reload(t), board(r)));

		agentBoardService.upsertRoleConfig(board(r), flag("coder", Boolean.FALSE), true, WU);
		agentTaskService.reopen(t.getUuid(), "architect", "the design missed a case", COORD, WU);
		assertEquals(w, waiver(t), "no flagged role at the reopen: the waiver is not cleared");
		agentBoardService.upsertRoleConfig(board(r), flag("coder", Boolean.TRUE), true, WU);

		assertFalse(agentRoutingService.waiverStands(reload(t), board(r)), "a reopen newer than the waiver ends it");
	}

	@Test
	public void ofTwoRoundsDeclaredInOneHopTheNewerIsWaived() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "two rounds in one hop");
		assign(r, t, r.architect());
		ReleaseData first = publish(r.architect(), t, ARCH);
		ReleaseData second = publish(r.architect(), t, ARCH);
		ReleaseData tm = publish(r.architect(), t, TM);
		signOffNoSplit(r.architect(), t, NOTE, List.of(first.getUuid(), second.getUuid(), tm.getUuid()));

		assertEquals(second.getUuid(), waiver(t).designRound(), "the newer round of the hop");
	}

	@Test
	public void anotherRolesRoundCannotBeWaivedByARoleThatAlsoProducesTheType() throws RelizaException {
		Rig r = reviewed();
		agentBoardService.upsertRoleConfig(board(r), role("security_reviewer", 30, HumanGate.NONE,
				List.of(input(ARCH), input(TM)),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS), produces(ARCH)), null, null), true, WU);
		AgentTaskData t = task(r, "another role's round");
		design(r, t, NOTE);
		pass(r, t, r.functional());
		assign(r, t, r.security());

		RelizaException e = refused(() -> signOffNoSplit(r.security(), t, "mine to waive", List.of()));
		assertTrue(e.getMessage().contains("was published by a hop of architect, not by a security_reviewer hop"),
				e.getMessage());
		assertNull(waiver(t));
	}

	@Test
	public void aRejectedRoundThatDeclaresADesignKeepsTheWaiver() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "rejected round");
		designNoSplit(r, t, NOTE);
		DesignCheckpointWaiver w = waiver(t);
		codersQuestion(r, t);
		assign(r, t, r.architect());
		ReleaseData round2 = publish(r.architect(), t, ARCH);
		signOff(r.architect(), t, SignOffOutcome.REJECTED, "round 2 is not ready", List.of(round2.getUuid()));

		assertEquals(w, waiver(t), "only a PASSED round without the flag clears it");
	}

	@Test
	public void anInactiveBlindRoleDoesNotMakeTheBoardBlind() throws RelizaException {
		Rig r = reviewed();
		agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec("functional_reviewer", null, null, null, null,
				Boolean.FALSE, null, null, null, null, null, null, null, null, Boolean.TRUE), true, WU);
		AgentTaskData t = task(r, "inactive blind role");
		designNoSplit(r, t, NOTE);
		pass(r, t, r.security());

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus(), after.getStatusHistory().toString());
		assertEquals(List.of(waiverInfo(after, "ARCHITECTURE round 1", "READY_TO_SHIP", NOTE)), waivedInfos(r, after),
				"the note is quoted: no active role is blind");
	}

	@Test
	public void theSwitchRefusesAnActorThatIsNoOne() throws RelizaException {
		Rig r = plain();
		Map<String, Object> settings = new HashMap<>();
		settings.put("architectCheckpointWaiver", Boolean.FALSE);
		Map<String, Object> spec = new HashMap<>();
		spec.put("kind", "BOARD");
		spec.put("version", 1);
		spec.put("name", r.board().getName());
		spec.put("settings", settings);

		ApplyResult result = agentBoardService.applyBoard(r.org().getUuid(), AgentBoardService.boardSpecFromInput(spec),
				false, null, null, WU);

		assertTrue(result.getErrors() > 0, result.getChanges().toString());
		assertTrue(result.getChanges().stream().anyMatch(c -> AgentBoardService.SWITCH_OPERATOR_ONLY.equals(c.getMessage())),
				result.getChanges().toString());
		assertNull(board(r).getArchitectCheckpointWaiver(), "nothing of the file is applied");
	}
}
