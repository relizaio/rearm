/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.DeclarativeConfigService;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.BranchesSpecDto;
import io.reliza.service.DeclarativeConfigService.CatalogSpecDto;
import io.reliza.service.DeclarativeConfigService.SourceDto;
import io.reliza.service.GetOrganizationService;

/**
 * Declarative configuration surface for API keys: apply / export of the Catalog and
 * Branches slices. FREEFORM and USER keys need the CONFIGURATION_WRITE function with write access
 * on the org to apply, and CONFIGURATION_READ (or WRITE, which implies it) with read
 * access to export; org admins pass implicitly. Legacy ORGANIZATION_RW keys have no
 * function grants and pass on their org-wide tier as before.
 */
@DgsComponent
public class DeclarativeConfigDataFetcher {

	@Autowired private AuthorizationService authorizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private DeclarativeConfigService declarativeConfigService;

	private record Authed(UUID orgUuid, WhoUpdated whoUpdated) {}

	private Authed authorize(DgsDataFetchingEnvironment dfe, CallType ct) throws RelizaException {
		// export: CONFIGURATION_READ, or CONFIGURATION_WRITE which implies it; apply: CONFIGURATION_WRITE
		if (ct == CallType.WRITE) return authorize(dfe, ct, PermissionFunction.CONFIGURATION_WRITE);
		try {
			return authorize(dfe, ct, PermissionFunction.CONFIGURATION_READ);
		} catch (AccessDeniedException readDenied) {
			return authorize(dfe, ct, PermissionFunction.CONFIGURATION_WRITE);
		}
	}

	private Authed authorize(DgsDataFetchingEnvironment dfe, CallType ct, PermissionFunction function) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		var servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(), servletWebRequest);
		var ahp = authCtx.ahp();
		UUID orgUuid = authCtx.orgUuid();
		if (null == ahp || null == orgUuid) throw new AccessDeniedException("Invalid authorization type");
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = ood.orElse(null);
		if (ro == null) throw new AccessDeniedException("Not authorized");
		AuthorizationResponse ar;
		if (ahp.isRbacKey()) {
			FreeformKeyVerification fkv = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(
					ahp, function, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), ct);
			ar = AuthorizationResponse.initialize(InitType.ALLOW);
			ar.setWhoUpdated(fkv.whoUpdated());
		} else {
			List<ApiTypeEnum> supportedApiTypes = Arrays.asList(ApiTypeEnum.ORGANIZATION_RW);
			ar = authorizationService.isApiKeyAuthorized(ahp, supportedApiTypes, orgUuid, ct, ro);
		}
		return new Authed(orgUuid, ar.getWhoUpdated());
	}

	private static SourceDto source(DgsDataFetchingEnvironment dfe) {
		Map<String, Object> m = dfe.getArgument("source");
		return m == null ? null : Utils.OM.convertValue(m, SourceDto.class);
	}

	private static boolean dryRun(DgsDataFetchingEnvironment dfe) {
		Boolean d = dfe.getArgument("dryRun");
		return Boolean.TRUE.equals(d);
	}

	@DgsData(parentType = "Mutation", field = "applyCatalogProgrammatic")
	public ApplyResult applyCatalogProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		Map<String, Object> specMap = dfe.getArgument("spec");
		CatalogSpecDto spec = Utils.OM.convertValue(specMap, CatalogSpecDto.class);
		return declarativeConfigService.applyCatalog(a.orgUuid(), spec, dryRun(dfe), source(dfe), a.whoUpdated());
	}

	@DgsData(parentType = "Mutation", field = "applyBranchesProgrammatic")
	public ApplyResult applyBranchesProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.WRITE);
		Map<String, Object> specMap = dfe.getArgument("spec");
		BranchesSpecDto spec = Utils.OM.convertValue(specMap, BranchesSpecDto.class);
		return declarativeConfigService.applyBranches(a.orgUuid(), spec, dryRun(dfe), source(dfe), a.whoUpdated());
	}

	@DgsData(parentType = "Query", field = "exportCatalogProgrammatic")
	public CatalogSpecDto exportCatalogProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.READ);
		List<String> names = dfe.getArgument("components");
		return declarativeConfigService.exportCatalog(a.orgUuid(), names);
	}

	@DgsData(parentType = "Query", field = "exportBranchesProgrammatic")
	public BranchesSpecDto exportBranchesProgrammatic(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Authed a = authorize(dfe, CallType.READ);
		String component = dfe.getArgument("component");
		return declarativeConfigService.exportBranches(a.orgUuid(), component);
	}
}
