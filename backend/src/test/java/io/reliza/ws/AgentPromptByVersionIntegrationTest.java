/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AgentBoardService;

/**
 * The served prompt by version (TPF-97 / TPF-109) on next, assign, the role read and the coordinator seat: the
 * version is the SHA-256 of the exact served text, heldVersions answers UNCHANGED without the text when it holds the
 * current one, the sign-off pin (promptVersion) keeps its meaning, and the bound on heldVersions is refused on the
 * field alone, as the shared handler renders a refusal.
 *
 * <p>Every method builds its own world; next never assigns, so it repeats on one queued task, while every assign
 * takes a task no earlier call in the method assigned.
 */
@SuppressWarnings("unchecked")
class AgentPromptByVersionIntegrationTest extends AgentBoardEnforcementTestBase {

	private static final String NEXT = "query($s: ID!, $b: ID, $h: [String!]) { agentTaskNextProgrammatic(sessionUuid: $s,"
			+ " boardUuid: $b) { task { uuid } role rolePrompt promptVersion promptByVersion(heldVersions: $h)"
			+ " { version status text } } }";
	private static final String ASSIGN_BV = "mutation($t: ID!, $s: ID!, $h: [String!]) { agentTaskAssignProgrammatic("
			+ "taskUuid: $t, sessionUuid: $s) { task { uuid status } promptVersion promptByVersion(heldVersions: $h)"
			+ " { version status text } } }";
	private static final String ROLES_P = "query($b: ID!, $h: [String!]) { agentTaskRoleConfigsProgrammatic(boardUuid: $b)"
			+ " { name servedPrompt promptVersion promptByVersion(heldVersions: $h) { version status text } } }";
	private static final String ROLES_PERSON = "query($b: ID!, $h: [String!]) { agentTaskRoleConfigsOfBoard(boardUuid: $b)"
			+ " { name servedPrompt promptVersion promptByVersion(heldVersions: $h) { version status text } } }";
	private static final String COORDINATE = "mutation($b: ID!, $s: ID!, $h: [String!]) { agentBoardCoordinateProgrammatic("
			+ "boardUuid: $b, sessionUuid: $s) { servedCoordinatorPrompt coordinatorPrompt"
			+ " coordinatorPromptByVersion(heldVersions: $h) { version status text } } }";

	private static final Pattern FULL_HEX = Pattern.compile("(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])");

	/** A key whose session may poll, assign and read the role configs of board A. */
	private Caller agent(World w) throws RelizaException {
		return key(w, on(w, PermissionScope.BOARD, w.a().getUuid(), PermissionType.READ_ONLY, PermissionFunction.BOARD_AGENT));
	}

	private Map<String, Object> next(Caller c, World w, List<String> held) {
		return data(asKey(c.key(), NEXT, vars("s", c.session().getUuid().toString(), "b", w.a().getUuid().toString(),
				"h", held)), "agentTaskNextProgrammatic");
	}

	private Map<String, Object> assign(Caller c, AgentTaskData t, List<String> held) {
		return data(asKey(c.key(), ASSIGN_BV, vars("t", t.getUuid().toString(), "s", c.session().getUuid().toString(),
				"h", held)), "agentTaskAssignProgrammatic");
	}

	private static Map<String, Object> byVersion(Map<String, Object> parent) {
		return (Map<String, Object>) parent.get("promptByVersion");
	}

	private static Map<String, Object> coder(List<Map<String, Object>> roles) {
		return roles.stream().filter(x -> "coder".equals(x.get("name"))).findFirst().orElseThrow();
	}

	// ---------- item 4: next and assign ----------

	@Test
	void nextAndAssignAnswerByVersion() throws RelizaException {
		World w = world();
		Caller c = agent(w);
		String pin = AgentBoardService.promptVersion("build");

		Map<String, Object> offer = next(c, w, null);
		String rolePrompt = (String) offer.get("rolePrompt");
		Map<String, Object> served = byVersion(offer);
		String version = (String) served.get("version");
		assertEquals("SERVED", served.get("status"));
		assertEquals(rolePrompt, served.get("text"), "the same text as rolePrompt");
		assertEquals(AgentBoardService.servedTextVersion(rolePrompt), version);
		assertEquals(64, version.length());

		Map<String, Object> held = next(c, w, List.of(version));
		assertEquals(Map.of("version", version, "status", "UNCHANGED"), Map.of("version", byVersion(held).get("version"),
				"status", byVersion(held).get("status")));
		assertNull(byVersion(held).get("text"), "no text when the caller holds it");
		assertEquals(rolePrompt, held.get("rolePrompt"), "rolePrompt keeps serving the text");
		assertEquals(pin, held.get("promptVersion"), "the pin is untouched");

		Map<String, Object> stale = next(c, w, List.of("stale", pin));
		assertEquals("SERVED", byVersion(stale).get("status"));
		assertEquals(rolePrompt, byVersion(stale).get("text"));

		AgentTaskData t2 = queued(w.a());
		AgentTaskData t3 = queued(w.a());
		Object[][] rows = { { w.onA(), null, "SERVED" }, { t2, List.of(version), "UNCHANGED" },
				{ t3, List.of("stale", pin), "SERVED" } };
		for (Object[] row : rows) {
			AgentTaskData t = (AgentTaskData) row[0];
			Map<String, Object> a = assign(c, t, (List<String>) row[1]);
			Map<String, Object> task = (Map<String, Object>) a.get("task");
			assertEquals(t.getUuid().toString(), task.get("uuid"), "the row's own task");
			assertEquals("ASSIGNED", task.get("status"));
			assertEquals(pin, a.get("promptVersion"));
			Map<String, Object> bv = byVersion(a);
			assertEquals(version, bv.get("version"), "one board and role, one served text, one version");
			assertEquals(row[2], bv.get("status"));
			assertEquals("SERVED".equals(row[2]) ? rolePrompt : null, bv.get("text"));
		}
	}

	// ---------- item 5: a settings change moves the served version, not the pin ----------

	@Test
	void aSettingsChangeMovesTheServedVersionNotThePin() throws RelizaException {
		World w = world();
		Caller c = agent(w);
		Map<String, Object> first = next(c, w, null);
		String old = (String) byVersion(first).get("version");

		agentBoardService.updateSettings(w.a().getUuid(), null, null, null, null, 2, null, WU);
		Map<String, Object> second = next(c, w, List.of(old));
		String now = (String) byVersion(second).get("version");
		assertNotEquals(old, now, "the routing rules are part of the served text");
		assertEquals("SERVED", byVersion(second).get("status"), "the old version no longer holds");
		assertEquals(second.get("rolePrompt"), byVersion(second).get("text"));
		assertNotEquals(first.get("rolePrompt"), second.get("rolePrompt"));
		assertEquals(first.get("promptVersion"), second.get("promptVersion"), "a settings change is not a prompt edit");

		Map<String, Object> both = next(c, w, List.of(old, now));
		assertEquals("UNCHANGED", byVersion(both).get("status"));
		assertNull(byVersion(both).get("text"));
	}

	// ---------- item 6: a prompt edit moves both, and the earlier hop's pin stands ----------

	@Test
	void aPromptEditMovesBothAndTheEarlierPinStands() throws RelizaException {
		World w = world();
		Caller c = agent(w);
		String pin = AgentBoardService.promptVersion("build");
		Map<String, Object> a = assign(c, w.onA(), null);
		String version = (String) byVersion(a).get("version");
		agentTaskService.signOff(w.onA().getUuid(), c.session().getUuid(), SignOffOutcome.PASSED, "done", WU);

		agentBoardService.upsertRoleConfig(agentBoardService.getBoardData(w.a().getUuid()).orElseThrow(),
				AgentBoardService.RoleConfigSpec.ofBasics("coder", "build, v2", 10, null, false, true, null), true, WU);
		AgentTaskData fresh = queued(w.a());
		Map<String, Object> offer = next(c, w, List.of(version));
		assertEquals(fresh.getUuid().toString(), ((Map<String, Object>) offer.get("task")).get("uuid"));
		assertNotEquals(version, byVersion(offer).get("version"), "a prompt edit moves the served version");
		assertEquals("SERVED", byVersion(offer).get("status"));
		assertEquals(AgentBoardService.promptVersion("build, v2"), offer.get("promptVersion"), "and the pin");
		assertNotEquals(pin, offer.get("promptVersion"));

		AgentTaskData done = agentTaskService.getTaskData(w.onA().getUuid()).orElseThrow();
		assertEquals(pin, done.getSignOffs().get(0).promptVersion(), "the earlier hop's pin stands");
		String records = String.valueOf(done.getSignOffs()) + done.getAssignment();
		assertFalse(FULL_HEX.matcher(records).find(), "no hop record carries a served version: " + records);
		assertFalse(records.contains(version), records);
	}

	// ---------- item 7: the role read agrees with the assignment ----------

	@Test
	void theRoleReadAgreesWithTheOffer() throws RelizaException {
		World w = world();
		Caller c = agent(w);
		String b = w.a().getUuid().toString();
		String offered = (String) byVersion(next(c, w, null)).get("version");

		Map<String, Object> role = coder(data(asKey(c.key(), ROLES_P, vars("b", b, "h", null)),
				"agentTaskRoleConfigsProgrammatic"));
		Map<String, Object> bv = byVersion(role);
		assertEquals(offered, bv.get("version"), "what task brief reads and what next serves hash one string");
		assertEquals("SERVED", bv.get("status"));
		assertEquals(role.get("servedPrompt"), bv.get("text"));
		assertEquals(AgentBoardService.promptVersion("build"), role.get("promptVersion"), "the version a sign-off pins");

		Map<String, Object> held = byVersion(coder(data(asKey(c.key(), ROLES_P, vars("b", b, "h", List.of(offered))),
				"agentTaskRoleConfigsProgrammatic")));
		assertEquals("UNCHANGED", held.get("status"));
		assertNull(held.get("text"));

		UserData admin = member(w, PermissionType.ADMIN);
		Map<String, Object> person = coder(data(asPerson(admin, ROLES_PERSON, vars("b", b, "h", null)),
				"agentTaskRoleConfigsOfBoard"));
		assertEquals(offered, byVersion(person).get("version"), "the type is shared: the person's read has the field too");
	}

	// ---------- item 8: the coordinator seat text ----------

	@Test
	void theCoordinatorSeatTextByVersion() throws RelizaException {
		World w = world();
		Caller seat = key(w, on(w, PermissionScope.BOARD, w.a().getUuid(), PermissionType.READ_WRITE,
				PermissionFunction.BOARD_WRITE));
		String b = w.a().getUuid().toString();
		String s = seat.session().getUuid().toString();

		Map<String, Object> first = data(asKey(seat.key(), COORDINATE, vars("b", b, "s", s, "h", null)),
				"agentBoardCoordinateProgrammatic");
		String servedText = (String) first.get("servedCoordinatorPrompt");
		Map<String, Object> bv = (Map<String, Object>) first.get("coordinatorPromptByVersion");
		String version = (String) bv.get("version");
		assertEquals("SERVED", bv.get("status"));
		assertEquals(servedText, bv.get("text"));
		assertEquals(AgentBoardService.servedTextVersion(servedText), version);
		Object stored = first.get("coordinatorPrompt");

		Map<String, Object> again = data(asKey(seat.key(), COORDINATE, vars("b", b, "s", s, "h", List.of(version))),
				"agentBoardCoordinateProgrammatic");
		Map<String, Object> held = (Map<String, Object>) again.get("coordinatorPromptByVersion");
		assertEquals("UNCHANGED", held.get("status"));
		assertNull(held.get("text"));
		assertEquals(version, held.get("version"));

		UserData admin = member(w, PermissionType.ADMIN);
		assertTrue(ok(asPerson(admin, "mutation($b: ID!, $i: AgentBoardInput!) { agentBoardUpdate(boardUuid: $b,"
				+ " input: $i) { uuid } }", vars("b", b, "i", Map.of("coordinatorCapabilities", List.of("PR_MERGE"),
						"deliveryPolicy", Map.of("merge", Map.of("by", "coordinator", "method", "SQUASH")))))),
				"the merge procedure changes");

		Map<String, Object> moved = data(asKey(seat.key(), COORDINATE, vars("b", b, "s", s, "h", List.of(version))),
				"agentBoardCoordinateProgrammatic");
		Map<String, Object> mbv = (Map<String, Object>) moved.get("coordinatorPromptByVersion");
		assertNotEquals(version, mbv.get("version"), "the merge procedure is part of the served text");
		assertEquals("SERVED", mbv.get("status"));
		assertEquals(moved.get("servedCoordinatorPrompt"), mbv.get("text"));
		assertEquals(stored, again.get("coordinatorPrompt"));
		assertEquals(stored, moved.get("coordinatorPrompt"), "the stored coordinatorPrompt is untouched");
	}

	// ---------- item 9: the refusal shape ----------

	private static void assertOneRefusal(ExecutionResult r) {
		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		assertEquals(AgentBoardService.HELD_VERSIONS_REFUSAL, r.getErrors().get(0).getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(r.getErrors().get(0).getErrorType()));
	}

	@Test
	void theBoundIsRefusedOnTheFieldAlone() throws RelizaException {
		World w = world();
		Caller c = agent(w);
		String b = w.a().getUuid().toString();
		String q = "query($b: ID!, $h: [String!]) { agentTaskRoleConfigsProgrammatic(boardUuid: $b)"
				+ " { name promptVersion promptByVersion(heldVersions: $h) { version status } } }";

		for (List<String> tooMuch : List.of(Collections.nCopies(51, "a"), List.of("a".repeat(129)))) {
			ExecutionResult r = asKey(c.key(), q, vars("b", b, "h", tooMuch));
			assertOneRefusal(r);
			assertNotNull(r.getData(), "the rest of the answer stands");
			Map<String, Object> row = coder((List<Map<String, Object>>) ((Map<String, Object>) r.getData())
					.get("agentTaskRoleConfigsProgrammatic"));
			assertNull(row.get("promptByVersion"), "the refused field is null");
			assertEquals("coder", row.get("name"));
			assertEquals(AgentBoardService.promptVersion("build"), row.get("promptVersion"));
		}

		ExecutionResult bound = asKey(c.key(), q, vars("b", b, "h", Collections.nCopies(50, "a".repeat(128))));
		assertTrue(ok(bound), bound.getErrors().toString());
		assertEquals("SERVED", byVersion(coder(data(bound, "agentTaskRoleConfigsProgrammatic"))).get("status"));

		ExecutionResult assigned = asKey(c.key(), "mutation($t: ID!, $s: ID!, $h: [String!]) {"
				+ " agentTaskAssignProgrammatic(taskUuid: $t, sessionUuid: $s) { task { uuid status } rolePrompt"
				+ " promptByVersion(heldVersions: $h) { version } } }", vars("t", w.onA().getUuid().toString(),
						"s", c.session().getUuid().toString(), "h", Collections.nCopies(51, "a")));
		assertOneRefusal(assigned);
		Map<String, Object> a = (Map<String, Object>) ((Map<String, Object>) assigned.getData()).get("agentTaskAssignProgrammatic");
		assertEquals("ASSIGNED", ((Map<String, Object>) a.get("task")).get("status"));
		String expected = agentBoardService.servedPromptFor(agentBoardService.getBoardData(w.a().getUuid()).orElseThrow(),
				"build");
		assertEquals(expected, a.get("rolePrompt"), "the text is still served beside the refused field");
		assertNull(a.get("promptByVersion"));
		assertEquals(TaskStatus.ASSIGNED, agentTaskService.getTaskData(w.onA().getUuid()).orElseThrow().getStatus(),
				"a bad list never undoes the hop");

		AgentTaskData fresh = queued(w.a());
		ExecutionResult nul = asKey(c.key(), ASSIGN_BV, vars("t", fresh.getUuid().toString(),
				"s", c.session().getUuid().toString(), "h", Arrays.asList("a", null)));
		assertEquals(1, nul.getErrors().size(), nul.getErrors().toString());
		assertTrue(nul.getErrors().get(0).getMessage().contains("NonNull type 'String!'"), nul.getErrors().get(0).getMessage());
		assertTrue(String.valueOf(nul.getErrors().get(0).toSpecification().get("extensions"))
				.contains("classification=ValidationError"), String.valueOf(nul.getErrors().get(0).toSpecification()));
		assertNull(nul.getData(), "refused before any resolver runs");
		assertEquals(TaskStatus.QUEUED, agentTaskService.getTaskData(fresh.getUuid()).orElseThrow().getStatus(),
				"nothing was done");
	}
}
