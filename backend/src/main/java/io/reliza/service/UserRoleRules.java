/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;

/**
 * How a member of an organization is counted by role, and under which key one person is counted
 * once. The Statistics user figures count by these rules; every other per-person count of users by
 * role must use them too, so the counts agree.
 */
public final class UserRoleRules {

	private UserRoleRules() {}

	/** The role level of a member, lowest first. */
	public enum RoleLevel {
		NONE,
		READ,
		WRITE;

		public static RoleLevel max(RoleLevel a, RoleLevel b) {
			if (null == a) return b;
			if (null == b) return a;
			return a.compareTo(b) >= 0 ? a : b;
		}
	}

	/**
	 * The level of an effective permission set: WRITE when any permission is a write grant, READ when
	 * any permission's type is above NONE, NONE otherwise, so a function-only grant with type NONE is
	 * no role.
	 */
	public static RoleLevel levelOf(Collection<UserPermission> effective) {
		if (null == effective) return RoleLevel.NONE;
		boolean read = false;
		for (UserPermission p : effective) {
			if (isWriteGrant(p)) return RoleLevel.WRITE;
			if (isAboveNone(p.getType())) read = true;
		}
		return read ? RoleLevel.READ : RoleLevel.NONE;
	}

	/** The level a permission type grants on its own: READ_WRITE and ADMIN write, other types above NONE read. */
	public static RoleLevel levelOf(PermissionType type) {
		if (!isAboveNone(type)) return RoleLevel.NONE;
		return (type == PermissionType.READ_WRITE || type == PermissionType.ADMIN) ? RoleLevel.WRITE : RoleLevel.READ;
	}

	/** Whether one permission makes its holder a write user: ADMIN or READ_WRITE, FINDING_ANALYSIS_WRITE, any approval. */
	public static boolean isWriteGrant(UserPermission p) {
		if (p.getType() == PermissionType.ADMIN || p.getType() == PermissionType.READ_WRITE) return true;
		if (null != p.getFunctions() && p.getFunctions().contains(PermissionFunction.FINDING_ANALYSIS_WRITE)) return true;
		return null != p.getApprovals() && !p.getApprovals().isEmpty();
	}

	private static boolean isAboveNone(PermissionType type) {
		return null != type && type.ordinal() > PermissionType.NONE.ordinal();
	}

	/**
	 * The key one person is counted under: their PRIMARY email lower-cased, never the address bound to
	 * an organization, or {@code user:<uuid>} without one. The same in every organization, so one person
	 * with several user records is counted once.
	 */
	public static String personKey(UserData ud) {
		return personKey(ud, ud.getEmail());
	}

	static String personKey(UserData ud, String email) {
		return StringUtils.isBlank(email) ? "user:" + ud.getUuid() : email.toLowerCase(Locale.ROOT);
	}
}
