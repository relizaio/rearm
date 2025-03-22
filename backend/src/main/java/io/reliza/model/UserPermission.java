/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
public class UserPermission {
	
	public enum PermissionScope {
		ORGANIZATION,
		COMPONENT,
		BRANCH,
		INSTANCE
		;
		
		private PermissionScope () {}
	}
	
	public enum PermissionType {
		// N.B. Enum order matters here! - P.S. 2025-03-01
		NONE,
		READ_ONLY,
		READ_WRITE,
		ADMIN
		;
		
		private PermissionType () {}
	}
	
	public record PermissionDto(UUID org, PermissionScope scope, UUID object, PermissionType type) {}
	
	@Setter(AccessLevel.PRIVATE)
	private UUID org;
	@Setter(AccessLevel.PRIVATE)
	private PermissionScope scope;
	@Setter(AccessLevel.PRIVATE)
	private UUID object; // object to which permission applies
	@Setter(AccessLevel.PRIVATE)
	private PermissionType type; // admin, read-only, write-only, read-write, none
	@JsonProperty(CommonVariables.APPROVALS_FIELD)
	private Set<String> approvals = new LinkedHashSet<>(); 
	@Setter(AccessLevel.PRIVATE)
	private String meta; // selectors for complex cases, i.e. instance envirionments or specific props
	
	private UserPermission () {}

	public PermissionScope getScope() {
		return scope;
	}


	private static UserPermission permissionFactory (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
		UserPermission up = new UserPermission();
		up.setOrg(orgUuid);
		up.setScope(scope);
		up.setObject(objectUuid);
		up.setType(type);
		if (null != approvals) {
			up.setApprovals(new LinkedHashSet<>(approvals));
		}
		return up;
	}
	
	public static class Permissions {		
		@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
		private Set<UserPermission> permissions = new LinkedHashSet<>();
		
		private void addPermission(UserPermission up) {
			this.permissions.add(up);
		}
		
		public Optional<UserPermission> getPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid) {
			// every user with a write permission on at least one org has read permissions on global
			boolean foundWritePermission = false;
			// for now, just do simple scan, later we may optimize this via hash map or bloom filter
			Optional<UserPermission> oup = Optional.empty();
			Iterator<UserPermission> upIter = permissions.iterator();
			while (oup.isEmpty() && upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg()) && scope == upCur.getScope() && objectUuid.equals(upCur.getObject())) {
					oup = Optional.of(upCur);
				} else if (upCur.getScope() == PermissionScope.ORGANIZATION && (upCur.getType() == PermissionType.ADMIN || upCur.getType() == PermissionType.READ_WRITE)) {
					foundWritePermission = true;
				}
			}
			if (oup.isEmpty() && CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(orgUuid) && foundWritePermission) {
				// grant read only access to external org
				UserPermission externalUp = UserPermission.permissionFactory(CommonVariables.EXTERNAL_PROJ_ORG_UUID, PermissionScope.ORGANIZATION, 
						CommonVariables.EXTERNAL_PROJ_ORG_UUID, PermissionType.READ_ONLY, null);
				oup = Optional.of(externalUp);
			}
			return oup;
		}
		
		/**
		 * Returns all permissions filtered by org UUID
		 * @param orgUuid
		 * @return
		 */
		public Permissions getOrgPermissions (UUID orgUuid) {
			Permissions orgPermissions = new Permissions();
			Iterator<UserPermission> upIter = permissions.iterator();
			while (upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg())) {
					orgPermissions.addPermission(upCur);
				}
			}
			return orgPermissions;
		}
		
		public Permissions cloneOrgPermissions (UUID orgUuid) {
			Permissions orgPermissions = new Permissions();
			Iterator<UserPermission> upIter = permissions.iterator();
			while (upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg())) {
					UserPermission clonedUpCur = new UserPermission();
					clonedUpCur.setMeta(upCur.getMeta());
					clonedUpCur.setObject(upCur.getObject());
					clonedUpCur.setOrg(upCur.getOrg());
					clonedUpCur.setScope(upCur.getScope());
					clonedUpCur.setType(upCur.getType());
					orgPermissions.addPermission(upCur);
				}
			}
			return orgPermissions;
		}
		
		public boolean hasType(PermissionType type){
			Iterator<UserPermission> upIter = permissions.iterator();
			while (upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (type.equals(upCur.getType())) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Returns number of UserPermission objects in this Permissions object
		 * @return
		 */
		@JsonIgnore
		public int getSize() {
			return permissions.size();
		}
		
		public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type, Collection<String> approvals) {
			// if permission exists, update it accordingly
			// for now, just do simple scan, later we may optimize this via hash map or bloom filter
			Iterator<UserPermission> upIter = permissions.iterator();
			boolean found = false;
			while (!found && upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg()) && scope == upCur.getScope() && objectUuid.equals(upCur.getObject())) {
					upCur.setType(type);
					if (null != approvals) upCur.setApprovals(new LinkedHashSet<>(approvals));
					found = true;
				}
			}
			// if permission doesn't exist, create it
			if (!found) {
				UserPermission upNew = permissionFactory(orgUuid, scope, objectUuid, type, approvals);
				addPermission(upNew);
			}
		}
		
		/**
		 * Deletes UserPermission from Permissions if it's present there
		 * @param orgUuid
		 * @param scope
		 * @param objectUuid
		 * @return true if UserPermission was inititally present in Permissions, false otherwise
		 */
		public boolean revokePermission (UUID orgUuid, PermissionScope scope, UUID objectUuid) {
			// for now, just do simple scan, later we may optimize this via hash map or bloom filter
			Iterator<UserPermission> upIter = permissions.iterator();
			boolean found = false;
			while (!found && upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg()) && scope == upCur.getScope() && objectUuid.equals(upCur.getObject())) {
					upIter.remove();
					found = true;
				}
			}
			return found;
		}

		public boolean revokeAllOrgPermissions(UUID orgUuid) {
			boolean removed = false;
			Iterator<UserPermission> upIter = permissions.iterator();
			while (upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg())) {
					upIter.remove();
					removed = true;
				}
			}
			return removed;
		}
	}
}