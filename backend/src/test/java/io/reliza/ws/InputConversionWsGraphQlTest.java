/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.CliSessionService;
import io.reliza.service.ComponentService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * One bad input per fetcher class of the ws/ top level, through the executor (task TPF-64,
 * architecture-2 section 4 row 5): an argument graphql-java passes and Jackson refuses answers
 * BAD_REQUEST naming the field under the argument's own name, instead of "Internal server error".
 * Only who is asking is mocked (the mock set of {@link AgentBoardGraphQlTest} and
 * {@link InputConversionGraphQlTest}, so the context is shared). Every conversion here runs before
 * the fetcher writes anything, so no state is compared.
 */
@SpringBootTest(classes = {App.class})
public class InputConversionWsGraphQlTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@MockitoBean private UserService userService;
	@MockitoBean private AuthorizationService authorizationService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;
	@Autowired private CliSessionService cliSessionService;

	private UserData user;

	@BeforeEach
	void signIn() {
		user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(user));
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf64-test").build();
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

	private String org() {
		return testInitializer.obtainOrganization().getUuid().toString();
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	/** Exactly one error with that message and type, no extensions, and the state read before unchanged. */
	private <S> void refused(ExecutionResult r, String message, String errorType, Supplier<S> state, S before) {
		assertEquals(1, r.getErrors().size(), "one refusal: " + r.getErrors());
		GraphQLError err = r.getErrors().get(0);
		assertEquals(message, err.getMessage());
		assertEquals(errorType, String.valueOf(err.getErrorType()));
		assertTrue(null == err.getExtensions() || err.getExtensions().isEmpty(), String.valueOf(err.getExtensions()));
		if (null != state) assertEquals(before, state.get());
	}

	/**
	 * A field of non-null type ({@code upsertNotificationChannel: NotificationChannelResult!}) also carries
	 * the engine's own NullValueInNonNullableField error for the null any refusal leaves, as
	 * {@link ProgrammaticSessionOrderTest} sets aside: exactly one such error, on that field, is removed
	 * and the rest is returned for {@link #refused}.
	 */
	@SuppressWarnings("unchecked")
	private static ExecutionResult withoutTheEnginesNullError(ExecutionResult r, String field) {
		List<GraphQLError> engine = r.getErrors().stream().filter(e -> "NullValueInNonNullableField".equals(
				((Map<String, Object>) e.toSpecification().getOrDefault("extensions", Map.of())).get("classification")))
				.toList();
		assertEquals(1, engine.size(), "one engine null error: " + r.getErrors());
		assertEquals(List.of(field), engine.get(0).getPath());
		return ExecutionResult.newExecutionResult().data(r.getData())
				.errors(r.getErrors().stream().filter(e -> !engine.contains(e)).toList()).build();
	}

	// row 5 case 1: AgentTaskDataFetcher (row 5 of table 3.2)
	@Test
	void aRequiredInputComponentThatIsNotAUuid() throws RelizaException {
		when(authorizationService.userOnBoard(any(), any(), any(), any(), any())).thenReturn(WhoUpdated.getTestWhoUpdated());
		UUID org = testInitializer.obtainOrganization().getUuid();
		Component target = componentService.createComponent("tpf64_" + UUID.randomUUID(), org,
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org, "tpf64-" + UUID.randomUUID(), "tpf64", List.of(),
				"coordinate", 4, null, target.getUuid(), null, WU);
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($b: ID!, $in: AgentTaskUserRegisterInput!) { agentTaskRegister(boardUuid: $b, input: $in) { uuid } }",
				map("b", board.getUuid().toString(), "in", map("title", "tpf64", "requiredInputs",
						List.of(map("kind", "DOCUMENT", "minLifecycle", "DRAFT", "component", "not-a-uuid")))));
		refused(r, "input.requiredInputs[0].component: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 2: AnalyticsDataFetcher (row 8)
	@Test
	void anActiveComponentsOrganizationThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"query($in: ActiveComponentsInput!) { mostActiveComponentsOverTime(activeComponentsInput: $in) { componentuuid } }",
				map("in", map("organization", "not-a-uuid")));
		refused(r, "activeComponentsInput.organization: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 3: CliSessionDataFetcher (row 12)
	@Test
	void aCliLoginPermissionObjectThatIsNotAUuid() {
		String userCode = cliSessionService.start(new CliSessionService.DeviceInfo("tpf64-host", null, null, null, null)).userCode();
		when(user.isGlobalAdmin()).thenReturn(true);
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($c: String!, $o: ID, $p: [PermissionInput]) { approveCliLogin(userCode: $c, createKeyOrgUuid: $o, permissions: $p) { uuid } }",
				map("c", userCode, "o", org(), "p", List.of(map("object", "not-a-uuid"))));
		refused(r, "permissions[0].object: not a uuid", "BAD_REQUEST", null, null);
		assertTrue(cliSessionService.pending(userCode).isPresent(), "the login stays pending");
	}

	// row 5 case 4: ComponentDataFetcher (row 13)
	@Test
	void aComponentVcsThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($c: CreateComponentInput!) { createComponent(component: $c) { uuid } }",
				map("c", map("name", "tpf64-" + UUID.randomUUID(), "type", "COMPONENT", "vcs", "not-a-uuid")));
		refused(r, "component.vcs: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 5: DeliverableDataFetcher (row 21)
	@Test
	void aDeliverablesReleaseThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($d: AddDeliverableInput!) { addOutboundDeliverablesManual(deliverables: $d) }",
				map("d", map("release", "not-a-uuid", "deliverables", List.of())));
		refused(r, "deliverables.release: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 6: FederatedTrustRuleDataFetcher (row 23)
	@Test
	void aFederatedGrantKeyThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($o: ID!, $m: FederatedMatcherInput!, $g: FederatedGrantInput!) { createFederatedTrustRule(orgUuid: $o, "
						+ "name: \"tpf64\", provider: GITHUB_ACTIONS, matcher: $m, grant: $g) { uuid } }",
				map("o", org(), "m", map("owner", "example"), "g", map("type", "KEY", "keyUuid", "not-a-uuid")));
		refused(r, "grant.keyUuid: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 7: IntegrationDataFetcher (row 26)
	@Test
	void anIntegrationOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($i: IntegrationInput!) { createIntegration(integration: $i) { uuid } }",
				map("i", map("org", "not-a-uuid", "identifier", "tpf64", "secret", "s", "type", "GITHUB")));
		refused(r, "integration.org: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 8: NotificationDataFetcher (row 29)
	@Test
	void aNotificationChannelOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($i: NotificationChannelInput!) { upsertNotificationChannel(input: $i) { uuid } }",
				map("i", map("org", "not-a-uuid", "name", "tpf64", "type", "SLACK")));
		refused(withoutTheEnginesNullError(r, "upsertNotificationChannel"), "input.org: not a uuid", "BAD_REQUEST",
				null, null);
	}

	// row 5 case 9: OrgTeamAssignmentRuleDataFetcher (row 32)
	@Test
	void aTeamAssignmentRuleOwnerTeamThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($o: ID!, $r: [GlobalTeamAssignmentRuleInput!]!) { setGlobalTeamAssignmentRules(orgUuid: $o, rules: $r) { uuid } }",
				map("o", org(), "r", List.of(map("name", "r", "namePattern", ".*", "ownerTeam", "not-a-uuid"))));
		refused(r, "rules[0].ownerTeam: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 10: OrganizationDataFetcher (row 33)
	@Test
	void aFreeformKeyPermissionObjectThatIsNotAUuid() {
		when(user.isGlobalAdmin()).thenReturn(true);
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($o: ID!, $p: [PermissionInput]) { requestFreeformApiKey(orgUuid: $o, permissions: $p) { uuid } }",
				map("o", org(), "p", List.of(map("object", "not-a-uuid"))));
		refused(r, "permissions[0].object: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 11: ReleaseDatafetcher (row 37)
	@Test
	void anUpdatedReleaseUuidThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($r: ReleaseInput!) { updateRelease(release: $r) { uuid } }",
				map("r", map("uuid", "not-a-uuid", "org", org())));
		refused(r, "release.uuid: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 12: SignatureDataFetcher (row 46)
	@Test
	void aCommitterOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($i: CommitterInput!) { upsertCommitter(input: $i) { uuid } }",
				map("i", map("org", "not-a-uuid", "name", "n", "email", "n@example.com")));
		refused(r, "input.org: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 13: SourceCodeEntryDataFetcher (row 49)
	@Test
	void aSourceCodeEntryBranchThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($s: SourceCodeEntryInput!) { createSourceCodeEntry(sourceCodeEntry: $s) { uuid } }",
				map("s", map("branch", "not-a-uuid", "commit", "abc")));
		refused(r, "sourceCodeEntry.branch: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 14: TeamDataFetcher (row 50)
	@Test
	void aTeamOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($t: CreateTeamInput!) { createTeam(team: $t) { uuid } }",
				map("t", map("name", "t", "org", "not-a-uuid")));
		refused(r, "team.org: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 15: UserGroupDataFetcher (row 52)
	@Test
	void aUserGroupOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($g: CreateUserGroupInput!) { createUserGroup(userGroup: $g) { uuid } }",
				map("g", map("name", "g", "org", "not-a-uuid")));
		refused(r, "userGroup.org: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 16: VulnAnalysisDataFetcher (row 56)
	@Test
	void aVulnAnalysisUuidThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($a: UpdateVulnAnalysisInput!) { updateVulnAnalysis(analysis: $a) { uuid } }",
				map("a", map("analysisUuid", "not-a-uuid", "state", "IN_TRIAGE")));
		refused(r, "analysis.analysisUuid: not a uuid", "BAD_REQUEST", null, null);
	}

	// row 5 case 17: control, valid input unchanged
	@Test
	void aCompleteActiveComponentsInputStillAnswersAList() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"query($in: ActiveComponentsInput!) { mostActiveComponentsOverTime(activeComponentsInput: $in) { componentuuid } }",
				map("in", map("organization", org(), "cutOffDate", "2026-01-01T00:00:00Z", "componentType", "COMPONENT",
						"maxComponents", 10)));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNotNull(data, "data");
		assertTrue(data.get("mostActiveComponentsOverTime") instanceof List<?>,
				String.valueOf(data.get("mostActiveComponentsOverTime")));
	}
}
