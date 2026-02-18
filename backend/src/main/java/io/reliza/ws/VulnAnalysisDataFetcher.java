/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.FindingType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateVulnAnalysisDto;
import io.reliza.model.dto.UpdateVulnAnalysisDto;
import io.reliza.model.dto.VulnAnalysisWebDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VulnAnalysisService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class VulnAnalysisDataFetcher {
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private VulnAnalysisService vulnAnalysisService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private ReleaseService releaseService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVulnAnalysis")
	public List<VulnAnalysisWebDto> getVulnAnalysis(@InputArgument("org") UUID org) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);
		
		List<VulnAnalysisData> analyses = vulnAnalysisService.findByOrg(org);
		
		return analyses.stream()
				.map(VulnAnalysisWebDto::fromVulnAnalysisData)
				.collect(Collectors.toList());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVulnAnalysisByComponent")
	public List<VulnAnalysisWebDto> getVulnAnalysisByComponent(@InputArgument("componentUuid") UUID componentUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		var ocd = getComponentService.getComponentData(componentUuid);

		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.COMPONENT, componentUuid, List.of(ro), CallType.READ);
		
		List<VulnAnalysisData> analyses = vulnAnalysisService.findAllVulnAnalysisAffectingComponent(componentUuid);
		
		return analyses.stream()
				.map(VulnAnalysisWebDto::fromVulnAnalysisData)
				.collect(Collectors.toList());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVulnAnalysisByBranch")
	public List<VulnAnalysisWebDto> getVulnAnalysisByBranch(@InputArgument("branchUuid") UUID branchUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		var obd = branchService.getBranchData(branchUuid);
		
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.BRANCH, branchUuid, List.of(ro), CallType.READ);
		
		List<VulnAnalysisData> analyses = vulnAnalysisService.findAllVulnAnalysisAffectingBranch(branchUuid);
		
		return analyses.stream()
				.map(VulnAnalysisWebDto::fromVulnAnalysisData)
				.collect(Collectors.toList());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVulnAnalysisByRelease")
	public List<VulnAnalysisWebDto> getVulnAnalysisByRelease(@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		var ord = sharedReleaseService.getReleaseData(releaseUuid);
		
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.RELEASE, releaseUuid, List.of(ro), CallType.READ);
		
		List<VulnAnalysisData> analyses = vulnAnalysisService.findAllVulnAnalysisAffectingRelease(releaseUuid);
		
		return analyses.stream()
				.map(VulnAnalysisWebDto::fromVulnAnalysisData)
				.collect(Collectors.toList());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getVulnAnalysisByLocationAndFinding")
	public List<VulnAnalysisWebDto> getVulnAnalysisByLocationAndFinding(
			@InputArgument("org") UUID org,
			@InputArgument("location") String location,
			@InputArgument("findingId") String findingId,
			@InputArgument("findingType") FindingType findingType) {
		
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.ORGANIZATION, org, List.of(ro), CallType.READ);
		
		List<VulnAnalysisData> analyses = vulnAnalysisService.findByOrgAndLocationAndFindingIdAndType(
				org, location, findingId, findingType);
		
		return analyses.stream()
				.map(VulnAnalysisWebDto::fromVulnAnalysisData)
				.collect(Collectors.toList());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createVulnAnalysis")
	public VulnAnalysisWebDto createVulnAnalysis(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Map<String, Object> inputMap = dfe.getArgument("analysis");
		CreateVulnAnalysisDto createDto = Utils.OM.convertValue(inputMap, CreateVulnAnalysisDto.class);
		
		List<RelizaObject> ros = new LinkedList<>();
		
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(createDto.getOrg());
		RelizaObject orgRo = ood.isPresent() ? ood.get() : null;
		ros.add(orgRo);
		
		// Resolve and validate scope object
		if (createDto.getScopeUuid() != null) {
			switch (createDto.getScope()) {
				case COMPONENT:
					ros.add(getComponentService.getComponentData(createDto.getScopeUuid()).orElseThrow(
							() -> new AccessDeniedException("Component not found: " + createDto.getScopeUuid())));
					break;
				case BRANCH:
					ros.add(branchService.getBranchData(createDto.getScopeUuid()).orElseThrow(
							() -> new AccessDeniedException("Branch not found: " + createDto.getScopeUuid())));
					break;
				case RELEASE:
					ros.add(releaseService.getReleaseData(createDto.getScopeUuid(), createDto.getOrg()).orElseThrow(
							() -> new AccessDeniedException("Release not found: " + createDto.getScopeUuid())));
					break;
				case ORG:
					ros.add(getOrganizationService.getOrganizationData(createDto.getScopeUuid()).orElseThrow(
							() -> new AccessDeniedException("Org not found: " + createDto.getScopeUuid())));
					break;
				case RESOURCE_GROUP:
				default:
					// TODO: implement resource group
					throw new AccessDeniedException("Unsupported create vuln analysis");
			}
		}
		
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.ORGANIZATION, createDto.getOrg(), ros, CallType.WRITE);
		
		// Validate justification is provided when org setting requires it
		if (ood.isPresent() && ood.get().getSettingsWithDefaults().isJustificationMandatory()
				&& createDto.getJustification() == null) {
			throw new RelizaException("Justification is required by organization settings");
		}
		
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		VulnAnalysisData vad = vulnAnalysisService.createVulnAnalysis(createDto, wu);
		
		return VulnAnalysisWebDto.fromVulnAnalysisData(vad);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateVulnAnalysis")
	public VulnAnalysisWebDto updateVulnAnalysis(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Map<String, Object> inputMap = dfe.getArgument("analysis");
		UpdateVulnAnalysisDto updateDto = Utils.OM.convertValue(inputMap, UpdateVulnAnalysisDto.class);
		
		// Get existing analysis to check org
		Optional<VulnAnalysisData> existingAnalysis = vulnAnalysisService.getVulnAnalysisData(updateDto.getAnalysisUuid());
		if (existingAnalysis.isEmpty()) {
			throw new AccessDeniedException("VulnAnalysis not found: " + updateDto.getAnalysisUuid());
		}
		
		VulnAnalysisData existing = existingAnalysis.get();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(existing.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.VULN_ANALYSIS, PermissionScope.ORGANIZATION, existing.getOrg(), List.of(ro), CallType.WRITE);
		
		// Validate justification is provided when org setting requires it
		if (ood.isPresent() && ood.get().getSettingsWithDefaults().isJustificationMandatory()
				&& updateDto.getJustification() == null) {
			throw new RelizaException("Justification is required by organization settings");
		}
		
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		VulnAnalysisData vad = vulnAnalysisService.updateAnalysisState(
				updateDto.getAnalysisUuid(),
				updateDto.getState(),
				updateDto.getJustification(),
				updateDto.getDetails(),
				updateDto.getFindingAliases(),
				updateDto.getSeverity(),
				wu);
		
		return VulnAnalysisWebDto.fromVulnAnalysisData(vad);
	}
	
	/** Sub-fields **/
	
	@DgsData(parentType = "VulnAnalysisWebDto", field = "releaseDetails")
	public ReleaseData releaseOfVulnAnalysis(DgsDataFetchingEnvironment dfe) {
		VulnAnalysisWebDto dto = dfe.getSource();
		if (dto.getRelease() == null) {
			return null;
		}
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(dto.getRelease());
		return ord.orElse(null);
	}
	
	@DgsData(parentType = "VulnAnalysisWebDto", field = "branchDetails")
	public BranchData branchOfVulnAnalysis(DgsDataFetchingEnvironment dfe) {
		VulnAnalysisWebDto dto = dfe.getSource();
		if (dto.getBranch() == null) {
			return null;
		}
		Optional<BranchData> obd = branchService.getBranchData(dto.getBranch());
		return obd.orElse(null);
	}
	
	@DgsData(parentType = "VulnAnalysisWebDto", field = "componentDetails")
	public ComponentData componentOfVulnAnalysis(DgsDataFetchingEnvironment dfe) {
		VulnAnalysisWebDto dto = dfe.getSource();
		if (dto.getComponent() == null) {
			return null;
		}
		Optional<ComponentData> ocd = getComponentService.getComponentData(dto.getComponent());
		return ocd.orElse(null);
	}
}
