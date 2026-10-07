/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board form's reads and writes through GraphQL itself (task 40f270be).
 *
 * <p>The usage reads (T-1). Their DateTime arguments were bound as
 * String, and DGS refused the scalar's OffsetDateTime before the fetcher ran, so every call
 * answered "Internal server error" -- which a test calling the fetcher method with strings could
 * never see. Here the query is executed with DateTime variables and inline literals.
 *
 * <p>A form save with a refused setting (T-2) writes nothing: the settings were checked after the
 * rest of the form had been saved, so a refused update kept its new description and a refused
 * create left a board without its settings.
 *
 * <p>Only who is asking is mocked; the boards, the checks and the usage reads are real.
 */
@SpringBootTest(classes = {App.class})
public class AgentBoardGraphQlTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@MockitoBean private UserService userService;
	@MockitoBean private AuthorizationService authorizationService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;
	@Autowired private io.reliza.service.GetComponentService getComponentService;

	@BeforeEach
	void signIn() {
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(user));
		// The board verbs check the board's functions (task d8e7bd7e); the mocked resolver grants them.
		try {
			when(authorizationService.userOnBoard(any(), any(), any(), any(), any()))
					.thenReturn(io.reliza.model.WhoUpdated.getTestWhoUpdated());
		} catch (io.reliza.exceptions.RelizaException e) {
			throw new IllegalStateException(e);
		}
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("usage-test").build();
		// A context of its own, not getContext(): several unit tests leave a mocked SecurityContext
		// in the holder, and setting an authentication on that mock does nothing.
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	private AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("usage_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), "usage-" + UUID.randomUUID(), "usage", List.of(),
				"coordinate", 4, null, target.getUuid(), null, WU);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> field(ExecutionResult r, String name) {
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNotNull(data, "data");
		return (Map<String, Object>) data.get(name);
	}

	@Test
	public void boardUsageTakesDateTimeVariables() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		ExecutionResult r = dgsQueryExecutor.execute("""
				query usage($board: ID!, $from: DateTime!, $to: DateTime!) {
					agentBoardUsage(boardUuid: $board, from: $from, to: $to) { requests reports derivedCostMicros }
				}""", Map.of("board", b.getUuid().toString(),
				"from", "2026-01-01T00:00:00Z", "to", "2099-01-01T00:00:00+02:00"));
		Map<String, Object> usage = field(r, "agentBoardUsage");
		assertNotNull(usage, "a board with no sessions still has totals");
		assertEquals(0, ((Number) usage.get("reports")).intValue());
	}

	@Test
	public void boardUsageTakesInlineDateTimeLiterals() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		ExecutionResult r = dgsQueryExecutor.execute("{ agentBoardUsage(boardUuid: \"" + b.getUuid()
				+ "\", from: \"2026-01-01T00:00:00Z\", to: \"2099-01-01T00:00:00Z\") { requests } }");
		assertNotNull(field(r, "agentBoardUsage"));
	}

	private static final String UPDATE = """
			mutation update($board: ID!, $input: AgentBoardInput!) {
				agentBoardUpdate(boardUuid: $board, input: $input) { uuid description budgetMicros softAlertPercent }
			}""";

	private static String refusal(ExecutionResult r) {
		assertEquals(1, r.getErrors().size(), "one refusal: " + r.getErrors());
		return r.getErrors().get(0).getMessage();
	}

	@Test
	public void aRefusedSettingLeavesTheRestOfTheUpdateUnsaved() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		ExecutionResult r = dgsQueryExecutor.execute(UPDATE, Map.of("board", b.getUuid().toString(),
				"input", Map.of("description", "changed by the refused form",
						"settings", Map.of("softAlertPercent", 150))));
		assertTrue(refusal(r).contains("settings.softAlertPercent must be 1..100"), refusal(r));
		AgentBoardData after = agentBoardService.getBoardData(b.getUuid()).orElseThrow();
		assertEquals("usage", after.getDescription(), "the description was not saved");
		assertEquals(b.getSoftAlertPercent(), after.getSoftAlertPercent());
	}

	@Test
	public void anAcceptedSettingSavesWithTheRestOfTheUpdate() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		Map<String, Object> updated = field(dgsQueryExecutor.execute(UPDATE, Map.of("board", b.getUuid().toString(),
				"input", Map.of("description", "with a budget", "settings", Map.of("budgetMicros", 5_000_000)))),
				"agentBoardUpdate");
		assertEquals("with a budget", updated.get("description"));
		assertEquals(5_000_000L, ((Number) updated.get("budgetMicros")).longValue());
	}

	@Test
	public void aRefusedSettingCreatesNoBoard() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("usage_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		String name = "refused-" + UUID.randomUUID();
		ExecutionResult r = dgsQueryExecutor.execute("""
				mutation create($org: ID!, $input: AgentBoardInput!) {
					agentBoardCreate(orgUuid: $org, input: $input) { uuid }
				}""", Map.of("org", org.getUuid().toString(), "input", Map.of("name", name,
				"target", target.getUuid().toString(), "settings", Map.of("softAlertPercent", 0))));
		assertTrue(refusal(r).contains("settings.softAlertPercent must be 1..100"), refusal(r));
		assertTrue(agentBoardService.listByOrg(org.getUuid()).stream().noneMatch(x -> name.equals(x.getName())),
				"no board was created, so a retry does not make a second one");
	}

	// ---------- the merge procedure from the form (task 71a3dd22) ----------

	@Test
	public void aMergeProcedureTheBoardCannotKeepLeavesTheUpdateUnsaved() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		ExecutionResult r = dgsQueryExecutor.execute(UPDATE, Map.of("board", b.getUuid().toString(),
				"input", Map.of("description", "changed by the refused form",
						"deliveryPolicy", Map.of("mode", "PR_ROWS", "merge", Map.of("by", "COORDINATOR")))));
		assertTrue(refusal(r).contains("delivery.merge.by COORDINATOR: the coordinator does not merge on this board"),
				refusal(r));
		AgentBoardData after = agentBoardService.getBoardData(b.getUuid()).orElseThrow();
		assertEquals("usage", after.getDescription(), "the description was not saved");
		assertEquals(null, after.getDeliveryPolicy());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theFormSetsAMergeProcedureWithTheCapabilityItNeeds() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		Map<String, Object> updated = field(dgsQueryExecutor.execute("""
				mutation update($board: ID!, $input: AgentBoardInput!) {
					agentBoardUpdate(boardUuid: $board, input: $input) {
						deliveryPolicy { merge { by method } }
						effectiveDeliveryPolicy { mode merge { by method atTestedHead requireDeclaration order } }
						servedCoordinatorPrompt
					}
				}""", Map.of("board", b.getUuid().toString(), "input", Map.of(
				"coordinatorCapabilities", List.of("PR_MERGE"),
				"deliveryPolicy", Map.of("merge", Map.of("by", "coordinator", "method", "SQUASH"))))), "agentBoardUpdate");
		Map<String, Object> merge = (Map<String, Object>) ((Map<String, Object>) updated.get("effectiveDeliveryPolicy")).get("merge");
		assertEquals(Map.of("by", "COORDINATOR", "method", "SQUASH", "atTestedHead", true, "requireDeclaration", false,
				"order", "NOTE_ORDER"), merge);
		assertEquals("COORDINATOR", ((Map<String, Object>) ((Map<String, Object>) updated.get("deliveryPolicy")).get("merge")).get("by"),
				"stored normalised");
		String seat = (String) updated.get("servedCoordinatorPrompt");
		assertTrue(seat.contains("- Delivery: merges on this board are made by the coordinator, method SQUASH"), seat);
	}

	@Test
	public void aMergeProcedureTheBoardCannotKeepCreatesNoBoard() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("usage_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		String name = "refused-merge-" + UUID.randomUUID();
		ExecutionResult r = dgsQueryExecutor.execute("""
				mutation create($org: ID!, $input: AgentBoardInput!) {
					agentBoardCreate(orgUuid: $org, input: $input) { uuid }
				}""", Map.of("org", org.getUuid().toString(), "input", Map.of("name", name,
				"target", target.getUuid().toString(),
				"deliveryPolicy", Map.of("mode", "DECLARED", "merge", Map.of("requireDeclaration", false)))));
		assertTrue(refusal(r).contains("delivery.merge.requireDeclaration cannot be false on a DECLARED board"), refusal(r));
		assertTrue(agentBoardService.listByOrg(org.getUuid()).stream().noneMatch(x -> name.equals(x.getName())),
				"no board was created");
	}

	@Test
	public void organizationUsageTakesDateTimeVariables() {
		Organization org = testInitializer.obtainOrganization();
		ExecutionResult r = dgsQueryExecutor.execute("""
				query usage($org: ID!, $from: DateTime!, $to: DateTime!) {
					organizationAgentUsage(orgUuid: $org, from: $from, to: $to) { requests derivedCostMicros }
				}""", Map.of("org", org.getUuid().toString(),
				"from", "2026-01-01T00:00:00Z", "to", "2099-01-01T00:00:00Z"));
		assertNotNull(field(r, "organizationAgentUsage"));
	}

	// ---------- the target's details (task RD2-4, T-1) ----------

	private static final String TARGET = """
			mutation update($board: ID!, $input: AgentBoardInput!) {
				agentBoardUpdate(boardUuid: $board, input: $input) { uuid target targetDetails { uuid name type } }
			}""";

	@Test
	@SuppressWarnings("unchecked")
	public void aBoardReadNamesItsTargetAndNothingWhenTheTargetIsGone() throws RelizaException {
		AgentBoardData b = board(testInitializer.obtainOrganization());
		String name = getComponentService.getComponentData(b.getTarget()).orElseThrow().getName();
		Map<String, Object> read = field(dgsQueryExecutor.execute(TARGET, Map.of("board", b.getUuid().toString(),
				"input", Map.of("description", "names its target"))), "agentBoardUpdate");
		Map<String, Object> details = (Map<String, Object>) read.get("targetDetails");
		assertNotNull(details, "targetDetails is answered: " + read);
		assertEquals(b.getTarget().toString(), details.get("uuid"));
		assertEquals(name, details.get("name"), "the chip's name");
		assertEquals("PRODUCT", details.get("type"));

		AgentBoardData gone = agentBoardService.getBoardData(b.getUuid()).orElseThrow();
		UUID missing = UUID.randomUUID();
		gone.setTarget(missing);
		agentBoardService.saveData(gone, WU);
		Map<String, Object> after = field(dgsQueryExecutor.execute(TARGET, Map.of("board", b.getUuid().toString(),
				"input", Map.of("description", "its target is gone"))), "agentBoardUpdate");
		assertEquals(missing.toString(), after.get("target"));
		assertEquals(null, after.get("targetDetails"), "a target that is gone reads as none, not an error");
	}

	/**
	 * RD3-2: a board's events come from the log, not the row. The single read and the boards list both
	 * serve each board's newest events, oldest first, and the list reads the log once for every board.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void theEventsFieldIsServedFromTheLogForOneBoardAndForTheList() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		io.reliza.model.AgentActor who = io.reliza.model.AgentActor.ofUser(UUID.randomUUID(), "operator");
		for (int i = 1; i <= 3; i++) {
			agentBoardService.postEvent(a.getUuid(), AgentBoardData.BoardEventKind.INFO, "a" + i, who, WU);
		}
		agentBoardService.postEvent(b.getUuid(), AgentBoardData.BoardEventKind.ALERT, "b1", who, WU);

		ExecutionResult one = dgsQueryExecutor.execute("query($b: ID!) { agentBoard(uuid: $b) { uuid events { kind message } } }",
				Map.of("b", a.getUuid().toString()));
		List<Map<String, Object>> events = (List<Map<String, Object>>) field(one, "agentBoard").get("events");
		List<Object> messages = events.stream().map(e -> e.get("message")).toList();
		assertEquals(List.of("a1", "a2", "a3"), messages.subList(messages.size() - 3, messages.size()), "oldest first");

		ExecutionResult list = dgsQueryExecutor.execute("query($o: ID!) { agentBoardsOfOrg(orgUuid: $o) { uuid events { kind message } } }",
				Map.of("o", org.getUuid().toString()));
		assertTrue(list.getErrors().isEmpty(), "no errors: " + list.getErrors());
		List<Map<String, Object>> boards = (List<Map<String, Object>>) ((Map<String, Object>) list.getData()).get("agentBoardsOfOrg");
		Map<String, List<Object>> byBoard = new java.util.HashMap<>();
		for (Map<String, Object> row : boards) {
			byBoard.put((String) row.get("uuid"), ((List<Map<String, Object>>) row.get("events")).stream().map(e -> e.get("message")).toList());
		}
		assertTrue(byBoard.get(a.getUuid().toString()).containsAll(List.of("a1", "a2", "a3")), byBoard.toString());
		assertFalse(byBoard.get(a.getUuid().toString()).contains("b1"), "each board its own feed");
		assertTrue(byBoard.get(b.getUuid().toString()).contains("b1"));
	}
}
