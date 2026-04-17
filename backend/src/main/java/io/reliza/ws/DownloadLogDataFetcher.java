/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.DownloadLogData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.AuthorizationService;
import io.reliza.service.DownloadLogService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

@DgsComponent
public class DownloadLogDataFetcher {

	@Autowired
	private UserService userService;

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private DownloadLogService downloadLogService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "listDownloadLogs")
	public List<DownloadLogData> listDownloadLogs(
			@InputArgument("org") UUID orgUuid,
			@InputArgument("fromDate") ZonedDateTime fromDate,
			@InputArgument("toDate") ZonedDateTime toDate,
			@InputArgument("perspective") UUID perspectiveUuid) throws Exception {

		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);

		var od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ADMIN);

		return downloadLogService.listDownloadLogs(orgUuid, fromDate, toDate, perspectiveUuid);
	}
}
