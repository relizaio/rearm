/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.VersionResponse;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.BranchData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.EventScope;
import io.reliza.model.dto.ReleaseInputEventDto;
import io.reliza.model.dto.ReleaseOutputEventDto;
import io.reliza.model.RelizaObject;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyForUserDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ReleaseVersionService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.VersionAssignmentService.GetNewVersionDto;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.saas.ApprovalPolicyService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class ComponentDataFetcher {
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private ComponentService componentService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	private ApiKeyService apiKeyService;
	
	@Autowired
	private ReleaseVersionService releaseVersionService;
	
	@Autowired
	private ApprovalPolicyService approvalPolicyService;

	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private OssPerspectiveService ossPerspectiveService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private io.reliza.service.PullRequestService pullRequestService;

	@Autowired
	private io.reliza.service.oss.OssPullRequestAggregatorService pullRequestAggregatorService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "component")
	public ComponentData getComponent(@InputArgument("componentUuid") String componentUuidStr) throws RelizaException {
		UUID componentUuid = UUID.fromString(componentUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(ro), CallType.READ);
		return opd.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "components")
	public Collection<ComponentData> getComponentsOfType(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("componentType") ComponentType componentType,
			@InputArgument("perspective") UUID perspectiveUuid) throws RelizaException {
		UUID orgUuid = UUID.fromString(orgUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var odComps = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roOrg = odComps.isPresent() ? odComps.get() : null;

		if (null != perspectiveUuid) {
			var pd = ossPerspectiveService.getPerspectiveData(perspectiveUuid).get();
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.PERSPECTIVE, perspectiveUuid, List.of(roOrg, pd), CallType.READ);
			Collection<ComponentData> perspectiveComponents = componentService.listComponentDataByOrganizationAndPerspective(orgUuid, perspectiveUuid, componentType);
			componentService.populateEffectiveLifecycle(perspectiveComponents);
			return perspectiveComponents;
		}

		var combinedPermissions = organizationService.obtainCombinedUserOrgPermissions(oud.get(), orgUuid);
		var orgPerm = combinedPermissions.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid);
		boolean hasOrgReadOrHigher = orgPerm.isPresent()
				&& orgPerm.get().getType().ordinal() >= io.reliza.model.UserPermission.PermissionType.READ_ONLY.ordinal();

		Collection<ComponentData> resolvedComponents = componentService.listComponentDataByOrganization(orgUuid, componentType);

		if (!hasOrgReadOrHigher) {
			Set<UUID> allowedComponentUuids = combinedPermissions.getOrgPermissionsAsSet(orgUuid).stream()
					.filter(p -> p.getScope() == PermissionScope.COMPONENT
							&& p.getType().ordinal() >= io.reliza.model.UserPermission.PermissionType.READ_ONLY.ordinal())
					.map(p -> p.getObject())
					.collect(java.util.stream.Collectors.toSet());
			if (allowedComponentUuids.isEmpty()) return List.of();
			resolvedComponents = resolvedComponents.stream()
					.filter(c -> allowedComponentUuids.contains(c.getUuid()))
					.toList();
		}

		// Batched pre-compute of the synthetic Component.effectiveLifecycle sub-field —
		// one SQL roundtrip across all components instead of N per-component release fetches.
		// Field resolver below reads from the cache when present.
		componentService.populateEffectiveLifecycle(resolvedComponents);
		return resolvedComponents;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createComponent")
	public ComponentData createComponentManual(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> createComponentInputMap = dfe.getArgument("component");
		CreateComponentDto cpd = Utils.OM.convertValue(createComponentInputMap, CreateComponentDto.class);
		List<RelizaObject> ros = new LinkedList<>();
		if (null != cpd.getOrganization()) ros.add(getOrganizationService.getOrganizationData(cpd.getOrganization()).orElse(null));
		if (null != cpd.getVcs()) ros.add(vcsRepositoryService.getVcsRepositoryData(cpd.getVcs()).orElseThrow());
		if (null != cpd.getApprovalPolicy() && !cpd.getApprovalPolicy().toString().isEmpty()) {
			ros.add(approvalPolicyService.getApprovalPolicyData(cpd.getApprovalPolicy()).orElseThrow());
		}
		UUID createCompOrg = cpd.getOrganization();
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, createCompOrg, ros, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		return ComponentData.dataFromRecord(componentService
									.createComponent(cpd, wu));
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setComponentApiKey")
	public ApiKeyForUserDto setApiKey(@InputArgument("componentUuid") UUID componentUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(ro), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		String apiKey = apiKeyService.setObjectApiKey(opd.get().getUuid(), ApiTypeEnum.COMPONENT, null, null, null, wu);
		
		ApiKeyForUserDto retKey = ApiKeyForUserDto.builder()
				.apiKey(apiKey)
				.id(ApiTypeEnum.COMPONENT.toString() + "__" + opd.get().getUuid().toString())
				.authorizationHeader("Basic " + HttpHeaders.encodeBasicAuth(ApiTypeEnum.COMPONENT.toString() + "__" + 
				opd.get().getUuid().toString(), apiKey, StandardCharsets.UTF_8))
				.build();
		
		return retKey;
	}
	
	@DgsData(parentType = "Mutation", field = "getNewVersionProgrammatic")
	public VersionResponse getNewVersionProgrammatic(DgsDataFetchingEnvironment dfe) throws IOException, RelizaException, Exception {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID authOrgUuid = authCtx.orgUuid();
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");

		Map<String, Object> getNewVersionInput = dfe.getArgument("newVersionInput");

		// First, try to resolve component normally
		UUID componentId = null;
		try {
			componentId = componentService.resolveComponentIdFromInput(getNewVersionInput, authCtx);
		} catch (RelizaException e) {
			Boolean createComponentIfMissing = (Boolean) getNewVersionInput.get("createComponentIfMissing");
			if (Boolean.TRUE.equals(createComponentIfMissing)) {
				// Will create component after authorization is established
				log.info("Component not found, will create due to createComponentIfMissing flag");
			} else {
				throw new RelizaException("Component cannot be resolved: " + e.getMessage());
			}
		}

		// Optional perspective for component creation. Only meaningful when the component
		// does not yet exist and createComponentIfMissing=true; otherwise it's ignored.
		String perspectiveStr = (String) getNewVersionInput.get("perspective");
		UUID perspectiveUuid = StringUtils.isNotEmpty(perspectiveStr) ? UUID.fromString(perspectiveStr) : null;

		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> ocd = (componentId != null) ? getComponentService.getComponentData(componentId) : Optional.empty();
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (null != ro) {
			// Existing component. Accept the historic key types (COMPONENT, ORGANIZATION_RW),
			// or a FREEFORM key whose permissions cover this component.
			if (ahp.getType() == ApiTypeEnum.FREEFORM) {
				FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
						ahp, PermissionFunction.RESOURCE, PermissionScope.COMPONENT, ro.getUuid(),
						List.of(ro), CallType.WRITE);
				ar = AuthorizationResponse.initialize(InitType.ALLOW);
				ar.setWhoUpdated(fkv.whoUpdated());
			} else {
				ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);
			}
		} else if (perspectiveUuid != null) {
			// Component will be created and assigned to a perspective. Required scope on the
			// API key is WRITE on the perspective (FREEFORM keys), or ORGANIZATION_RW which
			// covers the perspective implicitly. Only real perspectives are accepted.
			if (authOrgUuid == null) throw new AccessDeniedException("Invalid authorization type");
			var pd = ossPerspectiveService.getPerspectiveData(perspectiveUuid)
					.orElseThrow(() -> new RelizaException("Perspective not found"));
			if (pd.getType() != PerspectiveType.PERSPECTIVE) {
				throw new RelizaException("Cannot create component in a product-derived perspective");
			}
			OrganizationData od = getOrganizationService.getOrganizationData(authOrgUuid).get();
			if (ahp.getType() == ApiTypeEnum.FREEFORM) {
				FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
						ahp, PermissionFunction.RESOURCE, PermissionScope.PERSPECTIVE, perspectiveUuid,
						List.of(od, pd), CallType.WRITE);
				ar = AuthorizationResponse.initialize(InitType.ALLOW);
				ar.setWhoUpdated(fkv.whoUpdated());
			} else {
				ar = authorizationService.isApiKeyAuthorized(ahp, List.of(ApiTypeEnum.ORGANIZATION_RW),
						authOrgUuid, CallType.WRITE, od);
			}
			ro = od;
		} else {
			// Component doesn't exist yet, no perspective requested - authorize org-wide for creation.
			// FREEFORM keys carry their org UUID in authCtx.orgUuid(); ahp.getOrgUuid() is
			// only populated for legacy ORGANIZATION_RW / COMPONENT keys, so use authOrgUuid
			// here to avoid NPE on the createcomponent path with FREEFORM keys.
			if (authOrgUuid == null) throw new AccessDeniedException("Invalid authorization type");
			ro = getOrganizationService.getOrganizationData(authOrgUuid).get();
			if (ahp.getType() == ApiTypeEnum.FREEFORM) {
				FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
						ahp, PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, authOrgUuid,
						List.of(ro), CallType.WRITE);
				ar = AuthorizationResponse.initialize(InitType.ALLOW);
				ar.setWhoUpdated(fkv.whoUpdated());
			} else {
				ar = authorizationService.isApiKeyAuthorized(ahp, List.of(ApiTypeEnum.ORGANIZATION_RW), authOrgUuid, CallType.WRITE, ro);
			}
		}

		// If component was not resolved, create it now (authorization was done earlier)
		if (componentId == null) {
			String vcsUri = (String) getNewVersionInput.get("vcsUri");
			String repoPath = (String) getNewVersionInput.get("repoPath");
			String vcsDisplayName = (String) getNewVersionInput.get("vcsDisplayName");
			String versionSchema = (String) getNewVersionInput.get("createComponentVersionSchema");
			String featureBranchVersionSchema = (String) getNewVersionInput.get("createComponentFeatureBranchVersionSchema");
			String componentNameOverride = (String) getNewVersionInput.get("createComponentName");
			// Extract vcsType from sourceCodeEntry if provided
			VcsType vcsType = null;
			@SuppressWarnings("unchecked")
			Map<String, Object> sceInput = (Map<String, Object>) getNewVersionInput.get("sourceCodeEntry");
			if (sceInput != null) {
				String vcsTypeStr = (String) sceInput.get("type");
				if (StringUtils.isNotEmpty(vcsTypeStr)) {
					vcsType = VcsType.resolveStringToType(vcsTypeStr);
				}
			}
			ComponentData newComponent = componentService.createComponentFromVcsUri(authOrgUuid, vcsUri, repoPath, vcsDisplayName, vcsType, versionSchema, featureBranchVersionSchema, componentNameOverride, ar.getWhoUpdated());
			componentId = newComponent.getUuid();
			if (perspectiveUuid != null) {
				componentService.setPerspectives(componentId, List.of(perspectiveUuid), ar.getWhoUpdated());
				ocd = getComponentService.getComponentData(componentId);
			} else {
				ocd = Optional.of(newComponent);
			}
		}
		
		String branchStr = (String) getNewVersionInput.get(CommonVariables.BRANCH_FIELD);
		String modifier = (String) getNewVersionInput.get(CommonVariables.MODIFIER_FIELD);
		String metadata = (String) getNewVersionInput.get(CommonVariables.METADATA_FIELD);
		String action = (String) getNewVersionInput.get(CommonVariables.ACTION_FIELD);
		String setVersionPin = (String) getNewVersionInput.get(CommonVariables.VERSION_SCHEMA_FIELD);
		boolean onlyVersionFlag = getNewVersionInput.containsKey(CommonVariables.ONLY_VERSION_FLAG) ? (boolean) getNewVersionInput.get(CommonVariables.ONLY_VERSION_FLAG) : false;
		String status = (String) getNewVersionInput.get(CommonVariables.LIFECYCLE_FIELD);
		
		ReleaseLifecycle lifecycleResolved = StringUtils.isNotEmpty(status) ? ReleaseLifecycle.valueOf(status) : null;
		if(lifecycleResolved != ReleaseLifecycle.DRAFT)
			lifecycleResolved = ReleaseLifecycle.PENDING;
		
		
		SceDto sourceCodeEntry = null;
		if (getNewVersionInput.containsKey(CommonVariables.SOURCE_CODE_ENTRY_FIELD)) {
			sourceCodeEntry = Utils.OM.convertValue((Map<String, Object>) 
					getNewVersionInput.get(CommonVariables.SOURCE_CODE_ENTRY_FIELD), SceDto.class);
		}
		
		List<SceDto> commits = null;
		if (getNewVersionInput.containsKey(CommonVariables.COMMITS_FIELD)) {
			var commitsPrep = ((List<Map<String, Object>>) getNewVersionInput.get(CommonVariables.COMMITS_FIELD)).stream().map(x ->
					Utils.OM.convertValue(x, SceDto.class)).toList();
			commits = new LinkedList<>(commitsPrep);
		}

		Boolean rebuildFlag = (Boolean) getNewVersionInput.get("rebuild");

		GetNewVersionDto getNewVersionDto = new GetNewVersionDto(componentId, branchStr, modifier, action, metadata,
				setVersionPin, lifecycleResolved, onlyVersionFlag, sourceCodeEntry, commits, VersionTypeEnum.DEV,
				rebuildFlag);

		VersionResponse vr = releaseVersionService.getNewVersionWrapper(getNewVersionDto, ar.getWhoUpdated());

		// PR upsert at getNewVersion: gated on (PR input present AND
		// SCE-or-commits input present AND onlyVersion=false). When all
		// three hold, getNewVersionWrapper has just persisted an SCE and
		// created/matched a release that owns it — we resolve the
		// release by (component, version) and advance the PR head to its
		// SCE. Without those preconditions the upsert is silently skipped
		// (defer to the addrelease path).
		@SuppressWarnings("unchecked")
		Map<String, Object> prInput = (Map<String, Object>) getNewVersionInput.get("pullRequest");
		boolean hasSceInput = sourceCodeEntry != null || (commits != null && !commits.isEmpty());
		if (prInput != null && hasSceInput && !onlyVersionFlag) {
			Optional<io.reliza.model.Release> oRel =
					sharedReleaseService.findReleaseByComponentAndVersion(componentId, vr.version());
			UUID headSce = oRel
					.map(io.reliza.model.ReleaseData::dataFromRecord)
					.map(io.reliza.model.ReleaseData::getSourceCodeEntry)
					.orElse(null);
			UUID componentVcsFallback = ocd.map(ComponentData::getVcs).orElse(null);
			WhoUpdated wuPr = ar.getWhoUpdated();
			Optional<io.reliza.model.PullRequestData> oPrd = pullRequestService.applyFromInput(prInput,
					ocd.get().getOrg(), headSce, componentVcsFallback, wuPr);
			// Recompute aggregation now that the head has been advanced —
			// saveRelease's hook ran before commits[] was updated.
			oPrd.ifPresent(prd -> pullRequestAggregatorService.recomputeForPr(
					prd.getUuid(), wuPr));
		}

		return vr;
	}
	
	@DgsData(parentType = "Mutation", field = "createComponentProgrammatic")
	public ComponentDto createComponentProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		// FREEFORM keys carry their org UUID on authCtx; ahp.getOrgUuid() is only
		// populated for legacy ORGANIZATION_RW / COMPONENT keys, so source from
		// authCtx so both key types work.
		UUID orgUuid = authCtx.orgUuid();
		if (null == ahp || null == orgUuid) throw new AccessDeniedException("Invalid authorization type");

		Map<String, Object> createComponentInputMap = dfe.getArgument("component");
		CreateComponentDto cpd = Utils.OM.convertValue(createComponentInputMap, CreateComponentDto.class);
		cpd.setOrganization(orgUuid);

		if (cpd.getType() != ComponentType.COMPONENT && cpd.getType() != ComponentType.PRODUCT) {
			throw new RelizaException("Component type not allowed, must be COMPONENT or PRODUCT");
		}

		List<RelizaObject> ros = new LinkedList<>();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		ros.add(ood.orElse(null));
		if (null != cpd.getApprovalPolicy() && !cpd.getApprovalPolicy().toString().isEmpty()) {
			ros.add(approvalPolicyService.getApprovalPolicyData(cpd.getApprovalPolicy()).orElseThrow());
		}
		if (null != cpd.getVcs()) {
			ros.add(vcsRepositoryService.getVcsRepositoryData(cpd.getVcs()).orElseThrow());
		}
		UUID orgCheckUuid = authorizationService.getMatchingOrg(ros);
		if (null == orgCheckUuid) throw new AccessDeniedException("Not authorized");

		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		AuthorizationResponse ar;
		if (ahp.getType() == ApiTypeEnum.FREEFORM) {
			FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
					ahp, PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid,
					ros, CallType.WRITE);
			ar = AuthorizationResponse.initialize(InitType.ALLOW);
			ar.setWhoUpdated(fkv.whoUpdated());
		} else {
			List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.ORGANIZATION_RW);
			ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgUuid, CallType.WRITE, ro);
		}

		Optional<VcsRepository> vcsRepo = Optional.empty();
		
		if (cpd.getType() == ComponentType.COMPONENT) {
			if (null != cpd.getVcs()) {
				vcsRepo = vcsRepositoryService.getVcsRepository(cpd.getVcs());
			} else if (null != cpd.getVcsRepository()) {
				String vcsUri = cpd.getVcsRepository().getUri();
				if (vcsRepo.isEmpty() && vcsUri != null) {
					vcsRepo = vcsRepositoryService.getVcsRepositoryByUri(orgUuid, vcsUri, null, null, false, ar.getWhoUpdated());
				}
			
				if (vcsRepo.isEmpty() && vcsUri != null) {
					// Derive name from URI, but allow override from input
					String vcsName = Utils.deriveVcsNameFromUri(vcsUri);
					if (StringUtils.isNotEmpty(cpd.getVcsRepository().getName())) {
						vcsName = cpd.getVcsRepository().getName();
					}
					
					// Determine VCS type - default to GIT for known providers
					VcsType vcsType = VcsType.GIT;
					if (null != cpd.getVcsRepository().getType()) {
						String vcsTypeStr = StringUtils.capitalize((cpd.getVcsRepository().getType().toString().toLowerCase()));
						vcsType = VcsType.resolveStringToType(vcsTypeStr);
					}
					
					if (vcsName != null) {
						vcsRepo = Optional.of(vcsRepositoryService.createVcsRepository(vcsName, orgUuid, vcsUri, vcsType, ar.getWhoUpdated()));
					}
				}
			
				if (vcsRepo.isEmpty()) {
					throw new RelizaException("Vcs repository not found");
				}
				cpd.setVcs(vcsRepo.get().getUuid());
			}
		}

		try {
			var componentData = ComponentData.dataFromRecord(componentService
					.createComponent(cpd, ar.getWhoUpdated()));
			ComponentDto componentDto = Utils.OM.convertValue(componentData, ComponentDto.class);
			if (null != cpd.getIncludeApi() && cpd.getIncludeApi()) {
				String apiKeyId = ApiTypeEnum.COMPONENT.toString() + "__" + componentData.getUuid().toString();
				String apiKey = apiKeyService.setObjectApiKey(componentData.getUuid(), ApiTypeEnum.COMPONENT, null, null, null, ar.getWhoUpdated());
				componentDto.setApiKeyId(apiKeyId);
				componentDto.setApiKey(apiKey);
			}
		
			return componentDto;
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@Transactional
	@DgsData(parentType = "Mutation", field = "archiveBranch")
	public Boolean archiveBranch(@InputArgument("branchUuid") UUID branchUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);		
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.BRANCH, branchUuid, List.of(ro), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		Boolean archived = false;
		try {
			archived = branchService.archiveBranch(branchUuid, wu);
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
		return archived;
	}
	
	@PreAuthorize("isAuthenticated()")
	@Transactional
	@DgsData(parentType = "Mutation", field = "archiveComponent")
	public Boolean archiveComponent(DgsDataFetchingEnvironment dfe,
			@InputArgument("componentUuid") UUID componentUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);		
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(ro), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		Boolean archived = false;
		try {
			archived = componentService.archiveComponent(componentUuid, wu);
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
		return archived;
	}

/* Sub-fields */
	
	@DgsData(parentType = "Component", field = "effectiveLifecycle")
	public ReleaseLifecycle effectiveLifecycle(DgsDataFetchingEnvironment dfe) {
		// Synthetic — derived from the component's releases at read time.
		// Rules (per user spec):
		//   if every release lc is ≤ GA → return max(lc) over those
		//                                  (most-advanced pre/at-GA state)
		//   else                          → return min(lc) over releases
		//                                  whose lc is ≥ GA
		//                                  (sub-GA releases ignored once
		//                                   any release has reached GA)
		ComponentData cd = dfe.getSource();
		if (cd == null) return null;
		// Fast path: the components-list datafetcher pre-computes effectiveLifecycle for
		// every ComponentData in its result via a single batched SQL query
		// (ComponentService.populateEffectiveLifecycle). When that ran, the cache flag is
		// set and we just return the cached value — null included, because null is a valid
		// answer for "no releases yet".
		if (cd.isEffectiveLifecycleCached()) return cd.getCachedEffectiveLifecycle();
		// Fallback: single-component fetches (Query.component / Query.componentDetails)
		// don't go through the list datafetcher. Reuse the same batched helper with a
		// 1-element set rather than re-implementing the bucket logic.
		return componentService.effectiveLifecyclesForComponents(java.util.Set.of(cd.getUuid()))
				.get(cd.getUuid());
	}

	@DgsData(parentType = "Component", field = "releaseInputTriggers")
	public List<ReleaseInputEventDto> releaseInputTriggersWithScope(DgsDataFetchingEnvironment dfe) {
		ComponentData cd = dfe.getSource();
		if (null == cd.getReleaseInputTriggers()) return null;
		return cd.getReleaseInputTriggers().stream()
				.map(e -> ReleaseInputEventDto.fromData(e, EventScope.LOCAL))
				.toList();
	}

	@DgsData(parentType = "Component", field = "outputTriggers")
	public List<ReleaseOutputEventDto> outputTriggersWithScope(DgsDataFetchingEnvironment dfe) {
		ComponentData cd = dfe.getSource();
		if (null == cd.getOutputTriggers()) return null;
		return cd.getOutputTriggers().stream()
				.map(e -> ReleaseOutputEventDto.fromData(e, EventScope.LOCAL))
				.toList();
	}

	@DgsData(parentType = "Component", field = "vcsRepositoryDetails")
	public VcsRepositoryData vcsRepoOfProject (DgsDataFetchingEnvironment dfe) {
		VcsRepositoryData vrd = null;
		UUID vcsRepoUuid = null;
		UUID componentOrg = null;
		UUID componentUuid = null;
		if (dfe.getSource() instanceof ComponentData) {
			ComponentData cd = dfe.getSource();
			vcsRepoUuid = cd.getVcs();
			componentOrg = cd.getOrg();
			componentUuid = cd.getUuid();
		} else if (dfe.getSource() instanceof ComponentDto) {
			ComponentDto cd = dfe.getSource();
			vcsRepoUuid = cd.getVcs();
			componentUuid = cd.getUuid();
			ComponentData cData = getComponentService.getComponentData(componentUuid).get();
			componentOrg = cData.getOrg();
		}
		if (null != vcsRepoUuid) {
			var vrdo = vcsRepositoryService.getVcsRepositoryData(vcsRepoUuid);
			if (vrdo.isPresent()) {
				if (!componentOrg.equals(vrdo.get().getOrg())) {
					log.error("SECURITY: Mismatch org for vcs for component = " + componentUuid);
					throw new AccessDeniedException("Error loading component, please contact support");
				}
				vrd = vrdo.get();
			}
			
		}
		return vrd;
	}
}
