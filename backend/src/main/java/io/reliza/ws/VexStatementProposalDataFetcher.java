/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ArtifactData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ProposalStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserData;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.VexStatementProposalWebDto;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VexStatementProposalService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class VexStatementProposalDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private VexStatementProposalService proposalService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private ArtifactService artifactService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposals")
	public List<VexStatementProposalWebDto> getVexStatementProposals(
			@InputArgument("org") UUID org,
			@InputArgument("status") ProposalStatus status) throws RelizaException {
		// Org-wide list: keep ORGANIZATION scope — there's no narrower object to bind to.
		UserData oud = currentUser();
		gateOrg(oud, org, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		List<VexStatementProposalData> data = (status == null)
			? proposalService.listForOrg(org)
			: proposalService.listForOrgAndStatus(org, status);
		return data.stream().map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposal")
	public VexStatementProposalWebDto getVexStatementProposal(@InputArgument("uuid") UUID uuid) throws RelizaException {
		UserData oud = currentUser();
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		gateProposal(oud, opt.get(), PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return VexStatementProposalWebDto.fromData(opt.get());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposalsBySourceArtifact")
	public List<VexStatementProposalWebDto> getBySourceArtifact(@InputArgument("artifact") UUID artifact) throws RelizaException {
		// Gate at the artifact's binding (COMPONENT-scoped — same pattern as ArtifactDataFetcher).
		// Empty short-circuit precedes auth: leaking "no proposals for this artifact UUID" is
		// acceptable (no row data exposed).
		UserData oud = currentUser();
		List<VexStatementProposalData> data = proposalService.listForArtifact(artifact);
		if (data.isEmpty()) return List.of();
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifact);
		if (oad.isEmpty()) {
			// Proposals exist but artifact is gone — fall back to org gate on first proposal's org.
			gateOrg(oud, data.get(0).getOrg(), PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
			return data.stream().map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
		}
		gateArtifact(oud, oad.get(), PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return data.stream().map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposalsByRelease")
	public List<VexStatementProposalWebDto> getByRelease(
			@InputArgument("org") UUID org,
			@InputArgument("release") UUID release) throws RelizaException {
		UserData oud = currentUser();
		gateRelease(oud, org, release, PermissionFunction.FINDING_ANALYSIS_READ, CallType.READ);
		return proposalService.listForRelease(org, release).stream()
			.map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "acceptVexStatementProposal")
	public VexStatementProposalWebDto accept(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("comment") String comment) throws RelizaException {
		UserData oud = currentUser();
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		gateProposal(oud, opt.get(), PermissionFunction.FINDING_ANALYSIS_WRITE, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud);
		return VexStatementProposalWebDto.fromData(proposalService.accept(uuid, comment, wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "rejectVexStatementProposal")
	public VexStatementProposalWebDto reject(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("reason") String reason) throws RelizaException {
		UserData oud = currentUser();
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		gateProposal(oud, opt.get(), PermissionFunction.FINDING_ANALYSIS_WRITE, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud);
		return VexStatementProposalWebDto.fromData(proposalService.reject(uuid, wu, reason));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateVexStatementProposal")
	public VexStatementProposalWebDto updateProposal(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("updates") VexStatementProposalData updates) throws RelizaException {
		UserData oud = currentUser();
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		gateProposal(oud, opt.get(), PermissionFunction.FINDING_ANALYSIS_WRITE, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud);
		return VexStatementProposalWebDto.fromData(proposalService.updateProposal(uuid, updates, wu));
	}

	private UserData currentUser() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new AccessDeniedException("Unauthenticated"));
	}

	private void gateOrg(UserData oud, UUID org, PermissionFunction perm, CallType callType) throws RelizaException {
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm,
			PermissionScope.ORGANIZATION, org, List.of(ro), callType);
	}

	/**
	 * Gate a single proposal at the scope it was emitted at: RELEASE / BRANCH / COMPONENT proposals
	 * are checked at that narrower scope; ORG / RESOURCE_GROUP fall back to ORGANIZATION.
	 * Mirrors the pattern in VulnAnalysisDataFetcher.
	 */
	private void gateProposal(UserData oud, VexStatementProposalData d, PermissionFunction perm, CallType callType) throws RelizaException {
		AnalysisScope scope = d.getScope();
		UUID scopeUuid = d.getScopeUuid();
		if (scope == AnalysisScope.RELEASE && scopeUuid != null) {
			gateRelease(oud, d.getOrg(), scopeUuid, perm, callType);
		} else if (scope == AnalysisScope.COMPONENT && scopeUuid != null) {
			gateComponent(oud, d.getOrg(), scopeUuid, perm, callType);
		} else {
			gateOrg(oud, d.getOrg(), perm, callType);
		}
	}

	private void gateRelease(UserData oud, UUID org, UUID release, PermissionFunction perm, CallType callType) throws RelizaException {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(release, org);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm,
			PermissionScope.RELEASE, release, List.of(ro), callType);
	}

	private void gateComponent(UserData oud, UUID org, UUID component, PermissionFunction perm, CallType callType) throws RelizaException {
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm,
			PermissionScope.COMPONENT, component, List.of(ro), callType);
	}

	/**
	 * Gate by the artifact's binding. VEX artifacts attach to a RELEASE or SCE/DELIVERABLE
	 * (component-scoped). We pick the most-specific scope we can derive without an extra
	 * lookup chain.
	 */
	private void gateArtifact(UserData oud, ArtifactData ad, PermissionFunction perm, CallType callType) throws RelizaException {
		// Artifact-level gate: scope to the artifact's org and let AuthorizationService apply
		// component/release inheritance. ArtifactData already implements RelizaObject and
		// surfaces its component/release linkage for the authorizer.
		authorizationService.isUserAuthorizedForObjectGraphQL(oud, perm,
			PermissionScope.ORGANIZATION, ad.getOrg(), List.of((RelizaObject) ad), callType);
	}
}
