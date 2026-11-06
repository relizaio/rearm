/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * Helpers the split request tests share (task SPLIT-9, built by SPLIT-11), on the design checkpoint's boards: a SPLIT
 * return, a split, and what the feed and the children say about it. No tests.
 */
abstract class AgentSplitRequestTestBase extends AgentDesignCheckpointTestBase {

	static final String WHY = "too big: two repositories";

	static SplitChild child(String title, Integer... dependsOn) {
		return new SplitChild(title, null, null, null, null, List.of(dependsOn));
	}

	/** {@code w}, holding the task, returns it SPLIT with {@code why} and {@code proposal} (null for none). */
	AgentTaskData returnSplit(Worker w, AgentTaskData t, String why, List<SplitChild> proposal) throws RelizaException {
		return agentTaskService.returnTask(t.getUuid(), w.sessionUuid(), TaskReturnReason.SPLIT, why, List.of(), proposal,
				WU);
	}

	/** {@code w} takes the task and returns it SPLIT. */
	AgentTaskData takeAndReturnSplit(Rig r, AgentTaskData t, Worker w, List<SplitChild> proposal) throws RelizaException {
		assign(r, t, w);
		AgentTaskData back = returnSplit(w, t, WHY, proposal);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		return back;
	}

	List<AgentTaskData> split(Rig r, AgentTaskData t, SplitChild... children) throws RelizaException {
		return agentTaskService.split(t.getUuid(), board(r), List.of(children), COORD, WU);
	}

	/** "A, B": the children's keys as the split's note and INFO name them. */
	static String keys(List<AgentTaskData> children) {
		return children.stream().map(AgentTaskData::keyOrUuid).collect(Collectors.joining(", "));
	}

	/** The releases a child pins, in order; each a DOCUMENT pin at floor ASSEMBLED. */
	List<UUID> pins(AgentTaskData child) {
		List<RequiredInput> in = null == reload(child).getRequiredInputs() ? List.of() : reload(child).getRequiredInputs();
		for (RequiredInput ri : in) {
			assertEquals(InputKind.DOCUMENT, ri.kind(), ri.toString());
			assertEquals(ReleaseLifecycle.ASSEMBLED, ri.minLifecycle(), ri.toString());
		}
		return in.stream().map(RequiredInput::release).toList();
	}

	/** Every child of the split pins exactly {@code expected}, in order. */
	void assertEveryChildPins(List<AgentTaskData> children, List<ReleaseData> expected) {
		List<UUID> want = expected.stream().map(ReleaseData::getUuid).toList();
		for (AgentTaskData c : children) assertEquals(want, pins(c), c.keyOrUuid());
	}

	/** The split INFOs on the board naming the task. */
	List<String> splitInfos(Rig r, AgentTaskData t) {
		return messages(r, BoardEventKind.INFO, " split into ").stream().filter(m -> m.contains(t.label())).toList();
	}

	/** The split INFO the design's D3 reads. */
	static String splitInfo(AgentTaskData t, List<AgentTaskData> children, String rounds) {
		return "Task " + t.label() + " split into " + children.size() + " children: " + keys(children) + "; "
				+ (null == rounds ? "no design round to carry" : "each carries " + rounds);
	}

	/** The return ALERTs on the board naming the task. */
	List<String> returnAlerts(Rig r, AgentTaskData t) {
		return messages(r, BoardEventKind.ALERT, " returned by ").stream().filter(m -> m.contains(t.label())).toList();
	}

	/** The decline INFOs on the board naming the task. */
	List<String> declineInfos(Rig r, AgentTaskData t) {
		return messages(r, BoardEventKind.INFO, "declined the split request").stream()
				.filter(m -> m.contains(t.label())).toList();
	}

	/** "ARCHITECTURE round 1 (ASSEMBLED)": a round as the INFO names it. */
	static String round(ReleaseData rd) {
		return rd.getDocument().specification() + " round " + rd.getDocument().round() + " (" + rd.getLifecycle() + ")";
	}
}
