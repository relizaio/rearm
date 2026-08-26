/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentOwner;
import io.reliza.model.ComponentOwnerType;
import io.reliza.model.ComponentOwnershipStatus;
import io.reliza.model.OrganizationData;
import io.reliza.model.TeamData;
import io.reliza.model.TeamStatus;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ComponentOwnership;
import io.reliza.model.dto.ComponentOwnershipReportRow;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
@Service
public class ComponentOwnershipService {

	/** A team needs at least this many roster members to count as durable (unless SSO-backed). */
	public static final int DURABLE_MIN_MEMBERS = 2;

	/**
	 * Everything an ownership resolution needs from the org, fetched ONCE.
	 *
	 * <p>Ownership now resolves through two entities: the {@link TeamData} that
	 * owns a component, and the {@link UserGroupData}s that team contains --
	 * because candidacy is a permission question and durability is an SSO
	 * question, and both of those live on the group, not the team. Resolving
	 * either per component would be an N+1 over an org-wide report; hoisting both
	 * here keeps the batch to two queries no matter how many components.
	 *
	 * @param orgTeams   every team in the org, archived included -- the ACTIVE
	 *                   filter belongs to each rule, not to the fetch
	 * @param groupsById the org's user groups by uuid, for transitive resolution
	 * @param org        carries the team-assignment rules
	 */
	public record OwnershipContext (List<TeamData> orgTeams,
			Map<UUID, UserGroupData> groupsById, OrganizationData org) {}

	/** Hoist the org's teams, groups and record for a batch of resolutions. */
	@Transactional(readOnly = true)
	public OwnershipContext ownershipContext (UUID orgUuid) {
		List<TeamData> teams = teamService.getAllTeamsByOrganization(orgUuid);
		Map<UUID, UserGroupData> groups = new LinkedHashMap<>();
		for (UserGroupData ugd : userGroupService.getUserGroupsByOrganization(orgUuid)) {
			groups.put(ugd.getUuid(), ugd);
		}
		return new OwnershipContext(teams, groups,
				getOrganizationService.getOrganizationData(orgUuid).orElse(null));
	}

	@Autowired
	private UserGroupService userGroupService;

	@Autowired
	private TeamService teamService;

	@Autowired
	private UserService userService;

	@Autowired
	private ComponentTeamService componentTeamService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private OrgTeamAssignmentRuleService teamAssignmentRuleService;

	/**
	 * Convenience overload for the per-component read path. Skips the org-group
	 * fetch entirely when a stored owner is present (the common case once ownership
	 * is set) -- only the suggestion path needs the candidate list -- so a
	 * stored-owner component costs no {@code getUserGroupsByOrganization}.
	 */
	public ComponentOwnership resolveOwnership (ComponentData cd) {
		// A stored owner short-circuits the rule list and the candidate search, so
		// the org-wide fetches are skipped entirely in the common case. The stored
		// path still needs the team's contained groups for durability, and fetches
		// exactly those.
		if (hasStoredOwner(cd.getOwner())) return resolveStored(cd, cd.getOwner(), null);
		return resolveOwnership(cd, ownershipContext(cd.getOrg()));
	}

	/**
	 * Resolve ownership given the org's group list (pass it in from a batch/job to
	 * avoid an N+1 group fetch per component; sec. 10.5). {@code orgGroups} is only
	 * consulted on the suggestion path (no stored owner).
	 */
	/**
	 * Full form: also takes the org record so a batch caller can hoist BOTH the
	 * group list and the org (which carries the team-assignment rules) out of the
	 * per-component loop.
	 *
	 * <p>Precedence, highest first (T2, DECIDED 2026-07-29):
	 * <ol>
	 *   <li><b>Stored owner</b> -- someone chose it on this component; a rule
	 *       never overrides a deliberate human choice.</li>
	 *   <li><b>Team-assignment rule</b> -- first matching rule in org order. Sets
	 *       the owner (not a parallel field), flagged {@code derived}.</li>
	 *   <li><b>Candidate suggestion</b> -- no owner at all; suggest one.</li>
	 * </ol>
	 */
	public ComponentOwnership resolveOwnership (ComponentData cd, OwnershipContext ctx) {
		ComponentOwner owner = cd.getOwner();
		if (hasStoredOwner(owner)) return resolveStored(cd, owner, ctx);
		var ruleMatch = teamAssignmentRuleService.matchFor(cd, ctx.org(), ctx.orgTeams());
		if (ruleMatch.isPresent()) return fromRule(ruleMatch.get(), ctx);
		return suggestFromCandidates(cd, ctx);
	}

	/**
	 * A rule-assigned owner is a real owner -- same durability arithmetic as a
	 * stored one, so a rule pointing at a one-person team is honestly reported
	 * NON_DURABLE rather than laundering it into OWNED. {@code derived=true} plus
	 * the rule name in the reason is how the UI shows provenance without a second
	 * stored field.
	 */
	private ComponentOwnership fromRule (OrgTeamAssignmentRuleService.TeamAssignmentMatch match,
			OwnershipContext ctx) {
		TeamData team = match.team();
		String via = " (via rule '" + match.rule().getName() + "')";
		if (team.getStatus() != TeamStatus.ACTIVE) {
			return new ComponentOwnership(ComponentOwnerType.TEAM, team.getUuid(), false,
					ComponentOwnershipStatus.DEGRADED, true, "Owner team is archived/inactive" + via);
		}
		boolean durable = isTeamDurable(team, ctx.groupsById());
		return new ComponentOwnership(ComponentOwnerType.TEAM, team.getUuid(), durable,
				durable ? ComponentOwnershipStatus.OWNED : ComponentOwnershipStatus.NON_DURABLE, true,
				durable
						? "Assigned by rule '" + match.rule().getName() + "'"
						: "Owner team has fewer than " + DURABLE_MIN_MEMBERS
								+ " members and is not SSO-backed" + via);
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
		OwnershipContext ctx = ownershipContext(orgUuid);
		List<ComponentOwnershipReportRow> rows = new ArrayList<>();
		for (ComponentData cd : components) {
			ComponentOwnership o = resolveOwnership(cd, ctx);
			if (o.status() != ComponentOwnershipStatus.OWNED) {
				rows.add(new ComponentOwnershipReportRow(cd.getUuid(), cd.getName(), cd.getType(), o));
			}
		}
		return rows;
	}

	/**
	 * Ownership states a notification route may deliver to (T4a).
	 *
	 * <p>Deliberately NOT every state with a non-null {@code ownerRef}. A
	 * suggestion ({@code UNSET} carrying candidates) is not an owner -- routing
	 * to it would notify teams that never accepted the component. {@code
	 * DEGRADED} means the owner team is archived, so its channels are stale by
	 * definition. {@code ORPHANED} has nobody to tell.
	 *
	 * <p>{@code NON_DURABLE} IS included: a one-person team is a weak owner, not
	 * a wrong one, and silently withholding a KEV notification from the only
	 * person who owns the component is the worse failure. The durability
	 * distinction is a governance signal (the 4c report), not a delivery gate.
	 */
	private static final Set<ComponentOwnershipStatus> ROUTABLE_OWNERSHIP = Set.of(
			ComponentOwnershipStatus.OWNED, ComponentOwnershipStatus.NON_DURABLE);

	/**
	 * T4a -- the notification channels of the OWNER TEAMS of the given components.
	 *
	 * <p>This is what makes a route say "whoever owns the affected component"
	 * instead of naming a fixed team that goes stale the moment a T2 assignment
	 * rule reassigns it. Resolved at fan-out, never stored on the subscription.
	 *
	 * <p>The org's teams, groups and record are hoisted ONCE for the whole batch,
	 * exactly as {@link #ownershipReport} does: an event can affect many
	 * components, and rule-derived ownership consults all three per component.
	 *
	 * <p>USER owners contribute nothing here -- a user is not a team and has no
	 * channels. That is not an oversight: individual owners are reached through
	 * the inbox visibility arms, and inventing a per-user channel here would
	 * duplicate rows the inbox already produces.
	 *
	 * @param components the affected components; callers pass {@link ComponentData}
	 *                   rather than uuids so this service keeps taking no
	 *                   ComponentService dependency (see {@link #ownershipReport})
	 * @param expectedOrg org the event belongs to; components from any other org
	 *                    are skipped, and a null org resolves to nothing (fail closed)
	 */
	public List<UUID> resolveOwnerTeamChannels (List<ComponentData> components, UUID expectedOrg) {
		Set<UUID> ownerTeams = resolveOwnerTeams(components, expectedOrg);
		if (ownerTeams.isEmpty()) return List.of();
		// Reuses the T3 resolver, so an archived team or a cross-org ref is
		// dropped by the same rules that govern an explicitly named team.
		return teamService.resolveTeamChannelUuids(ownerTeams, expectedOrg);
	}

	/**
	 * The OWNER TEAMS of the given components -- the step
	 * {@link #resolveOwnerTeamChannels} takes before resolving channels.
	 *
	 * <p>Extracted because two callers need different halves of the same answer.
	 * Owner routing wants the channels; a team-scoped subscription
	 * ({@code NotificationSubscriptionData.ownedByTeam}) wants only to ask
	 * "is MY team among the owners of what this event affects?" and resolves its
	 * own destination separately. Sharing the resolver is what keeps the two
	 * answers from drifting -- a component whose owner is not routable must be
	 * invisible to both, or a team would receive events for a component that
	 * owner routing considers unowned.
	 *
	 * <p><b>Not interchangeable with {@link #resolveOwnerTeamChannels} for an
	 * emptiness check.</b> A team that owns the component but has no channel
	 * configured yields a NON-empty team set and an EMPTY channel list -- they
	 * answer different questions ("is this owned?" vs "can we reach the owner?").
	 * {@code SyntheticEventService} tests the latter; swapping this in there
	 * would silently change what it asserts.
	 *
	 * @return owner team uuids in stable first-seen order; empty when nothing
	 *         resolves. Never null, and unmodifiable.
	 */
	public Set<UUID> resolveOwnerTeams (List<ComponentData> components, UUID expectedOrg) {
		if (null == components || components.isEmpty() || null == expectedOrg) return Set.of();
		OwnershipContext ctx;
		try {
			ctx = ownershipContext(expectedOrg);
		} catch (RuntimeException e) {
			// dataFromRecord throws on an unsupported schemaVersion or malformed
			// record_data. Degrade owner routing to "no owner" and say so, rather
			// than letting an unreadable row decide the shape of the delivery set
			// silently.
			//
			// Defence in depth. getReadableUserGroupsByOrganization already contains
			// an unreadable row INSIDE its own transactional method, which is the
			// load-bearing part: were the exception allowed to escape it, the
			// cross-bean proxy would mark this transaction rollback-only on the way
			// out and no catch here could undo that -- the fan-out would then
			// discard every delivery for the event, including ones routed to
			// explicitly named channels that have nothing to do with ownership.
			// Confirmed live before the tolerant reads existed.
			//
			// This catch therefore only covers something unforeseen in the org
			// lookup. Keep it, but do not let it grow into the primary defence:
			// containment belongs in the method that throws, not at the call site.
			log.warn("Skipping owner routing for org {}: org lookups unreadable: {}",
					expectedOrg, e.getMessage());
			return Set.of();
		}
		// LinkedHashSet: stable, first-seen order so delivery rows for one event
		// come out deterministically regardless of map iteration order.
		Set<UUID> ownerTeams = new LinkedHashSet<>();
		for (ComponentData cd : components) {
			if (null == cd || !expectedOrg.equals(cd.getOrg())) continue;
			ComponentOwnership o;
			try {
				o = resolveOwnership(cd, ctx);
			} catch (RuntimeException e) {
				// Per component, so one unreadable owner team costs only the
				// component it owns -- not the other components on the event.
				// As above: resolveStored now reads through
				// getReadableUserGroupData, which contains an unreadable owner row
				// inside its own transaction and reports it as ORPHANED. This catch
				// covers the remainder, and keeps ONE bad component from costing the
				// other components on the same event.
				log.warn("Skipping owner routing for component {}: ownership unreadable: {}",
						cd.getUuid(), e.getMessage());
				continue;
			}
			if (null == o || null == o.ownerRef()) continue;
			if (ComponentOwnerType.TEAM != o.ownerType()) continue;
			if (!ROUTABLE_OWNERSHIP.contains(o.status())) continue;
			ownerTeams.add(o.ownerRef());
		}
		// unmodifiableSet, not Set.copyOf: the copy would discard the insertion
		// order the LinkedHashSet above exists to guarantee. The guard paths
		// return Set.of(), so every path out of here is unmodifiable.
		return Collections.unmodifiableSet(ownerTeams);
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
				TeamData team = teamService.getTeamData(owner.ownerRef()).orElse(null);
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
	 * The org's ACTIVE teams that already write to {@code cd}, and are therefore
	 * the natural durable-owner candidates.
	 *
	 * <p>A team holds no permissions -- permissions live on {@link UserGroup} --
	 * so a team qualifies THROUGH a group it contains: any contained group with a
	 * {@code >= READ_WRITE} COMPONENT-scoped permission on this component makes
	 * the team a candidate. This indirection is the whole reason a Team can
	 * contain user groups; without it, moving ownership off UserGroup would have
	 * silently deleted the suggestion path.
	 *
	 * <p>Uses the shared {@link PermissionType#atLeast}, so candidacy cannot
	 * re-fork the membership rule the write-team and inbox-audience arms share.
	 */
	public List<TeamData> candidateOwnerTeams (ComponentData cd, OwnershipContext ctx) {
		UUID obj = cd.getUuid();
		return ctx.orgTeams().stream()
				.filter(t -> t.getStatus() == TeamStatus.ACTIVE)
				.filter(t -> t.getUserGroups().stream()
						.map(ctx.groupsById()::get)
						.filter(java.util.Objects::nonNull)
						.anyMatch(g -> g.getPermission(PermissionScope.COMPONENT, obj)
								.map(up -> PermissionType.atLeast(up.getType(), PermissionType.READ_WRITE))
								.orElse(false)))
				.toList();
	}

	/**
	 * @param ctx the hoisted batch context, or null on the single-component path
	 *            -- in which case only the owner team's OWN groups are fetched,
	 *            so a stored-owner read never costs an org-wide query
	 */
	private ComponentOwnership resolveStored (ComponentData cd, ComponentOwner owner, OwnershipContext ctx) {
		switch (owner.ownerType()) {
			case TEAM: {
				// Tolerant read: this runs inside the fan-out, and an unreadable
				// owner row must not decide the fate of the caller's transaction.
				// Reported as ORPHANED, exactly as a missing team already is --
				// from a routing point of view "cannot be read" and "is not there"
				// are the same answer.
				TeamData team = teamService.getReadableTeamData(owner.ownerRef()).orElse(null);
				if (null == team || !cd.getOrg().equals(team.getOrg())) {
					return orphaned(owner, "Owner team no longer exists in this organization");
				}
				if (team.getStatus() != TeamStatus.ACTIVE) {
					return new ComponentOwnership(ComponentOwnerType.TEAM, owner.ownerRef(), false,
							ComponentOwnershipStatus.DEGRADED, false, "Owner team is archived/inactive");
				}
				boolean durable = isTeamDurable(team, groupsFor(team, ctx));
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

	private ComponentOwnership suggestFromCandidates (ComponentData cd, OwnershipContext ctx) {
		List<TeamData> candidates = candidateOwnerTeams(cd, ctx);
		if (!candidates.isEmpty()) {
			// Prefer a durable candidate, then the largest roster.
			TeamData best = candidates.stream()
					.max(Comparator.comparing((TeamData t) -> isTeamDurable(t, ctx.groupsById()))
							.thenComparingInt(t -> t.rosterWith(ctx.groupsById()).size()))
					.orElseThrow();
			boolean durable = isTeamDurable(best, ctx.groupsById());
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
	 * A team is durable when it is ACTIVE and either its ROSTER reaches
	 * {@link #DURABLE_MIN_MEMBERS} or one of its contained groups is SSO-backed.
	 *
	 * <p>Both arms resolve TRANSITIVELY through contained user groups, which is
	 * the point of letting a team hold them. Counting only direct members would
	 * report every IdP-managed team of two hundred people as NON_DURABLE the
	 * moment ownership moved off UserGroup -- a false alarm on precisely the
	 * teams most likely to be real, and one that is a routing input, not just a
	 * report column.
	 */
	private boolean isTeamDurable (TeamData team, Map<UUID, UserGroupData> groupsById) {
		return team.getStatus() == TeamStatus.ACTIVE
				&& (team.rosterWith(groupsById).size() >= DURABLE_MIN_MEMBERS
						|| team.hasSsoBackedGroup(groupsById));
	}

	/**
	 * The groups a single team contains, taken from the batch context when there
	 * is one and fetched individually otherwise. Keeps the single-component read
	 * path off the org-wide query while giving the batch path its hoisted map.
	 */
	private Map<UUID, UserGroupData> groupsFor (TeamData team, OwnershipContext ctx) {
		if (null != ctx) return ctx.groupsById();
		Map<UUID, UserGroupData> groups = new LinkedHashMap<>();
		for (UUID groupUuid : team.getUserGroups()) {
			if (null == groupUuid) continue;
			userGroupService.getReadableUserGroupData(groupUuid)
					.ifPresent(ugd -> groups.put(groupUuid, ugd));
		}
		return groups;
	}

	private static ComponentOwnership orphaned (ComponentOwner owner, String reason) {
		return new ComponentOwnership(owner.ownerType(), owner.ownerRef(), false,
				ComponentOwnershipStatus.ORPHANED, false, reason);
	}
}
