/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ComponentData;
import io.reliza.model.UserData;
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
	private GetComponentService getComponentService;

	public List<UserData> deriveTeam (ComponentData cd) {
		return deriveWithParentFallback(cd, this::directTeam);
	}

	public List<UserData> deriveApprovers (ComponentData cd) {
		return deriveWithParentFallback(cd, this::directApprovers);
	}

	private List<UserData> directTeam (ComponentData cd) {
		return userService.getUsersByPermissionObject(cd.getUuid()).stream()
				.filter(ud -> ud.getPermission(cd.getOrg(), PermissionScope.COMPONENT, cd.getUuid())
						.map(up -> up.getType() != null
								&& up.getType().ordinal() >= PermissionType.READ_WRITE.ordinal())
						.orElse(false))
				.toList();
	}

	private List<UserData> directApprovers (ComponentData cd) {
		return userService.getUsersByPermissionObject(cd.getUuid()).stream()
				.filter(ud -> ud.getPermission(cd.getOrg(), PermissionScope.COMPONENT, cd.getUuid())
						.map(up -> up.getApprovals() != null && !up.getApprovals().isEmpty())
						.orElse(false))
				.toList();
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
