/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
public class UserPermission {
	
	public enum PermissionScope {
		// N.B. Enum order matters here! - P.S. 2026-02-17
		RELEASE,
		BRANCH,
		COMPONENT,
		PERSPECTIVE,
		INSTANCE,
		ORGANIZATION
		;
		
		private PermissionScope () {}
	}
	
	public enum PermissionType {
		// N.B. Enum order matters here! - P.S. 2025-03-01
		NONE,
		ESSENTIAL_READ,
		READ_ONLY,
		READ_WRITE,
		ADMIN
		;
		
		public static PermissionType mapFromCallType (CallType ct) {
			PermissionType pt = NONE;
			switch(ct) {
				case ADMIN:
					pt = ADMIN;
					break;
				case ESSENTIAL_READ:
					pt = ESSENTIAL_READ;
					break;
				case READ:
					pt = READ_ONLY;
					break;
				case WRITE:
					pt = READ_WRITE;
					break;
				case GLOBAL_ADMIN:
				case INIT:
				default:
					pt = null;
					break;
			}
			return pt;
				
		}
		
		private PermissionType () {}
	}

	/**
	 * Permission function - vertical slice of permission
	 * If omitted, permission applies to all functions
	 */
	public enum PermissionFunction {
		RESOURCE, // self, includes functionality otherwise not covered by other functions, always granted implicitly
		FINDING_ANALYSIS_READ,
		FINDING_ANALYSIS_WRITE,
		ARTIFACT_DOWNLOAD;
		
		private PermissionFunction () {}
	}
	
	public record PermissionDto(UUID org, PermissionScope scope, UUID object, PermissionType type, Set<PermissionFunction> functions, Collection<String> approvals) {}
	
	@Setter(AccessLevel.PRIVATE)
	private UUID org;
	@Setter(AccessLevel.PRIVATE)
	private PermissionScope scope;
	@Setter(AccessLevel.PRIVATE)
	private UUID object; // object to which permission applies
	@Setter(AccessLevel.PRIVATE)
	private PermissionType type; // admin, read-only, write-only, read-write, none
	@Setter(AccessLevel.PRIVATE)
	private Set<PermissionFunction> functions = new LinkedHashSet<>();
	@JsonProperty(CommonVariables.APPROVALS_FIELD)
	private Set<String> approvals = new LinkedHashSet<>(); 
	@Setter(AccessLevel.PRIVATE)
	private String meta; // selectors for complex cases, i.e. instance envirionments or specific props
	
	private UserPermission () {}

	public PermissionScope getScope() {
		return scope;
	}


	public static UserPermission permissionFactory (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type,
			Collection<PermissionFunction> functions, Collection<String> approvals) {
		UserPermission up = new UserPermission();
		up.setOrg(orgUuid);
		up.setScope(scope);
		up.setObject(objectUuid);
		up.setType(type);
		if (null != functions) {
			up.setFunctions(new LinkedHashSet<>(functions));
		}
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
			// for now, just do simple scan, later we may optimize this via hash map or bloom filter
			Optional<UserPermission> oup = Optional.empty();
			Iterator<UserPermission> upIter = permissions.iterator();
			while (oup.isEmpty() && upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg()) && scope == upCur.getScope() && objectUuid.equals(upCur.getObject())) {
					oup = Optional.of(upCur);
				}
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
		
		/**
		 * Returns all permissions filtered by org UUID as a set of permissions rather than Permissions object
		 * @param orgUuid
		 * @return
		 */
		public Set<UserPermission> getOrgPermissionsAsSet (UUID orgUuid) {
			Set<UserPermission> orgPermissions = new HashSet<>();
			Iterator<UserPermission> upIter = permissions.iterator();
			while (upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg())) {
					orgPermissions.add(upCur);
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
			setPermission(orgUuid, scope, objectUuid, type, null, approvals);
		}

		public void setPermission (UUID orgUuid, PermissionScope scope, UUID objectUuid, PermissionType type,
				Collection<PermissionFunction> functions, Collection<String> approvals) {
			// if permission exists, update it accordingly
			// for now, just do simple scan, later we may optimize this via hash map or bloom filter
			Iterator<UserPermission> upIter = permissions.iterator();
			boolean found = false;
			while (!found && upIter.hasNext()) {
				UserPermission upCur = upIter.next();
				if (orgUuid.equals(upCur.getOrg()) && scope == upCur.getScope() && objectUuid.equals(upCur.getObject())) {
					upCur.setType(type);
					if (null != functions) upCur.setFunctions(new LinkedHashSet<>(functions));
					if (null != approvals) upCur.setApprovals(new LinkedHashSet<>(approvals));
					found = true;
				}
			}
			// if permission doesn't exist, create it
			if (!found) {
				UserPermission upNew = permissionFactory(orgUuid, scope, objectUuid, type, functions, approvals);
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