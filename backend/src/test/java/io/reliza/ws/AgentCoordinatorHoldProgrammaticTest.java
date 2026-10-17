/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.language.OperationDefinition.Operation;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * The coordinator's hold verbs on the programmatic endpoint (task c0a2134c): escalate hands a
 * coordinator hold to the operator, release carries a note to the feed, and both are the seat
 * holder's alone. Called through the real resolvers with the principal the programmatic filter
 * leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCoordinatorHoldProgrammaticTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private AgentService agentService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;

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

	private record Seat(Organization org, ApiKey key, AgentBoardData board, AgentSessionData holder,
			AgentSessionData stranger) {}

	/** A board whose seat one session of the key's agent holds, and another session of it does not. */
	private Seat seat() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "coordinator hold test", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT,
				PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_WRITE),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity,
				"coordinator-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData holder = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"seat-" + UUID.randomUUID(), "the seat", null, null, WU);
		AgentSessionData stranger = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"other-" + UUID.randomUUID(), "not the seat", null, null, WU);
		Component target = componentService.createComponent("hold_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "hold-" + UUID.randomUUID(), "hold board",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		board = agentBoardService.claimCoordinatorSeat(board.getUuid(), holder.getUuid(), agent.getUuid(), WU);
		return new Seat(org, key, board, holder, stranger);
	}

	private AgentTaskData heldByTheCoordinator(Seat s, String title) throws RelizaException {
		AgentTaskData td = agentTaskService.register(s.board(), null, title, null, null, null, null, null, null,
				AgentActor.ofUser(UUID.randomUUID(), "someone"), true, WU);
		return agentTaskService.hold(td.getUuid(), HoldLevel.COORDINATOR, "waiting on the tracker",
				AgentActor.ofSession(s.holder().getUuid()), WU);
	}

	private DgsDataFetchingEnvironment as(ApiKey key) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.9"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).build(), applicationContext);
	}

	@Test
	public void theSeatHolderEscalatesAndAStrangerIsRefused() throws RelizaException {
		Seat s = seat();
		AgentTaskData td = heldByTheCoordinator(s, "escalated through the endpoint");

		AccessDeniedException stranger = assertThrows(AccessDeniedException.class, () -> fetcher
				.agentTaskEscalateHoldProgrammatic(td.getUuid(), s.stranger().getUuid(), "not mine", as(s.key())));
		assertTrue(stranger.getMessage().contains("does not hold the coordinator seat"), stranger.getMessage());
		assertEquals(HoldLevel.COORDINATOR, agentTaskService.getTaskData(td.getUuid()).orElseThrow().getHold().level());

		AgentTaskData escalated = fetcher.agentTaskEscalateHoldProgrammatic(td.getUuid(), s.holder().getUuid(),
				"the tracker issue needs a person", as(s.key()));
		assertEquals(HoldLevel.OPERATOR, escalated.getHold().level());
		assertEquals(AgentActor.ofSession(s.holder().getUuid()), escalated.getHold().heldBy());
		assertTrue(escalated.getHold().reason().endsWith(" — escalated by the coordinator: the tracker issue needs a person"),
				escalated.getHold().reason());
	}

	@Test
	public void theSeatHoldersReleaseCarriesItsNote() throws RelizaException {
		Seat s = seat();
		AgentTaskData td = heldByTheCoordinator(s, "released with a note");
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskLiftHoldProgrammatic(td.getUuid(),
				s.stranger().getUuid(), null, "not mine", as(s.key())));

		AgentTaskData released = fetcher.agentTaskLiftHoldProgrammatic(td.getUuid(), s.holder().getUuid(), null,
				"the tracker answered", as(s.key()));
		assertTrue(TaskStatus.ON_HOLD != released.getStatus(), "released and routed: " + released.getStatus());
		assertTrue(agentBoardService.recentEvents(agentBoardService.getBoardData(s.board().getUuid()).orElseThrow().getUuid()).stream()
				.anyMatch(ev -> ev.message().contains(": the tracker answered")), "the note is on the feed");
	}

	@Test
	public void theEndpointServesEscalate() throws Exception {
		assertTrue(new ProgrammaticSchemaRegistry().allows(Operation.MUTATION, "agentTaskEscalateHoldProgrammatic"));
	}
}
