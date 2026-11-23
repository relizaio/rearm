/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;

/**
 * The record and the condition as clients read them (task TPF-155, 4.4, C14; T-93): the task's
 * {@code rolesSkippedByCondition} with {@code live} resolved by the server per task through the parent resolver's
 * local context, a role's {@code runWhen} on the role list, and the seat setting and clearing it through its mutation.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
public class AgentConditionalRoleReadsIntegrationTest extends AgentConditionalRoleTestBase {

	static final String SKIPS = " rolesSkippedByCondition { role roleUuid reason at by { kind name } waives live }";
	static final String TASK = "query($t: ID!) { agentTaskProgrammatic(taskUuid: $t) { uuid" + SKIPS + " } }";
	static final String TASKS = "query($b: ID!) { agentTasksProgrammatic(boardUuid: $b) { uuid" + SKIPS + " } }";
	static final String ROLES = "query($b: ID!) { agentTaskRoleConfigsProgrammatic(boardUuid: $b) { name"
			+ " runWhen { taggedAny taggedNone } } }";
	static final String SET = "mutation($b: ID!, $s: ID!, $i: AgentTaskRoleConfigInput!) {"
			+ " agentTaskRoleConfigSetProgrammatic(boardUuid: $b, sessionUuid: $s, input: $i) { name"
			+ " runWhen { taggedAny taggedNone } } }";

	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired ApiKeyService apiKeyService;
	@Autowired AgentIdentityService agentIdentityService;
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

	/** A FREEFORM key with AGENT, BOARD_READ, BOARD_AGENT, BOARD_WRITE and CONFIGURATION_WRITE on the organization. */
	private ApiKey agentKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "conditional reads", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_AGENT,
						PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE), null)), WU);
		return key;
	}

	private Worker workerOnKey(Organization org, String name, ApiKey key) throws RelizaException {
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), identity,
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

	private List<Map<String, Object>> skipsRead(ApiKey key, AgentTaskData t) {
		Map<String, Object> task = data(asKey(key, TASK, Map.of("t", t.getUuid().toString())), "agentTaskProgrammatic");
		return (List<Map<String, Object>>) task.get("rolesSkippedByCondition");
	}

	private static List<Object> live(List<Map<String, Object>> skips) {
		return skips.stream().map(m -> m.get("live")).toList();
	}

	/** T-93 (a), (b): the record reads back with routing as its actor; the role list carries the condition. */
	@Test
	public void theRecordAndTheConditionReadBack() throws RelizaException {
		CRig c = conditional();
		ApiKey key = agentKey(c.rig().org());
		AgentTaskData t = designAndCode(c, task(c, "read back"));
		List<Map<String, Object>> skips = skipsRead(key, t);
		assertEquals(1, skips.size(), skips.toString());
		Map<String, Object> s = skips.get(0);
		assertEquals("mutant", s.get("role"));
		assertEquals(NO_MUTATION_TAG, s.get("reason"));
		assertEquals(Map.of("kind", "SYSTEM", "name", "routing"), s.get("by"));
		assertEquals(List.of(), s.get("waives"));
		assertEquals(true, s.get("live"));

		Map<String, Map<String, Object>> roles = rolesRead(key, c);
		assertEquals(Map.of("taggedAny", List.of(MUTATION), "taggedNone", List.of()), roles.get("mutant").get("runWhen"));
		assertNull(roles.get("coder").get("runWhen"));

		// the seat sets it through its mutation, lower-cased, and clears it with null
		Worker seat = workerOnKey(c.rig().org(), "seat", key);
		agentBoardService.claimCoordinatorSeat(c.rig().board().getUuid(), seat.sessionUuid(), seat.agentUuid(), WU);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("name", "mutant");
		input.put("runWhen", Map.of("taggedAny", List.of("Mutation", "perf")));
		Map<String, Object> set = data(asKey(key, SET, Map.of("b", c.rig().board().getUuid().toString(), "s",
				seat.sessionUuid().toString(), "i", input)), "agentTaskRoleConfigSetProgrammatic");
		assertEquals(Map.of("taggedAny", List.of(MUTATION, "perf"), "taggedNone", List.of()), set.get("runWhen"));
		input.put("runWhen", null);
		Map<String, Object> cleared = data(asKey(key, SET, Map.of("b", c.rig().board().getUuid().toString(), "s",
				seat.sessionUuid().toString(), "i", input)), "agentTaskRoleConfigSetProgrammatic");
		assertNull(cleared.get("runWhen"));
		assertNull(rolesRead(key, c).get("mutant").get("runWhen"));
	}

	private Map<String, Map<String, Object>> rolesRead(ApiKey key, CRig c) {
		List<Map<String, Object>> roles = data(asKey(key, ROLES, Map.of("b", c.rig().board().getUuid().toString())),
				"agentTaskRoleConfigsProgrammatic");
		Map<String, Map<String, Object>> out = new HashMap<>();
		roles.forEach(m -> out.put((String) m.get("name"), m));
		return out;
	}

	/** T-93 (c): live is the server's answer per entry and per task, through a single read and a list read. */
	@Test
	public void liveIsResolvedByTheServerPerTask() throws RelizaException {
		CRig c = conditional();
		ApiKey key = agentKey(c.rig().org());
		AgentTaskData a = designAndCode(c, task(c, "run by name, then a second entry"));
		assertEquals(List.of(true), live(skipsRead(key, a)));
		agentTaskService.hold(a.getUuid(), HoldLevel.COORDINATOR, "run the mutant", COORD, WU);
		agentTaskService.liftHold(a.getUuid(), HoldLevel.COORDINATOR, COORD, null, "mutant", WU);
		pass(c.rig(), a, c.w("mutant"));
		assertEquals(List.of(false), live(skipsRead(key, a)), "the mutant signed off after it");
		testRejects(c.rig(), a, DD, "T-1");
		code(c.rig(), a);
		assertEquals(List.of(false, true), live(skipsRead(key, a)));

		AgentTaskData b = designAndCode(c, task(c, "completed, then reopened to the mutant"));
		pass(c.rig(), b, c.rig().tester());
		assertEquals(TaskStatus.COMPLETED, reload(b).getStatus());
		assertEquals(List.of(true), live(skipsRead(key, b)));
		agentTaskService.reopen(b.getUuid(), "mutant", "run it", PERSON, WU);
		assertEquals(List.of(false), live(skipsRead(key, b)), "a reopen to the mutant after it");

		AgentTaskData d = designAndCode(c, task(c, "live"));
		List<Map<String, Object>> tasks = data(asKey(key, TASKS, Map.of("b", c.rig().board().getUuid().toString())),
				"agentTasksProgrammatic");
		Map<String, List<Object>> byTask = new HashMap<>();
		tasks.forEach(m -> byTask.put((String) m.get("uuid"), live((List<Map<String, Object>>) m.get(
				"rolesSkippedByCondition"))));
		assertEquals(List.of(false, true), byTask.get(a.getUuid().toString()));
		assertEquals(List.of(false), byTask.get(b.getUuid().toString()));
		assertEquals(List.of(true), byTask.get(d.getUuid().toString()));
	}

	/** T-93 (c), the blind read: a blind tester reading the task it holds sees the same entries and the same live. */
	@Test
	public void aBlindReaderReadsTheSameLive() throws RelizaException {
		CRig c = conditional(mutantDef(TAGGED_MUTATION, List.of(assembled(DD)), List.of()),
				new RoleDef("tester", 50, RoleNecessity.REQUIRED, List.of(input(DD)), List.of(produces(BTR)), null, null,
						Boolean.TRUE));
		ApiKey key = agentKey(c.rig().org());
		AgentTaskData t = designAndCode(c, task(c, "blind tester"));
		Worker blind = workerOnKey(c.rig().org(), "tester", key);
		agentTaskService.assign(t.getUuid(), board(c.rig()), blind.agentUuid(), blind.sessionUuid(), WU);
		List<Map<String, Object>> skips = skipsRead(key, t);
		assertEquals(1, skips.size(), skips.toString());
		assertEquals("mutant", skips.get(0).get("role"));
		assertEquals(NO_MUTATION_TAG, skips.get(0).get("reason"));
		assertEquals(List.of(true), live(skips));
	}
}
