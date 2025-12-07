/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsDataLoader;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import org.dataloader.BatchLoader;
import org.dataloader.DataLoader;
import org.dataloader.MappedBatchLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthPrincipalType;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.CommonVariables.RequestType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.AcollectionData;
import io.reliza.model.ArtifactData;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.InputConditionGroup;
import io.reliza.model.Deliverable;
import io.reliza.model.DeliverableData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseDateComparator;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RelizaObject;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.AddDeliverablesDto;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.ComponentJsonDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto.FindingSourceDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.service.AcollectionService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.IntegrationService;
import io.reliza.service.IntegrationService.ComponentPurlToDtrackProject;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VariantService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseFinalizerService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class ReleaseDatafetcher {
	
	@Autowired
	private ReleaseService releaseService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private OssReleaseService ossReleaseService;
	
	@Autowired
	private BranchService branchService;

	@Autowired
	private GetOrganizationService getOrganizationService;
		
	@Autowired
	private SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;
	
	@Autowired
	private DeliverableService deliverableService;
	
	@Autowired
	private GetDeliverableService getDeliverableService;
	
	@Autowired
	private ArtifactService artifactService;
	
	@Autowired
	private SharedArtifactService sharedArtifactService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private ComponentService componentService;
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private VariantService variantService;
	
	@Autowired
	private AcollectionService acollectionService;
	
	@Autowired
	private IntegrationService integrationService;

	@Autowired
	private ReleaseFinalizerService releaseFinalizerService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "release")
	public ReleaseData getRelease(
			@InputArgument("releaseUuid") String releaseUuidStr,
			@InputArgument("orgUuid") String orgUuidStr
			) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		boolean useOrg = false;
		UUID org = null;
		
		RelizaObject ro = null;
		if (StringUtils.isNoneEmpty(releaseUuidStr)) {
			var rlzOpt = sharedReleaseService.getReleaseData(UUID.fromString(releaseUuidStr));
			if (rlzOpt.isPresent()) {
				ro = rlzOpt.get();
				org = rlzOpt.get().getOrg();
			}
		}
		
		if (StringUtils.isNotEmpty(orgUuidStr)) {
			org = UUID.fromString(orgUuidStr);
			useOrg = true;
		}
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		ReleaseData rd = null;
		if (useOrg) {
			try {
				rd = releaseService.getReleaseData(UUID.fromString(releaseUuidStr), org).get();
			} catch (RelizaException e) {
				log.error("Reliza Exception on getting release data with org", e);
				throw new RuntimeException(e);
			}
		} else {
			rd = (ReleaseData) ro; 
		}
		return rd;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "releaseSbomExport")
	public String releaseSbomExport(
			@InputArgument("release") String releaseUuidStr,
			@InputArgument("tldOnly") Boolean tldOnly,
			@InputArgument("ignoreDev") Boolean ignoreDev,
			@InputArgument("structure") BomStructureType structure,
			@InputArgument("belongsTo") ArtifactBelongsTo belongsTo,
			@InputArgument("mediaType") BomMediaType mediaType
			) throws RelizaException, JsonProcessingException{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		RelizaObject ro = null;
		if (StringUtils.isNoneEmpty(releaseUuidStr)) {
			var rlzOpt = sharedReleaseService.getReleaseData(UUID.fromString(releaseUuidStr));
			if (rlzOpt.isPresent()) {
				ro = rlzOpt.get();
			}
		}
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		ReleaseData rd = (ReleaseData) ro;
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		if (null == mediaType) {
			mediaType = BomMediaType.JSON;
		}
		log.info("mediaType: {}", mediaType);
		return releaseService.exportReleaseSbom(rd.getUuid(), tldOnly, ignoreDev, belongsTo, structure, mediaType, rd.getOrg(), wu);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseTagKeys")
	public Set<String> getReleaseTagKeys(@InputArgument("orgUuid") String orgUuidStr) throws RelizaException {
		UUID orgUuid = UUID.fromString(orgUuidStr);
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return releaseService.findDistinctReleaseTagKeysOfOrg(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releases")
	public List<ReleaseData> getReleases(
			@InputArgument("branchFilter") String branchFilterStr,
			@InputArgument("orgFilter") String orgFilterStr,
			@InputArgument("releaseFilter") List<String> releaseFilterStr,
			@InputArgument("numRecords") Integer numRecords, 
			@InputArgument("pullRequestFilter") Integer pullRequest)
	{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		UUID branchFilter = null;
		UUID orgFilter = null;
		
		if (StringUtils.isNotEmpty(branchFilterStr)) branchFilter = UUID.fromString(branchFilterStr);
		if (StringUtils.isNotEmpty(orgFilterStr)) orgFilter = UUID.fromString(orgFilterStr);
		Set<UUID> releaseFilter = null;
		if (null != releaseFilterStr && !releaseFilterStr.isEmpty()) {
			releaseFilter = releaseFilterStr.stream().map(r -> UUID.fromString(r)).collect(Collectors.toSet());
		}
		
		RelizaObject ro = null;
		if (null != branchFilter) {
			var obd = branchService.getBranchData(branchFilter);
			ro = obd.isPresent() ? obd.get() : null;
		} else if (null != orgFilter && ro == null) {
			var od = getOrganizationService.getOrganizationData(orgFilter);
			ro = od.isPresent() ? od.get() : null;
		}
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		
		List<ReleaseData> retRel = new LinkedList<>();
		if (null != branchFilter) {
			log.debug("num of release records in get releases dto = " + numRecords);
			
			retRel = sharedReleaseService.listReleaseDataOfBranch(branchFilter, pullRequest, numRecords, true);
			// TODO: combination of branchfilter and releasefilter
		} else if (null != orgFilter) {
			if (null != releaseFilter) {
				retRel = sharedReleaseService.getReleaseDataList(releaseFilter, orgFilter); 
			} else {
				retRel = releaseService.listReleaseDataOfOrg(orgFilter, false);
			}
		}
		return retRel;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getChangelogBetweenReleases")
	public ComponentJsonDto getChangelogBetweenReleases(DgsDataFetchingEnvironment dfe,
			@InputArgument("release1") UUID uuid1,
			@InputArgument("release2") UUID uuid2,
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("aggregated") AggregationType aggregated,
			@InputArgument("timeZone") String timeZone
		) throws RelizaException {

		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		
		return releaseService.getChangelogBetweenReleases(uuid1, uuid2, orgUuid, aggregated, timeZone);
	}
	

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addReleaseManual")
	public ReleaseData addRelease(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> manualReleaseInput = dfe.getArgument("release");
		ReleaseDto releaseDto = Utils.OM.convertValue(manualReleaseInput, ReleaseDto.class);
		UUID branchUuid = releaseDto.getBranch();
		Optional<BranchData> obd = branchService.getBranchData(branchUuid);
		RelizaObject robranch = obd.isPresent() ? obd.get() : null;
		UUID orgUuid = releaseDto.getOrg();
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roorg = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObjects(oud.get(), List.of(robranch, roorg), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			return ReleaseData.dataFromRecord(ossReleaseService.createRelease(releaseDto, wu));
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
	}
	

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateReleaseLifecycle")
	public ReleaseData updateReleaseLifecycle(@InputArgument("release") UUID releaseId,
			@InputArgument("newLifecycle") ReleaseLifecycle newLifecycle) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseId);
		if (ord.isEmpty()) throw new RuntimeException("Wrong release");
		CallType ct = CallType.ADMIN;
		ReleaseLifecycle oldLifecycle = ord.get().getLifecycle();
		if (newLifecycle.ordinal() - oldLifecycle.ordinal() == 1) ct = CallType.WRITE;
		RelizaObject ro = ord.get();
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, ct);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		var r = ossReleaseService.updateReleaseLifecycle(releaseId, newLifecycle, wu);
		return ReleaseData.dataFromRecord(r);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateRelease")
	public ReleaseData updateRelease(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> updateReleaseInput = dfe.getArgument("release");
		ReleaseDto releaseDto = Utils.OM.convertValue(updateReleaseInput, ReleaseDto.class);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseDto.getUuid());
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			ossReleaseService.updateRelease(releaseDto, wu);
		} catch (RelizaException e) {
			log.error("Exception on updateRelease", e);
			throw new RuntimeException(e.getMessage());
		}
		return sharedReleaseService.getReleaseData(releaseDto.getUuid()).get();
	}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateReleaseTagsMeta")
	public ReleaseData updateReleaseTagsMeta(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> updateReleaseInput = dfe.getArgument("release");
		ReleaseDto releaseDto = Utils.OM.convertValue(updateReleaseInput, ReleaseDto.class);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseDto.getUuid());
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			ossReleaseService.updateReleaseTagsMeta(releaseDto, wu);
		} catch (RelizaException e) {
			log.error("Exception on updateReleaseTagsMeta", e);
			throw new RuntimeException(e.getMessage());
		}
		return sharedReleaseService.getReleaseData(releaseDto.getUuid()).get();
	}
	
	@DgsData(parentType = "Query", field = "getReleaseByHashProgrammatic")
	public Optional<ReleaseData> getReleaseByHash(DgsDataFetchingEnvironment dfe,
			@InputArgument("hash") String hash, @InputArgument("componentId") String componentIdStr) {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT,
				ApiTypeEnum.ORGANIZATION, ApiTypeEnum.USER, ApiTypeEnum.ORGANIZATION_RW);

		UUID componentId = null;
		UUID orgId = null;
		if (ApiTypeEnum.COMPONENT == ahp.getType()) {
			componentId = ahp.getObjUuid();
		} else {
			try {
				orgId = ahp.getObjUuid();
				componentId = UUID.fromString(componentIdStr);
			} catch (NullPointerException e) {
				throw new IllegalArgumentException("Must provide component UUID as input if using organization wide API access.");
			}
		}
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
		
		RelizaObject ro = ocd.isPresent() ? ocd.get(): null;
		
		if (ApiTypeEnum.COMPONENT == ahp.getType() && ocd.isPresent()) orgId = ocd.get().getOrg(); 
		authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgId, CallType.READ, ro);
		
		Optional<Deliverable> od = getDeliverableService.getDeliverableByDigestAndComponent(hash, componentId);
		// locate lowest level release referencing this artifact
		// we pass component from artifact since we already scoped artifact search to only this component in getArtifactByDigestAndComponent
		Optional<ReleaseData> ord = Optional.empty();
		if (od.isPresent()) ord = sharedReleaseService.getReleaseByOutboundDeliverable(od.get().getUuid(), orgId);
		return ord;
	}
	
	public static record GetLatestReleaseInput (UUID component, UUID product, String branch,
			TagRecord tags, ReleaseLifecycle lifecycle, InputConditionGroup conditions,
			String vcsUri, String repoPath) {}

	private BranchData resolveAddReleaseProgrammaticBranchData (final UUID componentId, final String suppliedBranchStr, WhoUpdated wu) {
		UUID branchUuid = null;
		
		Optional<Branch> ob = Optional.empty();
		Optional<BranchData> obd = Optional.empty();
		if (StringUtils.isNotEmpty(suppliedBranchStr)) {
			String branchStr = Utils.cleanBranch(suppliedBranchStr);
			try {
				branchUuid = UUID.fromString(branchStr);
				ob = branchService.getBranch(branchUuid);
				obd = Optional.of(BranchData.branchDataFromDbRecord(ob.get()));
			} catch (IllegalArgumentException e) {
				try {
					ob = branchService.findBranchByName(componentId, branchStr, true, wu);
				} catch (RelizaException re) {
					throw new AccessDeniedException(re.getMessage());
				}
				branchUuid = ob.get().getUuid();
				obd = Optional.of(BranchData.branchDataFromDbRecord(ob.get()));
			}
		}
		
		if (ob.isEmpty() || !obd.get().getComponent().equals(componentId)) {
			throw new AccessDeniedException("submitted branch or feature set is invalid");
		}
		
		return BranchData.branchDataFromDbRecord(ob.get()); 
	}
	

	@DgsData(parentType = "Mutation", field = "addReleaseProgrammatic")
	@Transactional
	public ReleaseData addReleaseProgrammatic(DgsDataFetchingEnvironment dfe) throws IOException, RelizaException, Exception {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> progReleaseInput = dfe.getArgument("release");
		
		// First, try to resolve component normally
		UUID componentId = null;
		try {
			componentId = componentService.resolveComponentIdFromInput(progReleaseInput, ahp);
		} catch (RelizaException e) {
			Boolean createComponentIfMissing = (Boolean) progReleaseInput.get("createComponentIfMissing");
			if (Boolean.TRUE.equals(createComponentIfMissing)) {
				// Will create component after authorization is established
				log.info("Component not found, will create due to createComponentIfMissing flag");
			} else {
				throw new AccessDeniedException(e.getMessage());
			}
		}
		
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> ocd = (componentId != null) ? getComponentService.getComponentData(componentId) : Optional.empty();
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (null != ro) {
			ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);
		} else {
			// Component doesn't exist yet - authorize against org for component creation
			ro = getOrganizationService.getOrganizationData(ahp.getOrgUuid()).get();
			ar = authorizationService.isApiKeyAuthorized(ahp, List.of(ApiTypeEnum.ORGANIZATION_RW), ahp.getOrgUuid(), CallType.WRITE, ro);
		}
		
		// If component was not resolved, create it now (authorization was done earlier)
		if (componentId == null) {
			String vcsUri = (String) progReleaseInput.get("vcsUri");
			String repoPath = (String) progReleaseInput.get("repoPath");
			String versionSchema = (String) progReleaseInput.get("createComponentVersionSchema");
			String featureBranchVersionSchema = (String) progReleaseInput.get("createComponentFeatureBranchVersionSchema");
			// Extract vcsType from sourceCodeEntry if provided
			VcsType vcsType = null;
			@SuppressWarnings("unchecked")
			Map<String, Object> sceInput = (Map<String, Object>) progReleaseInput.get("sourceCodeEntry");
			if (sceInput != null) {
				String vcsTypeStr = (String) sceInput.get("type");
				if (StringUtils.isNotEmpty(vcsTypeStr)) {
					vcsType = VcsType.resolveStringToType(vcsTypeStr);
				}
			}
			ComponentData newComponent = componentService.createComponentFromVcsUri(ahp.getOrgUuid(), vcsUri, repoPath, vcsType, versionSchema, featureBranchVersionSchema, ar.getWhoUpdated());
			componentId = newComponent.getUuid();
			ocd = Optional.of(newComponent);
		}
		
		BranchData bd = resolveAddReleaseProgrammaticBranchData(componentId, (String) progReleaseInput.get(CommonVariables.BRANCH_FIELD),
				ar.getWhoUpdated());
		
		@SuppressWarnings("unchecked")
		var inboundDeliverablesList = (List<Map<String,Object>>) progReleaseInput.get("inboundDeliverables");
		Utils.addReleaseProgrammaticValidateDeliverables(inboundDeliverablesList, bd);
		
		@SuppressWarnings("unchecked")
		var outboundDeliverablesList = (List<Map<String,Object>>) progReleaseInput.get("outboundDeliverables");
		Utils.addReleaseProgrammaticValidateDeliverables(outboundDeliverablesList, bd);
		
		OrganizationData od = getOrganizationService.getOrganizationData(ocd.get().getOrg()).get();
		
		URI endpoint = null;
		String endpointStr = (String) progReleaseInput.get(CommonVariables.ENDPOINT_FIELD);
		if (StringUtils.isNotEmpty(endpointStr)) {
			endpoint = URI.create(endpointStr);
		}
		
		Optional<SourceCodeEntryData> osced = Optional.empty();
		String version = (String) progReleaseInput.get(CommonVariables.VERSION_FIELD);
		List<UUID> commits = new LinkedList<>();
		
		var releaseDtoBuilder = ReleaseDto.builder()
										 .branch(bd.getUuid())
										 .org(ocd.get().getOrg());

		List<UUID> inboundDeliverables = new LinkedList<>();
		if (null != inboundDeliverablesList && !inboundDeliverablesList.isEmpty()) {
			inboundDeliverables = deliverableService.prepareListofDeliverables(inboundDeliverablesList,
					bd.getUuid(), version, ar.getWhoUpdated());
		}
		
		List<UUID> artifacts = new LinkedList<>();
		if (progReleaseInput.containsKey("artifacts")) {
			@SuppressWarnings("unchecked")
			List<Map<String,Object>> artifactsList = (List<Map<String,Object>>) progReleaseInput.get("artifacts");
			// TODO allow propagation of purl from release purl
			artifacts = artifactService.uploadListOfArtifacts(od, artifactsList, new RebomOptions(ocd.get().getName(), od.getName(), version, ArtifactBelongsTo.RELEASE, null, StripBom.FALSE, null), ar.getWhoUpdated());
		}
		
		if (progReleaseInput.containsKey(CommonVariables.SOURCE_CODE_ENTRY_FIELD) || progReleaseInput.containsKey(CommonVariables.COMMITS_FIELD)) {
			ComponentData cd = getComponentService.getComponentData(bd.getComponent()).orElseThrow();
			@SuppressWarnings("unchecked")
			Map<String, Object> sceMap = progReleaseInput.containsKey(CommonVariables.SOURCE_CODE_ENTRY_FIELD) ?
					(Map<String, Object>) progReleaseInput.get(CommonVariables.SOURCE_CODE_ENTRY_FIELD) : new HashMap<>();
			
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> commitList = progReleaseInput.containsKey(CommonVariables.COMMITS_FIELD) ?
					(List<Map<String, Object>>) progReleaseInput.get(CommonVariables.COMMITS_FIELD) : null;
			
			// parse list of associated commits obtained via git log with previous CI build if any (note this may include osce)
			if (commitList != null) {
				for (var com : commitList) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> arts = (List<Map<String, Object>>) com.get("artifacts");
					com.remove("artifacts");
					SceDto sceDto = Utils.OM.convertValue(com, SceDto.class);
					List<UUID> sceUploadedArts = releaseService.uploadSceArtifacts(arts, od, sceDto, cd, version, ar.getWhoUpdated());
					var parsedCommit = releaseService.parseSceFromReleaseCreate(sceDto, sceUploadedArts, 
							bd, bd.getName(), version, ar.getWhoUpdated());
					if (parsedCommit.isPresent()) {
						commits.add(parsedCommit.get().getUuid());
					}
				}
				
				for (int i = 0; i < commitList.size(); i++) {
					var com = commitList.get(i);
					log.debug("Processing commitList element [{}]: {}", i, com);
				}
				log.debug("Current sceMap contents: {}", sceMap);

				// use the first commit of commitlist to fill in the missing fields of source code entry
				if (!commitList.isEmpty() && (
						sceMap.isEmpty() || ((String) sceMap.get(CommonVariables.COMMIT_FIELD))
												.equalsIgnoreCase((String) (commitList.get(0).get(CommonVariables.COMMIT_FIELD))))) {
					commitList.get(0).forEach((key, value) -> sceMap.merge( key, value, (v1, v2) -> v1));
				}
			}
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> arts = (List<Map<String, Object>>) sceMap.get("artifacts");
			sceMap.remove("artifacts");
			SceDto sceDto = Utils.OM.convertValue(sceMap, SceDto.class);
			List<UUID> sceUploadedArts = releaseService.uploadSceArtifacts(arts, od, sceDto, cd, version, ar.getWhoUpdated());
			osced = releaseService.parseSceFromReleaseCreate(sceDto, sceUploadedArts, bd, bd.getName(), version, ar.getWhoUpdated());
		}

		releaseDtoBuilder.commits(!commits.isEmpty() ? commits : null);
		if (osced.isPresent()) {
			releaseDtoBuilder.sourceCodeEntry(osced.get().getUuid());
		}
		
		ReleaseLifecycle lifecycle = ReleaseLifecycle.ASSEMBLED;
		if (progReleaseInput.containsKey(CommonVariables.LIFECYCLE_FIELD)) {
			ReleaseLifecycle suppliedLifecycle = ReleaseLifecycle.valueOf((String) progReleaseInput.get(CommonVariables.LIFECYCLE_FIELD));
			if (null != suppliedLifecycle) {
				lifecycle = suppliedLifecycle;
			}
		}
		
		// Check if rebuildRelease flag is set
		Boolean rebuildRelease = (Boolean) progReleaseInput.get("rebuildRelease");
		boolean shouldRebuild = Boolean.TRUE.equals(rebuildRelease);
		
		try {
			releaseDtoBuilder.version(version)
							.inboundDeliverables(inboundDeliverables)
							.artifacts(artifacts)
							.lifecycle(lifecycle)
							.endpoint(endpoint);
			var rd = ReleaseData.dataFromRecord(ossReleaseService.createRelease(releaseDtoBuilder.build(),
					ar.getWhoUpdated(), shouldRebuild));
			log.debug("release created: {}", rd);
			if (null != outboundDeliverablesList && !outboundDeliverablesList.isEmpty()) {
				List<UUID> outboundDeliverables = deliverableService
						.prepareListofDeliverables(outboundDeliverablesList, bd.getUuid(), version, ar.getWhoUpdated());
				VariantData vd = variantService.getBaseVariantForRelease(rd);
				variantService.addOutboundDeliverables(outboundDeliverables, vd.getUuid(), ar.getWhoUpdated());
			}
			return rd;
		} catch (RelizaException re) {
			log.warn("addReleaseProgrammatic failed for component={}, branch={}, version={}: {}",
				componentId, bd.getUuid(), version, re.getMessage());
			throw new RelizaException(re.getMessage());
		}
	}
	
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static record CreateArtifactInput(
            UUID release,
            UUID component,
            UUID deliverable,
            String releaseVersion,
            UUID sce,
            ArtifactBelongsTo belongsTo
    ) {}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addArtifactManual")
	public ReleaseData addArtifactManual(DgsDataFetchingEnvironment dfe, 
		@InputArgument("artifactUuid") UUID inputArtifactUuid) throws RelizaException {

		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Map<String, Object> artifactInput = dfe.getArgument("artifactInput");
		// Avoid Jackson trying to process nested 'artifact' (may contain MultipartFile) during record mapping
		Map<String, Object> convertible = new LinkedHashMap<>(artifactInput);
		@SuppressWarnings("unchecked")
		Map<String, Object> artifact = (Map<String, Object>) convertible.remove("artifact");
		CreateArtifactInput createArtifactInput = Utils.OM.convertValue(convertible, CreateArtifactInput.class);
		
		List<RelizaObject> ros = new LinkedList<>();
		
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(createArtifactInput.release());
		RelizaObject releaseRo = ord.isPresent() ? ord.get() : null;
		ros.add(releaseRo);

		if (null != inputArtifactUuid) {
			ros.add(artifactService.getArtifactData(inputArtifactUuid).orElseThrow());
		}

		if (null != createArtifactInput.component()) {
			ros.add(getComponentService.getComponentData(createArtifactInput.component()).orElseThrow());
		}	

		if (null != createArtifactInput.deliverable()) {
			ros.add(getDeliverableService.getDeliverableData(createArtifactInput.deliverable()).orElseThrow());
		}

		if (null != createArtifactInput.sce()) {
			ros.add(getSourceCodeEntryService.getSourceCodeEntryData(createArtifactInput.sce()).orElseThrow());
		}

		authorizationService.isUserAuthorizedOrgWideGraphQLWithObjects(oud.get(), ros, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		ReleaseData rd = ord.get();

		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).orElseThrow();
		OrganizationData od = getOrganizationService.getOrganizationData(rd.getOrg()).orElseThrow();

		ArtifactBelongsTo belongsTo = ArtifactBelongsTo.RELEASE;
		if(artifactInput.containsKey("belongsTo") && StringUtils.isNotEmpty((String)artifactInput.get("belongsTo")))
			belongsTo = ArtifactBelongsTo.valueOf((String)artifactInput.get("belongsTo"));
		
		MultipartFile multipartFile = null;
		if (artifact != null && artifact.containsKey("file")) {
			multipartFile = (MultipartFile) artifact.get("file");
			
		}

		if (artifact != null && multipartFile != null) {
			artifact.remove("file");
		}

		ArtifactDto artDto = Utils.OM.convertValue(artifact, ArtifactDto.class);
		artDto.setOrg(rd.getOrg());
		if(null!= inputArtifactUuid){
			artDto.setUuid(inputArtifactUuid);
			// artDto = artifactService.getArtifactData(artifactUuid);
		}
		// validations
		List<String> validationErrors =  new ArrayList<>();

		if(null == artDto.getType()){
			validationErrors.add("Artifact Type is required.");
		}

		if(null == artDto.getDisplayIdentifier()){
			validationErrors.add("Display Identifier is required.");
		}
		// if(artDto.getBomFormat())
		if(ArtifactType.BOM.equals(artDto.getType())
			|| ArtifactType.VEX.equals(artDto.getType())
			|| ArtifactType.VDR.equals(artDto.getType())
			|| ArtifactType.ATTESTATION.equals(artDto.getType())
		){
			if(null == artDto.getBomFormat())
			{
				validationErrors.add("Bom Format must be specified");
			}
		}
		
		if(StoredIn.EXTERNALLY.equals(artDto.getStoredIn())
			&& artDto.getDownloadLinks().isEmpty())
		{
			validationErrors.add("External Artifacts must specify at least one Download Link");
		}

		if(!validationErrors.isEmpty()){
			throw new RelizaException(validationErrors.stream().collect(Collectors.joining(", ")));
		}

		UUID artId = null;

		String purl = null;
		Optional<TeaIdentifier> purlId = Optional.empty();
		if(ArtifactBelongsTo.DELIVERABLE.equals(belongsTo) && artifactInput.containsKey("deliverable")){
			UUID deliverableId = UUID.fromString((String)artifactInput.get("deliverable"));
			DeliverableData dd = getDeliverableService.getDeliverableData(deliverableId).get();
			if (null != dd.getIdentifiers()) purlId = dd.getIdentifiers().stream().filter(id -> id.getIdType() == TeaIdentifierType.PURL).findFirst();
			if (purlId.isPresent()) purl = purlId.get().getIdValue();
		} else if(ArtifactBelongsTo.SCE.equals(belongsTo) && artifactInput.containsKey("sce")){
			// TODO purl for sce
		} else { // belongs to release
			if (null != ord.get().getIdentifiers()) purlId = ord.get().getIdentifiers().stream().filter(id -> id.getIdType() == TeaIdentifierType.PURL).findFirst();
			if (purlId.isPresent()) purl = purlId.get().getIdValue();
		}

		if (multipartFile != null) {
			String hash = null != artDto.getDigests() ? artDto.getDigests().stream().findFirst().orElse(null) : null;
			artDto.setOrg(rd.getOrg());
			artId = artifactService.uploadArtifact(artDto, multipartFile.getResource(),  new RebomOptions(cd.getName(), od.getName(), rd.getVersion(), belongsTo, hash, artDto.getStripBom(), purl), wu);
		} else {
			artId = artifactService.createArtifact(artDto, wu).getUuid();
		}

		//new artifact created now attach
		// here cross check and logic for the case when the id is getting replace
		// so 1. if null != artId != inputArtifactUuid means artifact is replaced
		// if 2. null == inputArtifactUuid != artId means a new artifact created
		// if 3. null != inputArtifactUuid == artId means artifact was replaced in place, nothing needs to be done.
		if(null != artId){
			if(null == inputArtifactUuid){
				if(ArtifactBelongsTo.DELIVERABLE.equals(belongsTo) && artifactInput.containsKey("deliverable")){
					UUID deliverableId = UUID.fromString((String)artifactInput.get("deliverable"));
					deliverableService.addArtifact(deliverableId, artId, wu);
				} else if(ArtifactBelongsTo.SCE.equals(belongsTo) && artifactInput.containsKey("sce")){
					UUID sceUuid = UUID.fromString((String)artifactInput.get("sce"));
					SCEArtifact sceArt = new SCEArtifact(artId, cd.getUuid());
					sourceCodeEntryService.addArtifact(sceUuid, sceArt, wu);
				} else { //default case, attach to the release
					releaseService.addArtifact(artId, ord.get().getUuid(),  wu);
				}
			}else if(!inputArtifactUuid.equals(artId)) {

				// replace the exisiting artifact here
				if(ArtifactBelongsTo.DELIVERABLE.equals(belongsTo) && artifactInput.containsKey("deliverable")){
					UUID deliverableId = UUID.fromString((String)artifactInput.get("deliverable"));
					deliverableService.replaceArtifact(deliverableId,inputArtifactUuid, artId, wu);
				} else if(ArtifactBelongsTo.SCE.equals(belongsTo) && artifactInput.containsKey("sce")){
					UUID sceUuid = UUID.fromString((String)artifactInput.get("sce"));
					SCEArtifact sceArt = new SCEArtifact(artId, cd.getUuid());
					SCEArtifact replaceArt = new SCEArtifact(inputArtifactUuid, cd.getUuid());
					sourceCodeEntryService.replaceArtifact(sceUuid, replaceArt, sceArt, wu);
				} else { //default case, attach to the release
					releaseService.replaceArtifact(inputArtifactUuid, artId, ord.get().getUuid(),  wu);
				}
				
				// Transfer version history from old artifact to new artifact
				sharedArtifactService.transferArtifactVersionHistory(inputArtifactUuid, artId, wu);
			} else {
				var releases = sharedReleaseService.findReleasesByReleaseArtifact(artId, rd.getOrg());
				releases.forEach(r -> acollectionService.resolveReleaseCollection(r.getUuid(), wu));
			}
			
			releaseService.reconcileMergedSbomRoutine(rd, wu);
		}

		
		return sharedReleaseService.getReleaseData(ord.get().getUuid()).get();
		// TODO
		// return releaseService.addArtifacts(ord.get(), addArtifactDto.getArtifacts(), wu);
	}
	
	@DgsData(parentType = "Mutation", field = "addArtifactProgrammatic")
	public ReleaseData addArtifactProg(DgsDataFetchingEnvironment dfe) throws RelizaException {
		
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> addArtifactInput = dfe.getArgument("artifactInput");
		AddDeliverablesDto addArtifactDto = Utils.OM.convertValue(addArtifactInput, AddDeliverablesDto.class);
		
		
		UUID componentId = null;
		if (ApiTypeEnum.COMPONENT == ahp.getType()) {
			componentId = ahp.getObjUuid();
		} else if (ApiTypeEnum.ORGANIZATION_RW == ahp.getType()) {
			componentId = addArtifactDto.getComponent();
		}

		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentId);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		AuthorizationResponse ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);
		
		Optional<ReleaseData> ord = Optional.empty();
		if (null != addArtifactDto.getRelease()) ord = sharedReleaseService.getReleaseData(addArtifactDto.getRelease());
		if (ord.isEmpty()) ord = releaseService.getReleaseDataByComponentAndVersion(componentId, addArtifactDto.getVersion());
		return null;
		// TODO
		// also need to check for whether explicitly recompute collections here
		// return releaseService.addArtifacts(ord.get(), addArtifactDto.getArtifacts(), ar.getWhoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "releasecompletionfinalizerProgrammatic")
	public Boolean releasecompletionfinalizerProgrammatic(@InputArgument("release") UUID releaseId, DgsDataFetchingEnvironment dfe) {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");

		Optional<ReleaseData> ord = Optional.empty();
		ord = sharedReleaseService.getReleaseData(releaseId);
		if (ord.isEmpty()) throw new RuntimeException("Wrong release");

		ReleaseData rd = ord.get();

		UUID componentId = rd.getComponent();

		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		AuthorizationResponse ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);

		releaseFinalizerService.scheduleFinalizeRelease(rd.getUuid());
		return true;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "triggerReleasecompletionfinalizer")
	public Boolean triggerReleasecompletionfinalizer(@InputArgument("release") UUID releaseId, DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseId);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		
		if (ord.isEmpty()) throw new RuntimeException("Wrong release");

		ReleaseData rd = ord.get();
	
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);

		releaseFinalizerService.finalizeRelease(rd.getUuid());
		return true;
	}
	
	public static record SearchDigestVersionResponse (List<ReleaseData> commitReleases) {}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "exportAsObomManual")
	public String exportAsObomManual(DgsDataFetchingEnvironment dfe,
			@InputArgument("releaseUuid") UUID releaseUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return releaseService.exportReleaseAsObom(releaseUuid).toString();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "searchDigestVersion")
	public SearchDigestVersionResponse searchDigestVersion(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("query") String query) {

		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		
		List<ReleaseData> retList = new LinkedList<>();
		
		if (StringUtils.isNotEmpty(query)) {
			// handle full docker images
			if (query.contains("@sha")) {
				query = query.split("@")[1];
			}
			
			Optional<ReleaseData> optArtSearchRd = releaseService.getReleaseDataByOutboundDeliverableDigest(query, orgUuid);
			if (optArtSearchRd.isPresent()) {
				retList.add(optArtSearchRd.get());
			} else {
				// attempt version search
				retList = releaseService.listReleaseDataByVersion(query, orgUuid);
			}
			
			if (retList.isEmpty()) {
				// attempt search by commit or tag
				retList = releaseService.getReleaseDataByCommitOrTag(query, orgUuid);
			}
			if (retList.isEmpty()) {
				// finally attempt search by build id
				retList = releaseService.listReleaseDataByBuildId(query, orgUuid);
			}

			// include all bundles
			if (!retList.isEmpty()) {
				// dedup map
				Map<UUID, ReleaseData> rlzUuidToRdMap = new LinkedHashMap<>();
				
				// resolve bundles
				List<ReleaseData> retListWithBundles = new LinkedList<>();
				retList.forEach(rlz -> {
					retListWithBundles.addAll(
						sharedReleaseService.greedylocateProductsOfRelease(rlz, orgUuid, true)
					);
				});
				
				retListWithBundles.forEach(rlz -> {
					rlzUuidToRdMap.put(rlz.getUuid(), rlz);
				});
				
				
				// bring back to collection
				var dedupBundles = new LinkedList<>(rlzUuidToRdMap.values());
				
				// sort
				Collections.sort(dedupBundles, new ReleaseDateComparator());
				
				// add to retList
				retList.addAll(dedupBundles);
			}
		}
		retList = new LinkedList<>(new LinkedHashSet<>(retList));
		return new SearchDigestVersionResponse(retList);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releasesByTags")
	public List<ReleaseData> getReleases(DgsDataFetchingEnvironment dfe,
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("branchUuid") UUID branchUuid,
			@InputArgument("tagKey") String tagKey,
			@InputArgument("tagValue") String tagValue) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		
		return releaseService.findReleasesByTags(orgUuid, branchUuid, tagKey, tagValue);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releasesByDtrackProjects")
	public List<ReleaseData> searchReleasesByDtrackProjects(DgsDataFetchingEnvironment dfe,
			@InputArgument("orgUuid") final UUID orgUuid,
			@InputArgument("dtrackProjects") List<UUID> dtrackProjects) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return sharedReleaseService.findReleaseDatasByDtrackProjects(dtrackProjects, orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomComponentSearch")
	public List<ComponentPurlToDtrackProject> sbomComponentSearch(DgsDataFetchingEnvironment dfe,
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("queries") List<Map<String, String>> queries) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		List<IntegrationService.SbomComponentSearchQuery> searchQueries = queries.stream()
			.map(q -> new IntegrationService.SbomComponentSearchQuery(q.get("name"), q.get("version")))
			.toList();
		return integrationService.searchDependencyTrackComponentBatch(searchQueries, orgUuid);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateComponentReleasesIdentifiers")
	public Boolean updateComponentReleasesIdentifiers(@InputArgument("componentUuid") UUID compUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ComponentData> ocd = getComponentService.getComponentData(compUuid);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		ossReleaseService.updateComponentReleasesWithIdentifiers(compUuid, wu);
		return true;
	}

	@DgsData(parentType = "Mutation", field = "createFeatureSetFromRelease")
	public BranchData createFeatureSetFromRelease(DgsDataFetchingEnvironment dfe,
			@InputArgument("releaseUuid") UUID releaseUuid,
			@InputArgument("featureSetName") String featureSetName) 
				throws RelizaException {

		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);

		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		return releaseService.createFeatureSetFromRelease(featureSetName, ord.get(), ord.get().getOrg(), wu);
	}
	
	
	/** Sub-fields **/
	
	@DgsData(parentType = "Release", field = "branchDetails")
	public BranchData branchOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		BranchData bd = null;
		if (null != rd.getBranch()) {
			bd = branchService.getBranchData(rd.getBranch()).get();
		} else {
			bd = BranchData.branchDataFromDbRecord(branchService.getBaseBranchOfComponent(rd.getComponent()).get());
		}
		return bd;
	}
	
	@DgsData(parentType = "Release", field = "inProducts")
	public Set<ReleaseData> productsofRelease (DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return sharedReleaseService.greedylocateProductsOfRelease(rd);
	}
	
	@DgsData(parentType = "Release", field = "componentDetails")
	public CompletionStage<Optional<ComponentData>> projectOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		
		if (rd.getComponent() == null) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		
		DataLoader<ComponentKey, Optional<ComponentData>> dataLoader = dfe.getDataLoader("componentDetailsLoader");
		return dataLoader.load(new ComponentKey(rd.getComponent()));
	}
	
	@DgsData(parentType = "Release", field = "sourceCodeEntryDetails")
	public SourceCodeEntryData sceOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		if (rd.getSourceCodeEntry() == null) {
			return null;
		}
		return getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry()).get();
	}
	
	@DgsData(parentType = "Release", field = "commitsDetails")
	public List<SourceCodeEntryData> commitsOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		if (rd.getCommits() == null || rd.getCommits().isEmpty()) {
			return new LinkedList<>();
		}
		return rd.getCommits().stream().map(c -> getSourceCodeEntryService.getSourceCodeEntryData(c).get()).collect(Collectors.toList());
	}
	
	@DgsData(parentType = "Release", field = "artifactDetails")
	public List<ArtifactData> artifactsOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		List<ArtifactData> artList = new LinkedList<>();
		for (UUID artUuid : rd.getArtifacts()) {
			Optional<ArtifactData> artifactOpt = artifactService.getArtifactData(artUuid);
			if (artifactOpt.isPresent()) {
				artList.add(artifactOpt.get());
			} else {
				log.warn("Artifact not found for UUID: {}, releaseId: {}", artUuid, rd.getUuid());
				// Skip missing artifacts instead of crashing
			}
		}
		return artList;
	}
	
	
	@DgsData(parentType = "Release", field = "inboundDeliverableDetails")
	public List<DeliverableData> inboundDeliverableDetailsOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		log.debug("fetching release deliverables for release: {}", rd);

		List<DeliverableData> artList = new LinkedList<>();
		for (UUID delUuid : rd.getInboundDeliverables()) {
			artList.add(getDeliverableService
										.getDeliverableData(delUuid)
										.get());
		}
		return artList;
	}
	
	@DgsData(parentType = "Release", field = "orgDetails")
	public OrganizationData orgOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return getOrganizationService.getOrganizationData(rd.getOrg()).get();
	}
	
	@DgsData(parentType = "Release", field = "variantDetails")
	public List<VariantData> variantsOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return variantService.getVariantsOfRelease(rd.getUuid());
	}
	
	public record ParentReleaseDto (UUID release, UUID org) {}
	
	@DgsData(parentType = "Release", field = "parentReleases")
	public List<ParentReleaseDto> parentReleasesOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return rd.getParentReleases().stream().map(x -> new ParentReleaseDto(x.getRelease(), rd.getOrg())).toList();
	}
	
	
	
	@DgsData(parentType = "Variant", field = "outboundDeliverableDetails")
	public List<DeliverableData> outboundDeliverableDetailsOfVariant (DgsDataFetchingEnvironment dfe) {
		VariantData vd = dfe.getSource();

		List<DeliverableData> artList = new LinkedList<>();
		for (UUID delUuid : vd.getOutboundDeliverables()) {
			artList.add(getDeliverableService
										.getDeliverableData(delUuid)
										.get());
		}
		return artList;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "artifactReleases")
	public List<ReleaseData> getArtifactReleases(
			@InputArgument("artUuid") String artifactUuidStr)
	{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		UUID artifactUuid = UUID.fromString(artifactUuidStr);
		Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		return sharedReleaseService.gatherReleasesForArtifact(artifactUuid, ro.getOrg());
	}

	@DgsData(parentType = "Release", field = "releaseCollection")
	public AcollectionData collectionOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return acollectionService.getLatestCollectionDataOfRelease(rd.getUuid());
	}
	
	// Data loader for release details to batch multiple release lookups
	@DgsDataLoader(name = "releaseDetailsLoader")
	public class MappedBatchLoader implements BatchLoader<ReleaseKey, Optional<ReleaseData>> {
		
		@Autowired
		private SharedReleaseService dataLoaderSharedReleaseService;
		
		@Override
		public CompletionStage<List<Optional<ReleaseData>>> load(List<ReleaseKey> keys) {
			List<Optional<ReleaseData>> results = new ArrayList<>(keys.size());
			for (ReleaseKey key : keys) {
				try {
					Optional<ReleaseData> releaseData = dataLoaderSharedReleaseService.getReleaseData(key.releaseUuid());
					results.add(releaseData);
				} catch (Exception e) {
					log.error("Error loading release data for key: " + key, e);
					results.add(Optional.empty());
				}
			}
			return CompletableFuture.completedFuture(results);
		}
	}
	
	public record ReleaseKey(UUID releaseUuid) {}
	
	public record ArtifactKey(UUID artifactUuid) {}
	
	public record ComponentKey(UUID componentUuid) {}
	
	// Data loader for artifact details to batch multiple artifact lookups
	@DgsDataLoader(name = "artifactDetailsLoader")
	public class ArtifactDetailsBatchLoader implements BatchLoader<ArtifactKey, Optional<ArtifactData>> {
		
		@Autowired
		private ArtifactService dataLoaderArtifactService;
		
		@Override
		public CompletionStage<List<Optional<ArtifactData>>> load(List<ArtifactKey> keys) {
			List<Optional<ArtifactData>> results = new ArrayList<>(keys.size());
			for (ArtifactKey key : keys) {
				try {
					Optional<ArtifactData> artifactData = dataLoaderArtifactService.getArtifactData(key.artifactUuid());
					results.add(artifactData);
				} catch (Exception e) {
					log.error("Error loading artifact data for key: " + key, e);
					results.add(Optional.empty());
				}
			}
			return CompletableFuture.completedFuture(results);
		}
	}
	
	// Data loader for component details to batch multiple component lookups
	@DgsDataLoader(name = "componentDetailsLoader")
	public class ComponentDetailsBatchLoader implements BatchLoader<ComponentKey, Optional<ComponentData>> {
		
		@Autowired
		private GetComponentService dataLoaderGetComponentService;
		
		@Override
		public CompletionStage<List<Optional<ComponentData>>> load(List<ComponentKey> keys) {
			List<Optional<ComponentData>> results = new ArrayList<>(keys.size());
			for (ComponentKey key : keys) {
				try {
					Optional<ComponentData> componentData = dataLoaderGetComponentService.getComponentData(key.componentUuid());
					results.add(componentData);
				} catch (Exception e) {
					log.error("Error loading component data for key: " + key, e);
					results.add(Optional.empty());
				}
			}
			return CompletableFuture.completedFuture(results);
		}
	}
	
	@DgsData(parentType = "FindingSourceDto", field = "releaseDetails")
	public CompletionStage<Optional<ReleaseData>> releaseDetailsOfFindingSource(DgsDataFetchingEnvironment dfe) {
		FindingSourceDto source = dfe.getSource();
		
		if (source.release() == null) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		
		DataLoader<ReleaseKey, Optional<ReleaseData>> dataLoader = dfe.getDataLoader("releaseDetailsLoader");
		return dataLoader.load(new ReleaseKey(source.release()));
	}

	@DgsData(parentType = "FindingSourceDto", field = "artifactDetails")
	public CompletionStage<Optional<ArtifactData>> artifactDetailsOfFindingSource(DgsDataFetchingEnvironment dfe) {
		FindingSourceDto source = dfe.getSource();
		
		if (source.artifact() == null) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		
		DataLoader<ArtifactKey, Optional<ArtifactData>> dataLoader = dfe.getDataLoader("artifactDetailsLoader");
		return dataLoader.load(new ArtifactKey(source.artifact()));
	}

	@DgsData(parentType = "ParentRelease", field = "releaseDetails")
	public Optional<ReleaseData> releaseDetailsOfParentRelease (DgsDataFetchingEnvironment dfe) {
		ParentReleaseDto prd = dfe.getSource();
		Optional<ReleaseData> ord = Optional.empty();
		try {
			ord = releaseService.getReleaseData(prd.release(), prd.org());
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
		return ord;
	}
}
