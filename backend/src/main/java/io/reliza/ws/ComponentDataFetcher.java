/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.ServletWebRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.VersionResponse;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.BranchData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.RelizaObject;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.WhoUpdated;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.ApiKeyForUserDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.dto.UpdateComponentDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ComponentJsonDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.IntegrationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.ReleaseVersionService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.VersionAssignmentService;
import io.reliza.service.VersionAssignmentService.GetNewVersionDto;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class ComponentDataFetcher {
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	ComponentService componentService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	BranchService branchService;
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	ApiKeyService apiKeyService;
	
	@Autowired
	VersionAssignmentService versionAssignmentService;
	
	@Autowired
	ReleaseVersionService releaseVersionService;
	
	@Autowired
	IntegrationService integrationService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "component")
	public ComponentData getComponent(@InputArgument("componentUuid") String componentUuidStr) {
		UUID componentUuid = UUID.fromString(componentUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		UUID org = null;
		if (opd.isPresent()) org = opd.get().getOrg();
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), org, CallType.READ);
		return opd.get();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "components")
	public Collection<ComponentData> getComponentsOfType(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("componentType") ComponentType componentType) {
		UUID orgUuid = UUID.fromString(orgUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return componentService.listComponentDataByOrganization(orgUuid, componentType);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getComponentChangeLog")
	public ComponentJsonDto getComponentChangeLog(
			@InputArgument("componentUuid") String componentUuidStr,
			@InputArgument("branchUuid") String branchUuidStr,
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("aggregated") AggregationType aggregated,
			@InputArgument("timeZone") String timeZone
		) {
		UUID orgUuid = UUID.fromString(orgUuidStr);
		UUID branchUuid = UUID.fromString(branchUuidStr);
		UUID componentUuid = UUID.fromString(componentUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		
		ComponentJsonDto changelog = null;
		aggregated = aggregated == null ? AggregationType.NONE : aggregated;
		if(opd.get().getType().equals(ComponentType.COMPONENT))
			changelog = releaseService.getComponentChangeLog(branchUuid, orgUuid, aggregated, timeZone);
		else if(opd.get().getType().equals(ComponentType.PRODUCT))
			changelog = releaseService.getProductChangeLog(branchUuid, orgUuid, aggregated, timeZone);
		
		return changelog;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createComponent")
	public ComponentData createComponentManual(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> createComponentInputMap = dfe.getArgument("component");
		CreateComponentDto cpd = Utils.OM.convertValue(createComponentInputMap, CreateComponentDto.class);
		UUID orgUuid = cpd.getOrganization();
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		try {
			return ComponentData.dataFromRecord(componentService
									.createComponent(cpd, wu));
		} catch (RelizaException re) {
			log.error("Error on creating component", re.getMessage());
			throw new RuntimeException("Error on creating component");
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setComponentApiKey")
	public ApiKeyForUserDto setApiKey(@InputArgument("componentUuid") UUID componentUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
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
	
	@PreAuthorize("isAuthenticated()")
	@Transactional
	@DgsData(parentType = "Mutation", field = "updateComponent")
	public ComponentData updateComponent(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> componentUpdateData = dfe.getArgument("component");
		
		UpdateComponentDto ucdto = Utils.OM.convertValue(componentUpdateData, UpdateComponentDto.class);
		UUID componentUuid = ucdto.getUuid();
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		List<RelizaObject> orgCheckList = new LinkedList<>();
		orgCheckList.add(ro);
		if (null != ocd.get().getVcs()) orgCheckList.add(vcsRepositoryService
				.getVcsRepositoryData(ocd.get().getVcs()).get());
		if (null != ucdto.getOutputTriggers() && !ucdto.getOutputTriggers().isEmpty()) {
			for (var trigger : ucdto.getOutputTriggers()) {
				if (null != trigger.getIntegrationObject() && null != trigger.getIntegrationObject().getVcs()) {
					orgCheckList.add(vcsRepositoryService
							.getVcsRepositoryData(trigger.getIntegrationObject().getVcs()).get());
				}
				if (null != trigger.getUsers() && trigger.getUsers().isEmpty()) {
					authorizationService.doUsersBelongToOrg(trigger.getUsers(), ro.getOrg());
				}
				if (StringUtils.isNotEmpty(trigger.getNotificationMessage())) {
					trigger.setNotificationMessage(Jsoup.clean(trigger.getNotificationMessage(), Safelist.basic()));
				}
			}
		}
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObjects(oud.get(), orgCheckList, CallType.WRITE);
		
		try {
			List<ReleaseOutputEvent> processedOutputTriggers = new LinkedList<>();
			if (null != ucdto.getOutputTriggers() && !ucdto.getOutputTriggers().isEmpty()) {
				for (var trigger : ucdto.getOutputTriggers()) {
					UUID integration = trigger.getIntegration();
					if (trigger.getType() == EventType.INTEGRATION_TRIGGER && null == integration) {
						var createdIntegration = integrationService.createIntegration(ocd.get(),
								trigger.getIntegrationObject(), wu);
						integration = createdIntegration.getUuid();
					}
					var processedTrigger = UpdateComponentDto
							.convertReleaseOutputEventFromInput(trigger, integration);
					processedOutputTriggers.add(processedTrigger);
				}
			}
			ComponentDto cdto = UpdateComponentDto.convertToComponentDto(ucdto, processedOutputTriggers);
			return ComponentData.dataFromRecord(componentService.updateComponent(cdto, wu));
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		} catch (JsonMappingException e) {
			log.error("Error on update component df", e);
			throw new AccessDeniedException("Wrong input");
		} catch (JsonProcessingException e) {
			log.error("Error on update component df", e);
			throw new AccessDeniedException("Wrong input");
		}
	}
	
	@DgsData(parentType = "Mutation", field = "getNewVersionProgrammatic")
	public VersionResponse getNewVersionProgrammatic(DgsDataFetchingEnvironment dfe) throws IOException, RelizaException, Exception {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> getNewVersionInput = dfe.getArgument("newVersionInput");
		String componentIdStr = (String) getNewVersionInput.get(CommonVariables.COMPONENT_FIELD);
		
		UUID componentId = null;
		UUID orgId = null;
		if (ApiTypeEnum.COMPONENT == ahp.getType() || ApiTypeEnum.VERSION_GEN == ahp.getType()) {
			componentId = ahp.getObjUuid();
		} else if (ApiTypeEnum.ORGANIZATION_RW == ahp.getType()) {
			try {
				orgId = ahp.getObjUuid();
				componentId = UUID.fromString(componentIdStr);
			} catch (NullPointerException e) {
				throw new IllegalArgumentException("Must provide component UUID as input if using organization wide API access.");
			}
		}
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.VERSION_GEN, ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentId);
		RelizaObject ro = null;
		if (opd.isPresent()) {
			if (null == orgId) orgId = opd.get().getOrg();
			ro = opd.get();
		}
		log.debug("before get new version programmatic auth");
		AuthorizationResponse ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgId, CallType.WRITE, ro);

		log.debug("get new version programmatic ar = " + ar.getAuthorizationStatus());
		
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

		GetNewVersionDto getNewVersionDto = new GetNewVersionDto(componentId, branchStr, modifier, action, metadata, setVersionPin, lifecycleResolved, onlyVersionFlag, sourceCodeEntry, commits, VersionTypeEnum.DEV);
		
		return releaseVersionService.getNewVersionWrapper(getNewVersionDto, ar.getWhoUpdated());

	}
	
	@DgsData(parentType = "Mutation", field = "createComponentProgrammatic")
	public ComponentDto createComponentProgrammatic(DgsDataFetchingEnvironment dfe) {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");

		UUID orgUuid = ahp.getOrgUuid();
		if (null == orgUuid) throw new AccessDeniedException("Not authorized");
		Optional<OrganizationData> ood = organizationService.getOrganizationData(orgUuid);
		if (ood.isEmpty()) throw new AccessDeniedException("Not authorized");

		Map<String, Object> createComponentInputMap = dfe.getArgument("component");
		CreateComponentDto cpd = Utils.OM.convertValue(createComponentInputMap, CreateComponentDto.class);
		cpd.setOrganization(orgUuid);

		if (cpd.getType() != ComponentType.COMPONENT && cpd.getType() != ComponentType.PRODUCT) {
			throw new AccessDeniedException("Component type not allowed");
		}
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.ORGANIZATION_RW);
		AuthorizationResponse ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgUuid, CallType.WRITE, ro);

		Optional<VcsRepository> vcsRepo = Optional.empty();
		
		if (cpd.getType() == ComponentType.COMPONENT) {
			if (null != cpd.getVcs()) {
				vcsRepo = vcsRepositoryService.getVcsRepository(cpd.getVcs());
			} else if (null != cpd.getVcsRepository()) {
				String vcsUri = cpd.getVcsRepository().getUri();
				if (vcsRepo.isEmpty() && vcsUri != null) {
					vcsRepo = vcsRepositoryService.getVcsRepositoryByUri(orgUuid, vcsUri, null, false, ar.getWhoUpdated());
				}
			
				if (vcsRepo.isEmpty() && vcsUri != null) {
					String vcsName = null;
					VcsType vcsType = null;
					
					if (vcsUri.contains("bitbucket.org/")) {
						vcsName = vcsUri.split("/src/")[0].split("bitbucket.org/")[1];
						vcsType = VcsType.GIT;
					} else if (vcsUri.contains("github.com/")) {
						vcsName = vcsUri.split("/tree/")[0].split("github.com/")[1];
						vcsType = VcsType.GIT;
					} else if (vcsUri.contains("gitlab.com/")) {
						vcsName = vcsUri.split("/-/")[0].split("gitlab.com/")[1];
						vcsType = VcsType.GIT;
					}
				
					if (StringUtils.isNotEmpty(cpd.getVcsRepository().getName())) vcsName = cpd.getVcsRepository().getName();
					if (null != cpd.getVcsRepository().getType()) {
						// TODO: VcsType very case sensitive, first letter capital rest lowercase
						String vcsTypeStr = StringUtils.capitalize((cpd.getVcsRepository().getType().toString().toLowerCase()));
						vcsType = VcsType.resolveStringToType(vcsTypeStr);
					}
					if (vcsName != null && vcsType != null) {
						vcsRepo = Optional.of(vcsRepositoryService.createVcsRepository(vcsName, orgUuid, vcsUri, vcsType, ar.getWhoUpdated()));
					}
				}
			
				if (vcsRepo.isEmpty()) {
					throw new DgsEntityNotFoundException("Vcs repository not found");
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
	public Boolean archiveBranch(@InputArgument("branchUuid") UUID branchUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);		
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject ro = obd.isPresent() ? obd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
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
			@InputArgument("componentUuid") UUID componentUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);		
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
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
