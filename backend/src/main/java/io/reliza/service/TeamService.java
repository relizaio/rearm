/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.IntegrationData;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.Team;
import io.reliza.model.TeamData;
import io.reliza.model.TeamStatus;
import io.reliza.model.UserGroupData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateTeamDto;
import io.reliza.model.dto.UpdateTeamDto;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.repositories.TeamRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD for {@link Team}, the org's addressable unit.
 *
 * <p>Phase 1 of the Team work (see {@code ai-plans/team-entity-design.md}): this
 * service creates and edits teams and nothing consumes them yet. The fan-out's
 * route targets, owner-team channel resolution and {@code ComponentOwnerType.TEAM}
 * all still resolve against {@code UserGroup}; switching them is a separate,
 * reviewable change.
 *
 * <p>Every referential rule lives HERE rather than in the data fetcher, so a
 * programmatic caller is held to the same invariants as the GraphQL one. No DB
 * foreign key backs any of these uuids -- validation on write plus tolerance on
 * read is the house pattern, and this service is the write half of it.
 */
@Slf4j
@Service
@Transactional
public class TeamService {

	@Autowired
	private TeamRepository teamRepository;

	@Autowired
	private AuditService auditService;

	@Autowired
	@Lazy
	private UserService userService;

	@Autowired
	@Lazy
	private UserGroupService userGroupService;

	@Autowired
	@Lazy
	private NotificationChannelService notificationChannelService;

	@Autowired
	@Lazy
	private GetOrganizationService getOrganizationService;

	// Lazy, like the collaborators above: TeamService <-> subscription wiring is
	// mutually referential (the subscription service validates team refs, this
	// one materialises a subscription), and eager injection would be a cycle.
	@Autowired
	@Lazy
	private NotificationSubscriptionService subscriptionService;


	public Optional<Team> getTeam(UUID teamUuid) {
		return teamRepository.findById(teamUuid);
	}

	public Optional<TeamData> getTeamData(UUID teamUuid) {
		return getTeam(teamUuid).map(TeamData::dataFromRecord);
	}

	/**
	 * ALL teams in an org, archived ones included -- the name says so because
	 * {@code UserGroupService} already established the opposite convention
	 * ({@code getUserGroupsByOrganization} is ACTIVE-only,
	 * {@code getAllUserGroupsByOrganization} is everything). Phase 2's fan-out
	 * resolver must drop INACTIVE teams, and reaching for a same-named method
	 * that silently included them would put archived teams back in the delivery
	 * set.
	 *
	 * <p>One unreadable row costs only that row: an org-wide LIST that dies for a
	 * single malformed {@code record_data} is useful to nobody.
	 */
	@Transactional(readOnly = true)
	public List<TeamData> getAllTeamsByOrganization(UUID orgUuid) {
		List<Team> teams = teamRepository.findAllByOrganization(orgUuid.toString());
		List<TeamData> out = new ArrayList<>(teams.size());
		for (Team t : teams) {
			try {
				out.add(TeamData.dataFromRecord(t));
			} catch (RuntimeException e) {
				log.warn("Skipping unreadable team {} in org {}: {}", t.getUuid(), orgUuid, e.getMessage());
			}
		}
		return out;
	}

	/**
	 * Tolerant single read: an unreadable {@code record_data} or unsupported
	 * {@code schemaVersion} yields empty rather than throwing.
	 *
	 * <p>The catch MUST live here, inside the transactional boundary that would
	 * otherwise propagate. A caller reaching this across the bean proxy cannot
	 * contain the exception itself -- by the time its catch runs, Spring has
	 * already marked the shared transaction rollback-only and the commit is
	 * doomed. Containment belongs in the method that throws.
	 */
	@Transactional(readOnly = true)
	public Optional<TeamData> getReadableTeamData(UUID teamUuid) {
		try {
			return getTeamData(teamUuid);
		} catch (RuntimeException e) {
			log.warn("Skipping unreadable team {}: {}", teamUuid, e.getMessage());
			return Optional.empty();
		}
	}

	/** Internal save with audit logging and revision increment. */
	private Team saveTeam(Team t, Map<String, Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("Team must have record data");
		}
		Optional<Team> existing = teamRepository.findById(t.getUuid());
		if (existing.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.TEAMS, t);
			t.setRevision(t.getRevision() + 1);
			t.setLastUpdatedDate(ZonedDateTime.now());
		}
		t.setRecordData(recordData);
		t = (Team) WhoUpdated.injectWhoUpdatedData(t, wu);
		return teamRepository.save(t);
	}

	public TeamData createTeam(CreateTeamDto createDto, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(createDto.getName())) {
			throw new RelizaException("Team name is required");
		}
		if (null == createDto.getOrg()) {
			throw new RelizaException("Team organization is required");
		}
		// The fetcher authorizes against this org, which incidentally proves it
		// exists -- but this service promises that every referential rule holds
		// for programmatic callers too, and an unchecked org here is how an
		// orphan team gets created with no screen that can ever show it.
		if (getOrganizationService.getOrganizationData(createDto.getOrg()).isEmpty()) {
			throw new RelizaException("Organization not found: " + createDto.getOrg());
		}
		requireNameAvailable(createDto.getOrg(), createDto.getName(), null);

		TeamData td = TeamData.teamDataFactory(createDto);
		Map<String, Object> recordData = Utils.OM.convertValue(td, new TypeReference<Map<String, Object>>() {});
		return TeamData.dataFromRecord(saveTeam(new Team(), recordData, wu));
	}

	/**
	 * Read-modify-write under a PESSIMISTIC_WRITE lock, as every other service
	 * doing this shape does (AgentService, SigningKeyService, PullRequestService,
	 * OssReleaseService...). Without it two concurrent edits both read revision N
	 * and both write N+1: one is silently lost, and because the audit record is
	 * keyed on (uuid, table, revision) the overwritten version leaves no trace to
	 * recover from. For an entity whose collections are replace-on-write, a lost
	 * update means a roster or channel list quietly reverting.
	 */
	@Transactional(rollbackFor = RelizaException.class)
	public TeamData updateTeam(UpdateTeamDto updateDto, WhoUpdated wu) throws RelizaException {
		Optional<Team> existing = teamRepository.findByIdWriteLocked(updateDto.getTeamId());
		if (existing.isEmpty()) {
			throw new RelizaException("Team not found: " + updateDto.getTeamId());
		}
		Team t = existing.get();
		TeamData td = TeamData.dataFromRecord(t);

		String effectiveName = null != updateDto.getName() ? updateDto.getName() : td.getName();
		if (StringUtils.isBlank(effectiveName)) {
			throw new RelizaException("Team name is required");
		}
		boolean renaming = !effectiveName.equals(td.getName());
		boolean restoring = TeamStatus.ACTIVE == updateDto.getStatus() && TeamStatus.INACTIVE == td.getStatus();
		if (renaming || restoring) {
			requireNameAvailable(td.getOrg(), effectiveName, td.getUuid());
		}

		TeamData updated = TeamData.updateTeamData(td, updateDto);

		// Validate the POST-MERGE state, not the incoming DTO. An update that
		// omits members but sends leads has to be checked against the members
		// already stored, otherwise a lead validates against an empty roster.
		validateMembers(updated.getMembers(), td.getOrg());
		validateUserGroups(updated.getUserGroups(), td.getOrg());
		notificationChannelService.validateChannelRefs(updated.getNotificationChannels(), td.getOrg(), "team");
		validateLeads(updated.getLeads(), resolveRoster(updated));
		// Up front, because the alternative is a save that half-succeeds: the
		// subscription seed would fail validateSeed's "at least one eventType"
		// deep inside materialisation, and RelizaException is CHECKED, so Spring's
		// default rollback rule (RuntimeException only) would commit the team
		// anyway. Catching it here means the operator gets a message about the
		// thing they actually did.
		validateOwnedComponentNotifications(updated);

		Map<String, Object> recordData = Utils.OM.convertValue(updated, new TypeReference<Map<String, Object>>() {});
		TeamData saved = TeamData.dataFromRecord(saveTeam(t, recordData, wu));
		syncOwnedComponentSubscription(saved, wu);
		return saved;
	}

	/**
	 * Bring the team's owned-component subscription in line with the team.
	 *
	 * <p>Runs in {@code updateTeam}'s transaction on purpose: a team whose toggle
	 * says "on" while no subscription exists is a lie no screen would reveal, so
	 * the two commit together or not at all. A failure here therefore rolls the
	 * team edit back, which is the intended trade -- the alternative is silent
	 * divergence between what the team claims and what fan-out will do.
	 *
	 * <p>What it does NOT do is delete. A toggle turned off, or a team archived,
	 * DISABLES the row: delivery history resolves a subscription's name through
	 * the row itself, so deleting one orphans the attribution of every delivery
	 * it ever produced. A disabled row is skipped by fan-out
	 * ({@code findActiveByOrg}) and costs nothing but a line in the list.
	 *
	 * <p>Idempotent. Saving a team that changed nothing relevant rewrites the
	 * same record, which matters because a rename, an archive, a restore and an
	 * exclusion edit all arrive through this one path.
	 */
	private void syncOwnedComponentSubscription(TeamData td, WhoUpdated wu) throws RelizaException {
		Optional<NotificationSubscription> existing =
				subscriptionService.findManagedFor(td.getOrg(), td.getUuid());
		boolean wanted = td.notifiesOnOwnedComponents();
		if (existing.isEmpty() && !wanted) {
			// Never configured, still not configured. Writing a DISABLED row for
			// every team in the org would fill the subscription list with rows
			// nobody asked for.
			return;
		}
		// ACTIVE only when the team both wants it AND is itself active. An
		// archived team resolves to no channels, so an ACTIVE subscription on one
		// would match events and deliver nothing -- indistinguishable, from the
		// list, from a subscription that is working.
		NotificationSubscriptionStatus status = wanted && TeamStatus.ACTIVE == td.getStatus()
				? NotificationSubscriptionStatus.ACTIVE
				: NotificationSubscriptionStatus.DISABLED;
		NotificationSubscriptionData seed = new NotificationSubscriptionData(
				td.getOrg(), td.getResourceGroup(), ownedComponentSubscriptionName(td), status,
				List.copyOf(td.effectiveOwnedComponentEventTypes()),
				null,
				// Scope and destination are the two halves that make this a team's
				// own feed: ownedByTeam matches only events about components this
				// team owns, and the team target delivers to whatever channels it
				// has AT FIRE TIME, so changing its Slack channel needs no re-save.
				List.of(new RouteConfig(null, null, null, List.of(), null, null,
						List.of(td.getUuid()))),
				null, null, td.getUuid(), td.getUuid());
		// Skip a no-op write. Every team edit reaches this -- adding one member
		// would otherwise bump the subscription's revision and write an audit
		// record for a byte-identical seed.
		if (existing.isPresent() && seedMatches(existing.get(), seed)) return;
		subscriptionService.upsertManagedSubscription(
				existing.map(NotificationSubscription::getUuid).orElse(null), seed, wu);
	}

	/**
	 * A team cannot exclude its way to an empty subscription.
	 *
	 * <p>Rejected here, with a message about event types, rather than left to
	 * surface from inside materialisation as "at least one eventType is
	 * required" -- an error about an object the operator never knew existed.
	 */
	private void validateOwnedComponentNotifications(TeamData td) throws RelizaException {
		if (!td.notifiesOnOwnedComponents()) return;
		if (td.effectiveOwnedComponentEventTypes().isEmpty()) {
			throw new RelizaException("A team cannot be notified about its components while"
					+ " excluding every event type -- switch the setting off instead");
		}
	}

	/** True when the stored row already says exactly what the seed would write. */
	private static boolean seedMatches(NotificationSubscription existing,
			NotificationSubscriptionData seed) {
		try {
			return Utils.OM.convertValue(existing.getRecordData(),
					NotificationSubscriptionData.class).equals(seed);
		} catch (RuntimeException e) {
			// Unreadable: rewrite it rather than skip. This is the one caller
			// entitled to overwrite the row.
			return false;
		}
	}

	/**
	 * The managed subscription's name, which follows the team's.
	 *
	 * <p>It shows up in delivery history and the subscription list, where "which
	 * team is this?" is the only question worth answering.
	 */
	private static String ownedComponentSubscriptionName(TeamData td) {
		return td.getName() + " -- owned components";
	}

	/**
	 * The deduplicated channels of the given teams, in first-seen order. This is
	 * what lets a notification route say "this team" and follow the team when it
	 * changes channel, instead of naming a channel that goes stale.
	 *
	 * <p>Runs inside the fan-out, so every failure mode here is contained rather
	 * than propagated: a team that no longer exists, belongs to another org, is
	 * archived, or cannot be read contributes nothing. One stale reference must
	 * not silence the other targets of the same route -- that is not a
	 * hypothetical, an unreadable {@code user_groups} row once wedged the fan-out
	 * for every org.
	 *
	 * <p>ARCHIVED TEAMS ARE DROPPED. The picker hides them, so an operator who
	 * archives a team reasonably expects delivery to stop; without this an
	 * already-saved route keeps firing at it forever.
	 *
	 * <p>Fails CLOSED on a missing org: a guard a caller can switch off by
	 * passing null is not a guard.
	 */
	@Transactional(readOnly = true)
	public List<UUID> resolveTeamChannelUuids(Iterable<UUID> teamUuids, UUID expectedOrg) {
		if (null == teamUuids || null == expectedOrg) return List.of();
		List<UUID> out = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (UUID teamUuid : teamUuids) {
			if (null == teamUuid) continue;
			// The READABLE variant, whose catch lives inside its own transactional
			// boundary. A try/catch here would be useless: the exception escaping a
			// @Transactional method marks this transaction rollback-only on the way
			// out, and nothing at a call site can undo that.
			TeamData td = getReadableTeamData(teamUuid).orElse(null);
			if (null == td) continue;
			// Org guard on the read side too. Writes validate, but this is the read
			// half of a cross-tenant path and one conditional between a stale route
			// and another org's team is not enough.
			if (!expectedOrg.equals(td.getOrg())) continue;
			if (TeamStatus.ACTIVE != td.getStatus()) continue;
			for (UUID ch : td.getNotificationChannels()) {
				if (null != ch && seen.add(ch)) out.add(ch);
			}
		}
		return out;
	}

	/**
	 * The team's full roster: individually-added members plus the members of
	 * every contained user group.
	 *
	 * <p>Flattening is ONE level by construction -- a team holds users and user
	 * groups, and a user group cannot contain a user group -- so there is no
	 * cycle to detect. A contained group that no longer exists or belongs to
	 * another org contributes nothing rather than failing the call: this is a
	 * read, and reads tolerate dangling references.
	 */
	public Set<UUID> resolveRoster(TeamData td) {
		Map<UUID, UserGroupData> groupsById = new LinkedHashMap<>();
		for (UUID groupUuid : td.getUserGroups()) {
			if (null == groupUuid) continue;
			// getREADABLE, not getUserGroupData: UserGroupService is @Transactional
			// at class level, so an exception escaping it across the bean proxy
			// marks OUR transaction rollback-only before any catch here could see
			// it -- updateTeam would then die at commit with an
			// UnexpectedRollbackException instead of tolerating one bad row.
			// Containment has to live in the method that throws.
			userGroupService.getReadableUserGroupData(groupUuid)
					.ifPresent(ugd -> groupsById.put(groupUuid, ugd));
		}
		// One implementation of "who is on this team", shared with the ownership
		// batch, which hoists the org's groups instead of looking them up per team.
		return td.rosterWith(groupsById);
	}

	/**
	 * Name uniqueness per org, covering ARCHIVED teams too -- restoring one must
	 * not collide with a name taken since it was archived.
	 *
	 * <p>This is a read-then-write check, so two concurrent creates can both pass
	 * it; the unique index added in V77 is what actually holds the line. Keeping
	 * both is deliberate: the index guarantees correctness, this gives the
	 * operator a sentence they can act on instead of a constraint-violation
	 * stack trace.
	 *
	 * <p>It reads the TOLERANT list, so a team whose {@code record_data} is
	 * unreadable is invisible here even though its name still occupies the
	 * index. That case degrades to the constraint violation rather than the
	 * friendly message -- accepted, because the alternative (a strict read) would
	 * make one malformed row block every create in the org, which is worse.
	 */
	private void requireNameAvailable(UUID orgUuid, String name, UUID selfUuid) throws RelizaException {
		Optional<TeamData> conflict = getAllTeamsByOrganization(orgUuid).stream()
				.filter(existing -> null == selfUuid || !selfUuid.equals(existing.getUuid()))
				.filter(existing -> name.equals(existing.getName()))
				.findFirst();
		if (conflict.isPresent()) {
			if (TeamStatus.INACTIVE == conflict.get().getStatus()) {
				throw new RelizaException("An archived team named '" + name + "' already exists. "
						+ "Restore it instead of creating a new one.");
			}
			throw new RelizaException("A team named '" + name + "' already exists.");
		}
	}

	/** Every member must be a user of this org. */
	void validateMembers(Set<UUID> members, UUID orgUuid) throws RelizaException {
		if (null == members) return;
		for (UUID userUuid : members) {
			if (null == userUuid) throw new RelizaException("Team members cannot contain null entries");
			if (userService.getUserDataWithOrg(userUuid, orgUuid).isEmpty()) {
				throw new RelizaException("User " + userUuid + " is not a member of this organization");
			}
		}
	}

	/** Every contained group must exist and belong to this org. */
	void validateUserGroups(Set<UUID> groups, UUID orgUuid) throws RelizaException {
		if (null == groups) return;
		for (UUID groupUuid : groups) {
			if (null == groupUuid) throw new RelizaException("Team user groups cannot contain null entries");
			// As in resolveRoster: a cross-bean call to the throwing variant would
			// poison this transaction before we could turn it into a message. The
			// readable variant collapses "unreadable" and "missing" into empty,
			// which is the same answer from here -- either way the operator has to
			// drop the reference.
			UserGroupData ugd = userGroupService.getReadableUserGroupData(groupUuid).orElse(null);
			if (null == ugd) {
				throw new RelizaException("User group not found or unreadable: " + groupUuid
						+ ". Remove it from this team to continue.");
			}
			if (!orgUuid.equals(ugd.getOrg())) {
				throw new RelizaException("User group " + groupUuid
						+ " does not belong to this organization");
			}
		}
	}

	/**
	 * A lead must be ON the team -- directly or through a contained group.
	 *
	 * <p>Leads will carry administrative authority over the team (Phase 3), so
	 * "lead of a team you are not on" is not a harmless label, it is a privilege
	 * with no membership behind it. Checked against the POST-MERGE roster, so an
	 * update that removes someone from members and leaves them in leads is
	 * rejected rather than silently retained.
	 */
	void validateLeads(Set<UUID> leads, Set<UUID> roster) throws RelizaException {
		if (null == leads) return;
		for (UUID leadUuid : leads) {
			if (null == leadUuid) throw new RelizaException("Team leads cannot contain null entries");
			if (!roster.contains(leadUuid)) {
				throw new RelizaException("A team lead must be a member of the team: " + leadUuid);
			}
		}
	}
}
