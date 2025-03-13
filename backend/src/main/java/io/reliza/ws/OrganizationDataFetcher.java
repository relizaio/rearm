/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserData.OrgUserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.ApiKeyForUserDto;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ResourceGroupService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OrganizationDataFetcher {
	
	@Autowired
	ApiKeyService apiKeyService;

	@Autowired
	ApiKeyAccessService apiKeyAccessService;
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	ResourceGroupService resourceGroupService;
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "organizations")
	public Iterable<OrganizationData> getOrganizations() {
		Collection<OrganizationData> orgs = new LinkedList<>();
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		if (oud.isPresent()) {
			orgs = organizationService.listMyOrganizationData(oud.get());
		}
		return orgs; 
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "users")
	public List<OrgUserData> getUsers(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = organizationService.getOrganizationData(orgUuid);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return userService.listOrgUserDataByOrg(od.get().getUuid());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "resourceGroups")
	public List<ResourceGroupData> getApplications(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return resourceGroupService.listResourceGroupDataOfOrg(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "totalsAnalytics")
	public Map<String,BigInteger> getTotalsAnalytics(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.READ);
		return organizationService.getNumericAnalytics(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "apiKeys")
	public List<ApiKeyDto> getApiKeys(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.ADMIN);
		Optional<OrganizationData> od = organizationService.getOrganizationData(orgUuid);
		return apiKeyService.listApiKeyDtoByOrgWithLastAccessDate(od.get().getUuid());
	}
	
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "defaultApprovalRoles")
	public void getDefaultApprovalRoles() {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addApprovalRole")
	public void addApprovalRole(DgsDataFetchingEnvironment dfe,
			@InputArgument("orgUuid") UUID orgUuid
		) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteApprovalRole")
	public OrganizationData archiveApprovalRole(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("approvalRoleId") String approvalRoleId
		) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setOrgApiKey")
	public ApiKeyForUserDto setOrgApiKey(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("apiType") ApiTypeEnum keyType,
			@InputArgument("keyOrder") String keyOrder,
			@InputArgument("notes") String notes
		) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		Optional<OrganizationData> od = organizationService.getOrganizationData(orgUuid);
		String apiKey = null;
		String keyId = null;
		if (keyType == ApiTypeEnum.APPROVAL) {
			UUID approvalUuid = UUID.randomUUID();
			apiKey = apiKeyService.setObjectApiKey(approvalUuid, ApiTypeEnum.APPROVAL, od.get().getUuid(), null, notes, wu);
			keyId = keyType.toString() + "__" + approvalUuid.toString();
		} else {
			apiKey = apiKeyService.setObjectApiKey(od.get().getUuid(), keyType, od.get().getUuid(), keyOrder, notes,  wu);
			keyId = keyType.toString() + "__" + od.get().getUuid().toString();
			if (StringUtils.isNotEmpty(keyOrder)) {
				keyId += "__ord__" + keyOrder;
			}
		}
		
		ApiKeyForUserDto retKey = ApiKeyForUserDto.builder()
				.apiKey(apiKey)
				.id(keyId)
				.authorizationHeader("Basic " + HttpHeaders.encodeBasicAuth(keyId, apiKey, StandardCharsets.UTF_8))
				.build();

		return retKey;
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteApiKey")
	public Boolean setOrgApiKey(@InputArgument("apiKeyUuid") String apiKeyUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		var oakd = apiKeyService.getApiKeyData(apiKeyUuid);
		RelizaObject ro = oakd.isPresent() ? oakd.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		apiKeyService.deleteApiKey(apiKeyUuid, wu);
		return true;
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateUserPermissions")
	public OrgUserData updateUserPermissions(DgsDataFetchingEnvironment dfe,
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("userUuid") String userUuidStr,
			@InputArgument("permissionType") PermissionType permissionType,
			@InputArgument("permissions") List<LinkedHashMap<String, Object>> permissions,
			@InputArgument("approvals") List<String> approvals) {
		
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		authorizationService.isUserAuthorizedOrgWideGraphQL(oud.get(), orgUuid, CallType.ADMIN);
		

		UUID userUuid = UUID.fromString(userUuidStr);

		if (null != approvals && !approvals.isEmpty()) {
			throw new RuntimeException("Currently not part of ReARM CE");
		}

		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());		
		OrgUserData retUserData = null;
		try {
			List<PermissionDto> convertedPermissions = permissions.stream()
					.map(p -> Utils.OM.convertValue(p, PermissionDto.class)).collect(Collectors.toList());
			UserData ud = userService.setUserPermissions(userUuid, orgUuid, approvals, Optional.of(permissionType), convertedPermissions, wu);
			retUserData = UserData.convertUserDataToOrgUserData(ud, orgUuid);
		} catch (RelizaException re) {
			throw new RuntimeException(re.getMessage());
		}
		return retUserData;
	}
	
	@Transactional
	@DgsData(parentType = "Mutation", field = "setApprovalsOnApiKey")
	public ApiKeyDto setApprovalsOnApiKey(
			@InputArgument("apiKeyUuid") UUID apiKeyUuid,
			@InputArgument("approvals") List<String> approvals,
			@InputArgument("notes") String notes
		) {
		throw new RuntimeException("Currently not part of ReARM CE");
	}
}