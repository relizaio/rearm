/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Collection;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserData.OrgUserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.dto.UserGroupWebDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserGroupService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class UserGroupDataFetcher {
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private UserGroupService userGroupService;
	
	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getUserGroups")
	public List<UserGroupWebDto> getUserGroupsByOrganization(@InputArgument("org") UUID org) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(org);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.ADMIN);
		List<UserGroupData> userGroups = userGroupService.getActiveUserGroupsByOrganization(org);
		return userGroups.stream()
				.map(UserGroupData::toWebDto)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createUserGroup")
	public UserGroupWebDto createUserGroup(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);

		Map<String, Object> userGroupInputMap = dfe.getArgument("userGroup");
		CreateUserGroupDto createDto = Utils.OM.convertValue(userGroupInputMap, CreateUserGroupDto.class);

		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(createDto.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return UserGroupData.toWebDto(userGroupService.createUserGroup(createDto, wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateUserGroup")
	public UserGroupWebDto updateUserGroup(DgsDataFetchingEnvironment dfe) {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Map<String, Object> userGroupInputMap = dfe.getArgument("userGroup");
		UpdateUserGroupDto userGroupInput = Utils.OM.convertValue(userGroupInputMap, UpdateUserGroupDto.class);
		UUID groupUuid = userGroupInput.getGroupId();

		Optional<UserGroupData> ugd = userGroupService.getUserGroupData(groupUuid);
		RelizaObject ro = ugd.isPresent() ? ugd.get() : null;

		authorizationService.isUserAuthorizedOrgWideGraphQLWithObject(oud.get(), ro, CallType.ADMIN);

		if (userGroupInput.getUsers() != null) {
			for (UUID userUuid : userGroupInput.getUsers()) {
				var optUserInGroup = userService.getUserData(userUuid);
				if (optUserInGroup.isEmpty() || !optUserInGroup.get().isInOrganization(ugd.get().getOrg())) {
					log.error("SECURITY: User " + userUuid + " is not in organization " + ugd.get().getOrg() + " - attempted to be included in the group " + groupUuid);
					throw new AccessDeniedException("You're trying to include unknown user to the group");
				}
			}
		}
		
		var approvals = userGroupInput.getApprovals();
		OrganizationData od = getOrganizationService.getOrganizationData(ugd.get().getOrg()).get();
		
		if (null != approvals && !approvals.isEmpty()) {
			if (!Utils.isSanitizedApprovalsSent(approvals, od)) {
				throw new RuntimeException("Invalid approvals sent");
			}
		}

		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		UserGroupData userGroupData = userGroupService.updateUserGroupComprehensive(userGroupInput, wu);
		
		return UserGroupData.toWebDto(userGroupData);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "UserGroupWebDto", field = "userDetails")
	public List<OrgUserData> getUserDetails(DgsDataFetchingEnvironment dfe) {
		UserGroupWebDto userGroup = dfe.getSource();
		
		if (userGroup.getUsers() == null || userGroup.getUsers().isEmpty()) {
			return new LinkedList<>();
		}
		
		List<OrgUserData> userDetails = new LinkedList<>();
		
		for (UUID userUuid : userGroup.getUsers()) {
			Optional<UserData> userData = userService.getUserData(userUuid);
			if (userData.isPresent()) {
				OrgUserData orgUserData = UserData.convertUserDataToOrgUserData(userData.get(), userGroup.getOrg());
				if (orgUserData != null) {
					userDetails.add(orgUserData);
				}
			}
		}
		
		return userDetails;
	}
}
