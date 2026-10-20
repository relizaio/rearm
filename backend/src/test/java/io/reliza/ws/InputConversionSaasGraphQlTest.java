/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.UserData;
import io.reliza.model.saas.InstanceData;
import io.reliza.model.saas.SecretData;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.UserService;
import io.reliza.service.saas.InstanceService;
import io.reliza.service.saas.SecretService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The ws/oss and ws/saas client-input sites through the executor (task TPF-65, architecture-1 section
 * 4.2): one input per fetcher class that does not fit the resolver's types answers BAD_REQUEST naming
 * the field instead of "Internal server error", the three nullable lists answer "required", and a valid
 * input still goes through. Only who is asking is mocked (the mock set of {@link AgentBoardGraphQlTest},
 * so the context is shared); the rig is copied from {@link InputConversionGraphQlTest}.
 */
@SpringBootTest(classes = {App.class})
public class InputConversionSaasGraphQlTest {

	private static final String BAD_REQUEST = "BAD_REQUEST";
	private static final String UPDATE_USER_PERMISSIONS = "mutation($o: ID!, $u: ID!, $p: [PermissionInput]) "
			+ "{ updateUserPermissions(orgUuid: $o, userUuid: $u, permissions: $p) { uuid } }";
	private static final String APPROVE_RELEASE_MANUAL = "mutation($r: ID!, $a: [ReleaseApprovalInput!]) "
			+ "{ approveReleaseManual(release: $r, approvals: $a) { uuid } }";
	private static final String CREATE_SECRET = "mutation($s: SecretInput!) { createSecret(secret: $s) { uuid } }";
	private static final String UPDATE_SECRET_DISTRIBUTION = "mutation($o: ID!, $s: ID!, $p: [PermissionInput]) "
			+ "{ updateSecretDistribution(orgUuid: $o, secretUuid: $s, permissions: $p) { uuid } }";

	@MockitoBean private UserService userService;
	@MockitoBean private AuthorizationService authorizationService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private SecretService secretService;
	@Autowired private InstanceService instanceService;
	@Autowired private ComponentService componentService;

	@BeforeEach
	void signIn() {
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(user));
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf65-test").build();
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

	private static String random() {
		return UUID.randomUUID().toString();
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private Set<UUID> instancesOf(String org) {
		return instanceService.listInstanceDataByOrganization(UUID.fromString(org)).stream()
				.map(InstanceData::getUuid).collect(Collectors.toSet());
	}

	private Set<UUID> componentsOf(String org) {
		return componentService.listComponentDataByOrganization(UUID.fromString(org), ComponentType.COMPONENT,
				ComponentType.PRODUCT).stream().map(ComponentData::getUuid).collect(Collectors.toSet());
	}

	private Set<UUID> secretsOf(String org) {
		return secretService.listSecretDataByOrg(UUID.fromString(org)).stream().map(SecretData::getUuid)
				.collect(Collectors.toSet());
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

	// 4.2 case 1: oss/MarketingReleaseDatafetcher
	@Test
	void aMarketingReleaseComponentThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($m: MarketingReleaseInput!) { addMarketingReleaseManual(marketingRelease: $m) { uuid } }",
				map("m", map("component", "not-a-uuid")));
		refused(r, "marketingRelease.component: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 2: oss/OssComponentDataFetcher
	@Test
	void anUpdatedComponentUuidThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($c: UpdateComponentInput!) { updateComponent(component: $c) { uuid } }",
				map("c", map("name", "tpf65", "uuid", "not-a-uuid")));
		refused(r, "component.uuid: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 3: oss/OssIntegrationDataFetcher
	@Test
	void anIntegrationOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($i: IntegrationInput!) { createTriggerIntegration(integration: $i) { uuid } }",
				map("i", map("org", "not-a-uuid", "identifier", "tpf65", "secret", "s", "type", "GITHUB")));
		refused(r, "integration.org: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 4: oss/OssOrganizationDataFetcher
	@Test
	void aUserPermissionObjectThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(UPDATE_USER_PERMISSIONS,
				map("o", org(), "u", random(), "p", List.of(map("object", "not-a-uuid"))));
		refused(r, "permissions[0].object: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 5: guard 3.4 item 2
	@Test
	void noUserPermissionsAreRequired() {
		ExecutionResult r = dgsQueryExecutor.execute(UPDATE_USER_PERMISSIONS, map("o", org(), "u", random()));
		refused(r, "permissions: required", BAD_REQUEST, null, null);
	}

	// 4.2 case 6: oss/OssReleaseDatafetcher
	@Test
	void anApprovalEntryThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(APPROVE_RELEASE_MANUAL, map("r", random(), "a",
				List.of(map("approvalEntry", "not-a-uuid", "approvalRoleId", "r", "state", "APPROVED"))));
		refused(r, "approvals[0].approvalEntry: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 7: guard 3.4 item 1
	@Test
	void noApprovalsAreRequired() {
		ExecutionResult r = dgsQueryExecutor.execute(APPROVE_RELEASE_MANUAL, map("r", random()));
		refused(r, "approvals: required", BAD_REQUEST, null, null);
	}

	// 4.2 case 8: saas/ApprovalPolicyDataFetcher
	@Test
	void anApprovalPolicyOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($p: ApprovalPolicyInput!) { createApprovalPolicy(approvalPolicy: $p) { uuid } }",
				map("p", map("org", "not-a-uuid")));
		refused(r, "approvalPolicy.org: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 9: saas/DeviceDataFetcher
	@Test
	void aDeviceShippedProductThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($d: DeviceInput!) { upsertDevice(input: $d) { uuid } }",
				map("d", map("org", org(), "shippedProduct", "not-a-uuid")));
		refused(r, "input.shippedProduct: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 10: saas/InstanceDataFetcher, row 79 (the plan converts outside the try)
	@Test
	void aPlanTargetReleaseThatIsNotAUuid() {
		String org = org();
		Set<UUID> before = instancesOf(org);
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($i: CreateInstanceInput!, $p: UpdateInstancePlanInput) "
						+ "{ createInstanceFromDto(instance: $i, plan: $p) { uuid } }",
				map("i", map("org", org, "name", "tpf65", "instanceType", "STANDALONE_INSTANCE",
						"uri", "https://tpf65-" + random() + ".example"),
					"p", map("targetReleases", List.of(map("release", "not-a-uuid")))));
		refused(r, "plan.targetReleases[0].release: not a uuid", BAD_REQUEST, () -> instancesOf(org), before);
	}

	// 4.2 case 11: saas/OrgApprovalPolicyDataFetcher
	@Test
	void aGlobalApprovalPolicyRulePolicyThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($o: ID!, $r: [GlobalApprovalPolicyRuleInput!]!) "
						+ "{ setGlobalApprovalPolicyRules(orgUuid: $o, rules: $r) { uuid } }",
				map("o", org(), "r", List.of(map("name", "r", "namePattern", ".*", "approvalPolicy", "not-a-uuid"))));
		refused(r, "rules[0].approvalPolicy: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 12: saas/OrgValidationTriggerDataFetcher
	@Test
	void aGlobalPrValidationTriggerIntegrationThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($o: ID!, $r: [GlobalPrValidationTriggerRuleInput!]!) "
						+ "{ setGlobalPrValidationTriggerRules(orgUuid: $o, rules: $r) { uuid } }",
				map("o", org(), "r", List.of(map("name", "r", "uriPattern", ".*", "trigger",
						map("name", "t", "type", "EXTERNAL_VALIDATION", "integration", "not-a-uuid")))));
		refused(r, "rules[0].trigger.integration: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 13: saas/SaaSDataFetcher
	@Test
	void anInvitationOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($i: InviteUserInput!) { inviteUser(invitationProperties: $i) { uuid } }",
				map("i", map("org", "not-a-uuid", "email", "tpf65@example.com", "type", "READ_ONLY")));
		refused(r, "invitationProperties.org: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 14: saas/SaasAnalyticsDataFetcher
	@Test
	void anActiveComponentsOrganizationThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"query($a: ActiveComponentsInput!, $p: ID!) "
						+ "{ mostActiveComponentsOverTimeByPerspective(activeComponentsInput: $a, perspectiveUuid: $p) "
						+ "{ componentuuid } }",
				map("a", map("organization", "not-a-uuid"), "p", random()));
		refused(r, "activeComponentsInput.organization: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 15: saas/SaasComponentDataFetcher
	@Test
	void aComponentVcsThatIsNotAUuid() {
		String org = org();
		Set<UUID> before = componentsOf(org);
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($c: CreateComponentInput!, $p: ID!) "
						+ "{ createComponentInPerspective(component: $c, perspectiveUuid: $p) { uuid } }",
				map("c", map("name", "tpf65-" + random(), "type", "COMPONENT", "org", org, "vcs", "not-a-uuid"),
					"p", random()));
		refused(r, "component.vcs: not a uuid", BAD_REQUEST, () -> componentsOf(org), before);
	}

	// 4.2 case 16: saas/SecretDataFetcher
	@Test
	void aSecretOrgThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(CREATE_SECRET,
				map("s", map("org", "not-a-uuid", "name", "n", "ptSecret", "p")));
		refused(r, "secret.org: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 17: saas/SecretDataFetcher, row 92 (the unchecked refusal passes the catch of RelizaException)
	@Test
	void aSecretPermissionObjectThatIsNotAUuid() {
		String org = org();
		ExecutionResult r = dgsQueryExecutor.execute(UPDATE_SECRET_DISTRIBUTION, map("o", org, "s", random(), "p",
				List.of(map("org", org, "object", "not-a-uuid", "type", "READ_ONLY", "scope", "ORGANIZATION"))));
		refused(r, "permissions[0].object: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 18: guard 3.4 item 3, thrown before the try, so not rewrapped
	@Test
	void noSecretPermissionsAreRequired() {
		ExecutionResult r = dgsQueryExecutor.execute(UPDATE_SECRET_DISTRIBUTION, map("o", org(), "s", random()));
		refused(r, "permissions: required", BAD_REQUEST, null, null);
	}

	// 4.2 case 19: saas/ShippedProductDataFetcher
	@Test
	void aShippedProductSiteThatIsNotAUuid() {
		ExecutionResult r = dgsQueryExecutor.execute(
				"mutation($s: ShipProductInput!) { shipProduct(input: $s) { uuid } }",
				map("s", map("org", org(), "site", "not-a-uuid", "featureSet", random(), "release", random())));
		refused(r, "input.site: not a uuid", BAD_REQUEST, null, null);
	}

	// 4.2 case 20: control
	@Test
	void aValidSecretIsCreated() {
		String org = org();
		int before = secretsOf(org).size();
		ExecutionResult r = dgsQueryExecutor.execute(CREATE_SECRET,
				map("s", map("org", org, "name", "tpf65-" + random(), "ptSecret", "p")));
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		assertEquals(before + 1, secretsOf(org).size());
	}
}
