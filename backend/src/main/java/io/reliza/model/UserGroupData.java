/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserGroupData extends RelizaDataParent implements RelizaObject {
	
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org; // group belongs to a single organization
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions = new Permissions();
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private UserGroupStatus status = UserGroupStatus.ACTIVE;
	@JsonProperty(CommonVariables.USERS_FIELD)
	private Set<UUID> users = new LinkedHashSet<>(); // SSO-managed users
	@JsonProperty("manualUsers")
	private Set<UUID> manualUsers = new LinkedHashSet<>(); // manually added users (not managed by SSO sync)
	@JsonProperty("connectedSsoGroups")
	private Set<String> connectedSsoGroups = new LinkedHashSet<>(); // SSO groups connected to this user group
	@JsonProperty
	private UUID resourceGroup = CommonVariables.DEFAULT_RESOURCE_GROUP;

	private UserGroupData() {}
	
	public UUID getUuid() {
		return uuid;
	}
	
	private void setUuid(UUID uuid) {
		this.uuid = uuid;
	}
	
	public String getName() {
		return name;
	}
	
	private void setName(String name) {
		this.name = name;
	}
	
	public String getDescription() {
		return description;
	}
	
	private void setDescription(String description) {
		this.description = description;
	}
	
	@Override
	public UUID getOrg() {
		return org;
	}
	
	private void setOrg(UUID orgParam) {
		this.org = orgParam;
	}
	
	public Set<UUID> getUsers() {
		return new LinkedHashSet<>(users);
	}
	
	private void setUsers(Collection<UUID> users) {
		this.users = new LinkedHashSet<>(users);
	}
	
	public void addUser(UUID userUuid) {
		this.users.add(userUuid);
	}
	
	public boolean removeUser(UUID userUuid) {
		return this.users.remove(userUuid);
	}
	
	public boolean hasUser(UUID userUuid) {
		return this.users.contains(userUuid) || this.manualUsers.contains(userUuid);
	}
	
	public Set<UUID> getManualUsers() {
		return new LinkedHashSet<>(manualUsers);
	}
	
	private void setManualUsers(Collection<UUID> manualUsers) {
		this.manualUsers = new LinkedHashSet<>(manualUsers);
	}
	
	public void addManualUser(UUID userUuid) {
		this.manualUsers.add(userUuid);
	}
	
	public boolean removeManualUser(UUID userUuid) {
		return this.manualUsers.remove(userUuid);
	}
	
	@JsonIgnore
	public Set<UUID> getAllUsers() {
		Set<UUID> allUsers = new LinkedHashSet<>(users);
		allUsers.addAll(manualUsers);
		return allUsers;
	}
	
	public Set<String> getConnectedSsoGroups() {
		return new LinkedHashSet<>(connectedSsoGroups);
	}
	
	private void setConnectedSsoGroups(Collection<String> connectedSsoGroups) {
		this.connectedSsoGroups = new LinkedHashSet<>(connectedSsoGroups);
	}
	
	public boolean hasConnectedSsoGroup(String ssoGroup) {
		return this.connectedSsoGroups.contains(ssoGroup);
	}
	
	public void setPermission(PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
		permissions.setPermission(this.org, scope, objectUuid, type, approvals);
	}

	public void setPermission(PermissionScope scope, UUID objectUuid, PermissionType type,
			Collection<PermissionFunction> functions, Collection<String> approvals) {
		permissions.setPermission(this.org, scope, objectUuid, type, functions, approvals);
	}
	
	public boolean revokePermission(PermissionScope scope, UUID objectUuid) {
		return permissions.revokePermission(this.org, scope, objectUuid);
	}
	
	public boolean revokeAllOrgPermissions() {
		return permissions.revokeAllOrgPermissions(this.org);
	}
	
	public Optional<UserPermission> getPermission(PermissionScope scope, UUID objectUuid) {
		return permissions.getPermission(this.org, scope, objectUuid);
	}
	
	private Permissions getPermissions() {
		return permissions;
	}
	
	public Set<UserPermission> getOrgPermissions (UUID orgUuid) {
		return permissions.getOrgPermissionsAsSet(orgUuid);
	}
	
	private void setPermissions(Permissions permissions) {
		this.permissions = permissions;
	}

	public UserGroupStatus getStatus() {
		return status;
	}

	public void setStatus(UserGroupStatus status) {
		this.status = status;
	}
	
	public static UserGroupData updateUserGroupData(UserGroupData ugd, UpdateUserGroupDto updateUgd) {
		UserGroupData updatedUserGroupData = new UserGroupData();
		
		// Organization cannot be changed once set
		updatedUserGroupData.setOrg(ugd.getOrg());
		
		if (null != updateUgd.getName()) {
			updatedUserGroupData.setName(updateUgd.getName());
		} else {
			updatedUserGroupData.setName(ugd.getName());
		}
		
		if (null != updateUgd.getDescription()) {
			updatedUserGroupData.setDescription(updateUgd.getDescription());
		} else {
			updatedUserGroupData.setDescription(ugd.getDescription());
		}
		
		if (null != updateUgd.getUsers()) {
			updatedUserGroupData.setUsers(updateUgd.getUsers());
		} else {
			updatedUserGroupData.setUsers(ugd.getUsers());
		}
		
		if (null != updateUgd.getManualUsers()) {
			updatedUserGroupData.setManualUsers(updateUgd.getManualUsers());
		} else {
			updatedUserGroupData.setManualUsers(ugd.getManualUsers());
		}
		
		if (null != updateUgd.getPermissions()) {
			for (UserGroupPermissionDto permission : updateUgd.getPermissions()) {
				updatedUserGroupData.setPermission(
					permission.getScope(),
					permission.getObjectId(),
					permission.getType(),
					permission.getFunctions(),
					new LinkedList<>()
				);
			}
		} else {
			updatedUserGroupData.setPermissions(ugd.getPermissions());
		}
		
		if (null != updateUgd.getApprovals()) {
			Optional<UserPermission> orgWidePermission = updatedUserGroupData.getPermission(PermissionScope.ORGANIZATION, updatedUserGroupData.getOrg());
			if (orgWidePermission.isEmpty()) {
				updatedUserGroupData.setPermission(PermissionScope.ORGANIZATION, updatedUserGroupData.getOrg(), PermissionType.NONE, updateUgd.getApprovals());
			} else {
				PermissionType pt = orgWidePermission.get().getType();
				updatedUserGroupData.setPermission(PermissionScope.ORGANIZATION, updatedUserGroupData.getOrg(), pt, updateUgd.getApprovals());
			}
		} 
		
		if (null != updateUgd.getStatus()) {
			updatedUserGroupData.setStatus(updateUgd.getStatus());
		} else {
			updatedUserGroupData.setStatus(ugd.getStatus());
		}
		
		if (null != updateUgd.getConnectedSsoGroups()) {
			updatedUserGroupData.setConnectedSsoGroups(updateUgd.getConnectedSsoGroups());
		} else {
			updatedUserGroupData.setConnectedSsoGroups(ugd.getConnectedSsoGroups());
		}
		
		return updatedUserGroupData;
	}
	
	public static UserGroupData userGroupDataFactory(CreateUserGroupDto createDto) {
		UserGroupData ugd = new UserGroupData();
		ugd.setName(createDto.getName());
		ugd.setDescription(createDto.getDescription());
		ugd.setOrg(createDto.getOrg());
		return ugd;
	}
	
	public static UserGroupData dataFromRecord(UserGroup ug) {
		if (ug.getSchemaVersion() != 0) { // if schema version is not supported, throw exception
			throw new IllegalStateException("Service schema version is " + ug.getSchemaVersion() 
			+ ", which is not currently supported");
		}
		Map<String,Object> recordData = ug.getRecordData();
		UserGroupData ugd = Utils.OM.convertValue(recordData, UserGroupData.class);
		ugd.setUuid(ug.getUuid());
		return ugd;
	}
	
	public static io.reliza.model.dto.UserGroupWebDto toWebDto(UserGroupData ugd) {
		return io.reliza.model.dto.UserGroupWebDto.builder()
				.uuid(ugd.getUuid())
				.name(ugd.getName())
				.description(ugd.getDescription())
				.org(ugd.getOrg())
				.permissions(ugd.getPermissions())
				.status(ugd.getStatus())
				.users(ugd.getAllUsers())
				.manualUsers(ugd.getManualUsers())
				.connectedSsoGroups(ugd.getConnectedSsoGroups())
				.build();
	}

	@Override
	public UUID getResourceGroup() {
		return this.resourceGroup;
	}
}
