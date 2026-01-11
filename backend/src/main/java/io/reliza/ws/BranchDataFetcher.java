/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.VersionResponse;
import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.DependencyPatternService;
import io.reliza.service.GetComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.versioning.VersionApi.ActionEnum;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class BranchDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	BranchService branchService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	ComponentService componentService;
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	@Autowired
	VersionAssignmentService versionAssignmentService;
	
	@Autowired
	VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	DependencyPatternService dependencyPatternService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "branch")
	public BranchData getBranch(@InputArgument("branchUuid") String branchUuidStr) {
		UUID branchUuid = UUID.fromString(branchUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return obd.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "branchesOfComponent")
	public List<BranchData> getBranchesOfComponent(DgsDataFetchingEnvironment dfe,
			@InputArgument("componentUuid") String compUuidStr) {
		UUID compUuid = UUID.fromString(compUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ComponentData> opd = getComponentService.getComponentData(compUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		
		return branchService.listBranchDataOfComponent(compUuid, null);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getNextVersion")
	public String getNextVersion(
			@InputArgument("branchUuid") String branchUuidStr,
			@InputArgument("versionType") String versionTypeStr
		){
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID branchUuid = UUID.fromString(branchUuidStr);
		VersionTypeEnum versionType = VersionTypeEnum.DEV;
		if(StringUtils.isNotEmpty(versionTypeStr)){
			
			versionType = VersionTypeEnum.valueOf(versionTypeStr);
		}
		
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return versionAssignmentService.getCurrentNextVersion(branchUuid, versionType);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createBranch")
	public BranchData createBranch(
			@InputArgument("componentUuid") String compUuidStr,
			@InputArgument("name") String name,
			@InputArgument("versionSchema") String versionSchema) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID compUuid = UUID.fromString(compUuidStr);
		Optional<ComponentData> opd = getComponentService.getComponentData(compUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		try {
			return BranchData.branchDataFromDbRecord(branchService
									.createBranch(name, opd.get(), null, null, versionSchema, null, wu));
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateBranch")
	public BranchData updateBranch(DgsDataFetchingEnvironment dfe) throws RelizaException{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> updateBranchInputMap = dfe.getArgument("branch");
		BranchDto updateBranchInput = Utils.OM.convertValue(updateBranchInputMap, BranchDto.class);
		
		// Validate dependency patterns
		if (updateBranchInput.getDependencyPatterns() != null) {
			for (BranchData.DependencyPattern pattern : updateBranchInput.getDependencyPatterns()) {
				if (pattern.getPattern() == null || pattern.getPattern().trim().isEmpty()) {
					throw new RelizaException("Pattern cannot be empty");
				}
				// Validate regex pattern
				try {
					Pattern.compile(pattern.getPattern());
				} catch (Exception e) {
					log.error("Invalid regex pattern: {} - {}", pattern.getPattern(), e.getMessage());
					throw new RelizaException("Invalid regex pattern: " + pattern.getPattern());
				}
			}
		}
		Optional<BranchData> obd = branchService.getBranchData(updateBranchInput.getUuid());
		List<RelizaObject> roList = new LinkedList<>();
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		roList.add(ro);
		if (obd.isPresent() && null != obd.get().getDependencies() && !obd.get().getDependencies().isEmpty()) {
			obd.get().getDependencies().forEach((dep) -> {
				roList.add(getComponentService.getComponentData(dep.getUuid()).orElseThrow());
				roList.add(branchService.getBranchData(dep.getBranch()).orElseThrow());
				if (null != dep.getRelease()) {
					roList.add(sharedReleaseService.getReleaseData(dep.getRelease()).orElseThrow());
				}
			});
		}
		if (null != updateBranchInput.getDependencies() && !updateBranchInput.getDependencies().isEmpty()) {
			updateBranchInput.getDependencies().forEach((dep) -> {
				roList.add(getComponentService.getComponentData(dep.getUuid()).orElseThrow());
				roList.add(branchService.getBranchData(dep.getBranch()).orElseThrow());
				if (null != dep.getRelease()) {
					roList.add(sharedReleaseService.getReleaseData(dep.getRelease()).orElseThrow());
				}
			});
		}
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObjects(oud.get(), roList, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		
		return branchService.updateBranch(updateBranchInput, wu);
		
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "getNewVersionManual")
	public VersionResponse getNewBranchVersion(
			@InputArgument("branchUuid") String branchUuidStr,
			@InputArgument("action") ActionEnum bumpAction) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID branchUuid = UUID.fromString(branchUuidStr);
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		// TODO add WhoUpdated wu = null;
		
		// Check for missing version schema before attempting version generation
		BranchData bd = obd.get();
		ComponentData pd = getComponentService.getComponentData(bd.getComponent()).orElseThrow();
		if (StringUtils.isEmpty(pd.getVersionSchema()) && StringUtils.isEmpty(bd.getVersionSchema())) {
			throw new RelizaException(String.format(
				"Component '%s' (%s) is missing version schema configuration.",
				pd.getName(), pd.getUuid()
			));
		}		
		Optional<VersionAssignment> ova = versionAssignmentService.getSetNewVersion(branchUuid, bumpAction, null, null, VersionTypeEnum.DEV);
		if (ova.isPresent()) {
			return new VersionResponse(ova.get().getVersion(), Utils.dockerTagSafeVersion(ova.get().getVersion()), "");
		} else {
			throw new RuntimeException("Failed to retrieve next version");
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "autoIntegrateFeatureSet")
	public Boolean autoIntegrateFeatureSet(@InputArgument("branchUuid") UUID branchUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		releaseService.autoIntegrateFeatureSetOnDemand(obd.get());
		return true;
	}
	
	@DgsData(parentType = "Mutation", field = "setNextVersion")
	public boolean setNextVersion (
			@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("versionString") String versionString,
			@InputArgument("versionType") String versionTypeStr
		) {
		
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		VersionTypeEnum versionType = VersionTypeEnum.DEV;
		if(StringUtils.isNotEmpty(versionTypeStr)){
			versionType = VersionTypeEnum.valueOf(versionTypeStr);
		}
		return versionAssignmentService.setNextVesion(branchUuid, versionString, versionType);
	}
	
	@DgsData(parentType = "Mutation", field = "synchronizeLiveBranches")
	public Boolean synchronizeLiveBranches(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> synchronizeBranchInput = dfe.getArgument("synchronizeBranchInput");
		
		UUID componentId = componentService.resolveComponentIdFromInput(synchronizeBranchInput, ahp);
		
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (null != ro)	ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);
		
		@SuppressWarnings("unchecked")
		List<String> liveBranches = (List<String>) synchronizeBranchInput.get("liveBranches");
		Set<UUID> deadBranches = branchService.findDeadBranches(componentId, liveBranches);
		for (UUID db : deadBranches) {
			branchService.archiveBranch(db, ar.getWhoUpdated());
		}
		return true;
	}
	
	
	/** Subfields **/
	
	@DgsData(parentType = "ChildComponent", field = "componentDetails")
	public ComponentData componentOfChildComponent(DgsDataFetchingEnvironment dfe) {
		ChildComponent cp = dfe.getSource();
		return getComponentService.getComponentData(cp.getUuid()).get();
	}
	
	@DgsData(parentType = "ChildComponent", field = "branchDetails")
	public Optional<BranchData> branchOfChildProject(DgsDataFetchingEnvironment dfe) {
		ChildComponent cp = dfe.getSource();
		Optional<BranchData> obd = Optional.empty();
		if (null != cp.getBranch()) {
			obd = branchService.getBranchData(cp.getBranch());
		}
		return obd;
	}
	
	@DgsData(parentType = "ChildComponent", field = "releaseDetails")
	public Optional<ReleaseData> releaseOfChildProject(DgsDataFetchingEnvironment dfe) {
		ChildComponent cp = dfe.getSource();
		Optional<ReleaseData> ord = Optional.empty();
		if (null != cp.getRelease()) {
			ord = sharedReleaseService.getReleaseData(cp.getRelease());
		}
		return ord;
	}
	
	@DgsData(parentType = "Branch", field = "vcsRepositoryDetails")
	public Optional<VcsRepositoryData> vcsRepoOfProject (DgsDataFetchingEnvironment dfe) {
		Optional<VcsRepositoryData> vrdo = Optional.empty();
		// TODO check on org for vcs repo
		BranchData bd = dfe.getSource();
		UUID vcsRepoUuid = bd.getVcs();
		if (null != vcsRepoUuid) {
			vrdo = vcsRepositoryService.getVcsRepositoryData(vcsRepoUuid);
		}
		return vrdo;
	}
	
	@DgsData(parentType = "Branch", field = "componentDetails")
	public ComponentData projectOfBranch(DgsDataFetchingEnvironment dfe) {
		BranchData bd = dfe.getSource();
		return getComponentService.getComponentData(bd.getComponent()).get();
	}
	
	@DgsData(parentType = "Branch", field = "effectiveDependencies")
	public List<Map<String, Object>> effectiveDependenciesOfBranch(DgsDataFetchingEnvironment dfe) {
		BranchData bd = dfe.getSource();
		List<ChildComponent> effectiveDeps = dependencyPatternService.resolveEffectiveDependencies(bd);
		List<Map<String, Object>> result = new LinkedList<>();
		
		for (ChildComponent cc : effectiveDeps) {
			Map<String, Object> dep = new java.util.HashMap<>();
			dep.put("component", getComponentService.getComponentData(cc.getUuid()).orElse(null));
			dep.put("branch", branchService.getBranchData(cc.getBranch()).orElse(null));
			dep.put("status", cc.getStatus());
			
			// Add release information if present
			if (cc.getRelease() != null) {
				dep.put("release", cc.getRelease());
				dep.put("releaseDetails", sharedReleaseService.getReleaseData(cc.getRelease()).orElse(null));
			} else {
				dep.put("release", null);
				dep.put("releaseDetails", null);
			}
			
			// Add isFollowVersion flag
			dep.put("isFollowVersion", cc.getIsFollowVersion() != null ? cc.getIsFollowVersion() : false);
			
			// Determine source - check if this is from manual dependencies or pattern
			boolean isManual = bd.getDependencies() != null && 
				bd.getDependencies().stream().anyMatch(d -> d.getUuid().equals(cc.getUuid()));
			dep.put("source", isManual ? "MANUAL" : "PATTERN");
			dep.put("sourcePattern", null); // TODO: track which pattern matched
			
			result.add(dep);
		}
		return result;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "previewPattern")
	public List<Map<String, Object>> previewPattern(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("pattern") String pattern,
			@InputArgument("targetBranchName") String targetBranchName,
			@InputArgument("defaultStatus") StatusEnum defaultStatus,
			DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		
		// Create a temporary pattern
		BranchData.DependencyPattern tempPattern = BranchData.DependencyPattern.builder()
			.uuid(UUID.randomUUID())
			.pattern(pattern)
			.targetBranchName(targetBranchName)
			.defaultStatus(defaultStatus != null ? defaultStatus : StatusEnum.REQUIRED)
			.build();
		
		// Get components matching the pattern
		List<ComponentData> matchedComponents = dependencyPatternService.findComponentsByPattern(orgUuid, pattern);
		
		List<Map<String, Object>> result = new LinkedList<>();
		for (ComponentData comp : matchedComponents) {
			// Find target branch for this component
			UUID branchUuid = null;
			
			// By branch name
			if (tempPattern.getTargetBranchName() != null) {
				Optional<BranchData> branch = branchService.findBranchByComponentAndName(
					comp.getUuid(), tempPattern.getTargetBranchName());
				if (branch.isPresent()) {
					branchUuid = branch.get().getUuid();
				}
			}
			
			// Default: BASE branch
			if (branchUuid == null) {
				Optional<Branch> baseBranch = branchService.getBaseBranchOfComponent(comp.getUuid());
				if (baseBranch.isPresent()) {
					branchUuid = baseBranch.get().getUuid();
				}
			}
			
			if (branchUuid == null) {
				continue;
			}
			
			// Get branch data
			Optional<BranchData> branchData = branchService.getBranchData(branchUuid);
			if (branchData.isEmpty() || branchData.get().getStatus() == CommonVariables.StatusEnum.ARCHIVED) {
				continue;
			}
			
			Map<String, Object> dep = new java.util.HashMap<>();
			dep.put("component", comp);
			dep.put("branch", branchData.get());
			dep.put("status", tempPattern.getDefaultStatus());
			log.info("Preview pattern: {} - status: {}", tempPattern.getPattern(), tempPattern.getDefaultStatus());
			result.add(dep);
		}
		
		return result;
	}
	
}