/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * A named set of tasks in one read (task cc14f4cb): order as given, what the reader may not see
 * absent as if unknown, at most 100. Called through the real resolvers with the principal the
 * programmatic filter leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTasksByUuidTest {

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

	@Test
	public void anAgentKeyReadsANamedSetInTheOrderGiven() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		List<AgentTaskData> mine = tasks(org, 3);
		List<AgentTaskData> theirs = tasks(testInitializer.obtainOrganization(), 1);
		List<UUID> asked = List.of(mine.get(2).getUuid(), theirs.get(0).getUuid(), UUID.randomUUID(),
				mine.get(0).getUuid(), mine.get(2).getUuid());

		List<AgentTaskData> read = fetcher.agentTasksByUuidProgrammatic(asked, as(agentKey(org)));

		assertEquals(List.of(mine.get(2).getUuid(), mine.get(0).getUuid()), uuids(read),
				"order as given, once each; another org's task and an unknown one absent alike");
	}

	@Test
	public void aPersonReadsWhatTheirOrganizationsHold() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		List<AgentTaskData> mine = tasks(org, 2);
		List<AgentTaskData> theirs = tasks(testInitializer.obtainOrganization(), 1);
		List<AgentTaskData> read = fetcher.agentTasksByUuid(
				List.of(theirs.get(0).getUuid(), mine.get(1).getUuid(), mine.get(0).getUuid()), as(personKey(org)));
		assertEquals(List.of(mine.get(1).getUuid(), mine.get(0).getUuid()), uuids(read));
		assertTrue(fetcher.agentTasksByUuid(List.of(UUID.randomUUID()), as(personKey(org))).isEmpty());
	}

	@Test
	public void moreThanAHundredIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		List<UUID> many = new ArrayList<>();
		for (int i = 0; i <= AgentTaskService.TASKS_BY_UUID_MAX; i++) many.add(UUID.randomUUID());
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.agentTasksByUuidProgrammatic(many, as(agentKey(org))));
		assertTrue(e.getMessage().contains("At most 100 tasks in one read"), e.getMessage());
		assertTrue(fetcher.agentTasksByUuidProgrammatic(many.subList(0, 100), as(agentKey(org))).isEmpty(),
				"a hundred is allowed");
	}

	@Test
	public void theEndpointServesBothReads() throws Exception {
		ProgrammaticSchemaRegistry registry = new ProgrammaticSchemaRegistry();
		assertTrue(registry.allows(Operation.QUERY, "agentTasksByUuidProgrammatic"));
		assertTrue(registry.allows(Operation.QUERY, "agentTasksByUuid"));
	}
}
