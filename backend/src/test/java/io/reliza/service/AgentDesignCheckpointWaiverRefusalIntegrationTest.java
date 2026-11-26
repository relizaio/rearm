/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import io.reliza.ws.App;

/**
 * Who may set the waiver (task TPF-152, W2, section 4.2, threat model T1): only a PASSED sign-off with a note, by a hop
 * whose role produces ARCHITECTURE, naming a design round that is not advisory and was declared by this hop or
 * published by a hop of the signer's role. Every refusal leaves the task as it was. T-32 (a) to (j).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointWaiverRefusalIntegrationTest extends AgentDesignCheckpointTestBase {

	static final String NOTE = "fits one coder pass";

	static String doesNotDesign(String role) {
		return "--no-split is the design author's: " + role + " does not produce ARCHITECTURE on this board";
	}

	/** The task is still held by {@code role}'s hop, with {@code signOffs} sign-offs and the waiver as given. */
	private void unchanged(AgentTaskData t, String role, int signOffs, DesignCheckpointWaiver waiver) {
		AgentTaskData now = reload(t);
		assertEquals(TaskStatus.ASSIGNED, now.getStatus());
		assertEquals(role, now.getAssignment().role());
		assertEquals(signOffs, now.getSignOffs().size(), "no sign-off added");
		assertEquals(waiver, now.getDesignCheckpointWaiver());
	}

	/** (a) A reviewer's --no-split is refused by the F-7 check. */
	@Test
	public void aReviewersNoSplitIsRefused() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "reviewer flags");
		design(r, t, NOTE);
		assign(r, t, r.functional());
		RelizaException e = refused(() -> signOffNoSplit(r.functional(), t, "fine", List.of()));
		assertEquals(doesNotDesign("functional_reviewer"), e.getMessage());
		unchanged(t, "functional_reviewer", 1, null);
	}

	/** (b) The coder's --no-split after SENT_ON is refused the same way. */
	@Test
	public void aCodersNoSplitIsRefused() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "coder flags");
		designNoSplit(r, t, NOTE);
		DesignCheckpointWaiver w = waiver(t);
		assign(r, t, r.coder());
		ReleaseData dd = publish(r.coder(), t, DD);
		RelizaException e = refused(() -> signOffNoSplit(r.coder(), t, "built", List.of(dd.getUuid())));
		assertEquals(doesNotDesign("coder"), e.getMessage());
		unchanged(t, "coder", 1, w);
	}

	/** (c) The architect's REJECTED --no-split is refused. */
	@Test
	public void aRejectionWithNoSplitIsRefused() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "rejected with the flag");
		assign(r, t, r.architect());
		ReleaseData arch = publish(r.architect(), t, ARCH);
		RelizaException e = refused(() -> agentTaskService.signOff(t.getUuid(), r.architect().sessionUuid(),
				SignOffOutcome.REJECTED, NOTE, List.of(arch.getUuid()), null, null, null, Boolean.TRUE, WU));
		assertEquals("--no-split says a passed design fits one coder pass; a rejection hands nothing over, so sign off"
				+ " REJECTED without it", e.getMessage());
		unchanged(t, "architect", 0, null);
	}

	/** (d) A hop that publishes no ARCHITECTURE on a task that has none is refused. */
	@Test
	public void noDesignRoundIsRefused() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "threat model only");
		assign(r, t, r.architect());
		ReleaseData tm = publish(r.architect(), t, TM);
		RelizaException e = refused(() -> signOffNoSplit(r.architect(), t, NOTE, List.of(tm.getUuid())));
		assertEquals("--no-split waives the design checkpoint for an ARCHITECTURE round; this hop publishes none and"
				+ " the task has none at or above ASSEMBLED", e.getMessage());
		assertTrue(e.getMessage().contains("ARCHITECTURE round"), e.getMessage());
		unchanged(t, "architect", 0, null);
	}

	/** (e) On a board without the flag the waiver is accepted and inert. */
	@Test
	public void onABoardWithoutTheFlagItIsAcceptedAndInert() throws RelizaException {
		Rig r = rig(false, false, HumanGate.NONE);
		AgentTaskData t = task(r, "no flagged role");
		List<ReleaseData> design = designNoSplit(r, t, NOTE);
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertNotNull(after.getDesignCheckpointWaiver());
		assertEquals(design.get(0).getUuid(), after.getDesignCheckpointWaiver().designRound());
		assertTrue(waivedInfos(r, after).isEmpty(), "no event");
		assertTrue(checkpointAlerts(r, after).isEmpty());
		assertNull(after.getDesignCheckpoint());
	}

	/**
	 * (f) A second design role cannot waive the architect's round; and its variant (F-11): the architect's own session
	 * holding the designer hop is refused the same way, the only row that reaches the producing-hop rule with the
	 * round's own session.
	 */
	@Test
	public void anotherDesignRoleCannotWaiveTheArchitectsRound() throws RelizaException {
		Rig r = plain();
		agentBoardService.upsertRoleConfig(board(r), role("designer", 15, HumanGate.NONE, List.of(),
				List.of(produces(ARCH)), null, null), true, WU);
		Worker designer = worker(r.org(), "designer");
		String expected = "--no-split is the design author's: the newest ARCHITECTURE round (round 1) was published by a"
				+ " hop of architect, not by a designer hop";

		AgentTaskData t = task(r, "designer flags");
		design(r, t, NOTE);
		assertEquals("designer", reload(t).getRole());
		assign(r, t, designer);
		RelizaException e = refused(() -> signOffNoSplit(designer, t, NOTE, List.of()));
		assertEquals(expected, e.getMessage());
		unchanged(t, "designer", 1, null);

		AgentTaskData v = task(r, "the architect's session holds the designer hop");
		design(r, v, NOTE);
		assertEquals("designer", reload(v).getRole());
		assign(r, v, r.architect());
		RelizaException same = refused(() -> signOffNoSplit(r.architect(), v, NOTE, List.of()));
		assertEquals(expected, same.getMessage());
		unchanged(v, "designer", 1, null);
	}

	/** (g) A --no-split with no note, or a blank one, is refused. */
	@Test
	public void aNoSplitWithoutANoteIsRefused() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "no note");
		assign(r, t, r.architect());
		ReleaseData arch = publish(r.architect(), t, ARCH);
		for (String note : new String[] {null, "   "}) {
			RelizaException e = refused(() -> signOffNoSplit(r.architect(), t, note, List.of(arch.getUuid())));
			assertEquals("--no-split needs a note: say in one line why the design fits one coder pass (--note '...')",
					e.getMessage());
			unchanged(t, "architect", 0, null);
		}
	}

	/** (h) The architect's session holding the functional reviewer's hop is refused by the F-7 check. */
	@Test
	public void theSessionIsNotTheRole() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "architect reviews");
		design(r, t, NOTE);
		assign(r, t, r.architect());
		assertEquals("functional_reviewer", reload(t).getAssignment().role());
		RelizaException e = refused(() -> signOffNoSplit(r.architect(), t, "fine", List.of()));
		assertEquals(doesNotDesign("functional_reviewer"), e.getMessage());
		unchanged(t, "functional_reviewer", 1, null);
	}

	/**
	 * (i) An advisory round is never waivable; and its variant (F-8): after a sign-off of the author's session follows
	 * the advisory round, the same refusal.
	 */
	@Test
	public void anAdvisoryRoundIsNotWaivable() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "advisory then no-split");
		designNoSplit(r, t, NOTE);
		DesignCheckpointWaiver w = waiver(t);
		publish(r.architect(), t, ARCH, true);
		codersQuestion(r, t);
		assign(r, t, r.architect());
		String expected = "--no-split cannot waive an advisory round: ARCHITECTURE round 2 was published --advisory,"
				+ " outside a hop; publish the round inside a hop, declare it and sign off --no-split";
		RelizaException e = refused(() -> signOffNoSplit(r.architect(), t, "as designed", List.of(), Boolean.TRUE));
		assertEquals(expected, e.getMessage());
		unchanged(t, "architect", 2, w);

		agentTaskService.signOff(t.getUuid(), r.architect().sessionUuid(), SignOffOutcome.PASSED, "as designed",
				List.of(), null, Boolean.TRUE, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(t).getStatus(), "the advisory round stops it");
		authorize(r, t, "architect", COORD);
		assign(r, t, r.architect());
		RelizaException again = refused(() -> signOffNoSplit(r.architect(), t, "as designed", List.of(), Boolean.TRUE));
		assertEquals(expected, again.getMessage(), "a later sign-off of the author's session does not make it waivable");
		unchanged(t, "architect", 3, w);
	}

	/** (j) A reviewer or coder hop declaring an ARCHITECTURE round of its own is refused by the F-7 check. */
	@Test
	public void aHopDeclaringItsOwnArchitectureRoundIsRefused() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "reviewer declares a design");
		design(r, t, NOTE);
		assign(r, t, r.functional());
		ReleaseData own = publish(r.functional(), t, ARCH);
		ReleaseData items = index(r.functional(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "PASSED", ARCH,
				"F-1", 3, "RESOLVED");
		RelizaException e = refused(() -> signOffNoSplit(r.functional(), t, "fine",
				List.of(own.getUuid(), items.getUuid())));
		assertEquals(doesNotDesign("functional_reviewer"), e.getMessage());
		unchanged(t, "functional_reviewer", 1, null);

		Rig p = plain();
		AgentTaskData c = task(p, "coder declares a design");
		designNoSplit(p, c, NOTE);
		DesignCheckpointWaiver w = waiver(c);
		assign(p, c, p.coder());
		ReleaseData arch = publish(p.coder(), c, ARCH);
		ReleaseData dd = publish(p.coder(), c, DD);
		RelizaException coder = refused(() -> signOffNoSplit(p.coder(), c, "built", List.of(arch.getUuid(), dd.getUuid())));
		assertEquals(doesNotDesign("coder"), coder.getMessage());
		unchanged(c, "coder", 1, w);
	}
}
