/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.AnalysisScope;
import io.reliza.model.Organization;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.LicenseStatus;
import io.reliza.service.SafetyContextService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * SAFETY_CONTEXT GraphQL gating through DgsQueryExecutor: a Finding Analysis reader sees the inbox
 * and the raw assertion; a caller without the permission gets, for an existing row, exactly what
 * an unknown uuid gets.
 */
@SpringBootTest(classes = {App.class})
class SafetyContextDataFetcherAuthorizationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private SafetyContextService safetyContextService;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		SecurityContextHolder.clearContext();
	}

	private UserData user(Organization org, List<PermissionFunction> functions) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Safety " + tag, "fda5-" + tag + "@safety-context.io", true,
			List.of(org.getUuid()), "fda5-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
			PermissionType.READ_WRITE, functions, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private SafetyContextProposalData proposal(Organization org) {
		SafetyContextProposalData d = new SafetyContextProposalData();
		d.setUuid(UUID.randomUUID());
		d.setOrg(org.getUuid());
		d.setScope(AnalysisScope.ORG);
		d.setScopeUuid(org.getUuid());
		d.setComponentPurl("pkg:generic/acme/fw@1.0.0");
		d.setSchemeKey("IEC-62304");
		d.setAssetRefId("asset-" + UUID.randomUUID());
		d.setLevelRaw("Class C");
		d.setLineageKey("lineage-" + UUID.randomUUID());
		d.setSourceAssertionHash("h");
		d.setSourceAssertionJson("{\"refId\":\"asset\"}");
		d.setStatus(ProposalStatus.PENDING);
		return safetyContextService.saveProposal(d, WU);
	}

	private ExecutionResult as(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("fda5-safety-context").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private static final String ASSERTION = "query($u: ID!) { getSafetyContextSourceAssertion(uuid: $u) }";
	private static final String ACCEPT = "mutation($u: ID!) { acceptSafetyContextProposal(uuid: $u, comment: \"x\") { status } }";

	@Test
	void readerSeesInboxAndAssertion() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		SafetyContextProposalData p = proposal(org);
		UserData reader = user(org, List.of(PermissionFunction.RESOURCE, PermissionFunction.FINDING_ANALYSIS_READ));
		ExecutionResult inbox = as(reader, "query($o: ID!) { getSafetyContextProposals(org: $o, status: PENDING) "
			+ "{ uuid kind verificationStatus levelRaw } }", Map.of("o", org.getUuid().toString()));
		assertTrue(inbox.getErrors().isEmpty(), inbox.getErrors().toString());
		String body = inbox.getData().toString();
		assertTrue(body.contains(p.getUuid().toString()), body);
		assertTrue(body.contains("PROPOSAL") && body.contains("UNVERIFIED"), body);

		ExecutionResult raw = as(reader, ASSERTION, Map.of("u", p.getUuid().toString()));
		assertTrue(raw.getErrors().isEmpty(), raw.getErrors().toString());
		assertTrue(raw.getData().toString().contains("asset"));
	}

	@Test
	void forbiddenLooksLikeMissing() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		SafetyContextProposalData p = proposal(org);
		UserData outsider = user(org, List.of(PermissionFunction.RESOURCE));
		for (String query : List.of(ASSERTION, ACCEPT)) {
			ExecutionResult existing = as(outsider, query, Map.of("u", p.getUuid().toString()));
			ExecutionResult missing = as(outsider, query, Map.of("u", UUID.randomUUID().toString()));
			assertFalse(existing.getErrors().isEmpty(), "refused: " + query);
			assertEquals(missing.getErrors().size(), existing.getErrors().size(), query);
			assertEquals(missing.getErrors().get(0).getMessage(), existing.getErrors().get(0).getMessage(), query);
		}
		assertEquals(ProposalStatus.PENDING, safetyContextService.getProposal(p.getUuid()).orElseThrow().getStatus(),
			"the refused accept changed nothing");
	}

	@Test
	void readerCannotAccept() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		SafetyContextProposalData p = proposal(org);
		UserData reader = user(org, List.of(PermissionFunction.RESOURCE, PermissionFunction.FINDING_ANALYSIS_READ));
		ExecutionResult r = as(reader, ACCEPT, Map.of("u", p.getUuid().toString()));
		assertFalse(r.getErrors().isEmpty());
		assertEquals(ProposalStatus.PENDING, safetyContextService.getProposal(p.getUuid()).orElseThrow().getStatus());
	}
}
