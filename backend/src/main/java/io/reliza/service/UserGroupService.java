/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.UserGroup;
import io.reliza.model.UserGroupData;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.UserGroupRepository;

@Service
@Transactional
public class UserGroupService {
	
	@Autowired
	private UserGroupRepository userGroupRepository;
	
	@Autowired
	private AuditService auditService;
	
	
	public Optional<UserGroup> getUserGroup(UUID groupUuid) {
		return userGroupRepository.findById(groupUuid);
	}

	public Optional<UserGroupData> getUserGroupData(UUID groupUuid) {
		Optional<UserGroupData> userGroupData = Optional.empty();
		Optional<UserGroup> userGroup = getUserGroup(groupUuid);
		if (userGroup.isPresent()) {
			userGroupData = Optional.of(UserGroupData.dataFromRecord(userGroup.get()));
		}
		return userGroupData;
	}

	/**
	 * Internal save method with audit logging and revision increment
	 */
	private UserGroup saveUserGroup(UserGroup ug, Map<String, Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("UserGroup must have record data");
		}
		
		Optional<UserGroup> existingGroup = userGroupRepository.findById(ug.getUuid());
		if (existingGroup.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.USER_GROUPS, ug);
			ug.setRevision(ug.getRevision() + 1);
			ug.setLastUpdatedDate(ZonedDateTime.now());
		}
		
		ug.setRecordData(recordData);
		ug = (UserGroup) WhoUpdated.injectWhoUpdatedData(ug, wu);
		return userGroupRepository.save(ug);
	}
	
	/**
	 * Creates a new user group
	 */
	public UserGroupData createUserGroup(CreateUserGroupDto createDto, WhoUpdated wu) {
		UserGroupData ugd = UserGroupData.userGroupDataFactory(createDto);
		UserGroup ug = new UserGroup();
		Map<String, Object> recordData = Utils.OM.convertValue(ugd, new TypeReference<Map<String, Object>>() {});
		var savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
	/**
	 * Comprehensive update method that handles all user group fields including permissions replacement
	 */
	public UserGroupData updateUserGroupComprehensive(UpdateUserGroupDto updateUserGroupDto, WhoUpdated wu) {
		Optional<UserGroup> existingGroup = userGroupRepository.findById(updateUserGroupDto.getGroupId());
		if (existingGroup.isEmpty()) {
			throw new IllegalArgumentException("User group not found: " + updateUserGroupDto.getGroupId());
		}
		
		UserGroup ug = existingGroup.get();
		UserGroupData ugd = UserGroupData.dataFromRecord(ug);
		
		UserGroupData updatedUgd = UserGroupData.updateUserGroupData(ugd, updateUserGroupDto);
		
		Map<String, Object> recordData = Utils.OM.convertValue(updatedUgd, new TypeReference<Map<String, Object>>() {});
		UserGroup savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
	/**
	 * Gets all user groups for an organization
	 */
	@Transactional(readOnly = true)
	public List<UserGroupData> getUserGroupsByOrganization(UUID orgUuid) {
		List<UserGroup> groups = userGroupRepository.findByOrganization(orgUuid.toString());
		return groups.stream()
				.map(UserGroupData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Gets all active user groups for an organization
	 */
	@Transactional(readOnly = true)
	public List<UserGroupData> getActiveUserGroupsByOrganization(UUID orgUuid) {
		List<UserGroup> groups = userGroupRepository.findByOrganization(orgUuid.toString());
		return groups.stream()
				.map(UserGroupData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Gets all user groups that contain a specific user
	 */
	@Transactional(readOnly = true)
	public List<UserGroupData> getUserGroupsByUserAndOrg(UUID userUuid, UUID orgUuid) {
		List<UserGroup> groups = userGroupRepository.findByUserAndOrg(userUuid.toString(), orgUuid.toString());
		return groups.stream()
				.map(UserGroupData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Adds a user to a user group
	 */
	public UserGroupData addUserToGroup(UUID groupUuid, UUID userUuid, WhoUpdated wu) {
		Optional<UserGroup> existingGroup = userGroupRepository.findById(groupUuid);
		if (existingGroup.isEmpty()) {
			throw new IllegalArgumentException("User group not found: " + groupUuid);
		}
		
		UserGroup ug = existingGroup.get();
		UserGroupData ugd = UserGroupData.dataFromRecord(ug);
		
		// Add user to the group
		ugd.addUser(userUuid);
		
		Map<String, Object> recordData = Utils.OM.convertValue(ugd, new TypeReference<Map<String, Object>>() {});
		UserGroup savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
	/**
	 * Removes a user from all user groups in an organization
	 */
	public void removeUserFromAllGroupsInOrg(UUID userUuid, UUID orgUuid, WhoUpdated wu) {
		List<UserGroupData> userGroups = getUserGroupsByUserAndOrg(userUuid, orgUuid);
		for (UserGroupData group : userGroups) {
			removeUserFromGroup(group.getUuid(), userUuid, wu);
		}
	}
	
	/**
	 * Removes a user from a user group
	 */
	public UserGroupData removeUserFromGroup(UUID groupUuid, UUID userUuid, WhoUpdated wu) {
		Optional<UserGroup> existingGroup = userGroupRepository.findById(groupUuid);
		if (existingGroup.isEmpty()) {
			throw new IllegalArgumentException("User group not found: " + groupUuid);
		}
		
		UserGroup ug = existingGroup.get();
		UserGroupData ugd = UserGroupData.dataFromRecord(ug);
		
		// Remove user from the group
		boolean wasRemoved = ugd.removeUser(userUuid);
		if (!wasRemoved) {
			// User was not in the group, but this is not an error condition
			return ugd;
		}
		
		Map<String, Object> recordData = Utils.OM.convertValue(ugd, new TypeReference<Map<String, Object>>() {});
		UserGroup savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
}
