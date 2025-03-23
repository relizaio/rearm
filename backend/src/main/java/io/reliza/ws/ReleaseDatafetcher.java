/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
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
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.AddDeliverablesDto;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.ComponentJsonDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetComponentService;
import io.reliza.service.OrganizationService;
import io.reliza.service.RebomService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VariantService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.RebomService.BomStructureType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class ReleaseDatafetcher {
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	@Autowired
	BranchService branchService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	SourceCodeEntryService sourceCodeEntryService;
	
	@Autowired
	DeliverableService deliverableService;
	
	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	GetComponentService getComponentService;

	@Autowired
	SourceCodeEntryService sceService;
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	ApiKeyService apiKeyService;
	
	@Autowired
	RebomService rebomService;
	
	@Autowired
	VariantService variantService;
	
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
			var rlzOpt = releaseService.getReleaseData(UUID.fromString(releaseUuidStr));
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
			@InputArgument("structure") BomStructureType structure,
			@InputArgument("belongsTo") ArtifactBelongsTo belongsTo
			) throws RelizaException, JsonProcessingException{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		RelizaObject ro = null;
		if (StringUtils.isNoneEmpty(releaseUuidStr)) {
			var rlzOpt = releaseService.getReleaseData(UUID.fromString(releaseUuidStr));
			if (rlzOpt.isPresent()) {
				ro = rlzOpt.get();
			}
		}
		
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		ReleaseData rd = (ReleaseData) ro;
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		return releaseService.exportReleaseSbom(rd.getUuid(), tldOnly, belongsTo, structure, wu);
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
			var od = organizationService.getOrganizationData(orgFilter);
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
				retRel = releaseService.getReleaseDataList(releaseFilter, orgFilter); 
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
		Optional<OrganizationData> ood = organizationService.getOrganizationData(orgUuid);
		RelizaObject roorg = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObjects(oud.get(), List.of(robranch, roorg), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			return ReleaseData.dataFromRecord(releaseService.createRelease(releaseDto, wu));
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
		Optional<ReleaseData> ord = releaseService.getReleaseData(releaseId);
		if (ord.isEmpty()) throw new RuntimeException("Wrong release");
		CallType ct = CallType.ADMIN;
		ReleaseLifecycle oldLifecycle = ord.get().getLifecycle();
		if (newLifecycle.ordinal() - oldLifecycle.ordinal() == 1) ct = CallType.WRITE;
		RelizaObject ro = ord.get();
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, ct);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		var r = releaseService.updateReleaseLifecycle(releaseId, newLifecycle, wu);
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
		Optional<ReleaseData> ord = releaseService.getReleaseData(releaseDto.getUuid());
		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		try {
			releaseService.updateRelease(releaseDto, wu);
		} catch (RelizaException e) {
			log.error("Exception on updateRelease", e);
			throw new RuntimeException(e.getMessage());
		}
		return releaseService.getReleaseData(releaseDto.getUuid()).get();
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
		
		Optional<Deliverable> od = deliverableService.getDeliverableByDigestAndComponent(hash, componentId);
		// locate lowest level release referencing this artifact
		// we pass component from artifact since we already scoped artifact search to only this component in getArtifactByDigestAndComponent
		Optional<ReleaseData> ord = Optional.empty();
		if (od.isPresent()) ord = releaseService.getReleaseByOutboundDeliverable(od.get().getUuid(), orgId);
		return ord;
	}
	
	public static record GetLatestReleaseInput (UUID component, UUID product, String branch,
			TagRecord tags, ReleaseLifecycle lifecycle, InputConditionGroup conditions) {}

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
	public ReleaseData addReleaseProgrammatic(DgsDataFetchingEnvironment dfe) throws IOException, RelizaException {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> progReleaseInput = dfe.getArgument("release");
		
		UUID componentId = Utils.resolveProgrammaticComponentId((String) progReleaseInput.get(CommonVariables.COMPONENT_FIELD), ahp);
		
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		AuthorizationResponse ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);
		
		BranchData bd = resolveAddReleaseProgrammaticBranchData(componentId, (String) progReleaseInput.get(CommonVariables.BRANCH_FIELD),
				ar.getWhoUpdated());
		
		@SuppressWarnings("unchecked")
		var inboundDeliverablesList = (List<Map<String,Object>>) progReleaseInput.get("inboundDeliverables");
		Utils.addReleaseProgrammaticValidateDeliverables(inboundDeliverablesList, bd);
		
		@SuppressWarnings("unchecked")
		var outboundDeliverablesList = (List<Map<String,Object>>) progReleaseInput.get("outboundDeliverables");
		Utils.addReleaseProgrammaticValidateDeliverables(outboundDeliverablesList, bd);
		
		OrganizationData od = organizationService.getOrganizationData(ocd.get().getOrg()).get();
		
		URI endpoint = null;
		String endpointStr = (String) progReleaseInput.get(CommonVariables.ENDPOINT_FIELD);
		if (StringUtils.isNotEmpty(endpointStr)) {
			endpoint = URI.create(endpointStr);
		}
		
		Optional<SourceCodeEntryData> osced = Optional.empty();
		String version = (String) progReleaseInput.get(CommonVariables.VERSION_FIELD);
		List<UUID> commits = new LinkedList<>();
				
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
					List<UUID>  sceUploadedArts = releaseService.uploadSceArtifacts(arts, od, sceDto, cd, version, ar.getWhoUpdated());
					var parsedCommit = releaseService.parseSceFromReleaseCreate(sceDto, sceUploadedArts, 
							bd, bd.getName(), version, ar.getWhoUpdated());
					if (parsedCommit.isPresent()) {
						commits.add(parsedCommit.get().getUuid());
					}
				}
				
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
		
		var releaseDtoBuilder = ReleaseDto.builder()
										 .branch(bd.getUuid())
										 .org(ocd.get().getOrg())
										 .commits(!commits.isEmpty() ? commits : null);
		if (osced.isPresent()) {
			releaseDtoBuilder.sourceCodeEntry(osced.get().getUuid());
		}

		List<UUID> inboundDeliverables = new LinkedList<>();
		if (null != inboundDeliverablesList && !inboundDeliverablesList.isEmpty()) {
			inboundDeliverables = deliverableService.prepareListofDeliverables(inboundDeliverablesList,
					bd.getUuid(), version, ar.getWhoUpdated());
		}
		
		List<UUID> artifacts = new LinkedList<>();
		if (progReleaseInput.containsKey("artifacts")) {
			@SuppressWarnings("unchecked")
			List<Map<String,Object>> artifactsList = (List<Map<String,Object>>) progReleaseInput.get("artifacts");
			artifacts = artifactsList.stream().map(artMap -> {
				MultipartFile file = (MultipartFile) artMap.get("file");
				artMap.remove("file");
				// validations
				if(!artMap.containsKey("storedIn") || StringUtils.isEmpty((String)artMap.get("storedIn"))){
					artMap.put("storedIn", "REARM");
				}
				ArtifactDto artDto = Utils.OM.convertValue(artMap, ArtifactDto.class);
				UUID artId = null;
				try {
					artId = artifactService.uploadArtifact(artDto, od.getUuid(), file.getResource(), new RebomOptions(ocd.get().getName(), od.getName(), version, ArtifactBelongsTo.RELEASE, null), ar.getWhoUpdated());
				} catch (Exception e) {
					throw new RuntimeException(e); // Re-throw the exception
				}
				return artId;
			}).filter(Objects::nonNull).toList();
		}
		
		ReleaseLifecycle lifecycle = ReleaseLifecycle.ASSEMBLED;
		if (progReleaseInput.containsKey(CommonVariables.LIFECYCLE_FIELD)) {
			ReleaseLifecycle suppliedLifecycle = ReleaseLifecycle.valueOf((String) progReleaseInput.get(CommonVariables.LIFECYCLE_FIELD));
			if (null != suppliedLifecycle) {
				lifecycle = suppliedLifecycle;
			}
		}
		
		try {
			releaseDtoBuilder.version(version)
							.inboundDeliverables(inboundDeliverables)
							.artifacts(artifacts)
							.lifecycle(lifecycle)
							.endpoint(endpoint);
			var rd = ReleaseData.dataFromRecord(releaseService.createRelease(releaseDtoBuilder.build(),
					ar.getWhoUpdated()));
			log.info("release created: {}", rd);
			if (null != outboundDeliverablesList && !outboundDeliverablesList.isEmpty()) {
				List<UUID> outboundDeliverables = deliverableService
						.prepareListofDeliverables(outboundDeliverablesList, bd.getUuid(), version, ar.getWhoUpdated());
				VariantData vd = variantService.getBaseVariantForRelease(rd);
				variantService.addOutboundDeliverables(outboundDeliverables, vd.getUuid(), ar.getWhoUpdated());
			}
			return rd;
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addArtifactManual")
	public ReleaseData addArtifactManual(DgsDataFetchingEnvironment dfe) throws Exception {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);


		Map<String, Object> variables = dfe.getVariables();
		Map<String, Object> artifactInput = (Map<String, Object>) variables.get("artifactInput");
		Map<String, Object> artifact = (Map<String, Object>) artifactInput.get("artifact");

				
		Optional<ReleaseData> ord = releaseService.getReleaseData(UUID.fromString((String)artifactInput.get("release")));

		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		UUID orgUuid = ord.isPresent() ? ord.get().getOrg() : null;

		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		ReleaseData rd = ord.get();

		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).orElseThrow();
		OrganizationData od = organizationService.getOrganizationData(rd.getOrg()).orElseThrow();
		UUID artId = null;

		ArtifactBelongsTo belongsTo = ArtifactBelongsTo.RELEASE;
		if(artifactInput.containsKey("belongsTo") && StringUtils.isNotEmpty((String)artifactInput.get("belongsTo")))
			belongsTo = ArtifactBelongsTo.valueOf((String)artifactInput.get("belongsTo"));


		if (artifact != null && artifact.containsKey("file")) {
			MultipartFile multipartFile = (MultipartFile) artifact.get("file");
			if (multipartFile != null) {
				artifact.remove("file");
				ArtifactDto artDto = Utils.OM.convertValue(artifact, ArtifactDto.class);
				String hash = null != artDto.getDigests() ? artDto.getDigests().stream().findFirst().orElse(null) : null;
				artId = artifactService.uploadArtifact(artDto, orgUuid, multipartFile.getResource(),  new RebomOptions(cd.getName(), od.getName(), rd.getVersion(), belongsTo, hash), wu);
			}
		}else{
			ArtifactDto artDto = Utils.OM.convertValue(artifact, ArtifactDto.class);
			artId = artifactService.createArtifact(artDto, wu).getUuid();
		}


		if(null != artId){
			if(ArtifactBelongsTo.DELIVERABLE.equals(belongsTo) && artifactInput.containsKey("deliverable")){
				UUID deliverableId = UUID.fromString((String)artifactInput.get("deliverable"));
				deliverableService.addArtifact(deliverableId, artId, wu);
			} else if(ArtifactBelongsTo.SCE.equals(belongsTo) && artifactInput.containsKey("sce")){
				UUID sceUuid = UUID.fromString((String)artifactInput.get("sce"));
				sourceCodeEntryService.addArtifact(sceUuid, artId, wu);
			} else { //default case, attach to the release
				releaseService.addArtifact(artId, ord.get().getUuid(),  wu);
			}
			releaseService.reconcileMergedSbomRoutine(rd, wu);
		}

		
		return releaseService.getReleaseData(ord.get().getUuid()).get();
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
		if (null != addArtifactDto.getRelease()) ord = releaseService.getReleaseData(addArtifactDto.getRelease());
		if (ord.isEmpty()) ord = releaseService.getReleaseDataByComponentAndVersion(componentId, addArtifactDto.getVersion());
		return null;
		// TODO
		// return releaseService.addArtifacts(ord.get(), addArtifactDto.getArtifacts(), ar.getWhoUpdated());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "removeReleaseArtifact")
	public Boolean removeReleaseArtifact(DgsDataFetchingEnvironment dfe,
		@InputArgument("artifactUuid") String artifactUuidStr,
		@InputArgument("releaseUuid") String releaseUuidStr
	) {

		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		UUID artifactUuid = UUID.fromString(artifactUuidStr);
		UUID releaseUuid = UUID.fromString(releaseUuidStr);
		
		Optional<ReleaseData> ord = releaseService.getReleaseData(releaseUuid);

		RelizaObject ro = ord.isPresent() ? ord.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		return releaseService.removeArtifact(artifactUuid, releaseUuid, wu);
	}
	
	public static record SearchDigestVersionResponse (List<ReleaseData> commitReleases) {}
	
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
						releaseService.greedylocateProductsOfRelease(rlz, orgUuid, true)
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
		return releaseService.greedylocateProductsOfRelease(rd);
	}
	
	@DgsData(parentType = "Release", field = "componentDetails")
	public ComponentData projectOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return getComponentService.getComponentData(rd.getComponent()).get();
	}
	
	@DgsData(parentType = "Release", field = "sourceCodeEntryDetails")
	public SourceCodeEntryData sceOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		if (rd.getSourceCodeEntry() == null) {
			return null;
		}
		return sourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry()).get();
	}
	
	@DgsData(parentType = "Release", field = "commitsDetails")
	public List<SourceCodeEntryData> commitsOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		if (rd.getCommits() == null || rd.getCommits().isEmpty()) {
			return new LinkedList<>();
		}
		return rd.getCommits().stream().map(c -> sourceCodeEntryService.getSourceCodeEntryData(c).get()).collect(Collectors.toList());
	}
	
	@DgsData(parentType = "Release", field = "artifactDetails")
	public List<ArtifactData> artifactsOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		List<ArtifactData> artList = new LinkedList<>();
		for (UUID artUuid : rd.getArtifacts()) {
			artList.add(artifactService
										.getArtifactData(artUuid)
										.get());
		}
		return artList;
	}
	
	
	@DgsData(parentType = "Release", field = "inboundDeliverableDetails")
	public List<DeliverableData> inboundDeliverableDetailsOfReleaseWithDep(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		log.debug("fetching release deliverables for release: {}", rd);

		List<DeliverableData> artList = new LinkedList<>();
		for (UUID delUuid : rd.getInboundDeliverables()) {
			artList.add(deliverableService
										.getDeliverableData(delUuid)
										.get());
		}
		return artList;
	}
	
	@DgsData(parentType = "Release", field = "orgDetails")
	public OrganizationData orgOfRelease(DgsDataFetchingEnvironment dfe) {
		ReleaseData rd = dfe.getSource();
		return organizationService.getOrganizationData(rd.getOrg()).get();
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
	
	
	@DgsData(parentType = "Variant", field = "outboundDeliverableDetails")
	public List<DeliverableData> outboundDeliverableDetailsOfVariant (DgsDataFetchingEnvironment dfe) {
		VariantData vd = dfe.getSource();

		List<DeliverableData> artList = new LinkedList<>();
		for (UUID delUuid : vd.getOutboundDeliverables()) {
			artList.add(deliverableService
										.getDeliverableData(delUuid)
										.get());
		}
		return artList;
	}
}
