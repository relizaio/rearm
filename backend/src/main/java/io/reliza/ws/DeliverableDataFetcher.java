/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.Deliverable;
import io.reliza.model.DeliverableData;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RelizaObject;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.model.dto.DeliverableDto.AddOutboundDeliverablesInput;
import io.reliza.service.DeliverableService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VariantService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class DeliverableDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	DeliverableService deliverableService;
	
	@Autowired
	GetDeliverableService getDeliverableService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	BranchService branchService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	ArtifactService artifactService;
	
	@Autowired
	VariantService variantService;
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	// TODO this should be put under RBAC but left as org wide only as currently this endpoint is not used by UI; PS - 2026-02-20
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "deliverable")
	public DeliverableData getDeliverable(@InputArgument("deliverable") String deliverableUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID deliverable = UUID.fromString(deliverableUuidStr);
		Optional<DeliverableData> oad = getDeliverableService.getDeliverableData(deliverable);
		RelizaObject ro = oad.isPresent() ? oad.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, ro.getOrg(), List.of(ro), CallType.READ);
		return oad.get();
	}
	
	@DgsData(parentType = "Query", field = "listDeliverablesByComponent")
	public List<DeliverableData> listDeliverablesByComponent(DgsDataFetchingEnvironment dfe,
			@InputArgument("componentUuid") String componentUuidStr) {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID componentUuid = UUID.fromString(componentUuidStr);
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		RelizaObject ro = opd.isPresent() ? opd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.COMPONENT, componentUuid, List.of(ro), CallType.READ);
		return getDeliverableService.listDeliverableDataByComponent(componentUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addOutboundDeliverablesManual")
	@Transactional
	public Boolean addOutboundDeliverablesManual(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Map<String, Object> addDeliverablesInputMap = dfe.getArgument("deliverables");
		AddOutboundDeliverablesInput addDeliverablesInput = Utils.OM.convertValue(addDeliverablesInputMap, AddOutboundDeliverablesInput.class);
		Optional<ReleaseData> ord = Optional.empty();
		Optional<VariantData> ovd = Optional.empty();
		
		if (null != addDeliverablesInput.release()) ord = sharedReleaseService.getReleaseData(addDeliverablesInput.release());
		if (null != addDeliverablesInput.variant()) ovd = variantService.getVariantData(addDeliverablesInput.variant());
		
		if (ord.isEmpty() && ovd.isEmpty()) {
			throw new RuntimeException("Either release or variant must be supplied.");
		}
		
		if (ord.isPresent() && ovd.isPresent() && !ovd.get().getRelease().equals(ord.get().getUuid())) {
			throw new RuntimeException("Release and variant don't match.");
		}
		
		if (null == addDeliverablesInput.deliverables() || addDeliverablesInput.deliverables().isEmpty()) {
			throw new RelizaException("At least one deliverable must be provided.");
		}

		if (ord.isPresent() && ord.get().getLifecycle() != ReleaseLifecycle.DRAFT) {
			throw new RelizaException("Only DRAFT releases may have new outbound deliverables added.");
		}
		
		if (ovd.isEmpty()) {
			ovd = Optional.of(variantService.getBaseVariantForRelease(addDeliverablesInput.release()));
		}
		
		if (ord.isEmpty()) {
			ord = sharedReleaseService.getReleaseData(ovd.get().getRelease());
		}
		
		boolean branchMatch = true;
		for (var d : addDeliverablesInput.deliverables()) {
			if (!d.getBranch().equals(ord.get().getBranch())) branchMatch = false;
		}
		if (!branchMatch) throw new RuntimeException("Branch mismatch");
		
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.RELEASE, ord.get().getUuid(), List.of(ord.get(), ovd.get()), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		List<UUID> createdDeliverables = new LinkedList<>();
		for (var d : addDeliverablesInput.deliverables()) {
			Deliverable a = deliverableService.createDeliverable(d, wu);
			createdDeliverables.add(a.getUuid());
		}
		
		return variantService.addOutboundDeliverables(createdDeliverables, ovd.get().getUuid(), wu);
		
	}
	
	@DgsData(parentType = "Mutation", field = "addOutboundDeliverablesProgrammatic")
	@Transactional
	public ReleaseData addOutboundDeliverablesProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
				DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> addDeliverablesInputMap = dfe.getArgument("deliverables");
		UUID componentId = Utils.resolveProgrammaticComponentId((String) addDeliverablesInputMap.get(CommonVariables.COMPONENT_FIELD), ahp);
		
		String version = (String) addDeliverablesInputMap.get(CommonVariables.VERSION_FIELD);

		Optional<ReleaseData> ord = Optional.empty();

		String releaseUuidStr = (String) addDeliverablesInputMap.get(CommonVariables.RELEASE_FIELD);
		String variantUuidStr = (String) addDeliverablesInputMap.get("variant");

		if (StringUtils.isNotEmpty(releaseUuidStr)) ord = sharedReleaseService.getReleaseData(UUID.fromString(releaseUuidStr));
		if (ord.isEmpty() && StringUtils.isNotEmpty(version) && null != componentId) ord = releaseService.getReleaseDataByComponentAndVersion(componentId, version);

		if(!ord.isEmpty() && null == componentId)
			componentId = ord.get().getComponent();

		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
		RelizaObject ro = ocd.isPresent() ? ocd.get() : null;
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (null != ro)	ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, ro.getOrg(), CallType.WRITE, ro);
		
		Optional<VariantData> ovd = Optional.empty();
		
		if(StringUtils.isNotEmpty(variantUuidStr)) ovd = variantService.getVariantData(UUID.fromString(variantUuidStr));
		
		if (ord.isEmpty() && ovd.isEmpty()) {
			throw new RuntimeException("Either release or variant must be supplied.");
		}
		
		if (ord.isPresent() && ovd.isPresent() && !ovd.get().getRelease().equals(ord.get().getUuid())) {
			throw new RuntimeException("Release and variant don't match.");
		}
		
		if (ovd.isEmpty()) {
			ovd = Optional.of(variantService.getBaseVariantForRelease(ord.get().getUuid()));
		}
		
		if (ord.isEmpty()) {
			ord = sharedReleaseService.getReleaseData(ovd.get().getRelease());
		}
		
		BranchData bd = branchService.getBranchData(ord.get().getBranch()).get();

		if(StringUtils.isEmpty(version)) version = ord.get().getVersion();

		@SuppressWarnings("unchecked")
		var deliverablesList = (List<Map<String,Object>>) addDeliverablesInputMap.get("deliverables");
		Utils.addReleaseProgrammaticValidateDeliverables(deliverablesList, bd);
		
		WhoUpdated wu = ar.getWhoUpdated();
		List<UUID> deliverables = new LinkedList<>();
		if (null != deliverablesList && !deliverablesList.isEmpty()) {
			deliverables = deliverableService.prepareListofDeliverables(deliverablesList,
					bd.getUuid(), version, ar.getWhoUpdated());
		}

		releaseService.reconcileMergedSbomRoutine(ord.get(), wu);

		variantService.addOutboundDeliverables(deliverables, ovd.get().getUuid(), wu);

		return ord.get();
		
	}

	@DgsData(parentType = "Deliverable", field = "artifactDetails")
	public List<ArtifactData> artifactsOfDeliverableWithDep(DgsDataFetchingEnvironment dfe) {
		DeliverableData rd = dfe.getSource();
		List<ArtifactData> artList = new LinkedList<>();
		for (UUID artUuid : rd.getArtifacts()) {
			artList.add(artifactService
										.getArtifactData(artUuid)
										.get());
		}
		return artList;
	}

}