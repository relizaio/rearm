/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.OrganizationData;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.BranchService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;

/**
 * The agent-session read gate reads a FREEFORM key's effective set (TPF-91 architecture-5 decision 10,
 * row 16): org-wide AGENT at ESSENTIAL_READ or above through an ACTIVE group passes step 1 for the
 * key's own session, read per call; a USER key and a FEDERATED identity keep today's step 1; a group of
 * another organization adds nothing.
 */
public class ApiKeyGroupAgentSessionReadTest extends ApiKeyGroupTestBase {

	private static final String LACKS = "Calling key lacks org-wide AGENT permission";
	private static final String READ = "query($r: ID!, $s: ID) { agenticReleaseProgrammatic(releaseUuid: $r, sessionUuid: $s) { uuid } }";

	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private FederatedTrustRuleService federatedTrustRuleService;
	@Autowired private GetOrganizationService getOrganizationService;

	/** An agent key with no grant of its own, its session, and a release attributed to the session. */
	private record Fixture(ApiKey ka, UUID session, UUID release) {}

	private Fixture agent(Organization org) throws RelizaException {
		UUID o = org.getUuid();
		ApiKey ka = apiKeyService.createObjectApiKey(o, io.reliza.model.ApiKey.ApiTypeEnum.FREEFORM, o,
				UUID.randomUUID().toString(), "tpf-91 agent", WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(o, IdentityType.REARM_API_KEY,
				ka.getUuid().toString(), WU).getUuid();
		AgentData root = agentService.findOrRegisterRootAgent(o, identity, "tpf91-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData sd = agentSessionService.initialize(o, root.getUuid(), ka.getUuid(), "tpf91-" + UUID.randomUUID(),
				"tpf-91 session", null, null, WU);
		Component c = componentService.createComponent("akg_agent_" + UUID.randomUUID(), o,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch b = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		UUID vcs = vcsRepositoryService.provisionVcsRepository(o, "github.com/example/tpf91-" + UUID.randomUUID(), VcsType.GIT, WU);
		UUID sce = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(b.getUuid()).vcs(vcs)
				.organizationUuid(o).commit(UUID.randomUUID().toString().replace("-", "") + "aaaaaaaa").build(), WU).getUuid();
		UUID r = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(o).branch(b.getUuid())
				.version("1.0.0").sourceCodeEntry(sce).status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(),
				WU).getUuid();
		sd.addCommit(sce);
		agentSessionService.saveData(sd, WU);
		return new Fixture(ka, sd.getUuid(), r);
	}

	private FreeformKeyVerification gate(Fixture f, AuthHeaderParse principal) throws RelizaException {
		return authorizationService.isFreeformKeyAuthorizedForAgenticSessionRead(principal, f.session(), null, f.release());
	}

	private FreeformKeyVerification gate(Fixture f, ApiKey key) throws RelizaException {
		return gate(f, as(key));
	}

	private void refusedWith(Fixture f, AuthHeaderParse principal, String text) {
		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> gate(f, principal));
		assertEquals(text, e.getMessage());
	}

	@Test
	public void aGroupsOrgWideAgentGrantPassesTheGateForTheKeysOwnSession() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Fixture f = agent(org);
		ApiKey ka = f.ka();

		// (a) no own grant, no group
		refusedWith(f, as(ka), LACKS);

		// (b) through the group: the whole gate passes
		ApiKeyGroupData ga = group(org, Set.of(ka.getUuid()), orgWide(org, PermissionType.ESSENTIAL_READ, PermissionFunction.AGENT));
		FreeformKeyVerification v = gate(f, ka);
		assertEquals(ka.getUuid(), v.apiKeyUuid());
		assertEquals(org.getUuid(), v.orgUuid());
		assertFalse(authorizationService.boardPermission(as(ka), org.getUuid(), board(org).getUuid(),
				PermissionFunction.BOARD_READ, CallType.READ), "the group grants AGENT, not a board");

		// (c) INACTIVE revokes, restore regrants, removal revokes
		apiKeyGroupService.deactivateApiKeyGroup(ga.getUuid(), WU);
		refusedWith(f, as(ka), LACKS);
		restoreGroup(ga);
		gate(f, ka);
		setMembers(ga);
		refusedWith(f, as(ka), LACKS);
		setMembers(ga, ka.getUuid());

		// (d) the level floor and the function test hold through a group
		setPermissions(ga, orgWide(org, PermissionType.NONE, PermissionFunction.AGENT));
		refusedWith(f, as(ka), LACKS);
		setPermissions(ga, orgWide(org, PermissionType.READ_ONLY));
		refusedWith(f, as(ka), LACKS);
		setPermissions(ga, orgWide(org, PermissionType.ESSENTIAL_READ, PermissionFunction.AGENT));
		gate(f, ka);

		// (e) a USER key with a planted membership gains nothing: its step 1 reads its own row
		UserData owner = member(org, PermissionType.READ_ONLY);
		userService.setUserPermission(owner.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.AGENT), null, WU);
		ApiKey userKey = userKey(org, owner);
		plant(ga, userKey.getUuid());
		refusedWith(f, as(userKey), LACKS);
		apiKeyService.setPermissionsOnApiKey(userKey.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.ESSENTIAL_READ, Set.of(PermissionFunction.AGENT), null)), WU);
		refusedWith(f, as(userKey), "Session not owned by calling key");
	}

	@Test
	public void aGroupOfAnotherOrganizationAddsNothingToTheGate() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		Fixture f = agent(org);
		ApiKeyGroupData ga = group(org, Set.of(f.ka().getUuid()), orgWide(org, PermissionType.ESSENTIAL_READ, PermissionFunction.AGENT));
		apiKeyGroupService.deactivateApiKeyGroup(ga.getUuid(), WU);
		ApiKeyGroupData foreign = group(org2, Set.of(), orgWide(org2, PermissionType.ESSENTIAL_READ, PermissionFunction.AGENT));
		plant(foreign, f.ka().getUuid());
		refusedWith(f, as(f.ka()), LACKS);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theWireReadPassesTheGateThroughTheGroup() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Fixture f = agent(org);
		ApiKeyGroupData ga = group(org, Set.of(f.ka().getUuid()), orgWide(org, PermissionType.ESSENTIAL_READ, PermissionFunction.AGENT));
		Map<String, Object> vars = Map.of("r", f.release().toString(), "s", f.session().toString());
		ExecutionResult ok = asKey(f.ka(), READ, vars);
		assertTrue(ok.getErrors().isEmpty(), ok.getErrors().toString());
		assertEquals(f.release().toString(),
				((Map<String, Object>) ((Map<String, Object>) ok.getData()).get("agenticReleaseProgrammatic")).get("uuid"));
		apiKeyGroupService.deactivateApiKeyGroup(ga.getUuid(), WU);
		ExecutionResult refused = asKey(f.ka(), READ, vars);
		assertEquals(1, refused.getErrors().size(), "both paths refused: " + refused.getErrors());
	}

	@Test
	public void aFederatedIdentityKeepsTodaysRefusalAtStepOne() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Fixture f = agent(org);
		FederatedMatcher matcher = new FederatedMatcher();
		matcher.setOwner("tpf91");
		FederatedGrant grant = new FederatedGrant();
		grant.setOrgPermission(PermissionType.ESSENTIAL_READ);
		grant.setFunctions(Set.of(PermissionFunction.AGENT));
		FederatedTrustRule rule = federatedTrustRuleService.create(org.getUuid(), "tpf91-rule-" + UUID.randomUUID(), null, null,
				matcher, grant, null, WU);
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(FederatedTrustRule.Provider.GITHUB_ACTIONS);
		fi.setOwner("tpf91");
		fi.setRepository("tpf91/app");
		fi.setRepositoryUri("github.com/tpf91/app");
		ApiKey identity = apiKeyService.ensureFederatedIdentity(org.getUuid(), fi, WU);
		FederatedContext ctx = new FederatedContext(List.of(rule.getUuid()), "github",
				"https://token.actions.githubusercontent.com", "tpf91", "tpf91/app", "github.com/tpf91/app",
				"refs/heads/main", "abc123", "wf.yml@refs/heads/main", null, "push", "octocat", "987");
		AuthHeaderParse fed = AuthHeaderParse.fromVerifiedFederation(identity, ctx, "198.51.100.14");
		assertTrue(fed.isFederatedIdentity());

		// Precondition: the rule's AGENT grant is live through keyPermissions (the agent-flow write check).
		OrganizationData od = getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow();
		FreeformKeyVerification write = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(fed, PermissionFunction.AGENT,
				PermissionScope.ORGANIZATION, org.getUuid(), List.of(od), CallType.ESSENTIAL_READ);
		assertEquals(identity.getUuid(), write.apiKeyUuid());

		// The gate's own FREEFORM guard keeps the identity row, which stores nothing, as step 1's read.
		refusedWith(f, fed, LACKS);
	}
}
