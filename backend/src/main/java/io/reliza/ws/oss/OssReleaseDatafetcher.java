/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.oss;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ConditionGroup;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
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
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.ReleaseDatafetcher.GetLatestReleaseInput;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OssReleaseDatafetcher {
	
	@Autowired
	ReleaseService releaseService;
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
	@Autowired
	OssReleaseService ossReleaseService;
	
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
	ComponentService componentService;

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

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "approveReleaseManual")
	public void approveReleaseManual(DgsDataFetchingEnvironment dfe,
		@InputArgument("release") String releaseUuidStr
	) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@DgsData(parentType = "Query", field = "getLatestReleaseProgrammatic")
	public Optional<ReleaseData> getLatestRelease(DgsDataFetchingEnvironment dfe) throws RelizaException {
		DgsWebMvcRequestData requestData =  (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		var ahp = authorizationService.authenticateProgrammatic(requestData.getHeaders(), servletWebRequest);
		if (null == ahp ) throw new AccessDeniedException("Invalid authorization type");
		
		Map<String, Object> latestReleaseInput = dfe.getArgument("release");
		GetLatestReleaseInput glri = Utils.OM.convertValue(latestReleaseInput, GetLatestReleaseInput.class);
		
		// TODO respect tags
		
		String branch = glri.branch();
		UUID componentUuid = null;
		UUID productUuid = glri.product();
		UUID orgId = null;
		ComponentData cd;
		try {
			componentUuid = componentService.resolveComponentIdFromInput(latestReleaseInput, ahp);
		} catch (RelizaException re) {
			throw new RelizaException("Component and or VCS Repository not found");
		}
		
		if (ApiTypeEnum.ORGANIZATION == ahp.getType() || ApiTypeEnum.ORGANIZATION_RW == ahp.getType()) {
			orgId = ahp.getObjUuid();
		}

		cd = getComponentService.getComponentData(componentUuid).get();
		RelizaObject ro = cd;
		if (null == orgId) orgId = cd.getOrg();
		
		if (null != productUuid) {
			Optional<ComponentData> obd = getComponentService.getComponentData(productUuid);
			if (obd.isPresent()) {
				if (null == ro) ro = obd.get();
				if (null == orgId) orgId = obd.get().getOrg();
				if (!obd.get().getOrg().equals(cd.getOrg())) {
					throw new AccessDeniedException("org mismatch");
				}
			}
		}
		
		List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION, 
				ApiTypeEnum.ORGANIZATION_RW);
		
		authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgId, CallType.READ, ro);
		
		Optional<ReleaseData> optRd = Optional.empty();
		
		if (StringUtils.isNotEmpty(branch)) {
			branch = Utils.cleanBranch(branch);
		}
			
		try {
			ConditionGroup cg = null;
			optRd = ossReleaseService.getReleasePerProductComponent(orgId, componentUuid, productUuid, branch, glri.lifecycle(), cg);
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
		
		return optRd;
	}
	
	@Transactional
	@DgsData(parentType = "Mutation", field = "approveReleaseProgrammatic")
	public ReleaseData approveReleaseProgrammatic(DgsDataFetchingEnvironment dfe) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}

}
