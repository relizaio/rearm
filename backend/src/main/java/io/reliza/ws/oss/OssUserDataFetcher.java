/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.oss;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.UserWebDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import io.reliza.service.SystemInfoService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OssUserDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	UserService userService;
	
	@Autowired
	ApiKeyService apiKeyService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	SystemInfoService systemInfoService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "user")
	public UserWebDto user() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		try {
			UserData ud = userService.resolveUser(auth);
			var udWebDto = UserData.toWebDto(ud);
			boolean adminForExternalOrg = false;
			InstallationType systemInstallationType = userService.getInstallationType();
			if (InstallationType.OSS != systemInstallationType && ud.isGlobalAdmin()) adminForExternalOrg = true;
			if (adminForExternalOrg) {
				var permissions = udWebDto.getPermissions();
				permissions.setPermission(CommonVariables.EXTERNAL_PROJ_ORG_UUID, PermissionScope.ORGANIZATION,
						CommonVariables.EXTERNAL_PROJ_ORG_UUID, PermissionType.ADMIN, null);
				udWebDto.setPermissions(permissions);
			}
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
