/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Autowired;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.AgentTaskRoleConfigData.RunWhen;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.AgentBoardService.RoleConfigSpec;
import io.reliza.service.AgentTaskInputService.InputVerdict;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;

/**
 * TPF-162 mutation round: what the carried-design classes leave open. Which roles a carry covers, a role the board no
 * longer has, a record naming a role by name only, a requirement the carried round must not stand in for (another
 * scope, a pin), the resolution of a requirement deciding whether an own draft ends the carried binding, the checks a
 * split makes on a parent (no build role, a report-only role, a design-stage role skipped by its condition, a build
 * role's requirement of another scope), a build role that is not flagged, and what completion counts as missing.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCarriedDesignEdgesIntegrationTest extends AgentCarriedDesignTestBase {

	static final RearmSpecificationType BTR = RearmSpecificationType.BOARD_TEST_REPORT;

	@Autowired OssReleaseService ossReleaseService;

	private InputVerdict verdictOf(Rig r, AgentTaskData t, String role, Predicate<RequiredInput> which) {
		return agentTaskInputService.evaluate(board(r), roleConfig(r, role), reload(t)).stream()
				.filter(v -> which.test(v.requirement())).findFirst().orElseThrow();
	}

	@Test
	public void onlyTheDesignStageRolesAreCarried() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "which roles carry");

		assertTrue(carryLive(r, c.flagged(), "architect"), "the control");
		assertFalse(carryLive(r, c.flagged(), "coder"), "the build role is not carried");
		assertFalse(carryLive(r, c.flagged(), "tester"), "nor the role after it");
	}

	@Test
	public void aCarriedRoleTheBoardNoLongerHasIsNotLiveAndOneNamedByNameAloneStillIs() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "ghost and nameless");
		AgentTaskData t = reload(c.flagged());
		CarriedRole real = carriedRole(t, "functional_reviewer");

		assertFalse(routingService.carryLive(t, new CarriedRole("ghost", UUID.randomUUID(), ZonedDateTime.now(), null, null, false)));
		assertTrue(routingService.carryLive(t, new CarriedRole("functional_reviewer", null, real.signedOffAt(), null, null, false)),
				"a record that names the role by name only is the same role");
		assertTrue(routingService.carryLive(t, real));
		assertTrue(routingService.carryLive(t, new CarriedRole("FUNCTIONAL_REVIEWER", null, real.signedOffAt(), null, null, false)),
				"a role named by name alone is matched without regard to case");
		assertTrue(routingService.carryLive(t, new CarriedRole("renamed since", real.roleUuid(), real.signedOffAt(), null, null, false)),
				"the role is the same row when its uuid is, whatever it was called");
		assertFalse(routingService.carryLive(t, new CarriedRole("functional_reviewer", UUID.randomUUID(), real.signedOffAt(), null, null, false)),
				"a role of that name that is another row is not the one that passed");
	}

	@Test
	public void aDesignThatIsNotCarriedIsNotStanding() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "standing");

		assertTrue(routingService.carryStanding(reload(c.flagged())), "the control");
		assertFalse(routingService.carryStanding(reload(c.plain())));
	}

	@Test
	public void aReviewersOtherScopeInputEndsNoCarry() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "a component-scope input");
		RequiredInput elsewhere = new RequiredInput(InputKind.DOCUMENT, DD, InputScope.COMPONENT, null, null, null);
		agentBoardService.upsertRoleConfig(board(r), role("functional_reviewer", 20, HumanGate.NONE,
				List.of(input(ARCH), input(TM), elsewhere), List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null),
				true, WU);

		code(r, c.flagged());

		assertTrue(carryLive(r, c.flagged(), "functional_reviewer"),
				"the coder's DETAILED_DESIGN is not a TASK-scope input of the reviewer, so it ends nothing");
	}

	@Test
	public void aReviewersPinnedInputEndsNoCarry() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "a pinned input");
		agentBoardService.upsertRoleConfig(board(r), role("functional_reviewer", 20, HumanGate.NONE,
				List.of(input(ARCH), input(TM), input(DD).pinnedTo(UUID.randomUUID())),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);

		code(r, c.flagged());

		assertTrue(carryLive(r, c.flagged(), "functional_reviewer"),
				"the reviewer reads one named release of DETAILED_DESIGN, so the coder's own round of it ends nothing");
	}

	@Test
	public void aReviewersReleaseKindInputEndsNoCarry() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "a release-kind input");
		agentBoardService.upsertRoleConfig(board(r), role("functional_reviewer", 20, HumanGate.NONE,
				List.of(input(ARCH), input(TM), new RequiredInput(InputKind.RELEASE, DD, InputScope.TASK, null, null, null)),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);

		code(r, c.flagged());

		assertTrue(carryLive(r, c.flagged(), "functional_reviewer"), "only a document the reviewer reads can end its carry");
	}

	@Test
	public void theHigherOfTwoFloorsOnOneDocumentDecidesWhatEndsACarry() throws RelizaException {
		Rig r = reviewed();
		Carried c = coderHolds(r, "two floors");
		agentBoardService.upsertRoleConfig(board(r), role("functional_reviewer", 20, HumanGate.NONE,
				List.of(input(ARCH), input(TM), inputAt(DD), input(DD)),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);

		publish(r.coder(), c.flagged(), DD);

		assertTrue(carryLive(r, c.flagged(), "functional_reviewer"),
				"a draft of DETAILED_DESIGN is below the ASSEMBLED floor the reviewer also states");
	}

	@Test
	public void aBuildRolesPinnedInputIsNotWhatTheDesignMustCarry() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "a pinned input on the build role");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), role("coder", 40, HumanGate.NONE,
				List.of(input(ARCH), input(TM), input(BTR).pinnedTo(UUID.randomUUID())),
				List.of(produces(DD)), CODER_HOP, Boolean.TRUE), true, WU);

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		assertTrue(reload(kids.get(0)).isDesignCarried());
	}

	@Test
	public void aPassThatReviewedNothingDoesNotCarryOverARoundTheReviewerNowReads() throws RelizaException {
		Rig r = reviewed();
		for (String name : List.of("functional_reviewer", "security_reviewer")) {
			agentBoardService.upsertRoleConfig(board(r), role(name, "functional_reviewer".equals(name) ? 20 : 30, HumanGate.NONE,
					List.of(), List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);
		}
		AgentTaskData parent = task(r, "reviewers that read nothing then");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), role("functional_reviewer", 20, HumanGate.NONE,
				List.of(input(ARCH), input(TM)), List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null),
				true, WU);

		RelizaException e = refused(() -> split(r, parent, flagged("part 1"), child("part 2")));

		assertRefusal(e, "part 1", "functional_reviewer's pass on ARCHITECTURE round 1; its pass reviewed no ARCHITECTURE round");
		assertNoChildren(parent);
	}

	@Test
	public void aCancelledRoundOfWhatAReviewerReadsEndsNoCarry() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "a cancelled round");
		agentBoardService.upsertRoleConfig(board(r), role("functional_reviewer", 20, HumanGate.NONE,
				List.of(input(ARCH), input(TM), input(DD)),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null, null), true, WU);

		code(r, c.flagged());
		assertFalse(carryLive(r, c.flagged(), "functional_reviewer"), "the control: the coder's round of what it reads ends the carry");

		ReleaseData round = reload(c.flagged()).getReleases().stream().map(this::release)
				.filter(rd -> null != rd.getDocument() && DD == rd.getDocument().specification()).findFirst().orElseThrow();
		ossReleaseService.updateReleaseLifecycle(round.getUuid(), ReleaseLifecycle.CANCELLED, WU);

		assertTrue(carryLive(r, c.flagged(), "functional_reviewer"), "a cancelled release is nobody's version, so it ends nothing");
	}

	@Test
	public void aRoleTheBoardHasSinceDeactivatedIsNotRecordedAsCarried() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "a reviewer deactivated after the pass");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec("functional_reviewer", null, null, null, null,
				Boolean.FALSE, null, null, null, null, null, null, null, null, null, null, null), true, WU);

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		assertEquals(List.of("architect", "security_reviewer"),
				reload(kids.get(0)).getCarriedDesign().roles().stream().map(CarriedRole::role).toList());
	}

	@Test
	public void thePersonWhoAcceptedAGatedPassIsKeptInTheCarriedRole() throws RelizaException {
		Rig r = rig(false, true, HumanGate.ON_PASS);
		AgentTaskData parent = task(r, "a gated pass");
		design(r, parent, "fits one coder pass");
		agentTaskService.humanReview(parent.getUuid(), true, "good", PERSON, WU);
		SignOff accepted = lastOf(parent, "architect");
		assertTrue(null != accepted.reviewedBy(), "the control: the parent's pass names the person");

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		assertEquals(accepted.reviewedBy(), carriedRole(reload(kids.get(0)), "architect").reviewedBy());
	}

	@Test
	public void aBuildRolesReleaseKindInputIsNotWhatTheDesignMustCarry() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "a release-kind input on the build role");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), role("coder", 40, HumanGate.NONE,
				List.of(input(ARCH), input(TM), new RequiredInput(InputKind.RELEASE, BTR, InputScope.TASK, null, null, null)),
				List.of(produces(DD)), CODER_HOP, Boolean.TRUE), true, WU);

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		assertTrue(reload(kids.get(0)).isDesignCarried());
	}

	@Test
	public void aBuildRolesDocumentInputNamingNothingIsNotWhatTheDesignMustCarry() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "a document input naming no specification");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), role("coder", 40, HumanGate.NONE,
				List.of(input(ARCH), input(TM), new RequiredInput(InputKind.DOCUMENT, null, InputScope.TASK, null, null, null)),
				List.of(produces(DD)), CODER_HOP, Boolean.TRUE), true, WU);

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		assertTrue(reload(kids.get(0)).isDesignCarried());
	}

	@Test
	public void aRequirementOfAnotherScopeIsNotStoodInFor() throws RelizaException {
		Rig r = reviewed();
		RequiredInput onTheNode = new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.COMPONENT, null,
				ReleaseLifecycle.ASSEMBLED, InputResolution.STRICT_LATEST);
		agentBoardService.upsertRoleConfig(board(r), role("tester", 50, HumanGate.NONE, List.of(input(DD), onTheNode),
				List.of(produces(BTR)), null, null), true, WU);
		Carried c = carried(r, "the node's ARCHITECTURE");
		Predicate<RequiredInput> node = ri -> InputScope.COMPONENT == ri.scope() && ARCH == ri.specification();
		assertTrue(verdictOf(r, c.flagged(), "tester", node).met(), "the control: the parent's round is the newest on the node");

		AgentTaskData other = task(r, "someone else's draft");
		assign(r, other, r.architect());
		publish(r.architect(), other, ARCH);

		InputVerdict v = verdictOf(r, c.flagged(), "tester", node);
		assertFalse(v.met(), "STRICT_LATEST on the node: another task's newer draft is below the floor, and the carried round is not stood in for it: " + v);
	}

	@Test
	public void aPinnedRequirementThatIsUnmetStaysUnmet() throws RelizaException {
		Rig r = reviewed();
		Carried c = carried(r, "a pin that names nothing");
		UUID ghost = UUID.randomUUID();
		agentTaskService.authorize(c.flagged().getUuid(), board(r), "coder", 10, null, null, null,
				List.of(input(ARCH).pinnedTo(ghost)), COORD, WU);

		InputVerdict v = verdictOf(r, c.flagged(), "coder", ri -> ghost.equals(ri.release()));
		assertFalse(v.met(), "the carried round does not stand in for a release the task was told to read: " + v);
	}

	@Test
	public void aStrictRequirementIsDecidedByAnOwnDraftNotByTheCarriedRound() throws RelizaException {
		Rig r = reviewedTpf();
		RequiredInput strict = new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.TASK, null, ReleaseLifecycle.ASSEMBLED,
				InputResolution.STRICT_LATEST);
		agentBoardService.upsertRoleConfig(board(r), role("coder", 40, HumanGate.NONE, List.of(strict, inputAt(TM)),
				List.of(produces(DD)), CODER_HOP, Boolean.TRUE), true, WU);
		Carried c = coderHolds(r, "strict latest");
		AgentTaskData t = c.flagged();
		Predicate<RequiredInput> own = ri -> InputResolution.STRICT_LATEST == ri.resolution() && ARCH == ri.specification();
		InputVerdict before = verdictOf(r, t, "coder", own);
		assertTrue(before.met(), "the control: no own round, so the carried one: " + before);
		assertEquals(c.design().get(0).getUuid(), before.resolved().release());

		coderRejects(r, t, ARCH);
		assign(r, t, r.architect());
		publish(r.architect(), t, ARCH);
		agentTaskService.returnTask(t.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "not done", WU);
		authorize(r, t, "coder", COORD);

		InputVerdict after = verdictOf(r, t, "coder", own);
		assertFalse(after.met(),
				"STRICT_LATEST: the own draft is the newest round and is below the floor, the carried one is not used: " + after);
	}

	@Test
	public void aBoardWithNoBuildRoleRefusesTheFlag() throws RelizaException {
		Rig r = rig(false, false, HumanGate.NONE);
		AgentTaskData parent = task(r, "no build role");
		design(r, parent, "fits one coder pass");

		RelizaException e = refused(() -> split(r, parent, flagged("part 1"), child("part 2")));

		assertRefusal(e, "part 1", "a build role on the board (coordinatorCheckpoint or CODE_PUSH)");
		assertNoChildren(parent);
	}

	@Test
	public void aReportOnlyRoleBeforeTheBuildRoleIsNotPartOfTheDesignStage() throws RelizaException {
		Rig r = reviewed();
		agentBoardService.upsertRoleConfig(board(r), role("investigator", 15, HumanGate.NONE, List.of(),
				List.of(produces(RearmSpecificationType.BOARD_INVESTIGATION_REPORT)), null, null), true, WU);

		Carried c = carried(r, "an investigator sits before the build role");

		assertTrue(c.flagged().isDesignCarried());
		assertTrue(c.flagged().getCarriedDesign().roleFor(null, "investigator") == null);
	}

	@Test
	public void aDesignRoleTheParentSkippedByItsConditionDoesNotRefuseTheFlag() throws RelizaException {
		Rig r = reviewed();
		agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec("functional_reviewer", null, null, null, null, null,
				null, null, RoleNecessity.OPTIONAL, null, null, null, null, null, null, null, null,
				Optional.of(new RunWhen(List.of("review"), List.of()))), true, WU);
		AgentTaskData parent = task(r, "the functional reviewer is skipped");
		design(r, parent, "fits one coder pass");
		pass(r, parent, r.security());
		assertTrue(reload(parent).checkpointPending(), "the parent stands at the checkpoint");

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		AgentTaskData flagged = reload(kids.get(0));
		assertTrue(flagged.isDesignCarried());
		assertEquals(List.of("architect", "security_reviewer"),
				flagged.getCarriedDesign().roles().stream().map(CarriedRole::role).toList());
	}

	@Test
	public void aBuildRolesInputOfAnotherScopeIsNotWhatTheDesignMustCarry() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData parent = task(r, "a component-scope input on the build role");
		atCheckpoint(r, parent);
		agentBoardService.upsertRoleConfig(board(r), role("coder", 40, HumanGate.NONE,
				List.of(input(ARCH), input(TM), new RequiredInput(InputKind.DOCUMENT, BTR, InputScope.COMPONENT, null, null, null)),
				List.of(produces(DD)), CODER_HOP, Boolean.TRUE), true, WU);

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		assertTrue(reload(kids.get(0)).isDesignCarried());
	}

	@Test
	public void aBuildRoleThatIsNotFlaggedLeavesTheChildNoCheckpointRecord() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData parent = task(r, "the build role holds CODE_PUSH only");
		design(r, parent, "fits one coder pass");

		List<AgentTaskData> kids = split(r, parent, flagged("part 1"), child("part 2"));

		AgentTaskData flagged = reload(kids.get(0));
		assertTrue(flagged.isDesignCarried());
		assertNull(flagged.getDesignCheckpoint(), "no role carries the checkpoint, so there is nothing to settle");
	}

	@Test
	public void completionCountsOnlyTheRolesTheChildStillOwes() throws RelizaException {
		Rig r = reviewed();
		for (String name : List.of("architect", "functional_reviewer", "security_reviewer", "coder", "tester")) {
			agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec(name, null, null, null, null, null, null, null,
					RoleNecessity.REQUIRED, null, null, null, null, null, null, null, null), true, WU);
		}
		Carried c = carried(r, "what is still owed");

		assertEquals(List.of("coder", "tester"), agentTaskService.missingRequiredRoles(reload(c.flagged())));
		assertEquals(List.of("coder", "tester"),
				agentTaskService.missingRequiredRolesForPerson(reload(c.flagged()), board(r)));
		assertEquals(List.of("architect", "functional_reviewer", "security_reviewer", "coder", "tester"),
				agentTaskService.missingRequiredRoles(reload(c.plain())));
	}
}
