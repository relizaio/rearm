/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentOwner;
import io.reliza.model.ComponentOwnerType;
import io.reliza.model.ComponentOwnershipStatus;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ComponentOwnership;
import io.reliza.model.dto.ComponentOwnershipReportRow;

/**
 * The single, pure read path for a component's durable ownership (RFC Phase 4,
 * sec. 10.3). {@link #resolveOwnership} validates a stored owner or -- when none is
 * set -- suggests a candidate owner-team, and classifies the result into a
 * {@link ComponentOwnershipStatus}. It has NO side effects and never mutates the
 * component, so it is safe to call from a GraphQL field resolver and from the
 * (future) reconciliation job.
 *
 * <p>Durability and the candidate-team query both reuse
 * {@link PermissionType#atLeast} -- the same tier-floor predicate the write-team
 * and inbox-audience rules share (PR #363) -- so ownership cannot re-fork the
 * membership rule.
 */
@Service
public class ComponentOwnershipService {

	/** A team needs at least this many direct members to count as durable (unless SSO-backed). */
	public static final int DURABLE_MIN_MEMBERS = 2;

	@Autowired
	private UserGroupService userGroupService;

	@Autowired
	private UserService userService;

	@Autowired
	private ComponentTeamService componentTeamService;

	/**
	 * Convenience overload for the per-component read path. Skips the org-group
	 * fetch entirely when a stored owner is present (the common case once ownership
	 * is set) -- only the suggestion path needs the candidate list -- so a
	 * stored-owner component costs no {@code getUserGroupsByOrganization}.
	 */
	public ComponentOwnership resolveOwnership (ComponentData cd) {
		List<UserGroupData> orgGroups = hasStoredOwner(cd.getOwner())
				? List.of()
				: userGroupService.getUserGroupsByOrganization(cd.getOrg());
		return resolveOwnership(cd, orgGroups);
	}

	/**
	 * Resolve ownership given the org's group list (pass it in from a batch/job to
	 * avoid an N+1 group fetch per component; sec. 10.5). {@code orgGroups} is only
	 * consulted on the suggestion path (no stored owner).
	 */
	public ComponentOwnership resolveOwnership (ComponentData cd, List<UserGroupData> orgGroups) {
		ComponentOwner owner = cd.getOwner();
		return hasStoredOwner(owner) ? resolveStored(cd, owner) : suggestFromCandidates(cd, orgGroups);
	}

	/**
	 * The org durable-ownership report (RFC Phase 4c, sec. 10.5): every NON-OWNED
	 * component with its computed {@link ComponentOwnership}, the actionable
	 * at-risk list. Hoists {@code getUserGroupsByOrganization} ONCE and threads it
	 * into every per-component resolution (no N+1 group fetch). Pure read -- no
	 * mutation, no event, never blocks a release; the reporting half of the
	 * flag-only reconciliation. {@code components} is supplied by the caller (the
	 * resolver already lists + authorizes them) so this service takes no
	 * ComponentService dependency.
	 */
	public List<ComponentOwnershipReportRow> ownershipReport (UUID orgUuid, List<ComponentData> components) {
		List<UserGroupData> orgGroups = userGroupService.getUserGroupsByOrganization(orgUuid);
		List<ComponentOwnershipReportRow> rows = new ArrayList<>();
		for (ComponentData cd : components) {
			ComponentOwnership o = resolveOwnership(cd, orgGroups);
			if (o.status() != ComponentOwnershipStatus.OWNED) {
				rows.add(new ComponentOwnershipReportRow(cd.getUuid(), cd.getName(), cd.getType(), o));
			}
		}
		return rows;
	}

	/** A component has a usable stored owner only when type AND ref are both set. */
	private static boolean hasStoredOwner (ComponentOwner owner) {
		return null != owner && null != owner.ownerType() && null != owner.ownerRef();
	}

	/**
	 * Synchronous write-time validation of an owner about to be stored on a
	 * component in {@code orgUuid} (RFC Phase 4b, sec. 10.4). No DB foreign key
	 * backs {@code ownerRef}, so this is the integrity gate: both fields present,
	 * and the ref resolves to a same-org referent of the matching kind (a TEAM ref
	 * to a {@code UserGroup} in this org, a USER ref to an org member). A USER
	 * owner is permitted -- it is accepted here and later reported NON_DURABLE by
	 * {@link #resolveOwnership}. A null {@code owner} is a no-op (no change).
	 *
	 * @throws RelizaException when the owner is malformed or its ref does not
	 *         resolve in this org -- belt-and-suspenders for raw/programmatic
	 *         callers, mirroring the notification enum normalizers.
	 */
	public void validateOwner (ComponentOwner owner, UUID orgUuid) throws RelizaException {
		if (null == owner) {
			return;
		}
		if (null == owner.ownerType() || null == owner.ownerRef()) {
			throw new RelizaException("Component owner requires both ownerType and ownerRef");
		}
		switch (owner.ownerType()) {
			case TEAM: {
				UserGroupData team = userGroupService.getUserGroupData(owner.ownerRef()).orElse(null);
				if (null == team || !orgUuid.equals(team.getOrg())) {
					throw new RelizaException("Owner team not found in this organization");
				}
				break;
			}
			case USER: {
				if (userService.getUserDataWithOrg(owner.ownerRef(), orgUuid).isEmpty()) {
					throw new RelizaException("Owner user is not a member of this organization");
				}
				break;
			}
			default:
				throw new RelizaException("Unknown component owner type");
		}
	}

	/**
	 * The org's ACTIVE teams ({@link UserGroup}s) that hold a
	 * {@code >= READ_WRITE} COMPONENT-scoped permission on {@code cd} -- i.e. teams
	 * that are already the component's write-team via a group grant, and thus the
	 * natural durable-owner candidates. Uses the shared {@link PermissionType#atLeast}.
	 */
	public List<UserGroupData> candidateOwnerTeams (ComponentData cd, List<UserGroupData> orgGroups) {
		UUID obj = cd.getUuid();
		return orgGroups.stream()
				.filter(g -> g.getStatus() == UserGroupStatus.ACTIVE)
				.filter(g -> g.getPermission(PermissionScope.COMPONENT, obj)
						.map(up -> PermissionType.atLeast(up.getType(), PermissionType.READ_WRITE))
						.orElse(false))
				.toList();
	}

	private ComponentOwnership resolveStored (ComponentData cd, ComponentOwner owner) {
		switch (owner.ownerType()) {
			case TEAM: {
				UserGroupData team = userGroupService.getUserGroupData(owner.ownerRef()).orElse(null);
				if (null == team || !cd.getOrg().equals(team.getOrg())) {
					return orphaned(owner, "Owner team no longer exists in this organization");
				}
				if (team.getStatus() != UserGroupStatus.ACTIVE) {
					return new ComponentOwnership(ComponentOwnerType.TEAM, owner.ownerRef(), false,
							ComponentOwnershipStatus.DEGRADED, false, "Owner team is archived/inactive");
				}
				boolean durable = isTeamDurable(team);
				return new ComponentOwnership(ComponentOwnerType.TEAM, owner.ownerRef(), durable,
						durable ? ComponentOwnershipStatus.OWNED : ComponentOwnershipStatus.NON_DURABLE, false,
						durable ? null
								: "Owner team has fewer than " + DURABLE_MIN_MEMBERS
										+ " members and is not SSO-backed");
			}
			case USER: {
				boolean inOrg = userService.getUserDataWithOrg(owner.ownerRef(), cd.getOrg()).isPresent();
				if (!inOrg) {
					return orphaned(owner, "Owner user is no longer a member of this organization");
				}
				return new ComponentOwnership(ComponentOwnerType.USER, owner.ownerRef(), false,
						ComponentOwnershipStatus.NON_DURABLE, false,
						"Individual owner -- not durable; will orphan on departure");
			}
			default:
				return orphaned(owner, "Unknown owner type");
		}
	}

	private ComponentOwnership suggestFromCandidates (ComponentData cd, List<UserGroupData> orgGroups) {
		List<UserGroupData> candidates = candidateOwnerTeams(cd, orgGroups);
		if (!candidates.isEmpty()) {
			// Prefer a durable candidate, then the largest roster.
			UserGroupData best = candidates.stream()
					.max(Comparator.comparing((UserGroupData g) -> isTeamDurable(g))
							.thenComparingInt(g -> g.getAllUsers().size()))
					.orElseThrow();
			boolean durable = isTeamDurable(best);
			String reason = candidates.size() == 1
					? "No owner set; suggest team '" + best.getName() + "'"
					: "No owner set; " + candidates.size() + " candidate teams -- suggest '"
							+ best.getName() + "'";
			return new ComponentOwnership(ComponentOwnerType.TEAM, best.getUuid(), durable,
					ComponentOwnershipStatus.UNSET, true, reason);
		}
		// No candidate team. If there are individual write-grant members (people,
		// no team), suggest creating a team; otherwise the component is orphaned.
		int writers = componentTeamService.deriveTeam(cd).size();
		if (writers > 0) {
			return new ComponentOwnership(null, null, false, ComponentOwnershipStatus.UNSET, true,
					"No owner set; " + writers + " individual write-team member(s) but no team -- create one");
		}
		return new ComponentOwnership(null, null, false, ComponentOwnershipStatus.ORPHANED, false,
				"No owner set and no team or write-team member to derive one from");
	}

	/**
	 * A team is durable when it is ACTIVE and either has at least
	 * {@link #DURABLE_MIN_MEMBERS} direct members ({@code getAllUsers()} =
	 * users + manualUsers) OR is SSO-backed (a non-empty {@code connectedSsoGroups}),
	 * so an IdP-managed team whose members have not logged in yet is not
	 * spuriously flagged (sec. 10.3). Directly-empty, non-SSO 1-person teams are
	 * non-durable, same as a USER owner.
	 */
	private boolean isTeamDurable (UserGroupData team) {
		return team.getStatus() == UserGroupStatus.ACTIVE
				&& (team.getAllUsers().size() >= DURABLE_MIN_MEMBERS
						|| !team.getConnectedSsoGroups().isEmpty());
	}

	private static ComponentOwnership orphaned (ComponentOwner owner, String reason) {
		return new ComponentOwnership(owner.ownerType(), owner.ownerRef(), false,
				ComponentOwnershipStatus.ORPHANED, false, reason);
	}
}
