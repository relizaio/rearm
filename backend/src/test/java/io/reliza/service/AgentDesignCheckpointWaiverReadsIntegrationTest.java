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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpointWaiver;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;

/**
 * The waiver as clients read it (task TPF-152, 4.6, 4.4): {@code standing} resolved by the server per task through
 * the parent resolver's local context (T-43, F-6, F-10, F-12), and a role marked blindReview reading no account of the
 * architect through the waiver, the waived checkpoint record or the board feed (T-46, F-13, F-14).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
public class AgentDesignCheckpointWaiverReadsIntegrationTest extends AgentDesignCheckpointTestBase {

	static final String NOTE = "fits one coder pass";
	static final String TASK = "query($t: ID!) { agentTaskProgrammatic(taskUuid: $t) { uuid"
			+ " designCheckpointWaiver { bySession byAgent role designRound note standing }"
			+ " designCheckpoint { state design settledAt note settledBy { kind uuid } }"
			+ " signOffs { role note session agent } } }";
	static final String TASKS = "query($b: ID!) { agentTasksProgrammatic(boardUuid: $b) { uuid"
			+ " designCheckpointWaiver { bySession byAgent role designRound note standing }"
			+ " designCheckpoint { state design settledAt note settledBy { kind uuid } } } }";

	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired ApiKeyService apiKeyService;
	@Autowired LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/** A FREEFORM key with AGENT, BOARD_READ and BOARD_AGENT on the organization: a reader that holds no task. */
	private ApiKey agentKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "waiver reads", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_AGENT), null)),
				WU);
		return key;
	}

	/** A worker whose session runs on {@code key}: its reads through that key are the holder's own. */
	private Worker workerOnKey(Organization org, String name, ApiKey key) throws RelizaException {
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				name + "-" + UUID.randomUUID(), null, null, null, WU);
		return new Worker(a, agentSessionService.initialize(org.getUuid(), a.getUuid(), key.getUuid(),
				"s-" + UUID.randomUUID(), name, null, null, WU));
	}

	private ExecutionResult asKey(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	private static <T> T data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	private Map<String, Object> read(ApiKey key, AgentTaskData t) {
		return data(asKey(key, TASK, Map.of("t", t.getUuid().toString())), "agentTaskProgrammatic");
	}

	/** The board's tasks as {@code key} lists them, by uuid. */
	private Map<String, Map<String, Object>> list(ApiKey key, Rig r) {
		List<Map<String, Object>> tasks = data(asKey(key, TASKS, Map.of("b", r.board().getUuid().toString())),
				"agentTasksProgrammatic");
		Map<String, Map<String, Object>> out = new HashMap<>();
		tasks.forEach(m -> out.put((String) m.get("uuid"), m));
		return out;
	}

	private static Map<String, Object> waiverOf(Map<String, Object> task) {
		return (Map<String, Object>) task.get("designCheckpointWaiver");
	}

	private static Object standing(Map<String, Object> task) {
		Map<String, Object> w = waiverOf(task);
		return null == w ? null : w.get("standing");
	}

	// ---------- T-43: standing, per task ----------

	@Test
	public void standingIsResolvedByTheServerPerTask() throws RelizaException {
		Rig r = plain();
		ApiKey key = agentKey(r.org());
		AgentTaskData a = task(r, "waived, standing");
		List<ReleaseData> design = designNoSplit(r, a, NOTE);
		AgentTaskData b = task(r, "waived, then an advisory round");
		designNoSplit(r, b, NOTE);
		publish(r.architect(), b, ARCH, true);
		AgentTaskData c = task(r, "no waiver");

		Map<String, Object> ra = read(key, a);
		Map<String, Object> wa = waiverOf(ra);
		assertEquals(true, wa.get("standing"));
		assertEquals(r.architect().sessionUuid().toString(), wa.get("bySession"));
		assertEquals("architect", wa.get("role"));
		assertEquals(design.get(0).getUuid().toString(), wa.get("designRound"));
		assertEquals(NOTE, wa.get("note"));
		assertEquals(false, standing(read(key, b)), "an advisory round since");
		assertNull(waiverOf(read(key, c)), "no waiver: null");

		Map<String, Map<String, Object>> listed = list(key, r);
		assertEquals(true, standing(listed.get(a.getUuid().toString())));
		assertEquals(false, standing(listed.get(b.getUuid().toString())));
		assertNull(standing(listed.get(c.getUuid().toString())));
		assertNull(waiverOf(listed.get(c.getUuid().toString())));

		// before and after a reopen to the architect: the reopen clears it
		AgentTaskData d = task(r, "completed, then reopened");
		designNoSplit(r, d, NOTE);
		code(r, d);
		assertEquals(TaskStatus.COMPLETED, pass(r, d, r.tester()).getStatus());
		assertEquals(true, standing(read(key, d)));
		agentTaskService.reopen(d.getUuid(), "architect", "the design missed a case", COORD, WU);
		assertNull(waiverOf(read(key, d)));
	}

	@Test
	public void standingIsFalseAfterAGateRejectionAndWithTheSwitchOff() throws RelizaException {
		Rig gated = rig(false, true, HumanGate.ON_PASS);
		ApiKey key = agentKey(gated.org());
		AgentTaskData rejected = task(gated, "gate rejected");
		designNoSplit(gated, rejected, NOTE);
		agentTaskService.humanReview(rejected.getUuid(), false, "not yet", PERSON, WU);
		Map<String, Object> w = waiverOf(read(key, rejected));
		assertNotNull(w);
		assertEquals(false, w.get("standing"), "a DRAFT round is never the newest live round");

		Rig off = plain();
		ApiKey offKey = agentKey(off.org());
		waiverSwitch(off, false);
		AgentTaskData t = task(off, "switch off");
		designNoSplit(off, t, NOTE);
		assertEquals(false, standing(read(offKey, t)), "the switch off");
		waiverSwitch(off, null);
		assertEquals(true, standing(read(offKey, t)), "on again: the round is still the newest");
	}

	// ---------- T-46: a blind role reads no account ----------

	@Test
	public void aBlindRoleReadsNoAccountThroughTheNewRecords() throws RelizaException {
		Rig r = reviewed();
		for (String blind : List.of("functional_reviewer", "tester")) {
			agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(blind, null, null, null,
					null, null, null, null, null, null, null, null, null, null, true), true, WU);
		}
		ApiKey other = agentKey(r.org());
		ApiKey reviewerKey = agentKey(r.org());
		ApiKey testerKey = agentKey(r.org());
		Worker reviewer = workerOnKey(r.org(), "functional_reviewer", reviewerKey);
		Worker tester = workerOnKey(r.org(), "tester", testerKey);

		AgentTaskData t = task(r, "blind board, waived");
		List<ReleaseData> design = designNoSplit(r, t, NOTE);

		// (e) the offer and the assignment carry the redaction
		WorkerAssignment offered = agentTaskService.next(List.of(board(r)), reviewer.agentUuid(), reviewer.sessionUuid())
				.orElseThrow();
		assertRedacted(offered.task().getDesignCheckpointWaiver(), design.get(0), "the offer");
		WorkerAssignment wa = agentTaskService.assign(t.getUuid(), board(r), reviewer.agentUuid(), reviewer.sessionUuid(),
				WU);
		assertRedacted(wa.task().getDesignCheckpointWaiver(), design.get(0), "the assignment");

		// (a) the holder's own key: role, round and standing; no session, agent or note
		Map<String, Object> mine = read(reviewerKey, t);
		assertBlindWaiver(waiverOf(mine), design.get(0));
		assertNull(mine.get("designCheckpoint"), "not consumed yet");
		for (Map<String, Object> so : (List<Map<String, Object>>) mine.get("signOffs")) {
			assertNull(so.get("note"));
			assertNull(so.get("session"));
		}
		assertBlindWaiver(waiverOf(list(reviewerKey, r).get(t.getUuid().toString())), design.get(0));
		// (b) a key that holds nothing reads it whole
		Map<String, Object> whole = waiverOf(read(other, t));
		assertEquals(r.architect().sessionUuid().toString(), whole.get("bySession"));
		assertEquals(r.architect().agentUuid().toString(), whole.get("byAgent"));
		assertEquals(NOTE, whole.get("note"));
		assertEquals(NOTE, waiverOf(list(other, r).get(t.getUuid().toString())).get("note"));

		// the reviews pass; the coder is queued: (f) the INFO quotes no note on a blind board
		signOff(reviewer, t, SignOffOutcome.PASSED, "fine", List.of());
		pass(r, t, r.security());
		AgentTaskData queued = reload(t);
		assertEquals("coder", queued.getRole());
		assertEquals(List.of(waiverInfo(queued, "ARCHITECTURE round 1", "READY_TO_SHIP", null)), waivedInfos(r, queued));
		assertFalse(waivedInfos(r, queued).get(0).contains("\""), waivedInfos(r, queued).get(0));
		assertFalse(waivedInfos(r, queued).get(0).contains(NOTE));

		// (c) the blind tester reads the waived record without the architect's session or words
		code(r, t);
		agentTaskService.assign(t.getUuid(), board(r), tester.agentUuid(), tester.sessionUuid(), WU);
		Map<String, Object> cp = (Map<String, Object>) read(testerKey, t).get("designCheckpoint");
		assertEquals("waived by architect", cp.get("note"));
		assertNull(cp.get("settledBy"));
		assertEquals("SENT_ON", cp.get("state"));
		assertNotNull(cp.get("settledAt"));
		assertEquals(2, ((List<?>) cp.get("design")).size());
		Map<String, Object> cpWhole = (Map<String, Object>) read(other, t).get("designCheckpoint");
		assertEquals("waived by architect: " + NOTE, cpWhole.get("note"));
		Map<String, Object> by = (Map<String, Object>) cpWhole.get("settledBy");
		assertEquals("SESSION", by.get("kind"));
		assertEquals(r.architect().sessionUuid().toString(), by.get("uuid"));
		assertEquals("waived by architect: " + NOTE, reload(t).getDesignCheckpoint().note(), "the stored record whole");
		signOff(tester, t, SignOffOutcome.PASSED, "ok", List.of());

		// (f) a design without the flag on the blind board: the ALERT has no architect clause
		AgentTaskData t2 = task(r, "blind board, stopped");
		design(r, t2, NOTE);
		signOff(reviewerAssigned(r, t2, reviewer), t2, SignOffOutcome.PASSED, "fine", List.of());
		pass(r, t2, r.security());
		AgentTaskData stopped = reload(t2);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus());
		assertEquals(List.of(alertFor(stopped, "ARCHITECTURE round 1 (READY_TO_SHIP), " + TM + " round 1 (READY_TO_SHIP)",
				null)), checkpointAlerts(r, stopped));
		String row = stopped.getStatusHistory().get(stopped.getStatusHistory().size() - 1).note();
		assertFalse(String.valueOf(row).contains(NOTE), "nor does the status row: " + row);

		// (d) a record the seat settled reads whole for the blind tester
		authorize(r, t2, "coder", COORD);
		code(r, t2);
		agentTaskService.assign(t2.getUuid(), board(r), tester.agentUuid(), tester.sessionUuid(), WU);
		Map<String, Object> seat = (Map<String, Object>) read(testerKey, t2).get("designCheckpoint");
		assertEquals("authorized for coder", seat.get("note"));
		assertEquals(COORD.uuid().toString(), ((Map<String, Object>) seat.get("settledBy")).get("uuid"));
	}

	private Worker reviewerAssigned(Rig r, AgentTaskData t, Worker reviewer) throws RelizaException {
		assign(r, t, reviewer);
		return reviewer;
	}

	private static void assertRedacted(DesignCheckpointWaiver w, ReleaseData round, String where) {
		assertNotNull(w, where);
		assertNull(w.bySession(), where);
		assertNull(w.byAgent(), where);
		assertNull(w.note(), where);
		assertEquals("architect", w.role(), where);
		assertNotNull(w.roleUuid(), where);
		assertNotNull(w.at(), where);
		assertEquals(round.getUuid(), w.designRound(), where);
	}

	private static void assertBlindWaiver(Map<String, Object> w, ReleaseData round) {
		assertNotNull(w);
		assertEquals("architect", w.get("role"));
		assertEquals(round.getUuid().toString(), w.get("designRound"));
		assertEquals(true, w.get("standing"), "standing works on the redacted copy");
		assertNull(w.get("bySession"));
		assertNull(w.get("byAgent"));
		assertNull(w.get("note"));
	}
}
