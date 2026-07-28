/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ComponentData;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import lombok.extern.slf4j.Slf4j;

/**
 * Derives a component's (or product's) <em>team</em> and <em>approvers</em>
 * live from the permission model — these are never persisted on the component.
 *
 * <ul>
 *   <li><b>Team</b> = org users holding a {@code READ_WRITE}-or-stronger
 *       {@link PermissionScope#COMPONENT}-scoped permission on the component.</li>
 *   <li><b>Approvers</b> = org users whose {@code COMPONENT}-scoped permission
 *       on the component carries one or more approval-role grants.</li>
 * </ul>
 *
 * Override semantics: a product acts as the default team/approvers for its
 * children. If a component has no <em>direct</em> members of its own, we walk up
 * the {@link ComponentData#getParent()} chain and return the first non-empty
 * ancestor set (fallback-when-empty, not a union). A visited-set guards against
 * a malformed parent cycle.
 */
@Slf4j
@Service
public class ComponentTeamService {

	@Autowired
	private UserService userService;

	@Autowired
	private UserGroupService userGroupService;

	@Autowired
	private GetComponentService getComponentService;

	public List<UserData> deriveTeam (ComponentData cd) {
		List<UserGroupData> orgGroups = userGroupService.getUserGroupsByOrganization(cd.getOrg());
		return deriveWithParentFallback(cd, c -> membersWithComponentPermission(c,
				up -> PermissionType.atLeast(up.getType(), PermissionType.READ_WRITE),
				orgGroups));
	}

	public List<UserData> deriveApprovers (ComponentData cd) {
		List<UserGroupData> orgGroups = userGroupService.getUserGroupsByOrganization(cd.getOrg());
		return deriveWithParentFallback(cd, c -> membersWithComponentPermission(c,
				up -> up.getApprovals() != null && !up.getApprovals().isEmpty(),
				orgGroups));
	}

	/**
	 * Org users whose COMPONENT-scoped permission on {@code cd} satisfies
	 * {@code pred}, counting membership granted BOTH directly (own user record)
	 * AND via a UserGroup that holds the permission. Group-folding here keeps
	 * the derived Team/Approvers consistent with the notification-inbox
	 * component-team arm (which resolves combined permissions); without it a
	 * user on the team only through a group was silently omitted from the
	 * component's Team display.
	 *
	 * <p>{@code pred} MUST be monotone / OR-decomposable over the combined-perm
	 * merge (higher type wins, approvals unioned): the predicate is evaluated on
	 * the own-record perm and each group perm IN ISOLATION and the results are
	 * unioned, which equals "the combined perm satisfies pred" only for such
	 * predicates. The two current predicates qualify (a {@code >= floor} type or
	 * a non-empty approval grant on EITHER source means the combined perm also
	 * satisfies it). A non-monotone predicate (e.g. {@code type == READ_ONLY}
	 * exactly) would give wrong results and must not be passed here.
	 *
	 * <p>{@code orgGroups} is the org's group list, resolved ONCE by the caller
	 * and threaded through {@link #deriveWithParentFallback} so the parent-walk
	 * does not re-fetch it per ancestor (all ancestors share {@code cd}'s org).
	 */
	private List<UserData> membersWithComponentPermission (ComponentData cd, Predicate<UserPermission> pred,
			List<UserGroupData> orgGroups) {
		UUID org = cd.getOrg();
		UUID obj = cd.getUuid();
		Map<UUID, UserData> out = new LinkedHashMap<>();
		for (UserData ud : userService.getUsersByPermissionObject(obj)) {
			if (ud.getPermission(org, PermissionScope.COMPONENT, obj).filter(pred).isPresent()) {
				out.put(ud.getUuid(), ud);
			}
		}
		for (UserGroupData ugd : orgGroups) {
			if (ugd.getPermission(PermissionScope.COMPONENT, obj).filter(pred).isEmpty()) {
				continue;
			}
			for (UUID memberUuid : ugd.getAllUsers()) {
				if (out.containsKey(memberUuid)) {
					continue;
				}
				userService.getUserData(memberUuid).ifPresent(m -> out.put(memberUuid, m));
			}
		}
		return new ArrayList<>(out.values());
	}

	private List<UserData> deriveWithParentFallback (ComponentData cd, Function<ComponentData, List<UserData>> fn) {
		UUID rootOrg = cd.getOrg();
		Set<UUID> visited = new HashSet<>();
		ComponentData cur = cd;
		while (cur != null && visited.add(cur.getUuid())) {
			List<UserData> members = fn.apply(cur);
			if (!members.isEmpty()) {
				return members;
			}
			UUID parent = cur.getParent();
			if (null == parent) {
				cur = null;
			} else {
				ComponentData parentCd = getComponentService.getComponentData(parent).orElse(null);
				// A product and its components always share an org; refuse to walk
				// into a different org so a malformed parent pointer can never surface
				// another org's users as this component's team/approvers.
				if (null != parentCd && !rootOrg.equals(parentCd.getOrg())) {
					log.warn("Component {} parent {} crosses org boundary; stopping team derivation walk",
							cur.getUuid(), parent);
					parentCd = null;
				}
				cur = parentCd;
			}
		}
		return List.of();
	}
}
