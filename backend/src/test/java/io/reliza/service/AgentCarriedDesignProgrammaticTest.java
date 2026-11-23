/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.ExecutionResult;
import graphql.GraphQLContext;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.ws.AgentTaskDataFetcher;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;

/**
 * The flag through the seat's programmatic split (task TPF-161, D10; built by TPF-162): only the seat holder splits,
 * a proposal's flag becomes effective only when the seat splits with it, and the record's {@code live} and
 * {@code standing} are resolved by the server. Rows T-17, T-22, T-24a and T-24b of the TPF-161 architecture, round 6
 * (TPF-162 architecture, round 1). Called through the real resolvers with the principal the programmatic filter
 * leaves on the request, the environment built as {@code AgentCoordinatorHoldProgrammaticTest} builds it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCarriedDesignProgrammaticTest extends AgentCarriedDesignTestBase {

	@Autowired AgentTaskDataFetcher fetcher;
	@Autowired ApiKeyService apiKeyService;
	@Autowired AgentIdentityService agentIdentityService;
	@Autowired LicenseStatus licenseStatus;
	@Autowired ApplicationContext applicationContext;
	@Autowired DgsQueryExecutor dgsQueryExecutor;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
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

	/** The seat on the rig's board: one session of the key's agent holds it, another does not. */
	record Seat(ApiKey key, AgentSessionData holder, AgentSessionData stranger) {}

	Seat seat(Rig r) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(r.org().getUuid(), ApiTypeEnum.FREEFORM, r.org().getUuid(),
				UUID.randomUUID().toString(), "carried design programmatic", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(r.org().getUuid(),
				PermissionScope.ORGANIZATION, r.org().getUuid(), PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT,
				PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_WRITE,
				PermissionFunction.CONFIGURATION_WRITE),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(r.org().getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(r.org().getUuid(), identity,
				"coordinator-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData holder = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), key.getUuid(),
				"seat-" + UUID.randomUUID(), "the seat", null, null, WU);
		AgentSessionData stranger = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), key.getUuid(),
				"other-" + UUID.randomUUID(), "not the seat", null, null, WU);
		agentBoardService.claimCoordinatorSeat(r.board().getUuid(), holder.getUuid(), agent.getUuid(), WU);
		return new Seat(key, holder, stranger);
	}

	/** The programmatic request environment with {@code children} as the mutation's argument. */
	DgsDataFetchingEnvironment as(ApiKey key, List<Map<String, Object>> children) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.13"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of("children", children)).build(), applicationContext);
	}

	static final List<Map<String, Object>> FLAGGED = List.of(Map.of("title", "part 1", "designFromParent", true));

	/** T-24a: a session that does not hold the seat is refused before the children are read; nothing is carried. */
	@Test
	public void aStrangerCannotSplitWithTheFlag() throws RelizaException {
		Rig r = reviewed();
		Seat s = seat(r);
		AgentTaskData parent = task(r, "programmatic, a stranger");
		atCheckpoint(r, parent);
		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskSplitProgrammatic(
				parent.getUuid(), s.stranger().getUuid(), as(s.key(), FLAGGED)));
		assertTrue(e.getMessage().contains("does not hold the coordinator seat"), e.getMessage());
		assertNoChildren(parent);
		assertNull(reload(parent).getCarriedDesign());
		assertTrue(reload(parent).checkpointPending());
	}

	/** T-24b: the seat's same call registers one flagged child carrying the design in the seat's name. */
	@Test
	public void theSeatSplitsWithTheFlag() throws RelizaException {
		Rig r = reviewed();
		Seat s = seat(r);
		AgentTaskData parent = task(r, "programmatic, the seat");
		atCheckpoint(r, parent);
		List<AgentTaskData> children = fetcher.agentTaskSplitProgrammatic(parent.getUuid(), s.holder().getUuid(),
				as(s.key(), FLAGGED));
		assertEquals(1, children.size());
		CarriedDesign cd = reload(children.get(0)).getCarriedDesign();
		assertEquals(AgentActor.ofSession(s.holder().getUuid()), cd.by());
		assertEquals(parentDesign(parent).stream().map(rd -> rd.getUuid()).toList(),
				cd.rounds().stream().map(cr -> cr.release()).toList());
	}

	/** T-17: a worker's flagged proposal carries nothing until the seat splits with the children it proposed. */
	@Test
	public void aProposalsFlagTakesEffectOnlyInTheSeatsSplit() throws RelizaException {
		Rig r = reviewed();
		Seat s = seat(r);
		AgentTaskData parent = task(r, "programmatic, from the request");
		sentOn(r, parent);
		takeAndReturnSplit(r, parent, r.coder(), List.of(flagged("part 1"), child("part 2", 0)));
		AgentTaskData requested = reload(parent);
		assertTrue(requested.splitRequested());
		assertNull(requested.getCarriedDesign());
		List<Map<String, Object>> fromRequest = requested.getSplitRequest().proposedChildren().stream()
				.map(AgentCarriedDesignProgrammaticTest::asInput).toList();

		List<AgentTaskData> children = fetcher.agentTaskSplitProgrammatic(parent.getUuid(), s.holder().getUuid(),
				as(s.key(), fromRequest));
		AgentTaskData first = reload(children.get(0));
		assertTrue(first.isDesignCarried());
		assertEquals(AgentActor.ofSession(s.holder().getUuid()), first.getCarriedDesign().by());
		assertFalse(reload(children.get(1)).isDesignCarried());
		assertEquals(List.of(first.getUuid()), reload(children.get(1)).getDependsOn());
	}

	/** 6.1: the task read serves the record, its rounds and roles, and the server-resolved live and standing. */
	@SuppressWarnings("unchecked")
	@Test
	public void theTaskReadServesTheRecord() throws RelizaException {
		Rig r = reviewed();
		Seat s = seat(r);
		Carried c = carried(r, "programmatic, the read");
		String query = "query($t: ID!) { agentTaskProgrammatic(taskUuid: $t) { carriedDesign { parentTask parentKey at"
				+ " by { kind } standing rounds { specification release round path } roles { role roleUuid signedOffAt"
				+ " session reviewedBy { kind } decidedOver live } } } }";
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(s.key(), 1, "198.51.100.13"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		ExecutionResult er = dgsQueryExecutor.execute(query, Map.of("t", c.flagged().getUuid().toString()), Map.of(),
				headers, null, new ServletWebRequest(request));
		assertTrue(er.getErrors().isEmpty(), er.getErrors().toString());
		Map<String, Object> cd = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) er.getData())
				.get("agentTaskProgrammatic")).get("carriedDesign");
		assertEquals(c.parent().keyOrUuid(), cd.get("parentKey"));
		assertEquals(Boolean.TRUE, cd.get("standing"));
		List<Map<String, Object>> rounds = (List<Map<String, Object>>) cd.get("rounds");
		assertEquals(List.of("ARCHITECTURE", "THREAT_MODEL"), rounds.stream().map(m -> m.get("specification")).toList());
		List<Map<String, Object>> roles = (List<Map<String, Object>>) cd.get("roles");
		assertEquals(DESIGN_ROLES, roles.stream().map(m -> m.get("role")).toList());
		for (Map<String, Object> role : roles) {
			assertEquals(Boolean.TRUE, role.get("live"), role.toString());
			assertEquals(Boolean.FALSE, role.get("decidedOver"), role.toString());
			assertEquals(lastOf(c.parent(), (String) role.get("role")).session().toString(), role.get("session"));
		}
		Map<String, Object> plain = (Map<String, Object>) ((Map<String, Object>) dgsQueryExecutor.execute(query,
				Map.of("t", c.plain().getUuid().toString()), Map.of(), headers, null, new ServletWebRequest(request))
				.getData()).get("agentTaskProgrammatic");
		assertNull(plain.get("carriedDesign"));
	}

	/** A proposed child as the CLI's --from-request sends it back: its fields as a children-json entry. */
	static Map<String, Object> asInput(SplitChild c) {
		Map<String, Object> m = new HashMap<>();
		m.put("title", c.title());
		m.put("dependsOnSiblingIndexes", c.dependsOnSiblingIndexes());
		if (null != c.designFromParent()) m.put("designFromParent", c.designFromParent());
		return m;
	}

	/** T-22: the resolvers read the task handed down: a reopened role is not live, the others are; not standing. */
	@Test
	public void theResolversReadLiveAndStandingFromTheTask() throws RelizaException {
		Rig r = reviewed();
		Carried c = authorizedForCoder(r, "programmatic, the resolvers");
		AgentTaskData t = c.flagged();
		code(r, t);
		pass(r, t, r.tester());
		if (TaskStatus.COMPLETED != reload(t).getStatus()) agentTaskService.complete(t.getUuid(), "done", COORD, WU);
		AgentTaskData before = reload(t);
		assertTrue(fetcher.carriedDesignStanding(resolverEnv(before.getCarriedDesign(), before)));

		agentTaskService.reopen(t.getUuid(), "security_reviewer", "look again", COORD, WU);
		AgentTaskData td = reload(t);
		DataFetcherResult<CarriedDesign> got = fetcher.taskCarriedDesign(resolverEnv(td, null));
		assertEquals(td.getCarriedDesign(), got.getData());
		assertEquals(td, got.getLocalContext(), "the task is handed down");
		for (CarriedRole cr : td.getCarriedDesign().roles()) {
			assertEquals(!"security_reviewer".equals(cr.role()), fetcher.carriedRoleLive(resolverEnv(cr, td)), cr.role());
		}
		assertFalse(fetcher.carriedDesignStanding(resolverEnv(td.getCarriedDesign(), td)));
		assertFalse(fetcher.carriedRoleLive(resolverEnv(td.getCarriedDesign().roles().get(0), null)),
				"no task handed down, nothing live");
	}

	private DgsDataFetchingEnvironment resolverEnv(Object source, AgentTaskData localContext) {
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment().source(source)
				.localContext(localContext).build(), applicationContext);
	}
}
