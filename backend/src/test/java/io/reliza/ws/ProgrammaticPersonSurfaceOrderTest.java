/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.FederatedContext;
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
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-19 (TPF-17 child 1), with TPF-34's agentTaskUnlinkPr: the 30 person-surface fields of
 * {@link AgentTaskDataFetcher} that look a
 * board or task up classify and verify the credential and check the system state before the lookup
 * on the programmatic endpoint. So a non-person reads {@link AgentTaskDataFetcher#PERSON_ONLY}, a
 * Basic credential that does not verify reads "Not authorized", and a personal key on a sealed or
 * unlicensed instance reads the state sentence, each for a missing and an existing uuid alike.
 * Calls go through the DGS executor, so the answer is the one the wire carries.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProgrammaticPersonSurfaceOrderTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String IP = "198.51.100.19";
	private static final String NOT_AUTHORIZED = "Not authorized";

	@Autowired private TestInitializer testInitializer;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private UserService userService;
	@Autowired private ComponentService componentService;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; each case sets the state it needs. */
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

	// ---------- the field table ----------

	/** What a row's variables are built from: a board, a task, the task's key and the organization asked. */
	record Ids(UUID board, UUID task, String taskKey, UUID org) {}

	record Row(String field, String operation, Function<Ids, Map<String, Object>> vars) {
		@Override public String toString() { return field; }
	}

	private static Map<String, Object> vars(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static Row board(String field, String op) {
		return new Row(field, op, ids -> vars("b", ids.board().toString()));
	}

	private static Row task(String field, String op) {
		return new Row(field, op, ids -> vars("t", ids.task().toString()));
	}

	static final String BY_KEY = "agentTaskByKey";
	static final String BY_UUID = "agentTasksByUuid";
	static final String UNLINK = "agentTaskUnlinkPr";
	static final String UNLINK_PR = "https://github.com/example/x/pull/1";

	/** The 29 rows of architecture 3.1 (TPF-19 round 3), in its order, then row 30 (TPF-34). */
	static Stream<Row> rows() {
		return Stream.of(
				board("agentBoard", "query($b: ID!) { agentBoard(uuid: $b) { uuid } }"),
				board("agentBoardEvents", "query($b: ID!) { agentBoardEvents(boardUuid: $b) { hasMore } }"),
				new Row(BY_KEY, "query($o: ID!, $k: String!) { agentTaskByKey(orgUuid: $o, key: $k) { uuid } }",
						ids -> vars("o", ids.org().toString(), "k", ids.taskKey())),
				board("agentTasksOfBoard", "query($b: ID!) { agentTasksOfBoard(boardUuid: $b) { uuid } }"),
				new Row(BY_UUID, "query($ts: [ID!]!) { agentTasksByUuid(taskUuids: $ts) { uuid } }",
						ids -> vars("ts", List.of(ids.task().toString()))),
				board("agentTaskRoleConfigsOfBoard", "query($b: ID!) { agentTaskRoleConfigsOfBoard(boardUuid: $b) { __typename } }"),
				board("agentBoardOperatorPause", "mutation($b: ID!) { agentBoardOperatorPause(boardUuid: $b, pause: true) { uuid } }"),
				task("agentTaskSetStrength", "mutation($t: ID!) { agentTaskSetStrength(taskUuid: $t, requiredStrength: 0.5) { uuid } }"),
				task("agentTaskHumanReview", "mutation($t: ID!) { agentTaskHumanReview(taskUuid: $t, accept: true) { uuid } }"),
				task("agentTaskHumanSignOff", "mutation($t: ID!) { agentTaskHumanSignOff(taskUuid: $t, outcome: PASSED) { uuid } }"),
				task("agentTaskOperatorHold", "mutation($t: ID!) { agentTaskOperatorHold(taskUuid: $t, hold: true) { uuid } }"),
				task("agentTaskRequireHumanReview", "mutation($t: ID!) { agentTaskRequireHumanReview(taskUuid: $t, value: true) { uuid } }"),
				task("agentTaskAnswer", "mutation($t: ID!) { agentTaskAnswer(taskUuid: $t, answerAll: \"a\") { uuid } }"),
				board("agentTaskRegister", "mutation($b: ID!) { agentTaskRegister(boardUuid: $b, input: { title: \"t\" }) { uuid } }"),
				board("agentTaskCommission", "mutation($b: ID!) { agentTaskCommission(input: { boardUuid: $b, role: \"coder\", title: \"t\" }) { uuid } }"),
				task("agentTaskAuthorize", "mutation($t: ID!) { agentTaskAuthorize(taskUuid: $t, role: \"coder\") { uuid } }"),
				task("agentTaskOrder", "mutation($t: ID!) { agentTaskOrder(taskUuid: $t, orderIndex: 1) { uuid } }"),
				task("agentTaskSetBudget", "mutation($t: ID!) { agentTaskSetBudget(taskUuid: $t, budgetMicros: 1) { uuid } }"),
				task("agentTaskSetWorkLevel", "mutation($t: ID!) { agentTaskSetWorkLevel(taskUuid: $t, workLevel: 1) { uuid } }"),
				task("agentTaskUnassign", "mutation($t: ID!) { agentTaskUnassign(taskUuid: $t, reason: \"r\") { uuid } }"),
				board("agentBoardGroupSet", "mutation($b: ID!) { agentBoardGroupSet(boardUuid: $b, group: { key: \"gx\" }) { key } }"),
				board("agentBoardGroupDelete", "mutation($b: ID!) { agentBoardGroupDelete(boardUuid: $b, key: \"gx\") }"),
				task("agentTaskSetGroup", "mutation($t: ID!) { agentTaskSetGroup(taskUuid: $t, group: \"gx\") { uuid } }"),
				task("agentTaskSetTags", "mutation($t: ID!) { agentTaskSetTags(taskUuid: $t, tags: []) { uuid } }"),
				task("agentTaskComplete", "mutation($t: ID!) { agentTaskComplete(taskUuid: $t) { uuid } }"),
				task("agentTaskDeclareDelivery", "mutation($t: ID!) { agentTaskDeclareDelivery(taskUuid: $t, unit: \"u\") { uuid } }"),
				task("agentTaskSupersedePullRequest", "mutation($t: ID!) { agentTaskSupersedePullRequest(taskUuid: $t,"
						+ " oldUrl: \"https://github.com/example/x/pull/1\", byUrl: \"https://github.com/example/x/pull/2\") { uuid } }"),
				task("agentTaskCancel", "mutation($t: ID!) { agentTaskCancel(taskUuid: $t) { uuid } }"),
				task("agentTaskDecideReviewItems", "mutation($t: ID!) { agentTaskDecideReviewItems(taskUuid: $t,"
						+ " specification: ARCHITECTURE, decisions: []) { uuid } }"),
				task(UNLINK, "mutation($t: ID!) { agentTaskUnlinkPr(taskUuid: $t,"
						+ " prUrl: \"" + UNLINK_PR + "\") { uuid } }"));
	}

	private static Row row(String field) {
		return rows().filter(r -> r.field().equals(field)).findFirst().orElseThrow();
	}

	/**
	 * The guard of architecture section 5: the person fields of the class that the programmatic SDL
	 * declares, minus the four that are not person-surface lookups, are exactly the table's.
	 */
	@Test
	public void theTableCoversEveryPersonSurfaceLookupField() throws IOException {
		Set<String> declared = new TreeSet<>();
		for (Method m : AgentTaskDataFetcher.class.getDeclaredMethods()) {
			DgsData d = m.getAnnotation(DgsData.class);
			if (null != d && ("Query".equals(d.parentType()) || "Mutation".equals(d.parentType()))
					&& !d.field().endsWith("Programmatic")) declared.add(d.field());
		}
		String sdl = new String(new ClassPathResource("schema/programmatic.graphqls").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		Map<String, Set<String>> roots = ProgrammaticSchemaRegistry.rootFieldsOfExtensions(sdl);
		Set<String> programmatic = new TreeSet<>(roots.getOrDefault("Query", Set.of()));
		programmatic.addAll(roots.getOrDefault("Mutation", Set.of()));
		Set<String> expected = new TreeSet<>(declared);
		expected.retainAll(programmatic);
		assertEquals(34, expected.size(), expected.toString());
		// classify first through authorizePersonCtx (agentBoardsOfOrg, the two ApplySpec fields), or a
		// static catalogue served with no credential (elementCheckCatalogue)
		expected.removeAll(Set.of("agentBoardsOfOrg", "agentBoardApplySpec", "agentRolePresetsApplySpec",
				"elementCheckCatalogue"));
		assertEquals(30, expected.size(), expected.toString());
		assertEquals(expected, new TreeSet<>(rows().map(Row::field).toList()));
	}

	// ---------- fixtures (AgentPersonProgrammaticTest's) ----------

	private User person(Organization org, PermissionType level) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Order " + tag, "order-" + tag + "@personsurface.io", true, List.of(org.getUuid()),
				"order-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				level, List.of(), null, WU);
		return u;
	}

	/** A personal key as rearm login mints it: owned by the user, with an org-wide level and the given grants. */
	private ApiKey personalKey(Organization org, User owner, PermissionType level, PermissionDto... grants)
			throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "person surface order", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), level, List.of(grants), WU);
		return apiKeyService.getApiKey(ak.getUuid()).orElseThrow();
	}

	private ApiKey freeformAdminKey(Organization org) throws RelizaException {
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "person surface order freeform", WU);
		apiKeyService.setPermissionsOnApiKey(ak.getUuid(), PermissionType.ADMIN, List.of(), WU);
		return apiKeyService.getApiKey(ak.getUuid()).orElseThrow();
	}

	private AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("order_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), "order-" + UUID.randomUUID(), "order board",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
	}

	private AgentTaskData task(AgentBoardData board) throws RelizaException {
		AgentTaskData td = agentTaskService.register(board, null, "a task the order test names", null, null, null,
				null, null, null, AgentActor.ofUser(UUID.randomUUID(), "someone"), true, WU);
		assertNotNull(td.getKey(), "setup: the task has a key");
		return td;
	}

	/**
	 * Organizations A and B; on A a board and a task; A's FREEFORM admin key, a personal key of A's
	 * admin and one of B's. The refusal cases never write, so they share it.
	 */
	record World(Organization a, Organization b, AgentBoardData board, AgentTaskData task, ApiKey freeformA,
			ApiKey personalA, ApiKey personalB) {
		Ids real() { return new Ids(board.getUuid(), task.getUuid(), task.getKey(), a.getUuid()); }
		Ids random() { return new Ids(UUID.randomUUID(), UUID.randomUUID(), "ZQ-" + (100_000 + (int) (Math.random() * 800_000)), a.getUuid()); }
	}

	private World world;

	private World world() throws RelizaException {
		if (null == world) {
			Organization a = testInitializer.obtainOrganization();
			Organization b = testInitializer.obtainOrganization();
			AgentBoardData bd = board(a);
			world = new World(a, b, bd, task(bd), freeformAdminKey(a),
					personalKey(a, person(a, PermissionType.ADMIN), PermissionType.ADMIN),
					personalKey(b, person(b, PermissionType.ADMIN), PermissionType.ADMIN));
		}
		return world;
	}

	// ---------- calls ----------

	private ExecutionResult execute(Row row, Ids ids, MockHttpServletRequest request, HttpHeaders headers) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(row.operation(), row.vars().apply(ids), Map.of(), headers, null,
				new ServletWebRequest(request));
	}

	/** Verified-principal form: what the programmatic filter leaves after it checked a bearer token. */
	private ExecutionResult asPrincipal(AuthHeaderParse principal, Row row, Ids ids) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setRemoteAddr(IP);
		HttpHeaders headers = new HttpHeaders();
		if (null != principal) {
			request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal);
			headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		}
		return execute(row, ids, request, headers);
	}

	private ExecutionResult asKey(ApiKey key, Row row, Ids ids) {
		return asPrincipal(AuthHeaderParse.fromVerifiedKey(key, 1, IP), row, ids);
	}

	/** Basic form: no principal attribute, so the resolver parses the header as production does. */
	private ExecutionResult asBasic(String keyId, String secret, Row row, Ids ids) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setRemoteAddr(IP);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		return execute(row, ids, request, headers);
	}

	/** The key id text of a personal key (AuthHeaderParse.fromVerifiedKey). */
	private static String keyId(ApiKey personal) {
		return ApiTypeEnum.USER.name() + "__" + personal.getObjectUuid() + "__ord__" + personal.getKeyOrder();
	}

	private static String forgedKeyId() {
		return ApiTypeEnum.USER.name() + "__" + UUID.randomUUID() + "__ord__0";
	}

	/** The single error a refused call carries; the field itself answers nothing (not null-as-missing, not []). */
	private static String refusal(Row row, ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), row.field() + ": expected a refusal, got " + String.valueOf((Object) r.getData()));
		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		assertNull(null == data ? null : data.get(row.field()), row.field() + ": a refused call returns nothing");
		return r.getErrors().get(0).getMessage();
	}

	private static Object answer(Row row, ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), row.field() + ": " + r.getErrors());
		Map<String, Object> data = r.getData();
		return data.get(row.field());
	}

	// ---------- 1. a non-person reads PERSON_ONLY before any lookup ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void aNonPersonReadsPersonOnlyForAMissingAndAnExistingUuid(Row row) throws RelizaException {
		World w = world();
		String missing = refusal(row, asKey(w.freeformA(), row, w.random()));
		String existing = refusal(row, asKey(w.freeformA(), row, w.real()));
		assertEquals(AgentTaskDataFetcher.PERSON_ONLY, missing);
		assertEquals(missing, existing);
	}

	@Test
	public void aFederatedPrincipalAndNoCredentialReadPersonOnlyBeforeTheLookup() throws RelizaException {
		World w = world();
		Row cancel = row("agentTaskCancel");
		FederatedContext fc = new FederatedContext(List.of(UUID.randomUUID()), "github",
				"https://token.actions.githubusercontent.com", "relizaio", "relizaio/rearm", null, "refs/heads/main",
				"abc123", "wf.yml@refs/heads/main", null, "push", "octocat", "987");
		AuthHeaderParse federated = AuthHeaderParse.fromVerifiedFederation(w.freeformA(), fc, IP);
		for (AuthHeaderParse principal : new AuthHeaderParse[] { federated, null }) {
			String missing = refusal(cancel, asPrincipal(principal, cancel, w.random()));
			String existing = refusal(cancel, asPrincipal(principal, cancel, w.real()));
			assertEquals(AgentTaskDataFetcher.PERSON_ONLY, missing);
			assertEquals(missing, existing);
		}
	}

	// ---------- 2. a personal key reads the state sentence first ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void aPersonalKeyOnASealedInstanceReadsTheSealedSentenceForAMissingAndAnExistingUuid(Row row)
			throws RelizaException {
		World w = world();
		licenseStatus.setSystemSealed(true);
		String missing = refusal(row, asKey(w.personalA(), row, w.random()));
		String existing = refusal(row, asKey(w.personalA(), row, w.real()));
		assertTrue(missing.contains("sealed"), missing);
		assertEquals(missing, existing);
	}

	@Test
	public void aPersonalKeyOnAnUnlicensedInstanceReadsTheLicenceSentenceForAMissingAndAnExistingUuid()
			throws RelizaException {
		World w = world();
		Row read = row("agentBoard");
		licenseStatus.setLicenseValid(false);
		String missing = refusal(read, asKey(w.personalA(), read, w.random()));
		String existing = refusal(read, asKey(w.personalA(), read, w.real()));
		assertTrue(missing.startsWith("License is invalid"), missing);
		assertEquals(missing, existing);
	}

	// ---------- 3. a personal key of another organization is refused as today ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void aForeignPersonalKeyIsStillRefusedAndReadsAsToday(Row row) throws RelizaException {
		World w = world();
		ExecutionResult onReal = asKey(w.personalB(), row, w.real());
		switch (row.field()) {
			case BY_UUID -> {
				// personReads drops A's task: the same answer an unknown uuid gives
				assertEquals(List.of(), answer(row, onReal));
				assertEquals(List.of(), answer(row, asKey(w.personalB(), row, w.random())));
			}
			case BY_KEY -> {
				// orgUuid is A's in both: found and refused on the task, or not found and refused by the org check
				assertEquals(NOT_AUTHORIZED, refusal(row, onReal));
				assertEquals(NOT_AUTHORIZED, refusal(row, asKey(w.personalB(), row, w.random())));
			}
			default -> assertEquals(NOT_AUTHORIZED, refusal(row, onReal));
		}
	}

	// ---------- 4. not-found texts unchanged for a person of the organization ----------

	@Test
	public void aPersonOfTheOrganizationStillReadsTheNotFoundTexts() throws RelizaException {
		World w = world();
		Ids missing = w.random();
		Row read = row("agentBoard");
		assertEquals("Board not found: " + missing.board(), refusal(read, asKey(w.personalA(), read, missing)));
		Row tags = row("agentTaskSetTags");
		assertEquals("Task not found: " + missing.task(), refusal(tags, asKey(w.personalA(), tags, missing)));
		Row byKey = row(BY_KEY);
		assertNull(answer(byKey, asKey(w.personalA(), byKey, missing)), "an unknown key of the organization reads null");
		Row byUuid = row(BY_UUID);
		assertEquals(List.of(), answer(byUuid, asKey(w.personalA(), byUuid, missing)));
		// TPF-34: the unlink text after the gate is unchanged, and the real task reaches the service
		Row unlink = row(UNLINK);
		assertEquals("Task not found: " + missing.task(), refusal(unlink, asKey(w.personalA(), unlink, missing)));
		assertNotLinked(refusal(unlink, asKey(w.personalA(), unlink, w.real())));
	}

	/** AgentTaskService.linkedPr's refusal: the lookup and the authorization ran and the service was reached. */
	private static void assertNotLinked(String message) {
		assertTrue(message.startsWith("The PR to unlink ") && message.contains(" is not linked to task "), message);
	}

	// ---------- 5. happy path per gate, and one function too few ----------

	private record Granted(User owner, ApiKey key) {}

	/** A READ_ONLY member holding {@code fns} on the board, and a personal key holding the same there. */
	private Granted granted(Organization org, AgentBoardData bd, PermissionType type, PermissionFunction... fns)
			throws RelizaException {
		User owner = person(org, PermissionType.READ_ONLY);
		userService.setUserPermission(owner.getUuid(), org.getUuid(), PermissionScope.BOARD, bd.getUuid(), type,
				List.of(fns), null, WU);
		ApiKey key = personalKey(org, owner, PermissionType.READ_ONLY,
				new PermissionDto(org.getUuid(), PermissionScope.BOARD, bd.getUuid(), type, Set.of(fns), null));
		return new Granted(owner, key);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void eachGateAdmitsItsFunctionsAndRefusesOneTooFew() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org);
		AgentTaskData td = task(bd);
		Ids ids = new Ids(bd.getUuid(), td.getUuid(), td.getKey(), org.getUuid());
		Granted reader = granted(org, bd, PermissionType.READ_ONLY, PermissionFunction.BOARD_READ);
		Granted writer = granted(org, bd, PermissionType.READ_WRITE, PermissionFunction.BOARD_WRITE);
		Granted configurer = granted(org, bd, PermissionType.READ_WRITE, PermissionFunction.CONFIGURATION_WRITE,
				PermissionFunction.BOARD_WRITE);

		Row read = row("agentBoard");
		assertEquals(bd.getUuid().toString(), ((Map<String, Object>) answer(read, asKey(reader.key(), read, ids))).get("uuid"));

		Row tags = row("agentTaskSetTags");
		assertEquals(td.getUuid().toString(), ((Map<String, Object>) answer(tags, asKey(writer.key(), tags, ids))).get("uuid"));
		assertEquals(NOT_AUTHORIZED, refusal(tags, asKey(reader.key(), tags, ids)));
		Row budget = row("agentTaskSetBudget");
		answer(budget, asKey(writer.key(), budget, ids));
		AgentActor setBy = agentTaskService.getTaskData(td.getUuid()).orElseThrow().getBudgetSetBy();
		assertEquals(writer.owner().getUuid(), setBy.uuid(), "the write is on record as the key's owner");

		Row unlink = row(UNLINK);
		assertNotLinked(refusal(unlink, asKey(writer.key(), unlink, ids)));
		assertEquals(NOT_AUTHORIZED, refusal(unlink, asKey(reader.key(), unlink, ids)));

		Row group = row("agentBoardGroupSet");
		assertEquals(NOT_AUTHORIZED, refusal(group, asKey(writer.key(), group, ids)));
		assertEquals("gx", ((Map<String, Object>) answer(group, asKey(configurer.key(), group, ids))).get("key"));
	}

	// ---------- 7. an unverified Basic credential is refused before any lookup ----------

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	public void aForgedBasicCredentialReadsNotAuthorizedForAMissingAndAnExistingUuid(Row row) throws RelizaException {
		World w = world();
		String missing = refusal(row, asBasic(forgedKeyId(), UUID.randomUUID().toString(), row, w.random()));
		String existing = refusal(row, asBasic(forgedKeyId(), UUID.randomUUID().toString(), row, w.real()));
		assertEquals(NOT_AUTHORIZED, missing);
		assertEquals(missing, existing);
	}

	@Test
	public void aRealKeyIdWithAWrongSecretReadsNotAuthorizedOnAHealthyAndOnASealedInstance() throws RelizaException {
		World w = world();
		ApiKey key = personalKey(w.a(), person(w.a(), PermissionType.ADMIN), PermissionType.ADMIN);
		apiKeyService.addApiKeySecret(key.getUuid(), null, WU);
		Row read = row("agentBoard");
		String wrong = "not-the-secret-" + UUID.randomUUID();
		assertEquals(NOT_AUTHORIZED, refusal(read, asBasic(keyId(key), wrong, read, w.real())));
		assertEquals(NOT_AUTHORIZED, refusal(read, asBasic(keyId(key), wrong, read, w.random())));
		licenseStatus.setSystemSealed(true);
		assertEquals(NOT_AUTHORIZED, refusal(read, asBasic(keyId(key), wrong, read, w.real())),
				"verified before the state: a wrong secret does not learn the instance is sealed");
		assertEquals(NOT_AUTHORIZED, refusal(read, asBasic(keyId(key), wrong, read, w.random())));
		assertTrue(apiKeyAccessService.listKeyAccessByOrg(w.a().getUuid()).stream()
				.noneMatch(a -> key.getUuid().equals(a.getApiKeyUuid())), "a refused key is not recorded as used");
	}

	// ---------- 8. a valid Basic personal key still works ----------

	@Test
	@SuppressWarnings("unchecked")
	public void aValidBasicPersonalKeyReadsTheBoardAndTheNotFoundText() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org);
		AgentTaskData td = task(bd);
		Granted reader = granted(org, bd, PermissionType.READ_ONLY, PermissionFunction.BOARD_READ);
		String secret = apiKeyService.addApiKeySecret(reader.key().getUuid(), null, WU);
		ApiKey key = apiKeyService.getApiKey(reader.key().getUuid()).orElseThrow();
		Row read = row("agentBoard");
		Ids real = new Ids(bd.getUuid(), td.getUuid(), td.getKey(), org.getUuid());
		assertEquals(bd.getUuid().toString(),
				((Map<String, Object>) answer(read, asBasic(keyId(key), secret, read, real))).get("uuid"));
		UUID missing = UUID.randomUUID();
		Ids random = new Ids(missing, UUID.randomUUID(), "ZQ-1", org.getUuid());
		assertEquals("Board not found: " + missing, refusal(read, asBasic(keyId(key), secret, read, random)));
	}
}
