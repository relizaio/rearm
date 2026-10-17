/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.language.OperationDefinition.Operation;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentActor.ActorKind;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ApiKey;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * A person's board verbs from the programmatic endpoint (task b6d7c308): a personal key or a CLI
 * login acts as its owner, with the same admin gate the browser has, and anything that is not a
 * person is refused. The fetcher is called with the request context the programmatic filter
 * leaves behind (the verified principal as a request attribute), so the dispatch under test is the
 * real one; only the HTTP hop is skipped.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentPersonProgrammaticTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String IP = "198.51.100.7";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private UserService userService;
	@Autowired private ComponentService componentService;
	@Autowired private ApplicationContext applicationContext;

	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
	@BeforeEach
	void operational() {
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

	private record Person(User user, String email, String oauthId) {}

	private Person person(Organization org, PermissionType level) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String email = "person-" + tag + "@persontest.io";
		String oauthId = "person-" + UUID.randomUUID();
		User u = userService.createUser("Person " + tag, email, true, List.of(org.getUuid()), oauthId,
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				level, List.of(), null, WU);
		return new Person(u, email, oauthId);
	}

	/** A personal key as rearm login mints it: owned by the user, with an org-wide level. */
	private ApiKey personalKey(Organization org, Person p, PermissionType level) throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(p.user().getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "person test", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), level, List.of(), WU);
		return ak;
	}

	private ApiKey freeformAdminKey(Organization org) throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "person test freeform", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), PermissionType.ADMIN, List.of(), WU);
		return ak;
	}

	private AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("person_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), "person-" + UUID.randomUUID(), "person board",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
	}

	private AgentTaskData task(AgentBoardData board) throws RelizaException {
		return agentTaskService.register(board, null, "a task a person finishes", null, null, null, null, null,
				null, AgentActor.ofUser(UUID.randomUUID(), "someone"), true, WU);
	}

	/** The environment a request on the programmatic endpoint arrives with, after the filter. */
	private DgsDataFetchingEnvironment env(AuthHeaderParse principal, Map<String, Object> arguments) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setRemoteAddr(IP);
		HttpHeaders headers = new HttpHeaders();
		if (null != principal) {
			request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal);
			headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		}
		DgsWebMvcRequestData requestData = new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request));
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, requestData).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	private DgsDataFetchingEnvironment env(AuthHeaderParse principal) {
		return env(principal, Map.of());
	}

	private static StatusChange last(AgentTaskData td) {
		return td.getStatusHistory().get(td.getStatusHistory().size() - 1);
	}

	@Test
	public void aPersonalKeyCompletesATaskAsThePerson() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Person admin = person(org, PermissionType.ADMIN);
		ApiKey key = personalKey(org, admin, PermissionType.ADMIN);
		AgentTaskData td = task(board(org));

		AgentTaskData done = fetcher.agentTaskComplete(td.getUuid(), "finished by hand", null,
				env(AuthHeaderParse.fromVerifiedKey(key, 1, IP)));

		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		AgentActor actor = last(done).actor();
		assertEquals(ActorKind.USER, actor.kind());
		assertEquals(admin.user().getUuid(), actor.uuid());
		assertEquals(admin.email(), actor.name());
		// the key is on record as what carried the call
		assertTrue(apiKeyAccessService.listKeyAccessByOrg(org.getUuid()).stream()
				.anyMatch(a -> key.getUuid().equals(a.getApiKeyUuid())), "the key's use is recorded");
	}

	@Test
	public void aPersonalKeySetsAndClearsATaskBudgetAsThePerson() throws RelizaException {
		// #615's setter, moved to the person context with the other person verbs (b6d7c308 round 3)
		Organization org = testInitializer.obtainOrganization();
		Person admin = person(org, PermissionType.ADMIN);
		ApiKey key = personalKey(org, admin, PermissionType.ADMIN);
		AgentTaskData td = task(board(org));

		AgentTaskData set = fetcher.agentTaskSetBudget(td.getUuid(),
				env(AuthHeaderParse.fromVerifiedKey(key, 1, IP), Map.of("taskUuid", td.getUuid(), "budgetMicros", 2_500_000L)));
		assertEquals(2_500_000L, set.getBudgetMicros());
		assertEquals(ActorKind.USER, set.getBudgetSetBy().kind());
		assertEquals(admin.user().getUuid(), set.getBudgetSetBy().uuid(), "set by the person the key belongs to");

		Map<String, Object> clear = new HashMap<>();
		clear.put("taskUuid", td.getUuid());
		clear.put("budgetMicros", null);
		assertNull(fetcher.agentTaskSetBudget(td.getUuid(), env(AuthHeaderParse.fromVerifiedKey(key, 1, IP), clear))
				.getBudgetMicros(), "null clears");

		ApiKey freeform = freeformAdminKey(org);
		RelizaException refused = assertThrows(RelizaException.class, () -> fetcher.agentTaskSetBudget(td.getUuid(),
				env(AuthHeaderParse.fromVerifiedKey(freeform, 1, IP), Map.of("taskUuid", td.getUuid(), "budgetMicros", 1L))));
		assertEquals(AgentTaskDataFetcher.PERSON_ONLY, refused.getMessage());
	}

	@Test
	public void aPersonalKeyHoldsAndReleasesWithTheRoleArgument() throws RelizaException {
		// the operator hold moved to the person lane (b6d7c308 round 4) carrying #627's role
		Organization org = testInitializer.obtainOrganization();
		Person admin = person(org, PermissionType.ADMIN);
		ApiKey key = personalKey(org, admin, PermissionType.ADMIN);
		AgentTaskData td = task(board(org));
		AuthHeaderParse p = AuthHeaderParse.fromVerifiedKey(key, 1, IP);

		AgentTaskData held = fetcher.agentTaskOperatorHold(td.getUuid(), true, "waiting on legal", null, env(p));
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(admin.user().getUuid(), last(held).actor().uuid());

		// the role reaches the service: one not on the board is refused there, and nothing moves
		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.agentTaskOperatorHold(td.getUuid(),
				false, "go", "no-such-role", env(p)));
		assertEquals("Role no-such-role is not active on this board", e.getMessage());
		assertEquals(TaskStatus.ON_HOLD, agentTaskService.getTaskData(td.getUuid()).orElseThrow().getStatus());

		AgentTaskData released = fetcher.agentTaskOperatorHold(td.getUuid(), false, "go", null, env(p));
		assertNotEquals(TaskStatus.ON_HOLD, released.getStatus());
		assertNull(released.getHold());
		assertTrue(released.getStatusHistory().stream().anyMatch(c -> admin.user().getUuid().equals(c.actor().uuid())
				&& c.to() == TaskStatus.AWAITING_COORDINATOR), "released by the person the key belongs to");
	}

	@Test
	public void aCliLoginRegistersAndAuthorizesAsTheUserWhoLoggedIn() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Person admin = person(org, PermissionType.ADMIN);
		ApiKey key = personalKey(org, admin, PermissionType.ADMIN);
		AgentBoardData board = board(org);
		AuthHeaderParse login = AuthHeaderParse.fromVerifiedSession(key, admin.user().getUuid(), UUID.randomUUID(), IP);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("title", "registered from a terminal");

		AgentTaskData td = fetcher.agentTaskRegister(board.getUuid(), env(login, Map.of("input", input)));
		assertEquals(TaskStatus.PENDING_INTAKE, td.getStatus());
		assertEquals(admin.user().getUuid(), last(td).actor().uuid());

		List<AgentTaskData> listed = fetcher.agentTasksOfBoard(board.getUuid(), null, null, env(login));
		assertTrue(listed.stream().anyMatch(t -> t.getUuid().equals(td.getUuid())), "the person reads the board with the same login");
	}

	@Test
	public void aFreeformKeyIsRefusedForAPersonsAction() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey key = freeformAdminKey(org);
		AgentTaskData td = task(board(org));

		RelizaException e = assertThrows(RelizaException.class, () -> fetcher.agentTaskComplete(
				td.getUuid(), "an agent trying", null, env(AuthHeaderParse.fromVerifiedKey(key, 1, IP))));
		assertEquals(AgentTaskDataFetcher.PERSON_ONLY, e.getMessage());
		assertEquals(TaskStatus.PENDING_INTAKE, agentTaskService.getTaskData(td.getUuid()).orElseThrow().getStatus());
		// refused on what the credential is, before its permissions are consulted: no use on record
		assertTrue(apiKeyAccessService.listKeyAccessByOrg(org.getUuid()).stream()
				.noneMatch(a -> key.getUuid().equals(a.getApiKeyUuid())), "a refused organization key is not recorded as used");
		// a person's read is a person's too
		assertThrows(RelizaException.class, () -> fetcher.agentBoardsOfOrg(org.getUuid(),
				env(AuthHeaderParse.fromVerifiedKey(key, 1, IP))));
	}

	@Test
	public void aFederatedIdentityIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Person admin = person(org, PermissionType.ADMIN);
		AgentTaskData td = task(board(org));
		FederatedContext fc = new FederatedContext(List.of(UUID.randomUUID()), "github",
				"https://token.actions.githubusercontent.com", "relizaio", "relizaio/rearm", null, "refs/heads/main",
				"abc123", "wf.yml@refs/heads/main", null, "push", "octocat", "987");

		// a CI run bound to an organization key
		AuthHeaderParse ci = AuthHeaderParse.fromVerifiedFederation(freeformAdminKey(org), fc, IP);
		assertThrows(RelizaException.class, () -> fetcher.agentTaskCancel(td.getUuid(), "ci", env(ci)));
		// and a federated principal on a personal key row is not a person either
		AuthHeaderParse onPersonal = AuthHeaderParse.fromVerifiedFederation(personalKey(org, admin, PermissionType.ADMIN), fc, IP);
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.agentTaskCancel(td.getUuid(), "ci", env(onPersonal)));
		assertEquals(AgentTaskDataFetcher.PERSON_ONLY, e.getMessage());
	}

	@Test
	public void aNonAdminPersonalKeyIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentTaskData td = task(board(org));

		// the owner is a writer, not an admin: an admin-level key does not lift them
		Person writer = person(org, PermissionType.READ_WRITE);
		ApiKey writersKey = personalKey(org, writer, PermissionType.ADMIN);
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskComplete(td.getUuid(), "no", null,
				env(AuthHeaderParse.fromVerifiedKey(writersKey, 1, IP))));

		// the owner is an admin, the key was issued lower: the key's ceiling holds
		Person admin = person(org, PermissionType.ADMIN);
		ApiKey lowKey = personalKey(org, admin, PermissionType.READ_WRITE);
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskComplete(td.getUuid(), "no", null,
				env(AuthHeaderParse.fromVerifiedKey(lowKey, 1, IP))));
		// ... and reads no board either: since board enforcement (task d8e7bd7e) a read needs BOARD_READ,
		// which an org tier without the function does not carry
		assertTrue(fetcher.agentBoardsOfOrg(org.getUuid(), env(AuthHeaderParse.fromVerifiedKey(lowKey, 1, IP))).isEmpty());

		assertEquals(TaskStatus.PENDING_INTAKE, agentTaskService.getTaskData(td.getUuid()).orElseThrow().getStatus());
	}

	@Test
	public void aKeyFromAnotherOrganizationIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		Person admin = person(other, PermissionType.ADMIN);
		ApiKey key = personalKey(other, admin, PermissionType.ADMIN);
		AgentTaskData td = task(board(org));
		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskCancel(td.getUuid(), "no",
				env(AuthHeaderParse.fromVerifiedKey(key, 1, IP))));
		assertEquals("The key is not in this board's organization", e.getMessage());
	}

	@Test
	public void noCredentialIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentTaskData td = task(board(org));
		// the one refusal whose words reach the caller: it says which credential to use
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.agentTaskCancel(td.getUuid(), "no", env(null)));
		assertEquals(AgentTaskDataFetcher.PERSON_ONLY, e.getMessage());
	}

	/** A browser session's authentication, in a context of this test's own (coding principles). */
	private static void browser(Jwt jwt) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new JwtAuthenticationToken(jwt));
		SecurityContextHolder.setContext(context);
	}

	@Test
	public void theBrowserPathIsUnchanged() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Person admin = person(org, PermissionType.ADMIN);
		AgentTaskData td = task(board(org));
		Jwt jwt = Jwt.withTokenValue("browser").header("alg", "none").claim("sub", admin.oauthId()).build();
		browser(jwt);

		// no programmatic principal at all: the JWT alone decides
		AgentTaskData cancelled = fetcher.agentTaskCancel(td.getUuid(), "from the browser", env(null));
		assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
		assertEquals(admin.user().getUuid(), last(cancelled).actor().uuid());
		assertEquals(admin.email(), last(cancelled).actor().name());

		// and a writer's browser session is refused as before
		Person writer = person(org, PermissionType.READ_WRITE);
		Jwt writerJwt = Jwt.withTokenValue("browser").header("alg", "none").claim("sub", writer.oauthId()).build();
		browser(writerJwt);
		AgentTaskData other = task(board(org));
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskCancel(other.getUuid(), "no", env(null)));
	}

	@Test
	public void programmaticAllowlistServesTheNewRoots() throws Exception {
		ProgrammaticSchemaRegistry registry = new ProgrammaticSchemaRegistry();
		for (String q : List.of("agentBoardsOfOrg", "agentBoard", "agentTasksOfBoard", "agentTaskRoleConfigsOfBoard")) {
			assertTrue(registry.allows(Operation.QUERY, q), q);
		}
		for (String m : List.of("agentTaskRegister", "agentTaskAuthorize", "agentTaskOrder", "agentTaskComplete",
				"agentTaskCancel", "agentTaskDecideReviewItems", "agentTaskAnswer", "agentTaskHumanReview",
				"agentTaskHumanSignOff", "agentTaskOperatorHold", "agentTaskRequireHumanReview", "agentTaskSetStrength",
				"agentTaskSetBudget",
				"agentBoardOperatorPause", "agentBoardApplySpec", "agentRolePresetsApplySpec")) {
			assertTrue(registry.allows(Operation.MUTATION, m), m);
		}
		// governance stays in the browser: board creation, prompts, reopen, force-close
		for (String m : List.of("agentBoardCreate", "agentBoardUpdate", "agentTaskRoleConfigSet", "agentTaskReopen",
				"agentSessionForceClose")) {
			assertFalse(registry.allows(Operation.MUTATION, m), m);
		}
		String bundle = registry.bundleSdl();
		for (String t : List.of("input AgentTaskUserRegisterInput", "input BoardReviewItemDecisionInput", "input BoardReviewItemsAboutInput",
				"input BoardReviewItemAnswerInput", "input BoardReviewItemLocationInput")) {
			assertTrue(bundle.contains(t), t + " reaches the published contract");
		}
	}
}
