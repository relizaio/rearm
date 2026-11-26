/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;

/**
 * TPF-152 mutation round: the flag reaches the service through the programmatic sign-off mutation that the CLI's
 * {@code --no-split} calls. Every other waiver test calls the service directly.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointWaiverProgrammaticIntegrationTest extends AgentDesignCheckpointTestBase {

	static final String NOTE = "fits one coder pass";
	static final String WAIVING = "mutation($t: ID!, $s: ID!, $o: [ID!]) { agentTaskSignOffProgrammatic(taskUuid: $t,"
			+ " sessionUuid: $s, outcome: PASSED, note: \"" + NOTE + "\", outputs: $o, noSplit: true) { uuid status } }";
	static final String PLAIN = "mutation($t: ID!, $s: ID!, $o: [ID!]) { agentTaskSignOffProgrammatic(taskUuid: $t,"
			+ " sessionUuid: $s, outcome: PASSED, note: \"" + NOTE + "\", outputs: $o) { uuid status } }";

	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired ApiKeyService apiKeyService;
	@Autowired LicenseStatus licenseStatus;
	@Autowired AgentIdentityService agentIdentityService;

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

	private ApiKey agentKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "waiver programmatic", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_AGENT), null)),
				WU);
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

	/** The architect, on a key, takes the task, publishes both design documents and signs off through the mutation. */
	private AgentTaskData signedOff(Rig r, ApiKey key, String title, String mutation) throws RelizaException {
		Worker architect = workerOnKey(r.org(), "architect", key);
		AgentTaskData t = task(r, title);
		assign(r, t, architect);
		ReleaseData arch = publish(architect, t, ARCH);
		ReleaseData tm = publish(architect, t, TM);
		ExecutionResult res = asKey(key, mutation, Map.of("t", t.getUuid().toString(), "s", architect.sessionUuid().toString(),
				"o", List.of(arch.getUuid().toString(), tm.getUuid().toString())));
		assertTrue(res.getErrors().isEmpty(), res.getErrors().toString());
		return reload(t);
	}

	@Test
	public void theNoSplitArgumentOfTheProgrammaticSignOffWaivesTheCheckpoint() throws RelizaException {
		Rig r = plain();
		ApiKey key = agentKey(r.org());

		AgentTaskData waived = signedOff(r, key, "programmatic, with the flag", WAIVING);
		assertEquals(TaskStatus.QUEUED, waived.getStatus(), waived.getStatusHistory().toString());
		assertEquals("coder", waived.getRole());
		assertNotNull(waived.getDesignCheckpointWaiver());
		assertEquals(NOTE, waived.getDesignCheckpointWaiver().note());
		SignOff last = waived.getSignOffs().get(waived.getSignOffs().size() - 1);
		assertEquals(Boolean.TRUE, last.noSplit());

		AgentTaskData stopped = signedOff(r, key, "programmatic, without the flag", PLAIN);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), "the control: no flag, the task stops");
		assertNull(stopped.getDesignCheckpointWaiver());
	}
}
