/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.InputConversion;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyGroupWebDto;
import io.reliza.model.dto.ApiKeyGroupWebDto.ApiKeyRef;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.service.ApiKeyGroupService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * API key groups on the browser lane (TPF-91, child of TPF-86): organization administrators only.
 * Each root field resolves the caller, answers the system state, converts its argument, looks the
 * group up where it names one, and only then runs the admin gate on the group's (or the input's)
 * organization; grant objects, approvals and members are read only after the gate. An unknown group
 * and a group of another organization both answer the literal "Not authorized".
 */
@Slf4j
@DgsComponent
public class ApiKeyGroupDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private ApiKeyGroupService apiKeyGroupService;

	@Autowired
	private ApiKeyService apiKeyService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	private UserData caller() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new AccessDeniedException("Not authorized"));
	}

	/** The admin gate on an organization: RESOURCE at ORGANIZATION scope at ADMIN, as the user-group fetcher. */
	private void authorizeOrgAdmin(UserData ud, UUID org) throws RelizaException {
		Optional<OrganizationData> ood = null == org ? Optional.empty() : getOrganizationService.getOrganizationData(org);
		if (ood.isEmpty()) throw new AccessDeniedException("Organization not found: " + org);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, org, List.of(ood.get()), CallType.ADMIN);
	}

	/** The group a caller names, then the admin gate on its organization. An unknown uuid answers as a foreign group. */
	private ApiKeyGroupData authorizedGroup(UserData ud, UUID groupUuid) throws RelizaException {
		Optional<ApiKeyGroupData> oakgd = null == groupUuid ? Optional.empty() : apiKeyGroupService.getApiKeyGroupData(groupUuid);
		if (oakgd.isEmpty()) throw new AccessDeniedException("API key group not found: " + groupUuid);
		ApiKeyGroupData akgd = oakgd.get();
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, akgd.getOrg(), List.of(akgd), CallType.ADMIN);
		return akgd;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "apiKeyGroupsOfOrg")
	public List<ApiKeyGroupWebDto> apiKeyGroupsOfOrg(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2: the system state is answered before any argument or row is read
		authorizationService.validateSystemOperational(CallType.ADMIN);
		UUID org = InputConversion.convert(dfe.getArgument("org"), UUID.class, "org");
		authorizeOrgAdmin(ud, org);
		return apiKeyGroupService.getAllApiKeyGroupsByOrganization(org).stream()
				.map(ApiKeyGroupData::toWebDto)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createApiKeyGroup")
	public ApiKeyGroupWebDto createApiKeyGroup(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2: the system state is answered before any argument or row is read
		authorizationService.validateSystemOperational(CallType.ADMIN);
		Map<String, Object> input = dfe.getArgument("apiKeyGroup");
		CreateApiKeyGroupDto createDto = InputConversion.convert(input, CreateApiKeyGroupDto.class, "apiKeyGroup");
		authorizeOrgAdmin(ud, createDto.getOrg());
		return ApiKeyGroupData.toWebDto(apiKeyGroupService.createApiKeyGroup(createDto, WhoUpdated.getWhoUpdated(ud)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateApiKeyGroup")
	public ApiKeyGroupWebDto updateApiKeyGroup(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2: the system state is answered before the group is looked up, so a sealed or
		// unlicensed instance cannot tell an existing group uuid from an unknown one
		authorizationService.validateSystemOperational(CallType.ADMIN);
		Map<String, Object> input = dfe.getArgument("apiKeyGroup");
		UpdateApiKeyGroupDto dto = InputConversion.convert(input, UpdateApiKeyGroupDto.class, "apiKeyGroup");
		ApiKeyGroupData akgd = authorizedGroup(ud, dto.getGroupId());

		if (null != dto.getPermissions()) {
			for (var p : dto.getPermissions()) {
				if (null != p) authorizationService.assertGrantObjectExists(akgd.getOrg(), p.getScope(), p.getObjectId());
			}
		}
		var approvals = dto.getApprovals();
		if (null != approvals && !approvals.isEmpty()) {
			OrganizationData od = getOrganizationService.getOrganizationData(akgd.getOrg()).orElseThrow();
			if (!Utils.isSanitizedApprovalsSent(approvals, od)) {
				throw new RuntimeException("Invalid approvals sent");
			}
		}
		return ApiKeyGroupData.toWebDto(apiKeyGroupService.updateApiKeyGroup(dto, WhoUpdated.getWhoUpdated(ud)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteApiKeyGroup")
	public ApiKeyGroupWebDto deleteApiKeyGroup(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2: the system state is answered before the group is looked up, so a sealed or
		// unlicensed instance cannot tell an existing group uuid from an unknown one
		authorizationService.validateSystemOperational(CallType.ADMIN);
		UUID groupUuid = InputConversion.convert(dfe.getArgument("groupUuid"), UUID.class, "groupUuid");
		ApiKeyGroupData akgd = authorizedGroup(ud, groupUuid);
		return ApiKeyGroupData.toWebDto(apiKeyGroupService.deactivateApiKeyGroup(akgd.getUuid(), WhoUpdated.getWhoUpdated(ud)));
	}

	/** Member keys by reference; a uuid that no longer resolves (a deleted key) is skipped. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "ApiKeyGroupWebDto", field = "apiKeyDetails")
	public List<ApiKeyRef> apiKeyDetails(DgsDataFetchingEnvironment dfe) {
		ApiKeyGroupWebDto group = dfe.getSource();
		List<ApiKeyRef> out = new ArrayList<>();
		if (null == group.getApiKeys()) return out;
		for (UUID keyUuid : group.getApiKeys()) {
			Optional<ApiKey> oak = apiKeyService.getApiKey(keyUuid);
			if (oak.isEmpty() || !oak.get().getOrg().equals(group.getOrg())) continue;
			ApiKey ak = oak.get();
			ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
			out.add(new ApiKeyRef(ak.getUuid(), ak.getKeyOrder(), ak.getObjectType(), akd.getStatus(),
					akd.getNotes(), akd.getDeclaredName()));
		}
		return out;
	}
}
