/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

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

	/**
	 * An addressing role attached to a registered ReARM user already on this
	 * team's roster ({@link #getAllUsers()}). This is a pure annotation -- it
	 * does NOT add anyone to the team, so the roster keeps exactly one source of
	 * truth ({@code users} + {@code manualUsers}) and SSO sync stays untouched.
	 * A {@code userRef} with no matching roster entry is rejected on write.
	 *
	 * <p>{@code customRole} carries the operator's label when {@code role} is
	 * {@link TeamRole#CUSTOM}, and is null otherwise. See {@link TeamRole} --
	 * roles never grant access.
	 */
	public record TeamMemberRole (UUID userRef, TeamRole role, String customRole) {}

	/**
	 * A team member who is not a registered ReARM user, reachable only by a
	 * freeform contact (email / Slack handle) -- the team-level analogue of
	 * {@code ComponentData.FreeformContact}, plus a role. Both freeform fields
	 * are operator-supplied and are HTML-sanitized via
	 * {@link #sanitizeExternalMembers} before persistence, since they render
	 * back into the team UI.
	 *
	 * <p><strong>External members deliberately do NOT count toward the durability
	 * bar</strong> (DECIDED 2026-07-28): they live outside {@code users} /
	 * {@code manualUsers}, so {@link #getAllUsers()} -- and therefore
	 * {@code ComponentOwnershipService.isTeamDurable} -- ignores them by
	 * construction. Durability means "survives a departure", which needs
	 * accountable, lifecycle-managed accounts; an external contact is
	 * addressable but confers no durability.
	 */
	public record ExternalTeamMember (String name, String contact, TeamRole role, String customRole) {}

	/**
	 * Returns a sanitized copy of {@code externalMembers} with each freeform
	 * field run through jsoup {@code Safelist.basic()} -- the same safelist the
	 * component contact path uses for operator-supplied text. Null input yields
	 * null; null individual fields are preserved as null. Role fields are
	 * enum/label data and are passed through untouched apart from the label,
	 * which is operator text and so is sanitized too.
	 */
	/**
	 * Returns a sanitized copy of {@code memberRoles}. Only {@code customRole} is
	 * operator-supplied free text ({@code userRef} is a UUID and {@code role} an
	 * enum), but it is the same class of label as
	 * {@link ExternalTeamMember#customRole} and renders into the same team UI, so
	 * it gets the same treatment -- sanitize-on-write applies to the WHOLE new
	 * surface, not just the external half.
	 */
	public static List<TeamMemberRole> sanitizeMemberRoles (List<TeamMemberRole> memberRoles) {
		if (null == memberRoles) return null;
		return memberRoles.stream()
				.map(mr -> new TeamMemberRole(
						mr.userRef(),
						mr.role(),
						StringUtils.isEmpty(mr.customRole()) ? mr.customRole() : Jsoup.clean(mr.customRole(), Safelist.basic())))
				.collect(Collectors.toList());
	}

	public static List<ExternalTeamMember> sanitizeExternalMembers (List<ExternalTeamMember> externalMembers) {
		if (null == externalMembers) return null;
		return externalMembers.stream()
				.map(m -> new ExternalTeamMember(
						StringUtils.isEmpty(m.name()) ? m.name() : Jsoup.clean(m.name(), Safelist.basic()),
						StringUtils.isEmpty(m.contact()) ? m.contact() : Jsoup.clean(m.contact(), Safelist.basic()),
						m.role(),
						StringUtils.isEmpty(m.customRole()) ? m.customRole() : Jsoup.clean(m.customRole(), Safelist.basic())))
				.collect(Collectors.toList());
	}

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
	/** Addressing roles for roster users; annotation only -- never a roster of its own. */
	@JsonProperty("memberRoles")
	private List<TeamMemberRole> memberRoles = new LinkedList<>();
	/** Non-ReARM team members; addressable, but excluded from durability by design. */
	@JsonProperty("externalMembers")
	private List<ExternalTeamMember> externalMembers = new LinkedList<>();
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
	
	public List<TeamMemberRole> getMemberRoles() {
		return new LinkedList<>(memberRoles);
	}

	private void setMemberRoles(Collection<TeamMemberRole> memberRoles) {
		this.memberRoles = new LinkedList<>(memberRoles);
	}

	public List<ExternalTeamMember> getExternalMembers() {
		return new LinkedList<>(externalMembers);
	}

	private void setExternalMembers(Collection<ExternalTeamMember> externalMembers) {
		this.externalMembers = new LinkedList<>(externalMembers);
	}

	/**
	 * The addressing role recorded for a roster user, if any. Used by the
	 * escalation path ("notify the security specialist on this component's owner
	 * team"); absence means the user is on the team with no declared role.
	 *
	 * <p>Deliberately re-checks {@link #hasUser} rather than trusting the stored
	 * annotation: a role can outlive its member. Write-time validation only fires
	 * when the caller sends {@code memberRoles}, so an update that shrinks the
	 * roster with {@code memberRoles} omitted -- and SSO-driven removals via
	 * {@code UserGroupService.removeUserFromGroupInternal}, which never touches
	 * this field -- both leave a role pointing at an ex-member. Filtering on read
	 * closes every such path by construction, so escalation can never target
	 * someone who has left the team.
	 */
	@JsonIgnore
	public Optional<TeamMemberRole> getRoleForUser(UUID userUuid) {
		if (!hasUser(userUuid)) return Optional.empty();
		return memberRoles.stream().filter(mr -> null != mr.userRef() && mr.userRef().equals(userUuid)).findFirst();
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
			// remove existing individual permissions for this org
			Set<UserPermission> existingPermissions = updatedUserGroupData.getOrgPermissions(updatedUserGroupData.getOrg());
			for (UserPermission p : existingPermissions) {
				if (p.getScope() != PermissionScope.ORGANIZATION) {
					updatedUserGroupData.revokePermission(p.getScope(), p.getObject());
				}
			}
			
			for (UserGroupPermissionDto permission : updateUgd.getPermissions()) {
				updatedUserGroupData.setPermission(
					permission.getScope(),
					permission.getObjectId(),
					permission.getType(),
					permission.getFunctions(),
					permission.getApprovals()
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

		// Sanitize on write, not on read: the freeform custom-label / name /
		// contact fields are operator text rendered back into the team UI. Both
		// member lists get it -- sanitizing only the external half would leave
		// memberRoles[].customRole as an unescaped sink.
		if (null != updateUgd.getMemberRoles()) {
			updatedUserGroupData.setMemberRoles(sanitizeMemberRoles(updateUgd.getMemberRoles()));
		} else {
			updatedUserGroupData.setMemberRoles(ugd.getMemberRoles());
		}

		if (null != updateUgd.getExternalMembers()) {
			updatedUserGroupData.setExternalMembers(sanitizeExternalMembers(updateUgd.getExternalMembers()));
		} else {
			updatedUserGroupData.setExternalMembers(ugd.getExternalMembers());
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
				.users(ugd.getUsers())
				.manualUsers(ugd.getManualUsers())
				.connectedSsoGroups(ugd.getConnectedSsoGroups())
				.memberRoles(ugd.getMemberRoles())
				.externalMembers(ugd.getExternalMembers())
				.build();
	}

	@Override
	public UUID getResourceGroup() {
		return this.resourceGroup;
	}
}
