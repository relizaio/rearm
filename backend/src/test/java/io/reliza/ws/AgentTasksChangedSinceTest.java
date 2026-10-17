/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
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
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Which tasks changed since an instant (task 9540d3b6): AgentTask.updatedAt moves on every save, and
 * agentTasksProgrammatic / agentTasksOfBoard(changedSince:) return the tasks changed at or after it,
 * oldest change first. Called through the real resolvers with the principal the programmatic filter
 * leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTasksChangedSinceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private UserService userService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;

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

	private List<AgentTaskData> tasks(Organization org, int n) throws RelizaException {
		Component target = componentService.createComponent("bu_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "bu-" + UUID.randomUUID(), "bulk",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		List<AgentTaskData> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(agentTaskService.register(board, null, "task " + i, null, null, null, null, null, null,
					AgentActor.ofUser(UUID.randomUUID(), "someone"), true, WU));
		}
		return out;
	}

	private ApiKey agentKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "bulk agent", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.AGENT,
				PermissionFunction.BOARD_READ),
				null)), WU);
		return key;
	}

	private ApiKey personKey(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Reader " + tag, "bulk-" + tag + "@tasks.io", true, List.of(org.getUuid()),
				"bulk-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.BOARD_READ), null, WU);
		ApiKey key = apiKeyService.createObjectApiKey(u.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "bulk person", WU);
		// A board function beside the org tier since board enforcement (task d8e7bd7e): reads need BOARD_READ.
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), PermissionType.READ_ONLY, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.BOARD_READ),
				null)), WU);
		return key;
	}

	private DgsDataFetchingEnvironment as(ApiKey key) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).build(), applicationContext);
	}

	private static List<UUID> uuids(List<AgentTaskData> ts) {
		return ts.stream().map(AgentTaskData::getUuid).toList();
	}

	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");

	/** A moment strictly after every save so far, and the next save strictly after it. */
	private static ZonedDateTime tick() {
		try {
			Thread.sleep(15);
			ZonedDateTime at = ZonedDateTime.now();
			Thread.sleep(15);
			return at;
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private ZonedDateTime updatedAt(AgentTaskData td) {
		return fetcher.taskUpdatedAt(new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl
				.newDataFetchingEnvironment().source(reload(td)).build(), applicationContext));
	}

	@Test
	public void updatedAtMovesOnEveryKindOfSave() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentTaskData t = tasks(org, 1).get(0);
		assertTrue(null != updatedAt(t), "a new task has one");

		ZonedDateTime before = tick();
		agentTaskService.setBudget(t.getUuid(), 5_000_000L, PERSON, WU);
		assertTrue(updatedAt(t).isAfter(before), "a budget, which changes no status");
		before = tick();
		agentTaskService.hold(t.getUuid(), HoldLevel.OPERATOR, "looking", PERSON, WU);
		assertTrue(updatedAt(t).isAfter(before), "a hold");
		before = tick();
		// Released on a board with no roles, the task routes straight to completion: a transition too.
		agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, WU);
		assertTrue(updatedAt(t).isAfter(before), "a release");
		assertEquals(reload(t).getUpdatedDate(), updatedAt(t), "served as stored on the row");
	}

	@Test
	public void changedSinceReturnsOnlyWhatMovedOldestChangeFirst() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		List<AgentTaskData> ts = tasks(org, 3);
		UUID board = ts.get(0).getBoard();
		ZonedDateTime cursor = tick();
		agentTaskService.setBudget(ts.get(2).getUuid(), 1_000_000L, PERSON, WU);
		tick();
		agentTaskService.hold(ts.get(0).getUuid(), HoldLevel.OPERATOR, "parked", PERSON, WU);

		List<AgentTaskData> moved = fetcher.agentTasksProgrammatic(board, null, cursor, as(agentKey(org)));
		assertEquals(List.of(ts.get(2).getUuid(), ts.get(0).getUuid()), uuids(moved),
				"the two that changed, oldest change first; the untouched one absent");
		ZonedDateTime next = updatedAt(moved.get(moved.size() - 1));
		assertEquals(List.of(ts.get(0).getUuid()), uuids(fetcher.agentTasksProgrammatic(board, null, next, as(agentKey(org)))),
				"at or after: the last one's updatedAt is the next cursor, and returns it once more");
		assertEquals(3, fetcher.agentTasksProgrammatic(board, null, null, as(agentKey(org))).size(), "no cursor, the whole board");

		String held = reload(ts.get(0)).getStatus().name();
		assertEquals(List.of(ts.get(0).getUuid()), uuids(fetcher.agentTasksProgrammatic(board, held, cursor, as(agentKey(org)))),
				"combined with a status");
		assertEquals(List.of(ts.get(2).getUuid(), ts.get(0).getUuid()),
				uuids(fetcher.agentTasksOfBoard(board, null, cursor, as(personKey(org)))), "a person's read too");
		assertEquals(List.of(), fetcher.agentTasksProgrammatic(board, null, tick(), as(agentKey(org))), "nothing since now");
	}
}
