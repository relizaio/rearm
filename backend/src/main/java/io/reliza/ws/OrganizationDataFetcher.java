/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.UserData;
import io.reliza.model.UserData.OrgUserData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.ApiKeyForUserDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ResourceGroupService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OrganizationDataFetcher {
	
	@Autowired
	private ApiKeyService apiKeyService;
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private OrganizationService organizationService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private ResourceGroupService resourceGroupService;
	
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
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roUsers = od.isPresent() ? od.get() : null;
		InstallationType systemInstallationType = userService.getInstallationType();
		CallType ct = CallType.READ;
		if (InstallationType.DEMO == systemInstallationType) {
			ct = CallType.ADMIN;
		}
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roUsers), ct);
		return userService.listOrgUserDataByOrg(od.get().getUuid());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "combinedUserOrgPermissions")
	public Permissions getCombinedUserOrgPermissions(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("userUuid") UUID userUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roUsers = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roUsers), CallType.ADMIN);
		var targetUser = userService.getUserDataWithOrg(userUuid, orgUuid)
				.orElseThrow(() -> new AccessDeniedException("User is not in organization"));
		return organizationService.obtainCombinedUserOrgPermissions(targetUser, orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "resourceGroups")
	public List<ResourceGroupData> getApplications(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		var odRg = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roRg = odRg.isPresent() ? odRg.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roRg), CallType.READ);
		return resourceGroupService.listResourceGroupDataOfOrg(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "totalsAnalytics")
	public Map<String,BigInteger> getTotalsAnalytics(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		var odTotals = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roTotals = odTotals.isPresent() ? odTotals.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roTotals), CallType.READ);
		return organizationService.getNumericAnalytics(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "apiKeys")
	public List<ApiKeyDto> getApiKeys(@InputArgument("orgUuid") String orgUuidStr) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roApiKeys = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roApiKeys), CallType.ADMIN);
		return apiKeyService.listApiKeyDtoByOrgWithLastAccessDate(od.get().getUuid());
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
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSetKey = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSetKey), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
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
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		apiKeyService.deleteApiKey(apiKeyUuid, wu);
		return true;
	}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "removeUser")
	public Boolean removeUser (@InputArgument("org") UUID org, @InputArgument("user") UUID user) {
		Optional<UserData> oud = Optional.empty();
		try {
			JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
			oud = userService.getUserDataByAuth(auth);
			var odRemove = getOrganizationService.getOrganizationData(org);
			RelizaObject roRemove = odRemove.isPresent() ? odRemove.get() : null;
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, org, List.of(roRemove), CallType.ADMIN);
			WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
			userService.removeUserFromOrg(org, user, wu);
		} catch (Exception e) {
			throw new AccessDeniedException("Error removing user from organization, please contact support at info@reliza.io");
		}
		return true;
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateOrganizationTerminology")
	public OrganizationData updateOrganizationTerminology(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("terminology") Map<String, Object> terminology) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var odTerm = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roTerm = odTerm.isPresent() ? odTerm.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roTerm), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		String featureSetLabel = terminology != null ? (String) terminology.get("featureSetLabel") : null;
		return organizationService.updateTerminology(orgUuid, featureSetLabel, wu);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateOrganizationIgnoreViolation")
	public OrganizationData updateOrganizationIgnoreViolation(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("ignoreViolation") Map<String, Object> ignoreViolation) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var odIgnore = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roIgnore = odIgnore.isPresent() ? odIgnore.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roIgnore), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		@SuppressWarnings("unchecked")
		List<String> licenseViolationRegexIgnore = ignoreViolation != null ? 
				(List<String>) ignoreViolation.get("licenseViolationRegexIgnore") : null;
		@SuppressWarnings("unchecked")
		List<String> securityViolationRegexIgnore = ignoreViolation != null ? 
				(List<String>) ignoreViolation.get("securityViolationRegexIgnore") : null;
		@SuppressWarnings("unchecked")
		List<String> operationalViolationRegexIgnore = ignoreViolation != null ? 
				(List<String>) ignoreViolation.get("operationalViolationRegexIgnore") : null;
		
		// Validate regex patterns
		validateRegexPatterns(licenseViolationRegexIgnore, "licenseViolationRegexIgnore");
		validateRegexPatterns(securityViolationRegexIgnore, "securityViolationRegexIgnore");
		validateRegexPatterns(operationalViolationRegexIgnore, "operationalViolationRegexIgnore");
		
		return organizationService.updateIgnoreViolation(orgUuid, licenseViolationRegexIgnore, 
				securityViolationRegexIgnore, operationalViolationRegexIgnore, wu);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateOrganizationSettings")
	public OrganizationData updateOrganizationSettings(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("settings") Map<String, Object> settings) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		var odSettings = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSettings = odSettings.isPresent() ? odSettings.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSettings), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		Boolean justificationMandatory = settings != null ? (Boolean) settings.get("justificationMandatory") : null;
		return organizationService.updateSettings(orgUuid, justificationMandatory, wu);
	}
	
	private void validateRegexPatterns(List<String> patterns, String fieldName) throws RelizaException {
		if (patterns == null) {
			return;
		}
		for (String pattern : patterns) {
			try {
				java.util.regex.Pattern.compile(pattern);
			} catch (java.util.regex.PatternSyntaxException e) {
				throw new RelizaException("Invalid regex pattern in " + fieldName + ": " + pattern + " - " + e.getMessage());
			}
		}
	}
	
}