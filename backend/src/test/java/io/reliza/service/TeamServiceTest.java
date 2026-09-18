/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.Organization;
import io.reliza.model.TeamData;
import io.reliza.model.TeamStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateTeamDto;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateTeamDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Team is a NEW entity with no consumers yet, so the only things worth pinning
 * are the rules that decide what can be stored -- every one of them guards a
 * dangling or nonsensical reference that no DB foreign key will catch.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class TeamServiceTest {

	@Autowired private TeamService teamService;
	@Autowired private UserGroupService userGroupService;
	@Autowired private UserService userService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private NotificationSubscriptionRepository subscriptionRepo;
	@Autowired private NotificationSubscriptionService subscriptionService;

	/** A real user of the given org -- membership is what every rule here checks. */
	private UUID userIn(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString();
		User u = userService.createUser("TeamTestUser_" + tag, tag + "@teamtest.io", false,
				List.of(org.getUuid()), "oauth_" + tag, OauthType.GITHUB, WhoUpdated.getTestWhoUpdated());
		return UserData.dataFromRecord(u).getUuid();
	}

	private TeamData createTeam(UUID org, String name) throws RelizaException {
		return teamService.createTeam(CreateTeamDto.builder()
				.name(name).org(org).build(), WhoUpdated.getTestWhoUpdated());
	}

	private TeamData update(UpdateTeamDto dto) throws RelizaException {
		return teamService.updateTeam(dto, WhoUpdated.getTestWhoUpdated());
	}

	@Test
	public void createdTeamIsActiveAndEmpty() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-created-" + UUID.randomUUID());
		Assertions.assertEquals(TeamStatus.ACTIVE, td.getStatus());
		Assertions.assertTrue(td.getMembers().isEmpty());
		Assertions.assertTrue(td.getUserGroups().isEmpty());
		Assertions.assertTrue(td.getLeads().isEmpty());
		Assertions.assertTrue(td.getNotificationChannels().isEmpty());
	}

	@Test
	public void aDuplicateNameInTheSameOrgIsRejected() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String name = "team-dup-" + UUID.randomUUID();
		createTeam(org.getUuid(), name);
		Assertions.assertThrows(RelizaException.class, () -> createTeam(org.getUuid(), name),
				"two teams in one org must not share a name");
	}

	@Test
	public void anArchivedNameIsStillTaken() throws RelizaException {
		// Archiving is a soft delete, so the name stays reserved -- otherwise
		// restoring the archived team later would collide with whatever took it.
		Organization org = testInitializer.obtainOrganization();
		String name = "team-archived-" + UUID.randomUUID();
		TeamData td = createTeam(org.getUuid(), name);
		update(UpdateTeamDto.builder().teamId(td.getUuid()).status(TeamStatus.INACTIVE).build());
		RelizaException e = Assertions.assertThrows(RelizaException.class,
				() -> createTeam(org.getUuid(), name));
		Assertions.assertTrue(e.getMessage().contains("Restore it"),
				"the operator needs to be told to restore, not just that the name is taken; got: "
				+ e.getMessage());
	}

	@Test
	public void omittingAFieldKeepsItAndAnEmptyCollectionClearsIt() throws RelizaException {
		// The null-means-keep contract. Anything that gets this wrong empties a
		// roster with no undo, so it is pinned rather than trusted.
		Organization org = testInitializer.obtainOrganization();
		UUID member = userIn(org);
		TeamData td = createTeam(org.getUuid(), "team-merge-" + UUID.randomUUID());

		TeamData withMember = update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).members(Set.of(member)).build());
		Assertions.assertEquals(Set.of(member), withMember.getMembers());

		TeamData renamedOnly = update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).description("just a description").build());
		Assertions.assertEquals(Set.of(member), renamedOnly.getMembers(),
				"omitting members must leave the roster alone");

		TeamData cleared = update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).members(Set.of()).build());
		Assertions.assertTrue(cleared.getMembers().isEmpty(),
				"an explicit empty set must replace, so a real clear is expressible");
	}

	@Test
	public void aMemberFromAnotherOrgIsRejected() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-foreign-member-" + UUID.randomUUID());
		UUID stranger = UUID.randomUUID();
		Assertions.assertThrows(RelizaException.class, () -> update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).members(Set.of(stranger)).build()));
	}

	@Test
	public void aMissingUserGroupIsRejected() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-missing-group-" + UUID.randomUUID());
		Assertions.assertThrows(RelizaException.class, () -> update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).userGroups(Set.of(UUID.randomUUID())).build()));
	}

	@Test
	public void anIntegrationThatIsNotANotificationChannelIsRejected() throws RelizaException {
		// Without this a same-org CI integration can be attached as a team
		// channel, and fan-out then writes a delivery row per event that the
		// worker terminates FAILED for want of a dispatcher.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-bad-channel-" + UUID.randomUUID());
		Assertions.assertThrows(RelizaException.class, () -> update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).notificationChannels(Set.of(UUID.randomUUID())).build()));
	}

	@Test
	public void aLeadWhoIsNotOnTheTeamIsRejected() throws RelizaException {
		// A lead will carry administrative authority over the team, so "lead of a
		// team you are not on" is a privilege with no membership behind it.
		Organization org = testInitializer.obtainOrganization();
		UUID user = userIn(org);
		TeamData td = createTeam(org.getUuid(), "team-orphan-lead-" + UUID.randomUUID());
		RelizaException e = Assertions.assertThrows(RelizaException.class,
				() -> update(UpdateTeamDto.builder().teamId(td.getUuid()).leads(Set.of(user)).build()));
		Assertions.assertTrue(e.getMessage().contains("must be a member"), e.getMessage());
	}

	@Test
	public void aDirectMemberMayBeALead() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID user = userIn(org);
		TeamData td = createTeam(org.getUuid(), "team-lead-ok-" + UUID.randomUUID());
		TeamData saved = update(UpdateTeamDto.builder().teamId(td.getUuid())
				.members(Set.of(user)).leads(Set.of(user)).build());
		Assertions.assertEquals(Set.of(user), saved.getLeads());
	}

	@Test
	public void membershipViaAContainedUserGroupCountsForLeadership() throws RelizaException {
		// The point of containing user groups: the roster flattens through them.
		// If this regresses, leads assigned through a group become unsettable and
		// -- in Phase 2 -- ownership durability silently changes meaning.
		Organization org = testInitializer.obtainOrganization();
		UUID user = userIn(org);
		UserGroupData ugd = userGroupService.createUserGroup(CreateUserGroupDto.builder()
				.name("group-for-team-" + UUID.randomUUID()).org(org.getUuid()).build(),
				WhoUpdated.getTestWhoUpdated());
		userGroupService.updateUserGroupComprehensive(UpdateUserGroupDto.builder()
				.groupId(ugd.getUuid()).manualUsers(Set.of(user)).status(UserGroupStatus.ACTIVE).build(),
				WhoUpdated.getTestWhoUpdated());

		TeamData td = createTeam(org.getUuid(), "team-group-lead-" + UUID.randomUUID());
		TeamData saved = update(UpdateTeamDto.builder().teamId(td.getUuid())
				.userGroups(Set.of(ugd.getUuid())).leads(Set.of(user)).build());
		Assertions.assertEquals(Set.of(user), saved.getLeads(),
				"a user on the team only through a contained group is still on the team");
		Assertions.assertTrue(teamService.resolveRoster(saved).contains(user));
		Assertions.assertFalse(saved.getMembers().contains(user),
				"the direct member list must NOT absorb group members -- one source of truth each");
	}

	@Test
	public void theRosterToleratesADanglingUserGroup() throws RelizaException {
		// Reads tolerate what writes reject: a group deleted after being attached
		// must cost only its own members, not the whole roster resolution.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-dangling-" + UUID.randomUUID());
		UUID member = userIn(org);
		TeamData saved = update(UpdateTeamDto.builder()
				.teamId(td.getUuid()).members(Set.of(member)).build());

		// Reach past validation the way reality does -- the group vanishes later.
		TeamData withGhost = TeamData.updateTeamData(saved, UpdateTeamDto.builder()
				.teamId(saved.getUuid()).userGroups(Set.of(UUID.randomUUID())).build());
		Set<UUID> roster = teamService.resolveRoster(withGhost);
		Assertions.assertEquals(Set.of(member), roster,
				"a dangling group contributes nothing and breaks nothing");
	}

	@Test
	public void teamsAreListedPerOrganization() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String name = "team-listed-" + UUID.randomUUID();
		createTeam(org.getUuid(), name);
		List<TeamData> teams = teamService.getAllTeamsByOrganization(org.getUuid());
		Assertions.assertTrue(teams.stream().anyMatch(t -> name.equals(t.getName())));
		Assertions.assertTrue(teams.stream().allMatch(t -> org.getUuid().equals(t.getOrg())));
	}

	// ---------- owned-component notifications: the materialised subscription ----------
	//
	// The team's toggle is the only visible control; everything below it is a
	// subscription the operator never wrote. So what is worth pinning is not that
	// the field stores -- it is that the row and the toggle can never disagree,
	// through every path that touches a team: enable, disable, archive, restore,
	// rename, and exclusion edits.

	private NotificationSubscriptionData managedFor(UUID teamUuid) {
		return subscriptionRepo.findManagedByTeamAnyOrg(teamUuid)
				.map(s -> Utils.OM.convertValue(s.getRecordData(), NotificationSubscriptionData.class))
				.orElse(null);
	}

	private TeamData setNotifications(TeamData td, boolean enabled,
			Set<NotificationEventType> excluded) throws RelizaException {
		return update(UpdateTeamDto.builder()
				.teamId(td.getUuid())
				.ownedComponentNotifications(new TeamData.OwnedComponentNotifications(enabled, excluded))
				.build());
	}

	@Test
	public void aTeamThatNeverConfiguredItGetsNoSubscription() throws RelizaException {
		// The list must not fill with DISABLED rows for every team in the org.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-none-" + UUID.randomUUID());
		update(UpdateTeamDto.builder().teamId(td.getUuid()).description("unrelated edit").build());
		Assertions.assertNull(managedFor(td.getUuid()),
				"a team that never opted in should own no subscription at all");
	}

	@Test
	public void enablingMaterialisesAScopedSubscriptionTargetingTheTeam() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-on-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());

		NotificationSubscriptionData sub = managedFor(td.getUuid());
		Assertions.assertNotNull(sub, "enabling should materialise a subscription");
		Assertions.assertEquals(NotificationSubscriptionStatus.ACTIVE, sub.status());
		Assertions.assertEquals(td.getUuid(), sub.managedByTeam(), "the row must name its owner");
		// The two halves that make it a team's OWN feed rather than an org-wide one.
		Assertions.assertEquals(td.getUuid(), sub.ownedByTeam(),
				"without the scope this delivers every team's events to this team");
		Assertions.assertEquals(List.of(td.getUuid()), sub.routes().get(0).teams(),
				"the destination is the team itself, resolved at fire time");
		Assertions.assertFalse(sub.eventTypes().isEmpty());
		Assertions.assertFalse(sub.eventTypes().contains(NotificationEventType.VEX_STATE_CHANGED),
				"VEX has no producer and could never match an ownership scope");
	}

	@Test
	public void exclusionsRemoveEventTypesAndNothingElse() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-excl-" + UUID.randomUUID());
		setNotifications(td, true, Set.of(NotificationEventType.RELEASE_CREATED,
				NotificationEventType.APPROVAL_REQUESTED));

		NotificationSubscriptionData sub = managedFor(td.getUuid());
		Assertions.assertFalse(sub.eventTypes().contains(NotificationEventType.RELEASE_CREATED));
		Assertions.assertFalse(sub.eventTypes().contains(NotificationEventType.APPROVAL_REQUESTED));
		Assertions.assertTrue(sub.eventTypes().contains(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
				"excluding two types must not narrow the rest");
	}

	@Test
	public void disablingKeepsTheRowAndOnlyStopsIt() throws RelizaException {
		// Deleting would orphan the attribution of every delivery it ever made:
		// history resolves a subscription's NAME through the row.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-off-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());
		UUID subUuid = subscriptionRepo.findManagedByTeam(td.getOrg(), td.getUuid()).get().getUuid();

		setNotifications(td, false, Set.of());

		NotificationSubscriptionData after = managedFor(td.getUuid());
		Assertions.assertNotNull(after, "the row must survive so past deliveries stay attributable");
		Assertions.assertEquals(NotificationSubscriptionStatus.DISABLED, after.status());
		Assertions.assertEquals(subUuid, subscriptionRepo.findManagedByTeam(td.getOrg(), td.getUuid()).get().getUuid(),
				"re-toggling must reuse the row, not accumulate one per flip");
	}

	@Test
	public void archivingTheTeamDisablesItAndRestoringBringsItBack() throws RelizaException {
		// An archived team resolves to no channels, so an ACTIVE subscription on
		// one would match events and deliver nothing -- indistinguishable, in the
		// list, from a subscription that works.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-arch-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());

		update(UpdateTeamDto.builder().teamId(td.getUuid()).status(TeamStatus.INACTIVE).build());
		Assertions.assertEquals(NotificationSubscriptionStatus.DISABLED,
				managedFor(td.getUuid()).status(), "archiving must stop it");

		update(UpdateTeamDto.builder().teamId(td.getUuid()).status(TeamStatus.ACTIVE).build());
		Assertions.assertEquals(NotificationSubscriptionStatus.ACTIVE,
				managedFor(td.getUuid()).status(),
				"restoring must bring it back -- the team never withdrew its request");
	}

	@Test
	public void renamingTheTeamRenamesItsSubscription() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-name-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());
		String newName = "team-ocn-renamed-" + UUID.randomUUID();

		update(UpdateTeamDto.builder().teamId(td.getUuid()).name(newName).build());

		Assertions.assertTrue(managedFor(td.getUuid()).name().startsWith(newName),
				"delivery history names the subscription; a stale name is a wrong answer there");
	}

	@Test
	public void anUnrelatedEditLeavesTheSettingAlone() throws RelizaException {
		// Null-means-keep. An editor that changed only the roster must not be able
		// to switch a team's notifications off by omitting the field.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-keep-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());
		UUID member = userIn(org);

		update(UpdateTeamDto.builder().teamId(td.getUuid()).members(Set.of(member)).build());

		Assertions.assertEquals(NotificationSubscriptionStatus.ACTIVE, managedFor(td.getUuid()).status());
	}

	@Test
	public void aManagedSubscriptionCannotBeEditedOrDeletedDirectly() throws RelizaException {
		// The toggle owns the row. Editing it there would leave the team's setting
		// describing something untrue, with nothing on the team showing it.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-guard-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());
		UUID subUuid = subscriptionRepo.findManagedByTeam(td.getOrg(), td.getUuid()).get().getUuid();
		NotificationSubscriptionData stored = managedFor(td.getUuid());

		RelizaException edit = Assertions.assertThrows(RelizaException.class,
				() -> subscriptionService.upsertSubscription(subUuid,
						new NotificationSubscriptionData(org.getUuid(), null, "hijacked",
								NotificationSubscriptionStatus.ACTIVE, stored.eventTypes(), null,
								stored.routes(), null, null),
						WhoUpdated.getTestWhoUpdated()));
		Assertions.assertTrue(edit.getMessage().contains("managed by a team"), edit.getMessage());

		RelizaException del = Assertions.assertThrows(RelizaException.class,
				() -> subscriptionService.deleteSubscription(subUuid));
		Assertions.assertTrue(del.getMessage().contains("managed by a team"), del.getMessage());
	}

	@Test
	public void aStatusFlipCannotOrphanTheRowFromItsTeam() throws RelizaException {
		// The failure this prevents is a chain, not a single wrong value: strip
		// managedByTeam on a status flip and the next team save finds no managed
		// row, materialises a SECOND one, and the team's channel gets every event
		// twice -- from a subscription nobody can trace back to the team.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-flip-" + UUID.randomUUID());
		setNotifications(td, true, Set.of());
		UUID subUuid = subscriptionRepo.findManagedByTeam(org.getUuid(), td.getUuid()).get().getUuid();

		// The flip itself is refused: a status change is an edit, and this row is
		// edited on its team.
		RelizaException e = Assertions.assertThrows(RelizaException.class,
				() -> subscriptionService.setSubscriptionStatus(subUuid,
						NotificationSubscriptionStatus.DISABLED, WhoUpdated.getTestWhoUpdated()));
		Assertions.assertTrue(e.getMessage().contains("managed by a team"), e.getMessage());

		// And the marker survives, so a later team save still finds this row
		// rather than creating another.
		update(UpdateTeamDto.builder().teamId(td.getUuid()).description("later edit").build());
		Assertions.assertEquals(subUuid,
				subscriptionRepo.findManagedByTeam(org.getUuid(), td.getUuid()).get().getUuid(),
				"a second managed row would deliver every event twice");
	}

	@Test
	public void excludingEveryEventTypeIsRejectedOnTheTeam() throws RelizaException {
		// Otherwise this fails deep inside materialisation as "at least one
		// eventType is required" -- an error about an object the operator never
		// knew existed -- and, because RelizaException is checked, the team row
		// would commit anyway, leaving the toggle on with no subscription.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-all-excl-" + UUID.randomUUID());
		Set<NotificationEventType> everything = Set.of(NotificationEventType.values());

		RelizaException e = Assertions.assertThrows(RelizaException.class,
				() -> setNotifications(td, true, everything));
		Assertions.assertTrue(e.getMessage().contains("excluding every event type"), e.getMessage());
		Assertions.assertNull(managedFor(td.getUuid()), "nothing should have been materialised");
		Assertions.assertFalse(teamService.getTeamData(td.getUuid()).get().notifiesOnOwnedComponents(),
				"the team edit must not have committed either");
	}

	@Test
	public void oneTeamsSaveLeavesAnotherTeamsSubscriptionAlone() throws RelizaException {
		// The lookup is by marker across a shared table; without the org and team
		// predicates a save on one team could pick up another's row and overwrite
		// it with the wrong scope and destination.
		Organization org = testInitializer.obtainOrganization();
		TeamData a = createTeam(org.getUuid(), "team-ocn-a-" + UUID.randomUUID());
		TeamData b = createTeam(org.getUuid(), "team-ocn-b-" + UUID.randomUUID());
		setNotifications(a, true, Set.of());
		setNotifications(b, true, Set.of());
		UUID subA = subscriptionRepo.findManagedByTeam(org.getUuid(), a.getUuid()).get().getUuid();
		UUID subB = subscriptionRepo.findManagedByTeam(org.getUuid(), b.getUuid()).get().getUuid();
		Assertions.assertNotEquals(subA, subB, "each team owns its own row");

		setNotifications(a, false, Set.of());

		Assertions.assertEquals(NotificationSubscriptionStatus.ACTIVE, managedFor(b.getUuid()).status(),
				"disabling team A must not touch team B");
		Assertions.assertEquals(b.getUuid(), managedFor(b.getUuid()).ownedByTeam());
	}

	@Test
	public void nobodyCanClaimTheirOwnSubscriptionIsTeamManaged() throws RelizaException {
		// The marker is what the guards trust, so a caller who could set it could
		// mint a subscription only a team can edit -- and no team would agree.
		Organization org = testInitializer.obtainOrganization();
		TeamData td = createTeam(org.getUuid(), "team-ocn-claim-" + UUID.randomUUID());
		RelizaException e = Assertions.assertThrows(RelizaException.class,
				() -> subscriptionService.upsertSubscription(null,
						new NotificationSubscriptionData(org.getUuid(), null, "claiming",
								NotificationSubscriptionStatus.ACTIVE,
								List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES), null,
								List.of(new NotificationSubscriptionData.RouteConfig(null, null, null,
										List.of(), null, null, List.of(td.getUuid()))),
								null, null, null, td.getUuid()),
						WhoUpdated.getTestWhoUpdated()));
		Assertions.assertTrue(e.getMessage().contains("managedByTeam"), e.getMessage());
	}
}
