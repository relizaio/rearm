/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.model.UserGroup;
import io.reliza.repositories.UserGroupRepository;

/**
 * {@code UserGroupService.resolveTeamChannelUuids} -- what the fan-out calls to
 * turn a route's {@code teams} target into channel UUIDs (T3).
 *
 * <p>Resolution is deliberately LATE (fan-out, not save) so retargeting a team's
 * channel takes effect without editing every subscription that names it. That
 * makes the read-side guards here load-bearing: cross-org and deactivated teams
 * must be skipped even though writes also validate, because a route saved before
 * a team changed must not keep delivering.
 */
class TeamChannelRoutingTest {

	private UserGroupRepository repo;
	private UserGroupService service;

	private final UUID org = UUID.randomUUID();
	private final UUID otherOrg = UUID.randomUUID();
	private final UUID teamA = UUID.randomUUID();
	private final UUID teamB = UUID.randomUUID();
	private final UUID chan1 = UUID.randomUUID();
	private final UUID chan2 = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		repo = mock(UserGroupRepository.class);
		service = new UserGroupService();
		ReflectionTestUtils.setField(service, "userGroupRepository", repo);
	}

	/** Stubs the repository so the REAL service method under test runs. */
	private void stubTeam(UUID uuid, UUID teamOrg, UserGroupStatus status, UUID... channels) {
		String chans = Arrays.stream(channels).map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
		UserGroup ug = new UserGroup();
		ug.setUuid(uuid);
		ug.setRecordData(Utils.OM.readValue(
				"{\"name\":\"T\",\"org\":\"%s\",\"status\":\"%s\",\"notificationChannels\":[%s]}"
						.formatted(teamOrg, status, chans),
				new TypeReference<Map<String, Object>>() {}));
		when(repo.findById(uuid)).thenReturn(Optional.of(ug));
	}

	@Test
	void nullAndEmptyYieldNoChannels() {
		assertTrue(service.resolveTeamChannelUuids(null, org).isEmpty());
		assertTrue(service.resolveTeamChannelUuids(List.of(), org).isEmpty());
	}

	@Test
	void teamChannelsAreResolvedDedupedInFirstSeenOrder() {
		stubTeam(teamA, org, UserGroupStatus.ACTIVE, chan1, chan2);
		stubTeam(teamB, org, UserGroupStatus.ACTIVE, chan2);
		assertEquals(List.of(chan1, chan2), service.resolveTeamChannelUuids(List.of(teamA, teamB), org),
				"a channel shared by two teams is delivered to once, in first-seen order");
	}

	@Test
	void aMissingTeamContributesNothingRatherThanFailingTheRoute() {
		when(repo.findById(teamA)).thenReturn(Optional.empty());
		stubTeam(teamB, org, UserGroupStatus.ACTIVE, chan2);
		assertEquals(List.of(chan2), service.resolveTeamChannelUuids(List.of(teamA, teamB), org),
				"one stale team reference must not silence the rest of the route");
	}

	@Test
	void aCrossOrgTeamIsSkipped() {
		// Writes validate too, but a route saved before a change must not become a
		// cross-tenant read/delivery path.
		stubTeam(teamA, otherOrg, UserGroupStatus.ACTIVE, chan1);
		assertTrue(service.resolveTeamChannelUuids(List.of(teamA), org).isEmpty());
	}

	@Test
	void aDeactivatedTeamStopsReceivingNotifications() {
		// The picker hides INACTIVE teams, so an operator who deactivates one
		// expects delivery to stop; without this an already-saved route fires forever.
		stubTeam(teamA, org, UserGroupStatus.INACTIVE, chan1);
		assertTrue(service.resolveTeamChannelUuids(List.of(teamA), org).isEmpty());
	}

	@Test
	void aTeamWithNoChannelsContributesNothing() {
		stubTeam(teamA, org, UserGroupStatus.ACTIVE);
		assertTrue(service.resolveTeamChannelUuids(List.of(teamA), org).isEmpty());
	}

	@Test
	void aDeactivatedTeamIsHarmlessAtResolutionSoItNeedNotBeRejectedAtSave() {
		// Regression guard for a lock-out found in live smoke: rejecting a
		// DEACTIVATED team at SAVE made the subscription un-editable (the operator
		// could neither keep the team nor drop it, since an emptied route is also
		// rejected). Resolution skipping it is the real protection -- nothing is
		// delivered -- so the save-time rejection was removed.
		stubTeam(teamA, org, UserGroupStatus.INACTIVE, chan1);
		stubTeam(teamB, org, UserGroupStatus.ACTIVE, chan2);
		assertEquals(List.of(chan2), service.resolveTeamChannelUuids(List.of(teamA, teamB), org),
				"the deactivated team contributes nothing while the active one still delivers");
	}
}
