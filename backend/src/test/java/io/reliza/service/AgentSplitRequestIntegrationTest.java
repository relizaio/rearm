/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SplitRequest;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.ws.App;

/**
 * The split request (task SPLIT-9, D4, D6, D9; built by SPLIT-11): a SPLIT return records the request and alerts the
 * coordinator, every field is checked before anything is written, and a second request rewrites the record. T-2,
 * T-3 (the service side) and T-14, all on the unflagged board whose coder holds CODE_PUSH ({@link #unflagged()}).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSplitRequestIntegrationTest extends AgentSplitRequestTestBase {

	/** A task on the unflagged board whose design has passed, queued for the coder. */
	AgentTaskData designed(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		design(r, t, "fits one coder pass");
		AgentTaskData queued = reload(t);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals("coder", queued.getRole());
		return queued;
	}

	/** T-2: the request is recorded, the return row kept, and one ALERT names the why and the proposed titles. */
	@Test
	public void aSplitReturnRecordsTheRequestAndAlertsTheCoordinator() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "split request");
		SplitChild backend = new SplitChild("backend part", null, null, null, null, List.of(), "the server", "backend",
				List.of(new TagRecord("area", "api")));
		SplitChild ui = child("ui part", 0);
		AgentTaskData back = takeAndReturnSplit(r, t, r.coder(), List.of(backend, ui));

		TaskReturn row = back.getReturns().get(back.getReturns().size() - 1);
		assertEquals(TaskReturnReason.SPLIT, row.reason());
		assertEquals(WHY, row.description());
		assertEquals("coder", row.role());
		assertNotNull(row.usage(), "the hop's usage");
		SplitRequest sr = reload(t).getSplitRequest();
		assertEquals(SplitRequestState.REQUESTED, sr.state());
		assertEquals("coder", sr.role());
		assertEquals(r.coder().sessionUuid(), sr.session());
		assertNotNull(sr.requestedAt());
		assertEquals(WHY, sr.description());
		assertNull(sr.settledBy());
		assertNull(sr.settledAt());
		assertEquals(2, sr.proposedChildren().size());
		SplitChild first = sr.proposedChildren().get(0);
		assertEquals("backend part", first.title());
		assertEquals("the server", first.description());
		assertEquals("backend", first.group());
		assertEquals("area", first.tags().get(0).key());
		assertEquals("api", first.tags().get(0).value());
		assertEquals(List.of(), first.dependsOnSiblingIndexes());
		assertEquals("ui part", sr.proposedChildren().get(1).title());
		assertEquals(List.of(0), sr.proposedChildren().get(1).dependsOnSiblingIndexes());
		assertNull(sr.proposedChildren().get(1).tags(), "no tags given: the child would inherit the parent's");
		assertEquals(List.of("Task " + back.label() + " returned by coder (SPLIT): " + WHY
				+ "; proposes 2 children: backend part; ui part"), boardAlerts(r));
	}

	/** T-2: without a proposal the record proposes none and the ALERT says so. */
	@Test
	public void aSplitReturnWithoutAProposalProposesNoChildren() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "no proposal");
		AgentTaskData back = takeAndReturnSplit(r, t, r.coder(), null);
		SplitRequest sr = back.getSplitRequest();
		assertEquals(SplitRequestState.REQUESTED, sr.state());
		assertEquals(List.of(), sr.proposedChildren());
		assertEquals(List.of("Task " + back.label() + " returned by coder (SPLIT): " + WHY + "; proposes no children"),
				returnAlerts(r, back));
	}

	/** T-2: the titles in the ALERT are cut to 200 characters, as the description is. */
	@Test
	public void theProposedTitlesAreCutInTheAlert() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "long titles");
		List<SplitChild> many = IntStream.range(0, 5).mapToObj(i -> child(i + "-" + "x".repeat(100))).toList();
		AgentTaskData back = takeAndReturnSplit(r, t, r.coder(), many);
		String alert = returnAlerts(r, back).get(0);
		String titles = alert.substring(alert.indexOf("; proposes 5 children: ") + "; proposes 5 children: ".length());
		assertEquals(200, titles.length(), titles);
		assertTrue(titles.endsWith("..."), titles);
	}

	/** T-3: every refusal at the return, each writing nothing: no return row, no record, the task still ASSIGNED. */
	@Test
	public void aSplitReturnIsCheckedBeforeAnythingIsWritten() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "validation");
		assign(r, t, r.coder());
		int rows = returnsOf(t);

		assertRefused(r, t, " ", null, "requires a description");
		assertRefused(r, t, WHY, List.of(child(" ")), "Every split child requires a title");
		assertRefused(r, t, WHY, List.of(child("t".repeat(121))), "Titles are at most 120 characters");
		assertRefused(r, t, WHY, List.of(child("a\nb")), "A title is one line");
		assertRefused(r, t, WHY, List.of(new SplitChild("described", null, null, null, null, List.of(),
				"d".repeat(4001))), "A description is at most 4000 characters");
		assertRefused(r, t, WHY, List.of(child("A"), child("B", 2)), "Invalid dependsOnSiblingIndexes entry 2 for split child 1");
		assertRefused(r, t, WHY, List.of(child("A", 0), child("B")), "Invalid dependsOnSiblingIndexes entry 0 for split child 0");
		assertRefused(r, t, WHY, IntStream.range(0, 21).mapToObj(i -> child("child " + i)).toList(),
				"A split request proposes at most 20 children");
		assertRefused(r, t, WHY, List.of(new SplitChild("url", null, "u".repeat(2001), null, null, List.of())),
				"A sourceUrl is at most 2000 characters (this one is 2001)");
		assertRefused(r, t, WHY, List.of(new SplitChild("ref", "github:acme/widget#" + "1".repeat(1982), null, null, null,
				List.of())), "An externalRef is at most 2000 characters (this one is 2001)");
		assertRefused(r, t, WHY, List.of(new SplitChild("ref", "widget-42", null, null, null, List.of())), "names no item");
		assertRefused(r, t, WHY, List.of(tagged(List.of(new TagRecord("k".repeat(41), "v")))),
				"A tag is 1 to 40 lower-case letters");
		List<TagRecord> tags = new ArrayList<>();
		for (int i = 0; i < 21; i++) tags.add(new TagRecord("tag" + i, "v"));
		assertRefused(r, t, WHY, List.of(tagged(tags)), "A task carries at most 20 tags");
		assertRefused(r, t, WHY, List.of(tagged(List.of(new TagRecord("area", "v".repeat(201))))),
				"A tag value is at most 200 characters (tag area)");
		assertRefused(r, t, WHY, List.of(grouped("A")), "A group key is 2 to 24");
		assertRefused(r, t, "w".repeat(4001), null, "A description is at most 4000 characters (this one is 4001)");
		assertRefusedAs(r, t, TaskReturnReason.OTHER, "o".repeat(4001), null,
				"A description is at most 4000 characters (this one is 4001)");
		assertRefusedAs(r, t, TaskReturnReason.OTHER, "not a split", List.of(child("A")), "proposedChildren needs reason SPLIT");

		assertEquals(rows, returnsOf(t), "no return row was written");
		assertNull(reload(t).getSplitRequest(), "no record was written");
		assertTrue(boardAlerts(r).isEmpty(), boardAlerts(r).toString());
	}

	/** T-3: what passes is stored as the split would register it: the canonical ref, the lower-cased group, one line. */
	@Test
	public void anAcceptedProposalIsStoredAsTheSplitWouldRegisterIt() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "normalised");
		assign(r, t, r.coder());
		SplitChild c = new SplitChild("ref", "github:Acme/Widget#042", "https://example.test/42", null, null, List.of(),
				null, "Backend", List.of(new TagRecord("Area", "api")));
		AgentTaskData back = returnSplit(r.coder(), t, "too big\nsecond line", List.of(c));

		SplitRequest sr = back.getSplitRequest();
		SplitChild stored = sr.proposedChildren().get(0);
		assertEquals("github:acme/widget#42", stored.externalRef());
		assertEquals("https://example.test/42", stored.sourceUrl());
		assertEquals("backend", stored.group());
		assertEquals("area", stored.tags().get(0).key());
		assertEquals("too big second line", sr.description());
		assertEquals("too big second line", back.getReturns().get(back.getReturns().size() - 1).description());
		assertEquals(List.of("Task " + back.label() + " returned by coder (SPLIT): too big second line; proposes 1"
				+ " children: ref"), returnAlerts(r, back));
	}

	/** T-3: an OTHER description keeps its lines: only a SPLIT description is folded. */
	@Test
	public void anOtherDescriptionStaysFreeText() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "free text");
		assign(r, t, r.coder());
		AgentTaskData back = agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER,
				"line one\nline two", List.of(), WU);
		assertEquals("line one\nline two", back.getReturns().get(back.getReturns().size() - 1).description());
		assertNull(back.getSplitRequest());
	}

	/** T-14: after a decline and a new hop, a second request rewrites the record; both rows stay in the history. */
	@Test
	public void aSecondRequestRewritesTheRecord() throws RelizaException {
		Rig r = unflagged();
		AgentTaskData t = designed(r, "asked twice");
		takeAndReturnSplit(r, t, r.coder(), List.of(child("A"), child("B")));
		SplitRequest firstRequest = reload(t).getSplitRequest();
		agentTaskService.authorize(t.getUuid(), board(r), "coder", null, null, null, null, null, "one pass is fine",
				COORD, WU);
		assertEquals(SplitRequestState.DECLINED, reload(t).getSplitRequest().state());

		takeAndReturnSplit(r, t, r.coder(), List.of(child("only one")));
		AgentTaskData after = reload(t);
		SplitRequest sr = after.getSplitRequest();
		assertEquals(SplitRequestState.REQUESTED, sr.state());
		assertTrue(sr.requestedAt().isAfter(firstRequest.requestedAt()), "the new request's time");
		assertEquals(List.of("only one"), sr.proposedChildren().stream().map(SplitChild::title).toList());
		assertNull(sr.settledBy());
		assertNull(sr.note());
		assertEquals(2, after.getReturns().stream().filter(tr -> TaskReturnReason.SPLIT == tr.reason()).count());
	}

	static SplitChild tagged(List<TagRecord> tags) {
		return new SplitChild("tagged", null, null, null, null, List.of(), null, null, tags);
	}

	static SplitChild grouped(String group) {
		return new SplitChild("grouped", null, null, null, null, List.of(), null, group, null);
	}

	int returnsOf(AgentTaskData t) {
		List<TaskReturn> rows = reload(t).getReturns();
		return null == rows ? 0 : rows.size();
	}

	void assertRefused(Rig r, AgentTaskData t, String why, List<SplitChild> proposal, String containing) {
		assertRefusedAs(r, t, TaskReturnReason.SPLIT, why, proposal, containing);
	}

	void assertRefusedAs(Rig r, AgentTaskData t, TaskReturnReason reason, String why, List<SplitChild> proposal,
			String containing) {
		RelizaException e = refused(() -> agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), reason, why,
				List.of(), proposal, WU));
		assertTrue(e.getMessage().contains(containing), e.getMessage());
		AgentTaskData now = reload(t);
		assertEquals(TaskStatus.ASSIGNED, now.getStatus(), containing);
		assertNull(now.getSplitRequest(), containing);
	}
}
