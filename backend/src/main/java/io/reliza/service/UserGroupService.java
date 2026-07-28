/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.TeamRole;
import io.reliza.model.UserData;
import io.reliza.model.UserGroup;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserGroupData.ExternalTeamMember;
import io.reliza.model.UserGroupData.TeamMemberRole;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;
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

	@Autowired
	@Lazy
	private OrganizationService organizationService;

	@Autowired
	@Lazy
	private UserService userService;
	
	
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
	 * Creates a new user group.
	 * Validates name uniqueness across all groups (active and inactive) in the org.
	 */
	public UserGroupData createUserGroup(CreateUserGroupDto createDto, WhoUpdated wu) throws RelizaException {
		List<UserGroupData> allGroups = getAllUserGroupsByOrganization(createDto.getOrg());
		
		// Check name uniqueness across all groups
		Optional<UserGroupData> nameConflict = allGroups.stream()
				.filter(g -> g.getName().equals(createDto.getName()))
				.findFirst();
		if (nameConflict.isPresent()) {
			if (nameConflict.get().getStatus() == UserGroupStatus.INACTIVE) {
				throw new RelizaException(
					"An inactive group with the name '" + createDto.getName() + "' already exists. "
					+ "Please restore it from the inactive groups list instead of creating a new one.");
			} else {
				throw new RelizaException(
					"A group with the name '" + createDto.getName() + "' already exists.");
			}
		}
		
		UserGroupData ugd = UserGroupData.userGroupDataFactory(createDto);
		UserGroup ug = new UserGroup();
		Map<String, Object> recordData = Utils.OM.convertValue(ugd, new TypeReference<Map<String, Object>>() {});
		var savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
	/**
	 * Comprehensive update method that handles all user group fields including permissions replacement.
	 * Validates name uniqueness on rename and name conflicts on restore.
	 */
	public UserGroupData updateUserGroupComprehensive(UpdateUserGroupDto updateUserGroupDto, WhoUpdated wu) throws RelizaException {
		Optional<UserGroup> existingGroup = userGroupRepository.findById(updateUserGroupDto.getGroupId());
		if (existingGroup.isEmpty()) {
			throw new RelizaException("User group not found: " + updateUserGroupDto.getGroupId());
		}
		
		UserGroup ug = existingGroup.get();
		UserGroupData ugd = UserGroupData.dataFromRecord(ug);
		// Check name uniqueness on rename or restore
		String effectiveName = updateUserGroupDto.getName() != null ? updateUserGroupDto.getName() : ugd.getName();
		boolean nameChanging = !effectiveName.equals(ugd.getName());
		boolean restoring = updateUserGroupDto.getStatus() == UserGroupStatus.ACTIVE 
				&& ugd.getStatus() == UserGroupStatus.INACTIVE;
		if (nameChanging || restoring) {
			List<UserGroupData> allGroups = getAllUserGroupsByOrganization(ugd.getOrg());
			Optional<UserGroupData> nameConflict = allGroups.stream()
					.filter(g -> !g.getUuid().equals(ugd.getUuid()))
					.filter(g -> g.getName().equals(effectiveName))
					.findFirst();
			if (nameConflict.isPresent()) {
				throw new RelizaException(
					"A group with the name '" + effectiveName + "' already exists.");
			}
		}
		
		// Team-member validation lives here, next to the other domain rules
		// (name uniqueness / restore conflict) rather than in the data fetcher,
		// so every caller of this method is held to the invariant -- not just the
		// GraphQL one. Sanitize BEFORE validating so the checks see exactly what
		// will persist: a customRole of "<script>x</script>" is non-empty on the
		// way in but sanitizes to "", which is precisely the state the CUSTOM
		// rule exists to reject.
		updateUserGroupDto.setMemberRoles(UserGroupData.sanitizeMemberRoles(updateUserGroupDto.getMemberRoles()));
		updateUserGroupDto.setExternalMembers(UserGroupData.sanitizeExternalMembers(updateUserGroupDto.getExternalMembers()));
		validateMemberRoles(updateUserGroupDto.getMemberRoles(), effectiveRoster(ugd, updateUserGroupDto));
		validateExternalMembers(updateUserGroupDto.getExternalMembers());

		UserGroupData updatedUgd = UserGroupData.updateUserGroupData(ugd, updateUserGroupDto);

		// check if group permissions are being elevated from read to write
		boolean wasWrite = hasWritePermissions(ugd);
		boolean willBeWrite = hasWritePermissions(updatedUgd);
		if (!wasWrite && willBeWrite) {
			int newWriteUsers = countNewWriteUsersInGroup(updatedUgd);
			if (newWriteUsers > 0) {
				organizationService.validateWriteUserElevationAllowedByLicense(newWriteUsers);
			}
		}

		Map<String, Object> recordData = Utils.OM.convertValue(updatedUgd, new TypeReference<Map<String, Object>>() {});
		UserGroup savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
	private int countNewWriteUsersInGroup(UserGroupData ugd) {
		Set<UUID> userUuids = ugd.getAllUsers();
		if (userUuids == null || userUuids.isEmpty()) return 0;
		int count = 0;
		for (UUID userUuid : userUuids) {
			var oud = userService.getUserData(userUuid);
			if (oud.isPresent()) {
				UserData ud = oud.get();
				boolean alreadyWrite = false;
				for (UUID orgUuid : ud.getOrganizations()) {
					var combined = organizationService.obtainCombinedUserOrgPermissions(ud, orgUuid);
					for (UserPermission p : combined.getOrgPermissionsAsSet(orgUuid)) {
						if (p.getType() == PermissionType.ADMIN || p.getType() == PermissionType.READ_WRITE) { alreadyWrite = true; break; }
						if (null != p.getFunctions() && p.getFunctions().contains(PermissionFunction.FINDING_ANALYSIS_WRITE)) { alreadyWrite = true; break; }
						if (null != p.getApprovals() && !p.getApprovals().isEmpty()) { alreadyWrite = true; break; }
					}
					if (alreadyWrite) break;
				}
				if (!alreadyWrite) count++;
			}
		}
		return count;
	}

	/**
	 * The roster this update will LAND on -- mirrors
	 * {@code UserGroupData.updateUserGroupData}'s merge (null means keep
	 * existing, non-null replaces), so adding a user and giving them a role in
	 * the same call works. Package-private for test access.
	 */
	static Set<UUID> effectiveRoster(UserGroupData existing, UpdateUserGroupDto dto) {
		Set<UUID> roster = new LinkedHashSet<>(
				null != dto.getUsers() ? dto.getUsers() : existing.getUsers());
		roster.addAll(null != dto.getManualUsers() ? dto.getManualUsers() : existing.getManualUsers());
		return roster;
	}

	/**
	 * A role annotates an EXISTING roster member -- it must never be a back door
	 * for adding someone to a team, which would fork the roster into two sources
	 * of truth. Note this only fires when the caller SENDS memberRoles; a role
	 * can still go stale when the roster shrinks separately, which is why
	 * {@code UserGroupData.getRoleForUser} re-checks membership on read.
	 */
	static void validateMemberRoles(List<TeamMemberRole> memberRoles, Set<UUID> roster) throws RelizaException {
		if (null == memberRoles) return;
		Set<UUID> seen = new LinkedHashSet<>();
		for (TeamMemberRole mr : memberRoles) {
			if (null == mr.userRef() || !roster.contains(mr.userRef())) {
				throw new RelizaException(
						"A team role can only be assigned to an existing team member: " + mr.userRef());
			}
			if (!seen.add(mr.userRef())) {
				// getRoleForUser returns a single Optional, so a second entry for
				// the same user would be silently dropped -- reject instead of
				// persisting a role that never takes effect.
				throw new RelizaException("Duplicate team role for member: " + mr.userRef());
			}
			validateCustomRole(mr.role(), mr.customRole());
		}
	}

	/** External members carry no roster identity, so identity/reachability is all we can check. */
	static void validateExternalMembers(List<ExternalTeamMember> externalMembers) throws RelizaException {
		if (null == externalMembers) return;
		for (ExternalTeamMember em : externalMembers) {
			// Checked post-sanitization: GraphQL String! rejects null but not "",
			// and a pure-markup value sanitizes down to "". An external member
			// without a contact is unreachable, which defeats the point of storing
			// one at all.
			if (StringUtils.isBlank(em.name()) || StringUtils.isBlank(em.contact())) {
				throw new RelizaException("An external team member requires a non-blank name and contact");
			}
			validateCustomRole(em.role(), em.customRole());
		}
	}

	/**
	 * These are malformed-input errors, not authorization failures, so they go
	 * through {@link RelizaException} -- the domain validation channel
	 * {@code GraphQLExceptionHandlers.handleReliza} surfaces as BAD_REQUEST with
	 * the actual message. Throwing AccessDeniedException here would flatten every
	 * one of them to a generic "Not authorized", misleading both the operator
	 * fixing their request and anyone reading the logs.
	 */
	static void validateCustomRole(TeamRole role, String customRole) throws RelizaException {
		if (TeamRole.CUSTOM == role && StringUtils.isBlank(customRole)) {
			throw new RelizaException("A CUSTOM team role requires a customRole label");
		}
	}

	private boolean hasWritePermissions(UserGroupData ugd) {
		Set<UserPermission> perms = ugd.getOrgPermissions(ugd.getOrg());
		for (UserPermission p : perms) {
			if (p.getType() == PermissionType.ADMIN || p.getType() == PermissionType.READ_WRITE) return true;
			if (null != p.getFunctions()
					&& p.getFunctions().contains(PermissionFunction.FINDING_ANALYSIS_WRITE)) return true;
			if (null != p.getApprovals() && !p.getApprovals().isEmpty()) return true;
		}
		return false;
	}

	/**
	 * Gets all user groups for an organization (active only, used by SSO sync)
	 */
	@Transactional(readOnly = true)
	public List<UserGroupData> getUserGroupsByOrganization(UUID orgUuid) {
		List<UserGroup> groups = userGroupRepository.findAllByOrganization(orgUuid.toString());
		return groups.stream()
				.map(UserGroupData::dataFromRecord)
				.filter(g -> g.getStatus() == UserGroupStatus.ACTIVE)
				.collect(Collectors.toList());
	}
	
	/**
	 * Gets all user groups for an organization, including inactive ones
	 */
	@Transactional(readOnly = true)
	public List<UserGroupData> getAllUserGroupsByOrganization(UUID orgUuid) {
		List<UserGroup> groups = userGroupRepository.findAllByOrganization(orgUuid.toString());
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
	protected UserGroupData addUserToGroup(UUID groupUuid, UUID userUuid, WhoUpdated wu) {
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
	 * Removes a user from all user groups in an organization (both SSO and manual).
	 * Used when a user is removed from an organization entirely.
	 */
	protected void removeUserFromAllGroupsInOrg(UUID userUuid, UUID orgUuid, WhoUpdated wu) {
		List<UserGroupData> userGroups = getUserGroupsByUserAndOrg(userUuid, orgUuid);
		for (UserGroupData group : userGroups) {
			removeUserFromGroup(group.getUuid(), userUuid, wu);
		}
	}
	
	/**
	 * Removes a user from a user group's SSO users only.
	 * Used by SSO sync — does not touch manualUsers.
	 */
	public UserGroupData removeSsoUserFromGroup(UUID groupUuid, UUID userUuid, WhoUpdated wu) {
		return removeUserFromGroupInternal(groupUuid, userUuid, wu, false);
	}
	
	/**
	 * Removes a user from a user group (both SSO users and manualUsers).
	 * Used when a user is removed from the organization entirely.
	 */
	protected UserGroupData removeUserFromGroup(UUID groupUuid, UUID userUuid, WhoUpdated wu) {
		return removeUserFromGroupInternal(groupUuid, userUuid, wu, true);
	}
	
	private UserGroupData removeUserFromGroupInternal(UUID groupUuid, UUID userUuid, WhoUpdated wu, boolean includeManual) {
		Optional<UserGroup> existingGroup = userGroupRepository.findById(groupUuid);
		if (existingGroup.isEmpty()) {
			throw new IllegalArgumentException("User group not found: " + groupUuid);
		}
		
		UserGroup ug = existingGroup.get();
		UserGroupData ugd = UserGroupData.dataFromRecord(ug);
		
		boolean wasRemoved = ugd.removeUser(userUuid);
		if (includeManual) {
			wasRemoved |= ugd.removeManualUser(userUuid);
		}
		if (!wasRemoved) {
			return ugd;
		}
		
		Map<String, Object> recordData = Utils.OM.convertValue(ugd, new TypeReference<Map<String, Object>>() {});
		UserGroup savedUG = saveUserGroup(ug, recordData, wu);
		return UserGroupData.dataFromRecord(savedUG);
	}
	
}
