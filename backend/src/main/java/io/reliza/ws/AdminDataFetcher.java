/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;


import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.ReleaseFinalizerService;
import io.reliza.service.UserService;
import io.reliza.service.SystemInfoService.SystemInfoDto;
import io.reliza.service.SystemInfoService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class AdminDataFetcher {
	
	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private UserService userService;

	@Autowired
	private SystemInfoService systemInfoService;

	@Autowired
	private ReleaseFinalizerService releaseFinalizerService;

	@Autowired
	private LicenseStatus licenseStatus;

	@Autowired
	private io.reliza.service.EmailService emailService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getSystemInfoIsSet")
	public SystemInfoDto getSystemInfoIsSet() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.authorize(oud.get(), CallType.GLOBAL_ADMIN);
		return systemInfoService.getSystemInfoIsSet();
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "finalizeAllReleases")
	public Boolean finalizeAllReleases() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.authorize(oud.get(), CallType.GLOBAL_ADMIN);
		releaseFinalizerService.finalizeAllReleases();
		return true;
	}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "inactivateUser")
	public UserData inactivateUser(@InputArgument("userUuid") UUID userUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID defaultOrg = systemInfoService.getDefaultOrg();
		if (defaultOrg != null) {
			var orgo = getOrganizationService.getOrganizationData(defaultOrg);
			RelizaObject oo = orgo.isPresent() ? orgo.get() : null;
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, defaultOrg, List.of(oo), CallType.ADMIN);
		} else {
			authorizationService.authorize(oud.get(), CallType.GLOBAL_ADMIN);
		}
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		UserData result = userService.softDeleteUser(userUuid, wu);
		if (result == null) throw new RuntimeException("User not found");
		return result;
	}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "reactivateUser")
	public UserData reactivateUser(@InputArgument("userUuid") UUID userUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID defaultOrg = systemInfoService.getDefaultOrg();
		if (defaultOrg != null) {
			var orgo = getOrganizationService.getOrganizationData(defaultOrg);
			RelizaObject oo = orgo.isPresent() ? orgo.get() : null;
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, defaultOrg, List.of(oo), CallType.ADMIN);
		} else {
			authorizationService.authorize(oud.get(), CallType.GLOBAL_ADMIN);
		}
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		UserData result = userService.reactivateUser(userUuid, wu);
		if (result == null) throw new RuntimeException("User not found");
		return result;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "unSealSystem")
	public Boolean unSealSystem(DgsDataFetchingEnvironment dfe,
			@InputArgument("secret") String secret) throws RuntimeException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		try {
			UserData ud = userService.resolveUser(auth);
			systemInfoService.unSealSystem(secret, ud);
			licenseStatus.setSystemSealed(false);
			return true;
		} catch (RelizaException re) {
			throw new AccessDeniedException(re.getMessage());
		}
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "sendTestEmail")
	public Boolean sendTestEmail() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.authorize(oud.get(), CallType.GLOBAL_ADMIN);
		
		String email = oud.get().getEmail();
		String subject = "Test Email from ReARM";
		String content = "This is a test email sent from ReARM to verify your email configuration. If you received this email, your email settings are working correctly.";
		
		return emailService.sendEmail(java.util.List.of(email), subject, "text/html", content);
	}
}