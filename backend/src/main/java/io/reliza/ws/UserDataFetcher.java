/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.dto.ApiKeyForUserDto;
import io.reliza.model.dto.UserWebDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.SystemInfoService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class UserDataFetcher {
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private ApiKeyService apiKeyService;

	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private OrganizationService organizationService;
	
	@Autowired
	private SystemInfoService systemInfoService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "acceptUserPolicies")
	public UserWebDto acceptUserPolicies (
			@InputArgument("tosAccepted") Boolean acceptPolicies, 
			@InputArgument("marketingAccepted") Boolean acceptMarketing,
			@InputArgument("email") String email
		) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		User retU = null;
		try {
			UserData ud = userService.resolveUser(auth);
		    retU = userService.parsePostSignupData(ud.getUuid(), acceptPolicies, acceptMarketing, email);
		} catch (RelizaException re) {
			log.error("Error on accepting user policies", re);
		    throw new RuntimeException(re.getMessage());
		} catch (UnsupportedEncodingException uee) {
			log.error("Error with encoding on accepting user policies", uee);
			throw new RuntimeException(uee.getMessage());
		}
		return UserData.toWebDto(UserData.dataFromRecord(retU));
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateUserName")
	public UserWebDto acceptUserPolicies (@InputArgument("name") String name) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		try {
			UserData ud = userService.resolveUser(auth);
			WhoUpdated wu = WhoUpdated.getWhoUpdated(ud);
		    return userService.updateUserName(ud, name, wu);
		} catch (RelizaException re) {
			log.error("Error updating user name", re);
		    throw new RuntimeException(re.getMessage());
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setUserOrgApiKey")
	public ApiKeyForUserDto setUserOrgApiKey(@InputArgument("orgUuid") UUID orgUuid) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.READ);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		String apiKey = apiKeyService.setObjectApiKey(oud.get().getUuid(), ApiTypeEnum.USER, ood.get().getUuid(), null, null, wu);
		String apiId = ApiTypeEnum.USER.toString() + "__" + oud.get().getUuid().toString() +
				"__" + ApiTypeEnum.ORGANIZATION.toString() + "__" + ood.get().getUuid().toString();
		
		ApiKeyForUserDto retKey = ApiKeyForUserDto.builder()
				.apiKey(apiKey)
				.id(apiId)
				.authorizationHeader("Basic " + HttpHeaders.encodeBasicAuth(apiId, apiKey, StandardCharsets.UTF_8))
				.build();
		
		return retKey;
	}
	
	@PreAuthorize("permitAll()")
	@DgsData(parentType = "Query", field = "healthCheck")
	public String getHealthCheck() {
		log.trace("in healthcheck query");
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		if (ood.isPresent() && ood.get().getUuid().equals(CommonVariables.EXTERNAL_PROJ_ORG_UUID)) {
			return "OK";
		} else {
			throw new RuntimeException("HealthCheck Failed");
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "user")
	public UserWebDto user() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		try {
			UserData ud = userService.resolveUser(auth);
			ud.getOrganizations().forEach(org -> {
				var oup = organizationService.obtainUserOrgPermission(ud, org);
				if (oup.isPresent()) ud.setPermission(oup.get().getOrg(), PermissionScope.ORGANIZATION, oup.get().getOrg(), oup.get().getType(), oup.get().getApprovals());
			});
			
			var udWebDto = UserData.toWebDto(ud);
			InstallationType systemInstallationType = userService.getInstallationType();
			if(systemInfoService.isSystemSealed()){
				udWebDto.setSystemSealed(true);
			}
			udWebDto.setInstallationType(systemInstallationType.toString());
			return udWebDto;
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
	}
		
}
