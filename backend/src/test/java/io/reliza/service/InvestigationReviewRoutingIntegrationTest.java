/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * How an investigation routes, and how it leaves work tasks alone (task RD4-12): a review round goes to the
 * reviewing role, back to the investigator with its review items, and completes on the review's pass; a work task never
 * routes to a role that only investigates; and a role that also investigates keeps its work-task contract.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class InvestigationReviewRoutingIntegrationTest extends InvestigationTestBase {

	@Test
	public void aReviewedReportGoesToTheReviewerBackToTheInvestigatorAndCompletesOnThePass() throws RelizaException {
		Rig r = rig();
		AgentTaskData from = held(r, "architect", r.architect());
		AgentTaskData inv = commissionedFrom(r, from, ask("tester", from.getUuid(), "lead"));
		assertEquals("lead", inv.getInvestigation().review());

		investigate(r, r.tester(), inv);
		AgentTaskData toReview = reload(inv);
		assertEquals(TaskStatus.QUEUED, toReview.getStatus());
		assertEquals("lead", toReview.getRole(), "the report goes to the reviewer first");
		assertTrue(reload(from).getReportsReturned().isEmpty(), "nothing returns before the review passes");

		agentTaskService.assign(inv.getUuid(), board(r), r.lead().agentUuid(), r.lead().sessionUuid(), WU);
		ReleaseData rejected = reviewItems(r.lead(), reload(inv), "REJECTED", "F-1", "OPEN");
		agentTaskService.signOff(inv.getUuid(), r.lead().sessionUuid(), SignOffOutcome.REJECTED, "thin",
				List.of(rejected.getUuid()), WU);
		AgentTaskData back = reload(inv);
		assertEquals("tester", back.getRole(), "the investigator made the report, so the review items go to it");
		assertEquals(TaskStatus.QUEUED, back.getStatus());

		ReleaseData second = investigate(r, r.tester(), inv);
		assertEquals("lead", reload(inv).getRole(), "the fixed report goes back to the reviewer");

		agentTaskService.assign(inv.getUuid(), board(r), r.lead().agentUuid(), r.lead().sessionUuid(), WU);
		ReleaseData passed = reviewItems(r.lead(), reload(inv), "PASSED", "F-1", "RESOLVED");
		AgentTaskData done = agentTaskService.signOff(inv.getUuid(), r.lead().sessionUuid(), SignOffOutcome.PASSED, "ok",
				List.of(passed.getUuid()), WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(second.getUuid(), done.getInvestigation().report(), "the newest report returns");
		assertTrue(reload(from).getRequiredInputs().stream().anyMatch(ri -> second.getUuid().equals(ri.release())));
	}

	@Test
	public void aWorkTaskNeverRoutesToARoleThatOnlyInvestigates() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = held(r, "architect", r.architect());
		ReleaseData design = publish(r.architect(), t, RearmSpecificationType.ARCHITECTURE);
		agentTaskService.signOff(t.getUuid(), r.architect().sessionUuid(), SignOffOutcome.PASSED, "designed",
				List.of(design.getUuid()), WU);
		AgentTaskData next = reload(t);
		assertEquals(TaskStatus.QUEUED, next.getStatus());
		assertEquals("coder", next.getRole(), "the researcher (order 5) only investigates; the coder is next");
		assertTrue(AgentRoutingService.investigatesOnly(roleNamed(r, "researcher")));
		assertFalse(AgentRoutingService.investigatesOnly(roleNamed(r, "tester")));
	}

	@Test
	public void aRoleThatAlsoInvestigatesStillReviewsAndOwesNoReportOnWork() throws RelizaException {
		Rig r = rig();
		assertTrue(AgentRoutingService.isReviewer(roleNamed(r, "tester")),
				"BOARD_TEST_REPORT alone decides: the report is what it makes when asked");
		// The researcher declares the report required; authorized on a work task by hand, it passes without one.
		AgentTaskData t = agentTaskService.register(board(r), null, "look at it", null, null, null, null, null, null,
				PERSON, false, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "researcher", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.researcher().agentUuid(), r.researcher().sessionUuid(), WU);
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.researcher().sessionUuid(), SignOffOutcome.PASSED,
				"nothing to report", List.of(), WU);
		assertFalse(TaskStatus.ASSIGNED == passed.getStatus(), "the pass went through");
	}
}
