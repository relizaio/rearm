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
import io.reliza.model.OrganizationData;
import io.reliza.model.ProposalStatus;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.VexStatementProposalWebDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
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

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposals")
	public List<VexStatementProposalWebDto> getVexStatementProposals(
			@InputArgument("org") UUID org,
			@InputArgument("status") ProposalStatus status) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_READ, PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);

		List<VexStatementProposalData> data = (status == null)
			? proposalService.listForOrg(org)
			: proposalService.listForOrgAndStatus(org, status);
		return data.stream().map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposal")
	public VexStatementProposalWebDto getVexStatementProposal(@InputArgument("uuid") UUID uuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		VexStatementProposalData d = opt.get();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(d.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_READ, PermissionScope.ORGANIZATION, d.getOrg(), List.of(ro), CallType.READ);
		return VexStatementProposalWebDto.fromData(d);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposalsBySourceArtifact")
	public List<VexStatementProposalWebDto> getBySourceArtifact(@InputArgument("artifact") UUID artifact) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		// Permission gate via the org of the first row (all share the same org per-artifact).
		// Empty short-circuit precedes auth: leaking "no proposals for this artifact UUID" is acceptable
		// (no row data exposed); v2 may tighten via an artifact→org lookup if proposal-count probing becomes a concern.
		List<VexStatementProposalData> data = proposalService.listForArtifact(artifact);
		if (data.isEmpty()) return List.of();
		UUID org = data.get(0).getOrg();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_READ, PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);
		return data.stream().map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVexStatementProposalsByRelease")
	public List<VexStatementProposalWebDto> getByRelease(
			@InputArgument("org") UUID org,
			@InputArgument("release") UUID release) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_READ, PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);
		return proposalService.listForRelease(org, release).stream()
			.map(VexStatementProposalWebDto::fromData).collect(Collectors.toList());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "acceptVexStatementProposal")
	public VexStatementProposalWebDto accept(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("comment") String comment) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		VexStatementProposalData d = opt.get();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(d.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_WRITE, PermissionScope.ORGANIZATION, d.getOrg(), List.of(ro), CallType.READ);

		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return VexStatementProposalWebDto.fromData(proposalService.accept(uuid, comment, wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "rejectVexStatementProposal")
	public VexStatementProposalWebDto reject(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("reason") String reason) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		VexStatementProposalData d = opt.get();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(d.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_WRITE, PermissionScope.ORGANIZATION, d.getOrg(), List.of(ro), CallType.READ);

		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return VexStatementProposalWebDto.fromData(proposalService.reject(uuid, wu, reason));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateVexStatementProposal")
	public VexStatementProposalWebDto updateProposal(
			@InputArgument("uuid") UUID uuid,
			@InputArgument("updates") VexStatementProposalData updates) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<VexStatementProposalData> opt = proposalService.getProposal(uuid);
		if (opt.isEmpty()) throw new AccessDeniedException("Proposal not found: " + uuid);
		VexStatementProposalData d = opt.get();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(d.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(),
			PermissionFunction.FINDING_ANALYSIS_WRITE, PermissionScope.ORGANIZATION, d.getOrg(), List.of(ro), CallType.READ);

		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return VexStatementProposalWebDto.fromData(proposalService.updateProposal(uuid, updates, wu));
	}
}
