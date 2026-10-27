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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseApprovalInput;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.saas.ApprovalEntryDto;
import io.reliza.model.dto.saas.ApprovalPolicyDto;
import io.reliza.model.saas.ApprovalEntryData.ApprovalRequirement;
import io.reliza.model.saas.ApprovalEntryData;
import io.reliza.service.BranchService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.saas.ApprovalEntryService;
import io.reliza.service.saas.ApprovalPolicyService;
import io.reliza.service.saas.SaasAuthorizationService;
import io.reliza.service.saas.SaasAuthorizationService.VoteAuthorization;

/**
 * The programmatic release approval vote reads a FREEFORM key's effective set (TPF-91 architecture-5
 * decision 9, row 15): approval roles and the org-ADMIN shortcut a key's ACTIVE groups hold count on
 * the vote, read per call; a USER key keeps its own set under the owner clamp; a group of another
 * organization adds nothing.
 */
public class ApiKeyGroupApprovalVoteTest extends ApiKeyGroupTestBase {

	private static final String APPROVE = "mutation($a: ReleaseApprovalProgrammaticInput!) {"
			+ " approveReleaseProgrammatic(releaseApprovals: $a) { uuid } }";

	@Autowired private SaasAuthorizationService saasAuthorizationService;
	@Autowired private ApprovalEntryService approvalEntryService;
	@Autowired private ApprovalPolicyService approvalPolicyService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;

	private record Vote(UUID release, UUID entry) {}

	/** Component, release, an entry allowing QA and SEC (one approval), and a policy holding it on the component. */
	private Vote release(Organization org) throws RelizaException {
		Component c = componentService.createComponent("akg_vote_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch b = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		UUID r = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).branch(b.getUuid())
				.org(org.getUuid()).status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0").build(), WU).getUuid();
		ApprovalRequirement req = new ApprovalRequirement();
		req.setAllowedApprovalRoleIds(Set.of("QA", "SEC"));
		req.setRequiredNumberOfApprovals(1);
		UUID e = approvalEntryService.createApprovalEntry(ApprovalEntryDto.builder().org(org.getUuid())
				.approvalName("akg-" + UUID.randomUUID()).approvalRequirements(List.of(req)).build(), WU).getUuid();
		UUID p = approvalPolicyService.createApprovalPolicy(ApprovalPolicyDto.builder().org(org.getUuid())
				.policyName("akg-" + UUID.randomUUID()).approvalEntries(List.of(e)).build(), WU).getUuid();
		componentService.updateComponent(ComponentDto.builder().uuid(c.getUuid()).name(ComponentData.dataFromRecord(c).getName())
				.approvalPolicy(p).build(), WU);
		return new Vote(r, e);
	}

	/** The authorization alone, without casting, so every step may repeat on the same release. */
	private VoteAuthorization va(Vote v, ApiKey key, String role) throws RelizaException {
		Optional<ReleaseData> rd = sharedReleaseService.getReleaseData(v.release());
		return saasAuthorizationService.isApiKeyAuthorizedToApprove(as(key),
				List.of(new ReleaseApprovalInput(v.entry(), role, ApprovalState.APPROVED, "c")), rd);
	}

	private void refused(Vote v, ApiKey key, String role, String text) {
		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> va(v, key, role));
		assertTrue(e.getMessage().contains(text), e.getMessage());
	}

	@Test
	public void aGroupsApprovalRoleAndOrgAdminCountOnTheVoteReadPerCall() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Vote v = release(org);
		ApiKey k1 = freeform(org, "voter");

		// (a) approval role through a group
		refused(v, k1, "QA", "Missing Permissions");
		ApiKeyGroupData gq = group(org, Set.of(k1.getUuid()), orgApprovals(org, "QA"));
		VoteAuthorization qa = va(v, k1, "QA");
		assertTrue(AuthorizationResponse.isAllowed(qa.response()));
		assertFalse(qa.orgAdmin());
		refused(v, k1, "SEC", "Unknown approval");

		// (b) org ADMIN through a group
		setPermissions(gq, orgWide(org, PermissionType.ADMIN));
		VoteAuthorization admin = va(v, k1, "SEC");
		assertTrue(admin.orgAdmin(), "the admin shortcut through the group");
		setPermissions(gq, orgApprovals(org, "QA"));
		refused(v, k1, "SEC", "Unknown approval");
		assertFalse(va(v, k1, "QA").orgAdmin());

		// (c) INACTIVE revokes, restore regrants, removal revokes
		apiKeyGroupService.deactivateApiKeyGroup(gq.getUuid(), WU);
		refused(v, k1, "QA", "Missing Permissions");
		restoreGroup(gq);
		assertFalse(va(v, k1, "QA").orgAdmin());
		setMembers(gq);
		refused(v, k1, "QA", "Missing Permissions");
		setMembers(gq, k1.getUuid());

		// (d) a USER key with a planted membership gains nothing while its owner holds the role
		UserData owner = member(org, PermissionType.READ_ONLY);
		userTeam(org, owner, orgApprovals(org, "QA"));
		ApiKey userKey = userKey(org, owner);
		plant(gq, userKey.getUuid());
		refused(v, userKey, "QA", "Missing Permissions");
		apiKeyService.setApprovalTypes(userKey.getUuid(), List.of("QA"), WU);
		assertFalse(va(v, userKey, "QA").orgAdmin(), "positive control: the owner side allows QA");

		// (e) own approvals are unioned with the group's, not replaced
		ApiKey k2 = freeform(org, "own SEC");
		apiKeyService.setApprovalTypes(k2.getUuid(), List.of("SEC"), WU);
		setMembers(gq, k1.getUuid(), k2.getUuid());
		va(v, k2, "QA");
		va(v, k2, "SEC");
		setMembers(gq, k1.getUuid());
		va(v, k2, "SEC");
		refused(v, k2, "QA", "Unknown approval");
	}

	@Test
	public void aKeyInSeveralGroupsVotesWithTheRoleOfEachAndTheAdminShortcutOfAny() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Vote v = release(org);
		ApiKey k1 = freeform(org, "voter");
		ApiKeyGroupData gq = group(org, Set.of(k1.getUuid()), orgApprovals(org, "QA"));
		ApiKeyGroupData gs = group(org, Set.of(k1.getUuid()), orgApprovals(org, "SEC"));

		assertTrue(AuthorizationResponse.isAllowed(va(v, k1, "QA").response()), "the role of the first group");
		assertTrue(AuthorizationResponse.isAllowed(va(v, k1, "SEC").response()), "and the role of the second");
		assertFalse(va(v, k1, "QA").orgAdmin());

		ApiKeyGroupData ga = group(org, Set.of(k1.getUuid()), orgWide(org, PermissionType.ADMIN));
		assertTrue(va(v, k1, "QA").orgAdmin(), "the admin shortcut of a third group, whichever the order of the query");
		assertTrue(va(v, k1, "SEC").orgAdmin());
		apiKeyGroupService.deactivateApiKeyGroup(ga.getUuid(), WU);
		assertFalse(va(v, k1, "QA").orgAdmin());
		apiKeyGroupService.deactivateApiKeyGroup(gq.getUuid(), WU);
		refused(v, k1, "QA", "Unknown approval");
		assertTrue(AuthorizationResponse.isAllowed(va(v, k1, "SEC").response()), "the one group left still counts");
		apiKeyGroupService.deactivateApiKeyGroup(gs.getUuid(), WU);
		refused(v, k1, "SEC", "Missing Permissions");
	}

	@Test
	public void aGroupOfAnotherOrganizationAddsNothingToTheVote() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		Vote v = release(org);
		ApiKey k1 = freeform(org, "voter");
		ApiKeyGroupData gq = group(org, Set.of(k1.getUuid()), orgApprovals(org, "QA"));
		apiKeyGroupService.deactivateApiKeyGroup(gq.getUuid(), WU);
		ApiKeyGroupData foreign = group(org2, Set.of(), orgApprovals(org2, "QA"));
		plant(foreign, k1.getUuid());
		refused(v, k1, "QA", "Missing Permissions");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theWireVoteIsCastWithTheGroupsRole() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Vote v = release(org);
		ApiKey k1 = freeform(org, "voter");
		group(org, Set.of(k1.getUuid()), orgApprovals(org, "QA"));
		int before = eventsOf(v).size();
		ExecutionResult r = asKey(k1, APPROVE, Map.of("a", Map.of("release", v.release().toString(), "approvals", List.of(Map.of(
				"approvalEntry", v.entry().toString(), "approvalRoleId", "QA", "state", "APPROVED")))));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<ReleaseApprovalEvent> events = eventsOf(v);
		assertEquals(before + 1, events.size());
		ReleaseApprovalEvent cast = events.get(events.size() - 1);
		assertEquals("QA", cast.approvalRoleId());
		assertEquals(k1.getUuid(), ApprovalEntryData.voterOf(cast), "no human behind the key: the key votes");
	}

	private List<ReleaseApprovalEvent> eventsOf(Vote v) {
		List<ReleaseApprovalEvent> events = sharedReleaseService.getReleaseData(v.release()).orElseThrow().getApprovalEvents();
		return null == events ? List.of() : events;
	}
}
