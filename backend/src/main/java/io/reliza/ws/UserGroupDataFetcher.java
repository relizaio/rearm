/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
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
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
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
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, org, List.of(ro), CallType.ADMIN);
		List<UserGroupData> userGroups = userGroupService.getAllUserGroupsByOrganization(org);
		return userGroups.stream()
				.map(UserGroupData::toWebDto)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createUserGroup")
	public UserGroupWebDto createUserGroup(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);

		Map<String, Object> userGroupInputMap = dfe.getArgument("userGroup");
		CreateUserGroupDto createDto = Utils.OM.convertValue(userGroupInputMap, CreateUserGroupDto.class);

		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(createDto.getOrg());
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, createDto.getOrg(), List.of(ro), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return UserGroupData.toWebDto(userGroupService.createUserGroup(createDto, wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateUserGroup")
	public UserGroupWebDto updateUserGroup(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		Map<String, Object> userGroupInputMap = dfe.getArgument("userGroup");
		UpdateUserGroupDto userGroupInput = Utils.OM.convertValue(userGroupInputMap, UpdateUserGroupDto.class);
		UUID groupUuid = userGroupInput.getGroupId();

		Optional<UserGroupData> ugd = userGroupService.getUserGroupData(groupUuid);
		RelizaObject ro = ugd.isPresent() ? ugd.get() : null;

		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, ro != null ? ro.getOrg() : null, List.of(ro), CallType.ADMIN);

		validateUsersInOrganization(userGroupInput.getUsers(), ugd.get().getOrg(), groupUuid);
		validateUsersInOrganization(userGroupInput.getManualUsers(), ugd.get().getOrg(), groupUuid);
		
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

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "UserGroupWebDto", field = "manualUserDetails")
	public List<OrgUserData> getManualUserDetails(DgsDataFetchingEnvironment dfe) {
		UserGroupWebDto userGroup = dfe.getSource();
		
		if (userGroup.getManualUsers() == null || userGroup.getManualUsers().isEmpty()) {
			return new LinkedList<>();
		}
		
		List<OrgUserData> userDetails = new LinkedList<>();
		
		for (UUID userUuid : userGroup.getManualUsers()) {
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

	private void validateUsersInOrganization(java.util.Collection<UUID> userUuids, UUID orgUuid, UUID groupUuid) {
		if (userUuids == null) return;
		for (UUID userUuid : userUuids) {
			var optUserInGroup = userService.getUserData(userUuid);
			if (optUserInGroup.isEmpty() || !optUserInGroup.get().isInOrganization(orgUuid)) {
				log.error("SECURITY: User " + userUuid + " is not in organization " + orgUuid + " - attempted to be included in the group " + groupUuid);
				throw new AccessDeniedException("You're trying to include unknown user to the group");
			}
		}
	}
}
