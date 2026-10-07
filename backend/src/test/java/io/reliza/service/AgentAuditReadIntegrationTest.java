/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentActor.ActorKind;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentAuditReadService.AgentBoardRevision;
import io.reliza.service.AgentAuditReadService.AgentRoleConfigRevision;
import io.reliza.service.AgentAuditReadService.AgentTaskRevision;
import io.reliza.ws.oss.TestInitializer;

/**
 * The read path over the audit rows of tasks, boards and role configs (22ddc644). Integration
 * because what is under test is that the rows the saves already write come back as the data the
 * live resolvers serve; a mocked audit table would only be asserting the mock.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
public class AgentAuditReadIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor HUMAN = AgentActor.ofUser(UUID.randomUUID(), "operator");

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentAuditReadService agentAuditReadService;
	@Autowired private AuditService auditService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;

	private record Rig(Organization org, AgentBoardData board, AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("audit_" + UUID.randomUUID(),
				org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(),
				"audit-" + UUID.randomUUID(), "audit history board", List.of(), "coordinate", 4, null,
				target.getUuid(), null, WU);
		designer(board, "design it");
		AgentData worker = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"wa-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), worker.getUuid(), null,
				"s-" + UUID.randomUUID(), "test session", null, null, WU);
		return new Rig(org, board, worker, session);
	}

	private AgentTaskRoleConfigData designer(AgentBoardData board, String prompt) throws RelizaException {
		return agentBoardService.upsertRoleConfig(board,
				new AgentBoardService.RoleConfigSpec("designer", prompt, 10, null, false, true,
						null, null, null, null, List.of(),
						List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false))),
				true, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	/** Register, authorize the designer, assign, sign off: four transitions, each saved. */
	private AgentTaskData workedTask(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, "audit " + UUID.randomUUID(), null, null,
				null, null, null, null, HUMAN, true, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, HUMAN, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "designed",
				List.of(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private static <T> List<T> oldestFirst(List<T> newestFirst) {
		List<T> out = new ArrayList<>(newestFirst);
		java.util.Collections.reverse(out);
		return out;
	}

	@Test
	public void everyTaskSaveIsAReadableRevision() throws RelizaException {
		Rig r = rig();
		AgentTaskData live = workedTask(r);
		List<AgentTaskRevision> history = agentAuditReadService.taskHistory(live.getUuid(), null, null);

		assertTrue(history.size() >= 3, "register, authorize, assign and sign off each save: " + history.size());
		// Newest first, with no gap: every save below the live row is there.
		for (int i = 0; i < history.size(); i++) {
			assertEquals(history.size() - 1 - i, history.get(i).revision(), "revisions without a gap");
			assertNotNull(history.get(i).task(), "revision " + history.get(i).revision() + " parses");
			assertEquals(live.getUuid(), history.get(i).task().getUuid());
			assertEquals(live.getCreatedDate().toInstant(), history.get(i).task().getCreatedDate().toInstant());
			if (i > 0) assertTrue(!history.get(i).at().isAfter(history.get(i - 1).at()), "newer first");
		}
		// Each revision is the task at one point of its transitions: the statuses it passed
		// through, in the order the live history records them.
		List<TaskStatus> recorded = live.getStatusHistory().stream().map(StatusChange::to).toList();
		int cursor = 0;
		List<TaskStatus> seen = new ArrayList<>();
		for (AgentTaskRevision rev : oldestFirst(history)) {
			TaskStatus s = rev.task().getStatus();
			seen.add(s);
			List<StatusChange> own = rev.task().getStatusHistory();
			if (!own.isEmpty()) assertEquals(own.get(own.size() - 1).to(), s, "a snapshot agrees with itself");
			int at = recorded.subList(cursor, recorded.size()).indexOf(s);
			assertTrue(at >= 0, s + " is in the live history after position " + cursor + ": " + recorded);
			cursor += at;
		}
		assertTrue(seen.contains(TaskStatus.QUEUED) && seen.contains(TaskStatus.ASSIGNED), "seen: " + seen);
		assertTrue(seen.indexOf(TaskStatus.QUEUED) < seen.lastIndexOf(TaskStatus.ASSIGNED), "seen: " + seen);
		// The live row is not repeated: its status is what the newest revision moved to.
		assertEquals(TaskStatus.ASSIGNED, history.get(0).task().getStatus(), "the revision before the sign-off");
		assertTrue(live.getStatus() != TaskStatus.ASSIGNED, "the live task has moved on: " + live.getStatus());
	}

	private static Map<String, Object> boardFile(String name, Object... more) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", "BOARD");
		m.put("version", 1);
		m.put("name", name);
		for (int i = 0; i < more.length; i += 2) m.put((String) more[i], more[i + 1]);
		return m;
	}

	@Test
	public void aBoardApplyIsAReadableRevision() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String target = "audit-node-" + UUID.randomUUID();
		componentService.createComponent(target, org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		String name = "audit-decl-" + UUID.randomUUID();
		agentBoardService.applyBoard(org.getUuid(), AgentBoardService.boardSpecFromInput(
				boardFile(name, "target", target, "settings", Map.of("cycleCap", 4))), false, null, HUMAN, WU);
		agentBoardService.applyBoard(org.getUuid(), AgentBoardService.boardSpecFromInput(
				boardFile(name, "settings", Map.of("cycleCap", 3))), false, null, HUMAN, WU);
		AgentBoardData live = agentBoardService.listByOrg(org.getUuid()).stream()
				.filter(b -> name.equals(b.getName())).findFirst().orElseThrow();

		List<AgentBoardRevision> history = agentAuditReadService.boardHistory(live.getUuid(), null, null);
		assertEquals(Integer.valueOf(3), live.getCycleCap(), "the live board has the new value");
		// An apply may save the board more than once; the revisions before the second apply's
		// settings save still hold the old value.
		Integer before = history.stream().map(rev -> rev.board().getCycleCap())
				.filter(c -> !Integer.valueOf(3).equals(c)).findFirst().orElse(null);
		assertEquals(Integer.valueOf(4), before, "the newest revision before the change has the old value");
		assertEquals(live.getUuid(), history.get(0).board().getUuid());
		assertEquals(name, history.get(0).board().getName());
	}

	@Test
	public void aRolePromptChangeIsReadable() throws RelizaException {
		Rig r = rig();
		designer(board(r), "design it, carefully");
		AgentTaskRoleConfigData live = agentBoardService.getRoleConfig(r.board().getUuid(), "designer").orElseThrow();
		assertEquals(live.getUuid(), agentBoardService.getRoleConfigData(live.getUuid()).orElseThrow().getUuid());
		List<AgentRoleConfigRevision> history = agentAuditReadService.roleConfigHistory(live.getUuid(), null, null);
		assertEquals("design it, carefully", live.getPrompt());
		assertEquals("design it", history.get(0).roleConfig().getPrompt(), "the prompt before the edit");
		assertEquals("designer", history.get(0).roleConfig().getName());
	}

	@Test
	public void limitAndOffsetPage() throws RelizaException {
		Rig r = rig();
		AgentTaskData live = workedTask(r);
		List<Integer> all = agentAuditReadService.taskHistory(live.getUuid(), null, null).stream()
				.map(AgentTaskRevision::revision).toList();
		assertTrue(all.size() >= 3, "enough revisions to page: " + all);
		assertEquals(all.subList(0, 2), agentAuditReadService.taskHistory(live.getUuid(), 2, 0).stream()
				.map(AgentTaskRevision::revision).toList());
		assertEquals(all.subList(2, Math.min(4, all.size())), agentAuditReadService.taskHistory(live.getUuid(), 2, 2).stream()
				.map(AgentTaskRevision::revision).toList());
		assertEquals(all, agentAuditReadService.taskHistory(live.getUuid(), 0, -3).stream()
				.map(AgentTaskRevision::revision).toList(), "a limit below one is the default, a negative offset 0");
		assertEquals(AgentAuditReadService.DEFAULT_LIMIT, AgentAuditReadService.pageLimit(null));
		assertEquals(AgentAuditReadService.MAX_LIMIT, AgentAuditReadService.pageLimit(10_000));
		assertEquals(7, AgentAuditReadService.pageLimit(7));
		assertTrue(agentAuditReadService.taskHistory(UUID.randomUUID(), null, null).isEmpty(), "no rows, no revisions");
	}

	/** An audit row written by hand: the snapshot as a save of that era would have left it. */
	private void seed(UUID uuid, int revision, Map<String, Object> snapshot) {
		AgentTask t = new AgentTask();
		t.setUuid(uuid);
		t.setRevision(revision);
		t.setCreatedDate(ZonedDateTime.now().minusDays(30));
		t.setLastUpdatedDate(ZonedDateTime.now().minusDays(30 - revision));
		t.setRecordData(snapshot);
		auditService.createAndSaveAuditRecord(TableName.AGENT_TASKS, t);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void anOldShapeRevisionStillParses() throws RelizaException {
		Rig r = rig();
		AgentTaskData current = agentTaskService.register(board(r), null, "old shape", null, null, null, null,
				null, null, HUMAN, true, WU);
		UUID uuid = UUID.randomUUID();

		// Before #563 the actors were strings: an email, the bare "operator" literal, a session
		// uuid. A field since retired is still in the row.
		Map<String, Object> old = new LinkedHashMap<>(Utils.dataToRecord(current));
		List<Map<String, Object>> sh = new ArrayList<>();
		for (Object o : (List<Object>) old.get("statusHistory")) {
			Map<String, Object> change = new LinkedHashMap<>((Map<String, Object>) o);
			change.put("actor", "pavel@example.com");
			sh.add(change);
		}
		old.put("statusHistory", sh);
		Map<String, Object> hold = new LinkedHashMap<>();
		hold.put("level", "OPERATOR");
		hold.put("kind", "MANUAL");
		hold.put("reason", "held by hand");
		hold.put("heldBy", "operator");
		old.put("hold", hold);
		old.put("retiredField", Map.of("anything", 1));
		seed(uuid, 0, old);

		// A row nothing can read any more: the list still comes back, with that one empty.
		Map<String, Object> broken = new LinkedHashMap<>(Utils.dataToRecord(current));
		broken.put("status", "NO_SUCH_STATUS");
		seed(uuid, 1, broken);

		List<AgentTaskRevision> history = agentAuditReadService.taskHistory(uuid, null, null);
		assertEquals(List.of(1, 0), history.stream().map(AgentTaskRevision::revision).toList());
		assertNull(history.get(0).task(), "an unreadable snapshot is served empty, not as an error");
		assertNotNull(history.get(0).at());

		AgentTaskData parsed = history.get(1).task();
		assertNotNull(parsed, "the old shape parses");
		assertEquals(uuid, parsed.getUuid());
		AgentActor by = parsed.getStatusHistory().get(0).actor();
		assertEquals(ActorKind.USER, by.kind());
		assertNull(by.uuid(), "a string actor names nobody the board can resolve");
		assertEquals("pavel@example.com", by.name());
		assertEquals(ActorKind.USER, parsed.getHold().heldBy().kind());
		assertEquals("operator", parsed.getHold().heldBy().name());
	}


	/**
	 * RD3-1: keep-alives are stamps, not revisions, so a session's history holds its real changes only.
	 * Touched through the day, then closed: the audit table holds the session as it was open, the live
	 * row is the close, and nothing sits between them.
	 */
	@Test
	public void aTouchedThenClosedSessionsHistoryIsTheOpenAndTheClose() throws RelizaException {
		Rig r = rig();
		UUID s = r.session().getUuid();
		ZonedDateTime t0 = r.session().getLastActivityAt();
		for (int hour = 1; hour <= 8; hour++) agentSessionService.touch(s, t0.plusHours(hour), WU);
		agentSessionService.close(s, WU);

		List<io.reliza.model.Audit> history = auditService.getAuditForEntity(s, TableName.AGENT_SESSIONS, 1000, 0);
		assertEquals(1, history.size(), "one revision before the close, not one per touch: " + history.size());
		AgentSessionData open = Utils.OM.convertValue(history.get(0).getRevisionRecordData(), AgentSessionData.class);
		assertEquals(AgentSessionData.SessionStatus.OPEN, open.getStatus(), "the open");
		assertEquals(t0.plusHours(8).toInstant().toEpochMilli(), open.getLastActivityAt().toInstant().toEpochMilli(),
				"carrying the stamp current when the close came");
		assertEquals(AgentSessionData.SessionStatus.CLOSED,
				agentSessionService.getSessionData(s).orElseThrow().getStatus(), "and the live row is the close");
	}
}
