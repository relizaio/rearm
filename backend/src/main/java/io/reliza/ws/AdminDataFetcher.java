/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserData;
import io.reliza.service.AuthorizationService;
import io.reliza.service.IntegrationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import io.reliza.service.SystemInfoService.SystemInfoDto;
import io.reliza.service.SystemInfoService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class AdminDataFetcher {
	
	@Autowired
	AuthorizationService authorizationService;
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired
	IntegrationService integrationService;
	
	@Autowired
	UserService userService;

	@Autowired
	SystemInfoService systemInfoService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "unSealSystem")
	public Boolean unSealSystem(DgsDataFetchingEnvironment dfe,
			@InputArgument("secret") String secret) throws RuntimeException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		try {
			UserData ud = userService.resolveUser(auth);
			systemInfoService.unSealSystem(secret, ud);
			return true;
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSystemInfoIsSet")
	public SystemInfoDto getSystemInfoIsSet() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.authorize(oud.get(), CallType.GLOBAL_ADMIN);
		return systemInfoService.getSystemInfoIsSet();
	}
}