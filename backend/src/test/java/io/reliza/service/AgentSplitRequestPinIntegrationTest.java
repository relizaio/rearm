/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;

/**
 * What a split pins on its children (task SPLIT-9, D1-D3, 3.6; built by SPLIT-11): the parent's newest handed-over
 * design, computed at the split, whatever the status and the checkpoint record say. T-4, T-5, T-7, T-8, T-9 (b) and
 * T-20. The rigs, one per arm of D2's boundary: the unflagged board whose coder holds CODE_PUSH ({@link #unflagged()}:
 * T-4, T-5, T-8 a, T-20), the flagged boards ({@link #plain()}: T-7; {@link #reviewed()}: T-9 b, which needs its
 * functional reviewer), and a docs-only board with no boundary at all ({@link #docsOnly()}: T-8 b).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSplitRequestPinIntegrationTest extends AgentSplitRequestTestBase {

	@Autowired OssReleaseService ossReleaseService;

	static final String BOTH_ASSEMBLED = "ARCHITECTURE round 1 (ASSEMBLED), " + TM + " round 1 (ASSEMBLED)";

	/** T-4: on a board without the flag a split after a SPLIT return pins the architect's two rounds on every child. */
	@Test
	public void aSplitAfterAReturnPinsTheDesignWithoutTheFlag() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "split after a return");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		takeAndReturnSplit(r, t, r.coder(), List.of(child("A"), child("B", 0)));

		List<AgentTaskData> children = split(r, t, child("A"), child("B", 0));

		assertEveryChildPins(children, design);
		AgentTaskData parent = reload(t);
		assertNull(parent.getDesignCheckpoint(), "no checkpoint on this board, and the split writes none");
		assertEquals(SplitRequestState.SPLIT, parent.getSplitRequest().state());
		assertEquals(COORD, parent.getSplitRequest().settledBy());
		assertEquals("2 children: " + keys(children), parent.getSplitRequest().note());
		assertEquals(List.of(splitInfo(parent, children, BOTH_ASSEMBLED)), splitInfos(r, parent));
	}

	/** T-5: a DRAFT round a returned hop left is skipped for the previous handed-over round; a passed one is pinned. */
	@Test
	public void aDraftRoundIsSkippedForTheLastHandedOverOne() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "draft left by a return");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		takeAndReturnSplit(r, t, r.coder(), null);
		authorize(r, t, "architect", COORD);
		assign(r, t, r.architect());
		ReleaseData draft = publish(r.architect(), t, ARCH);
		agentTaskService.returnTask(t.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "not finished",
				List.of(), WU);
		assertEquals(ReleaseData.ReleaseLifecycle.DRAFT, release(draft.getUuid()).getLifecycle());

		List<AgentTaskData> first = split(r, t, child("A"), child("B"));
		assertEveryChildPins(first, design);
		assertEquals(List.of(splitInfo(t, first, BOTH_ASSEMBLED)), splitInfos(r, t));

		authorize(r, t, "architect", COORD);
		ReleaseData third = redesign(r, t, ARCH);
		assertEquals(3, third.getDocument().round());
		List<AgentTaskData> second = split(r, t, child("C"));
		assertEveryChildPins(second, List.of(third, design.get(1)));
		assertTrue(splitInfos(r, t).contains(splitInfo(t, second, "ARCHITECTURE round 3 (ASSEMBLED), " + TM
				+ " round 1 (ASSEMBLED)")), splitInfos(r, t).toString());
	}

	/** T-7: with the flag the boundary is the flagged coder: never DETAILED_DESIGN; a SENT_ON record is left alone. */
	@Test
	public void theFlaggedRoleIsTheBoundary() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "flagged boundary");
		sentOn(r, t);
		DesignCheckpoint sent = checkpoint(t);
		code(r, t);
		takeAndReturnSplit(r, t, r.tester(), null);

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));

		List<UUID> design = sent.design();
		for (AgentTaskData c : children) assertEquals(design, pins(c), "ARCHITECTURE and " + TM + " only");
		DesignCheckpoint after = checkpoint(t);
		assertEquals(DesignCheckpointState.SENT_ON, after.state(), "a split settles only a PENDING record");
		assertTrue(sent.settledAt().isEqual(after.settledAt()), "settledAt unchanged");
		assertEquals(List.of(splitInfo(t, children, BOTH_ASSEMBLED)), splitInfos(r, t));
	}

	/** T-8 (a): without the flag the first CODE_PUSH role is the boundary: the coder's DETAILED_DESIGN is not pinned. */
	@Test
	public void theFirstCodePushRoleIsTheBoundaryWithoutTheFlag() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "code push boundary");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		code(r, t);
		takeAndReturnSplit(r, t, r.tester(), null);

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEveryChildPins(children, design);
	}

	/** T-8 (b): a board with no builder and no flag has no boundary: every role's prose is the design. */
	@Test
	public void aDocsOnlyBoardPinsEveryProseRound() throws RelizaException {
		Rig r = docsOnly();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "docs only", null,
				null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "writer", 10, null, null, null, null, COORD, WU);
		assign(r, t, r.architect());
		ReleaseData arch = publish(r.architect(), t, ARCH);
		signOff(r.architect(), t, SignOffOutcome.PASSED, "written", List.of(arch.getUuid()));
		assign(r, t, r.coder());
		ReleaseData interfaces = publish(r.coder(), t, RearmSpecificationType.INTERFACES);
		signOff(r.coder(), t, SignOffOutcome.PASSED, "edited", List.of(interfaces.getUuid()));
		assertEquals("reviewer", reload(t).getRole());
		takeAndReturnSplit(r, t, r.tester(), null);

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEveryChildPins(children, List.of(arch, interfaces));
		assertEquals(List.of(splitInfo(t, children, "ARCHITECTURE round 1 (ASSEMBLED), INTERFACES round 1 (ASSEMBLED)")),
				splitInfos(r, t));
	}

	/**
	 * T-9 (b), F-3: a record left stale by a mid-review redesign: the split pins the newest round at or above
	 * ASSEMBLED, not the record's, and the INFO names each lifecycle. On the reviewed board, the case's functional
	 * reviewer being one of its roles.
	 */
	@Test
	public void aStaleCheckpointRecordIsNotWhatTheSplitPins() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "stale record");
		AgentTaskData pending = atCheckpoint(r, t);
		List<UUID> recorded = pending.getDesignCheckpoint().design();
		authorize(r, t, "architect", COORD);
		assertTrue(checkpoint(t).pending(), "authorizing the architect keeps it PENDING");
		ReleaseData second = redesign(r, t, ARCH);
		assign(r, t, r.functional());
		agentTaskService.returnTask(t.getUuid(), r.functional().sessionUuid(), TaskReturnReason.OTHER, "too big",
				List.of(), WU);
		AgentTaskData back = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertTrue(back.checkpointPending());
		assertEquals(recorded, back.getDesignCheckpoint().design(), "the record still names round 1");

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		for (AgentTaskData c : children) assertEquals(List.of(second.getUuid(), recorded.get(1)), pins(c));
		assertEquals(List.of(splitInfo(t, children, "ARCHITECTURE round 2 (ASSEMBLED), " + TM
				+ " round 1 (READY_TO_SHIP)")), splitInfos(r, t));
		assertEquals(DesignCheckpointState.SPLIT, checkpoint(t).state());
	}

	/** T-20 (a), F-9: the coder's own ARCHITECTURE round, passed and ASSEMBLED, is not the design: round 1 is pinned. */
	@Test
	public void aLaterRolesOwnDesignRoundIsNotPinned() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "coder's own round");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		assign(r, t, r.coder());
		ReleaseData coders = publish(r.coder(), t, ARCH);
		assertEquals("coder", coders.getDocument().publishedByRole());
		ReleaseData dd = publish(r.coder(), t, DD);
		signOff(r.coder(), t, SignOffOutcome.PASSED, "built", List.of(coders.getUuid(), dd.getUuid()));
		assertEquals(ReleaseData.ReleaseLifecycle.ASSEMBLED, release(coders.getUuid()).getLifecycle());
		takeAndReturnSplit(r, t, r.tester(), null);

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEveryChildPins(children, design);
		assertEquals(List.of(splitInfo(t, children, BOTH_ASSEMBLED)), splitInfos(r, t));
	}

	/** T-20 (b): an advisory round a design role vouched for is pinned as that role's. */
	@Test
	public void anAdvisoryDesignRoundIsPinned() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "advisory round");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		assign(r, t, r.coder());
		ReleaseData advisory = publish(r.architect(), t, ARCH, true);
		assertEquals("architect", advisory.getDocument().publishedByRole());
		assertEquals(ReleaseData.ReleaseLifecycle.ASSEMBLED, release(advisory.getUuid()).getLifecycle());
		returnSplit(r.coder(), t, WHY, null);

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEveryChildPins(children, List.of(advisory, design.get(1)));
		assertEquals(List.of(splitInfo(t, children, "ARCHITECTURE round 2 (ASSEMBLED), " + TM + " round 1 (ASSEMBLED)")),
				splitInfos(r, t));
	}

	/**
	 * T-20 (c), as architecture round 3 section 4.1 fixes it: the same for the second design spec. The tester's
	 * THREAT_MODEL round makes the coder's pass no longer current, so routing queues the coder, who returns SPLIT; the
	 * tester's round is skipped for round 1.
	 */
	@Test
	public void aLaterRolesOwnThreatModelRoundIsNotPinned() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "tester's own round");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		code(r, t);
		assign(r, t, r.tester());
		ReleaseData testers = publish(r.tester(), t, TM);
		signOff(r.tester(), t, SignOffOutcome.PASSED, "tested", List.of(testers.getUuid()));
		assertEquals(ReleaseData.ReleaseLifecycle.ASSEMBLED, release(testers.getUuid()).getLifecycle());
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus(), after.getStatusHistory().toString());
		assertEquals("coder", after.getRole(), "the coder reads THREAT_MODEL, so its pass is no longer current");
		takeAndReturnSplit(r, t, r.coder(), null);

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEveryChildPins(children, design);
	}

	/** T-20 (d): a round with no publishedByRole (the shape from before it was written) is skipped, fail closed. */
	@Test
	public void aRoundWithoutAPublisherIsSkipped() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = task(r, "unattributed round");
		List<ReleaseData> design = design(r, t, "fits one coder pass");
		assign(r, t, r.coder());
		agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER, "design gap",
				List.of(), WU);
		authorize(r, t, "architect", COORD);
		ReleaseData second = redesign(r, t, ARCH);
		withoutPublisher(second);
		assertNull(release(second.getUuid()).getDocument().publishedByRole());

		List<AgentTaskData> children = split(r, t, child("A"), child("B"));
		assertEveryChildPins(children, design);
	}

	/** Rewrites a round's document as it was written before #750: no publishedByRole. */
	void withoutPublisher(ReleaseData rd) throws RelizaException {
		Release row = sharedReleaseService.getRelease(rd.getUuid()).orElseThrow();
		ReleaseData data = ReleaseData.dataFromRecord(row);
		DocumentRef d = data.getDocument();
		data.setDocument(new DocumentRef(d.specification(), d.path(), d.digest(), d.mediaType(), d.indexPath(),
				d.indexDigest(), d.task(), d.session(), d.round(), d.reviewItems(), d.elements(), d.elementChecks(),
				d.advisory(), null, d.supersededBy()));
		ossReleaseService.saveRelease(row, data, WU, false);
	}

	/**
	 * T-8 (b)'s board: writer (10, produces ARCHITECTURE) -> editor (20, reads it, produces INTERFACES) -> reviewer
	 * (30, reads INTERFACES, produces review items only), no CODE_PUSH anywhere and no flag. The reviewer keeps the
	 * task from completing at the editor's pass and is the one that returns SPLIT. The writer sits in the rig's
	 * architect slot, the editor in its coder slot, the reviewer in its tester slot.
	 */
	Rig docsOnly() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("docs_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "docs-" + UUID.randomUUID(), "docs only",
				List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, role("writer", 10, HumanGate.NONE, List.of(), List.of(produces(ARCH)),
				null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("editor", 20, HumanGate.NONE, List.of(input(ARCH)),
				List.of(produces(RearmSpecificationType.INTERFACES)), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("reviewer", 30, HumanGate.NONE,
				List.of(input(RearmSpecificationType.INTERFACES)),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		Worker writer = worker(org, "writer");
		Worker editor = worker(org, "editor");
		return new Rig(org, board, writer, null, null, editor, worker(org, "reviewer"));
	}

}
