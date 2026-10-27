/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;

/**
 * combinePermissions with an empty entry among the group sets (task TPF-91, mutation round): a group set that
 * is null is read as none, and the sets after it still merge.
 */
public class CombinePermissionsNullSetTest {

	private static Set<UserPermission> perms(UUID org, PermissionType type, String approval) {
		Permissions p = new Permissions();
		p.setPermission(org, PermissionScope.ORGANIZATION, org, type, List.<PermissionFunction>of(), List.of(approval));
		return p.getOrgPermissionsAsSet(org);
	}

	@Test
	public void aNullGroupSetIsReadAsNoneAndTheNextSetStillMerges() {
		UUID org = UUID.randomUUID();
		Set<UserPermission> own = perms(org, PermissionType.READ_ONLY, "own");
		Set<UserPermission> group = perms(org, PermissionType.READ_WRITE, "group");

		Set<UserPermission> merged = OrganizationService.combinePermissions(own, org,
				Arrays.<Set<UserPermission>>asList(null, group)).getOrgPermissionsAsSet(org);
		assertEquals(1, merged.size(), merged.toString());
		UserPermission up = merged.iterator().next();
		assertEquals(PermissionType.READ_WRITE, up.getType());
		assertEquals(Set.of("own", "group"), Set.copyOf(up.getApprovals()));
	}
}
