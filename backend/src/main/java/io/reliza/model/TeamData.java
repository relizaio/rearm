/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.dto.CreateTeamDto;
import io.reliza.model.dto.TeamWebDto;
import io.reliza.model.dto.UpdateTeamDto;

/**
 * A Team is the organization's ADDRESSABLE unit: the thing you point a
 * notification route at, and (from Phase 2) the thing a component is owned by.
 *
 * <p><strong>A team is not a permission group and grants nothing.</strong>
 * Membership here confers no access whatsoever -- access comes from
 * {@link UserGroup} and the permission model, and the two are deliberately
 * separate entities so that neither drifts into doing the other's job. Approvals
 * in particular do NOT follow a team: {@code ApprovalNeedsService.usersAbleToApprove}
 * resolves purely from combined permissions and never reads a roster as a
 * roster. If you find yourself wanting "the team that approves this", the answer
 * is a permission, not a team.
 *
 * <p><strong>Why a team can contain user groups.</strong> This is not a
 * convenience feature. Component ownership reads UserGroup-specific state in two
 * places -- {@code ComponentOwnershipService.candidateOwnerTeams} looks for a
 * {@code >= READ_WRITE} COMPONENT-scoped permission, and {@code isTeamDurable}
 * looks at {@code connectedSsoGroups}. A team that could hold only individual
 * users would have neither, so once ownership retargets here in Phase 2 every
 * SSO-managed team of 200 people would report NON_DURABLE until two of them
 * happened to log in. Containing groups restores both transitively.
 *
 * <p><strong>No team-in-team.</strong> A team holds users and user groups, and a
 * user group cannot contain a user group, so the roster flattens in one step and
 * a cycle is not representable. There is deliberately no cycle detection here:
 * writing one would guard a hazard the schema forbids. If team nesting is ever
 * added, it needs the visited-set walk that
 * {@code ComponentTeamService.deriveWithParentFallback} models, including its
 * refusal to cross an org boundary.
 *
 * <p><strong>No external members.</strong> The UserGroup piggyback carried a
 * freeform {@code name}/{@code contact} pair for people without a ReARM account,
 * and nothing ever delivered to one -- there is no transport discriminator on
 * the contact string, the email dispatcher sends to a static per-channel
 * recipient list, and Slack/Teams post to an incoming webhook that cannot direct
 * message anyone. The field is not carried forward; when delivery to externals
 * is built it will want a TYPED contact, which this shape could not express.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamData extends RelizaDataParent implements RelizaObject {

	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org; // a team belongs to a single organization
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private TeamStatus status = TeamStatus.ACTIVE;
	/** Individually-added members. */
	@JsonProperty("members")
	private Set<UUID> members = new LinkedHashSet<>();
	/** Permission groups whose members are also on this team (see class javadoc). */
	@JsonProperty("userGroups")
	private Set<UUID> userGroups = new LinkedHashSet<>();
	/**
	 * Channels this team is reachable on -- e.g. its Slack channel. Held as
	 * DIRECT channel refs rather than a {@code NotificationChannelGroup}
	 * reference on purpose: the operator's model is "this team has a channel",
	 * and routing that through a second named entity would add a screen for no
	 * user benefit. Channel groups stay the org-level bundling concept for
	 * subscriptions; this is the team's own address.
	 */
	@JsonProperty("notificationChannels")
	private Set<UUID> notificationChannels = new LinkedHashSet<>();
	/**
	 * Members who may administer this team.
	 *
	 * <p>A SET, not a single lead, deliberately: one lead is a bus factor of one
	 * on a concept whose entire purpose is durable ownership, and
	 * {@code ComponentOwnershipService.DURABLE_MIN_MEMBERS} already encodes that
	 * judgement elsewhere in this subsystem.
	 *
	 * <p>Distinct from {@code ComponentData.leads}, which names people on a
	 * COMPONENT and grants nothing. This field is the one that will carry
	 * authority (Phase 3); nothing reads it for authorization yet.
	 */
	@JsonProperty("leads")
	private Set<UUID> leads = new LinkedHashSet<>();
	/**
	 * "Notify this team about the components it owns."
	 *
	 * <p>The team-facing half of the feature; the machinery is an ordinary
	 * subscription that {@code TeamService} materialises and keeps in step. Null
	 * on every team that has never touched the setting, and equivalent to
	 * disabled -- see {@link OwnedComponentNotifications}.
	 */
	@JsonProperty("ownedComponentNotifications")
	private OwnedComponentNotifications ownedComponentNotifications;
	@JsonProperty
	private UUID resourceGroup = CommonVariables.DEFAULT_RESOURCE_GROUP;

	/**
	 * A team's own notification preference for the components it owns.
	 *
	 * <p>{@code excludedEventTypes} is an OPT-OUT list, not a selection. The
	 * effective set is "every event type ReARM emits, minus these", which is what
	 * makes the editor's all-preselected picker honest for an existing team as
	 * well as a new one -- and means a team hears about a NEW class of event
	 * happening to its components without anyone editing the team. That is the
	 * intended default for an accountability feature, and it is the reason the
	 * list stores what a team said NO to rather than what it once said yes to: a
	 * stored inclusion list silently freezes a team's coverage at the moment it
	 * was written.
	 *
	 * <p>A null instance means never configured, and reads as disabled. The
	 * difference matters only for {@code TeamService}, which does no
	 * materialisation at all for a team that has never opted in, rather than
	 * writing a DISABLED subscription nobody asked for.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record OwnedComponentNotifications(
			boolean enabled,
			Set<NotificationEventType> excludedEventTypes) {

		/** Normalises the exclusion list so consumers never see null. */
		public OwnedComponentNotifications {
			// LinkedHashSet, not Set.copyOf: the latter's iteration order depends
			// on a per-JVM salt, so the same exclusion list would serialise into
			// JSONB differently after every restart -- churn in the record and
			// noise in the audit diff for a field nobody edited.
			excludedEventTypes = null == excludedEventTypes
					? Set.of()
					: Collections.unmodifiableSet(new LinkedHashSet<>(excludedEventTypes));
		}
	}

	private TeamData() {}

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

	public TeamStatus getStatus() {
		return status;
	}

	private void setStatus(TeamStatus status) {
		this.status = status;
	}

	public Set<UUID> getMembers() {
		return new LinkedHashSet<>(members);
	}

	private void setMembers(Collection<UUID> members) {
		this.members = new LinkedHashSet<>(members);
	}

	public Set<UUID> getUserGroups() {
		return new LinkedHashSet<>(userGroups);
	}

	private void setUserGroups(Collection<UUID> userGroups) {
		this.userGroups = new LinkedHashSet<>(userGroups);
	}

	public Set<UUID> getNotificationChannels() {
		return new LinkedHashSet<>(notificationChannels);
	}

	private void setNotificationChannels(Collection<UUID> notificationChannels) {
		this.notificationChannels = new LinkedHashSet<>(notificationChannels);
	}

	public Set<UUID> getLeads() {
		return new LinkedHashSet<>(leads);
	}

	private void setLeads(Collection<UUID> leads) {
		this.leads = new LinkedHashSet<>(leads);
	}

	/** Null when the team has never configured it; treat as disabled. */
	public OwnedComponentNotifications getOwnedComponentNotifications() {
		return ownedComponentNotifications;
	}

	private void setOwnedComponentNotifications(OwnedComponentNotifications ocn) {
		this.ownedComponentNotifications = ocn;
	}

	/** True when this team has asked to be notified about the components it owns. */
	@JsonIgnore
	public boolean notifiesOnOwnedComponents() {
		return null != ownedComponentNotifications && ownedComponentNotifications.enabled();
	}

	/**
	 * The event types this team's owned-component subscription should carry:
	 * everything with a producer, minus what the team excluded.
	 *
	 * <p>Event types that carry no affected component are dropped regardless of
	 * the exclusion list -- today that is VEX alone. An ownership-scoped
	 * subscription could never match one, so including it would put a
	 * permanently dead event type on every team's subscription, which is exactly
	 * the kind of control that makes an operator distrust the rest of the list.
	 * The rule lives on {@link NotificationEventType#carriesAffectedComponents}
	 * so it changes in one place when a VEX producer ships.
	 */
	@JsonIgnore
	public Set<NotificationEventType> effectiveOwnedComponentEventTypes() {
		Set<NotificationEventType> excluded = null == ownedComponentNotifications
				? Set.of() : ownedComponentNotifications.excludedEventTypes();
		Set<NotificationEventType> out = new LinkedHashSet<>();
		for (NotificationEventType t : NotificationEventType.values()) {
			if (!t.carriesAffectedComponents()) continue;
			if (!excluded.contains(t)) out.add(t);
		}
		return out;
	}

	@Override
	public UUID getResourceGroup() {
		return this.resourceGroup;
	}

	/**
	 * The team's full roster -- direct members plus the members of every
	 * contained user group -- given groups the caller has ALREADY resolved.
	 *
	 * <p>Taking the map rather than a service is what keeps one implementation
	 * serving both entry points: {@code TeamService.resolveRoster} looks the
	 * groups up per team for the write path, while the ownership batch hoists
	 * every group in the org once and passes the same map for hundreds of
	 * components. Two roster computations would be two chances to disagree about
	 * who is on a team, and durability and lead-validation would drift apart.
	 *
	 * <p>Flattening is ONE level by construction: a user group cannot contain a
	 * user group, so there is no cycle to detect. A group that is missing from
	 * the map or belongs to another org contributes nothing -- reads tolerate
	 * dangling references.
	 */
	@JsonIgnore
	public Set<UUID> rosterWith(Map<UUID, UserGroupData> groupsById) {
		Set<UUID> roster = new LinkedHashSet<>(members);
		if (null == groupsById) return roster;
		for (UUID groupUuid : userGroups) {
			UserGroupData ugd = groupsById.get(groupUuid);
			if (null == ugd || !org.equals(ugd.getOrg())) continue;
			roster.addAll(ugd.getAllUsers());
		}
		return roster;
	}

	/**
	 * True when any contained group is SSO-backed. Durability treats that as
	 * good as a full roster: an IdP-managed team whose members have not logged
	 * in yet is not actually fragile, and flagging it would be a false alarm on
	 * exactly the teams most likely to be real.
	 */
	@JsonIgnore
	public boolean hasSsoBackedGroup(Map<UUID, UserGroupData> groupsById) {
		if (null == groupsById) return false;
		for (UUID groupUuid : userGroups) {
			UserGroupData ugd = groupsById.get(groupUuid);
			if (null == ugd || !org.equals(ugd.getOrg())) continue;
			if (!ugd.getConnectedSsoGroups().isEmpty()) return true;
		}
		return false;
	}

	/**
	 * Merge semantics, matching every other record here: a null field on the DTO
	 * means "leave unchanged", a non-null one REPLACES. Callers that hold a
	 * partially-loaded view must omit rather than send an empty collection --
	 * sending {@code []} is how a roster gets silently emptied.
	 */
	public static TeamData updateTeamData(TeamData td, UpdateTeamDto updateDto) {
		TeamData updated = new TeamData();
		// Org is fixed at creation: a team cannot move tenant.
		updated.setOrg(td.getOrg());
		updated.setName(null != updateDto.getName() ? updateDto.getName() : td.getName());
		updated.setDescription(null != updateDto.getDescription()
				? updateDto.getDescription() : td.getDescription());
		updated.setStatus(null != updateDto.getStatus() ? updateDto.getStatus() : td.getStatus());
		updated.setMembers(null != updateDto.getMembers() ? updateDto.getMembers() : td.getMembers());
		updated.setUserGroups(null != updateDto.getUserGroups()
				? updateDto.getUserGroups() : td.getUserGroups());
		updated.setNotificationChannels(null != updateDto.getNotificationChannels()
				? updateDto.getNotificationChannels() : td.getNotificationChannels());
		updated.setLeads(null != updateDto.getLeads() ? updateDto.getLeads() : td.getLeads());
		// Null-means-keep, like every collection above. An editor that never
		// loaded the setting must not be able to switch a team's notifications
		// off by omitting it.
		updated.setOwnedComponentNotifications(null != updateDto.getOwnedComponentNotifications()
				? updateDto.getOwnedComponentNotifications() : td.getOwnedComponentNotifications());
		return updated;
	}

	public static TeamData teamDataFactory(CreateTeamDto createDto) {
		TeamData td = new TeamData();
		td.setName(createDto.getName());
		td.setDescription(createDto.getDescription());
		td.setOrg(createDto.getOrg());
		return td;
	}

	public static TeamData dataFromRecord(Team t) {
		if (t.getSchemaVersion() != 0) { // if schema version is not supported, throw exception
			throw new IllegalStateException("Team schema version is " + t.getSchemaVersion()
			+ ", which is not currently supported");
		}
		Map<String,Object> recordData = t.getRecordData();
		TeamData td = Utils.OM.convertValue(recordData, TeamData.class);
		td.setUuid(t.getUuid());
		return td;
	}

	public static TeamWebDto toWebDto(TeamData td) {
		return TeamWebDto.builder()
				.uuid(td.getUuid())
				.name(td.getName())
				.description(td.getDescription())
				.org(td.getOrg())
				.status(td.getStatus())
				.members(td.getMembers())
				.userGroups(td.getUserGroups())
				.notificationChannels(td.getNotificationChannels())
				.leads(td.getLeads())
				.ownedComponentNotifications(td.getOwnedComponentNotifications())
				.build();
	}
}
