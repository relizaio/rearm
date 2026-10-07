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
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.BoardEventKind;
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
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Who may read a board's event log (task 1c5442d2): an agent key of the board's organization on
 * the agent read, a person with board access on the person read, and nobody from another
 * organization. Called through the real resolvers with the principal the programmatic filter
 * leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBoardEventsAccessTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private UserService userService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
	@BeforeEach
	void operational() {
		// A context of this test's own, as the coding principles ask: a unit test that left a mocked
		// JWT in the holder would otherwise send the person's read down the browser branch.
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

	private AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("ev_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData bd = agentBoardService.createBoard(org.getUuid(), "ev-" + UUID.randomUUID(), "events",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.postEvent(bd.getUuid(), BoardEventKind.INFO, "hello", AgentActor.system("test"), WU);
		return bd;
	}

	private ApiKey agentKey(Organization org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "events agent", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.AGENT,
				PermissionFunction.BOARD_READ),
				null)), WU);
		return key;
	}

	/** A reader of the organization, and the personal key rearm login would mint for them. */
	private ApiKey personKey(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Reader " + tag, "reader-" + tag + "@events.io", true, List.of(org.getUuid()),
				"reader-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.BOARD_READ), null, WU);
		ApiKey key = apiKeyService.createObjectApiKey(u.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "events person", WU);
		// A board function beside the org tier since board enforcement (task d8e7bd7e): reads need BOARD_READ.
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), PermissionType.READ_ONLY, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY, Set.of(PermissionFunction.BOARD_READ),
				null)), WU);
		return key;
	}

	private DgsDataFetchingEnvironment as(ApiKey key) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.11"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).build(), applicationContext);
	}

	@Test
	public void anAgentKeyReadsItsOrganizationsLogAndNoOther() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org);
		AgentBoardService.EventPage page = fetcher.agentBoardEventsProgrammatic(bd.getUuid(), null, null, null,
				as(agentKey(org)));
		assertTrue(page.events().stream().anyMatch(e -> "hello".equals(e.message())), page.events().toString());

		Organization stranger = testInitializer.obtainOrganization();
		assertThrows(AccessDeniedException.class, () -> fetcher.agentBoardEventsProgrammatic(bd.getUuid(), null, null,
				null, as(agentKey(stranger))));
	}

	@Test
	public void aPersonWithBoardAccessReadsItAndAStrangerDoesNot() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org);
		AgentBoardService.EventPage page = fetcher.agentBoardEvents(bd.getUuid(), null, null, 10, as(personKey(org)));
		assertEquals("hello", page.events().get(page.events().size() - 1).message());

		Organization stranger = testInitializer.obtainOrganization();
		assertThrows(AccessDeniedException.class, () -> fetcher.agentBoardEvents(bd.getUuid(), null, null, 10,
				as(personKey(stranger))));
	}

	@Test
	public void theEndpointServesBothReads() throws Exception {
		ProgrammaticSchemaRegistry registry = new ProgrammaticSchemaRegistry();
		assertTrue(registry.allows(Operation.QUERY, "agentBoardEventsProgrammatic"));
		assertTrue(registry.allows(Operation.QUERY, "agentBoardEvents"));
	}
}
