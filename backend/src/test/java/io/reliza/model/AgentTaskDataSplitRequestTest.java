/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData.SplitRequest;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.SplitChild;

/**
 * T-1 (task SPLIT-9, built by SPLIT-11): the split request's record in JSONB and the lapse hook in
 * {@code transitionStatus}. Rows written before it read as no record; the record round-trips with its children;
 * settling stamps the time; every way out of the coordinator's wait that is not a hold lapses a REQUESTED record, and
 * nothing else is touched.
 */
public class AgentTaskDataSplitRequestTest {

	static final AgentActor ROUTING = AgentActor.system("routing");

	static SplitRequest requested() {
		return new SplitRequest(ZonedDateTime.now(), "coder", UUID.randomUUID(), UUID.randomUUID(), "too big",
				List.of(), SplitRequestState.REQUESTED, null, null, null);
	}

	/** A task waiting on the coordinator with a REQUESTED record, routed last to the coder. */
	static AgentTaskData waiting(SplitRequest sr) {
		AgentTaskData td = new AgentTaskData();
		td.setStatus(TaskStatus.AWAITING_COORDINATOR);
		td.setRole("coder");
		td.setSplitRequest(sr);
		return td;
	}

	@Test
	public void aTaskWrittenBeforeTheRecordReadsNone() {
		AgentTaskData td = Utils.OM.convertValue(Map.of("title", "old", "status", "QUEUED"), AgentTaskData.class);
		assertNull(td.getSplitRequest());
		assertFalse(td.splitRequested());
	}

	@Test
	public void theRecordRoundTripsWithItsChildren() {
		SplitChild a = new SplitChild("backend", "github:acme/widget#42", "https://example.test/42", 2, null, List.of(),
				"the server side", "backend", List.of(new TagRecord("area", "api", null)));
		SplitChild b = new SplitChild("ui", null, null, null, null, List.of(0), null, null, null);
		SplitRequest sr = new SplitRequest(ZonedDateTime.now(), "coder", UUID.randomUUID(), UUID.randomUUID(),
				"too big: two repositories", List.of(a, b), SplitRequestState.REQUESTED, null, null, null);
		AgentTaskData td = new AgentTaskData();
		td.setSplitRequest(sr);
		assertTrue(td.splitRequested());

		AgentTaskData back = Utils.OM.convertValue(Utils.OM.convertValue(td, Map.class), AgentTaskData.class);
		SplitRequest read = back.getSplitRequest();
		assertEquals("coder", read.role());
		assertEquals(sr.roleUuid(), read.roleUuid());
		assertEquals(sr.session(), read.session());
		assertEquals("too big: two repositories", read.description());
		assertEquals(SplitRequestState.REQUESTED, read.state());
		assertTrue(sr.requestedAt().isEqual(read.requestedAt()));
		assertEquals(2, read.proposedChildren().size());
		SplitChild ra = read.proposedChildren().get(0);
		assertEquals("backend", ra.title());
		assertEquals("github:acme/widget#42", ra.externalRef());
		assertEquals("https://example.test/42", ra.sourceUrl());
		assertEquals(2, ra.workLevel());
		assertEquals("the server side", ra.description());
		assertEquals("backend", ra.group());
		assertEquals("area", ra.tags().get(0).key());
		assertEquals("api", ra.tags().get(0).value());
		SplitChild rb = read.proposedChildren().get(1);
		assertEquals(List.of(0), rb.dependsOnSiblingIndexes());
		assertNull(rb.tags());
		assertNull(rb.group());
		assertTrue(back.splitRequested());
	}

	@Test
	public void aRecordWithoutChildrenReadsAnEmptyList() {
		SplitRequest sr = Utils.OM.convertValue(Map.of("role", "coder", "state", "REQUESTED"), SplitRequest.class);
		assertEquals(List.of(), sr.proposedChildren());
		assertEquals(List.of(), new SplitRequest(null, "coder", null, null, null, null, SplitRequestState.REQUESTED,
				null, null, null).proposedChildren());
	}

	@Test
	public void settlingStampsTheTimeAndKeepsTheRest() {
		AgentActor seat = AgentActor.ofSession(UUID.randomUUID());
		SplitRequest sr = requested();
		ZonedDateTime before = ZonedDateTime.now();
		SplitRequest settled = sr.withSettled(SplitRequestState.DECLINED, seat, "one pass is fine");
		assertEquals(SplitRequestState.DECLINED, settled.state());
		assertFalse(settled.requested());
		assertEquals(seat, settled.settledBy());
		assertEquals("one pass is fine", settled.note());
		assertNotNull(settled.settledAt());
		assertFalse(settled.settledAt().isBefore(before));
		assertEquals(sr.requestedAt(), settled.requestedAt());
		assertEquals(sr.description(), settled.description());
		assertEquals(sr.session(), settled.session());
	}

	@Test
	public void requestedIsTrueOnlyForRequested() {
		for (SplitRequestState s : SplitRequestState.values()) {
			SplitRequest sr = new SplitRequest(null, "coder", null, null, null, null, s, null, null, null);
			assertEquals(SplitRequestState.REQUESTED == s, sr.requested(), s.name());
		}
	}

	@Test
	public void aSplitReturnRowReads() {
		TaskReturn tr = Utils.OM.convertValue(Map.of("role", "coder", "reason", "SPLIT", "description", "too big"),
				TaskReturn.class);
		assertEquals(TaskReturnReason.SPLIT, tr.reason());
		assertEquals("too big", tr.description());
	}

	@Test
	public void routingTheTaskOnLapsesTheRequestWithTheRoleAndTheNote() {
		AgentTaskData td = waiting(requested());
		ZonedDateTime before = ZonedDateTime.now();
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, ROUTING, "open F-1; back to coder");
		SplitRequest sr = td.getSplitRequest();
		assertEquals(SplitRequestState.LAPSED, sr.state());
		assertEquals(ROUTING, sr.settledBy());
		assertEquals("routed to coder: open F-1; back to coder", sr.note());
		assertNotNull(sr.settledAt());
		assertFalse(sr.settledAt().isBefore(before));
		assertFalse(td.splitRequested());
	}

	@Test
	public void routingWithoutANoteLapsesWithTheRoleAlone() {
		AgentTaskData td = waiting(requested());
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, ROUTING);
		assertEquals("routed to coder", td.getSplitRequest().note());
	}

	@Test
	public void completingOrCancellingLapsesTheRequest() {
		AgentTaskData done = waiting(requested());
		done.transitionStatus(TaskStatus.COMPLETED, StatusTrigger.COMPLETE, ROUTING);
		assertEquals(SplitRequestState.LAPSED, done.getSplitRequest().state());
		assertEquals("completed", done.getSplitRequest().note());

		AgentTaskData gone = waiting(requested());
		gone.transitionStatus(TaskStatus.CANCELLED, StatusTrigger.CANCEL, ROUTING);
		assertEquals(SplitRequestState.LAPSED, gone.getSplitRequest().state());
		assertEquals("cancelled", gone.getSplitRequest().note());
	}

	@Test
	public void anyOtherExitLapsesWithTheStatusWord() {
		AgentTaskData td = waiting(requested());
		td.transitionStatus(TaskStatus.DELIVERING, StatusTrigger.DELIVER_WAIT, ROUTING);
		assertEquals(SplitRequestState.LAPSED, td.getSplitRequest().state());
		assertEquals("delivering", td.getSplitRequest().note());
	}

	@Test
	public void aHoldOverTheWaitLeavesTheRequestStanding() {
		SplitRequest sr = requested();
		AgentTaskData td = waiting(sr);
		td.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, ROUTING);
		assertSame(sr, td.getSplitRequest());
		assertTrue(td.splitRequested());
		// and the lift back into the wait leaves it too
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.LIFT_HOLD, ROUTING);
		assertSame(sr, td.getSplitRequest());
	}

	@Test
	public void theSplitRowInTheWaitLeavesTheRequestStanding() {
		SplitRequest sr = requested();
		AgentTaskData td = waiting(sr);
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.SPLIT, ROUTING);
		assertSame(sr, td.getSplitRequest());
	}

	@Test
	public void aSettledRecordIsUntouchedByEveryTransition() {
		SplitRequest declined = requested().withSettled(SplitRequestState.DECLINED, ROUTING, "authorized for coder");
		for (TaskStatus to : List.of(TaskStatus.QUEUED, TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.ON_HOLD)) {
			AgentTaskData td = waiting(declined);
			td.transitionStatus(to, StatusTrigger.AUTHORIZE, ROUTING, "note");
			assertSame(declined, td.getSplitRequest(), to.name());
		}
	}

	@Test
	public void noRecordStaysNoRecord() {
		AgentTaskData td = waiting(null);
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, ROUTING);
		assertNull(td.getSplitRequest());
	}
}
