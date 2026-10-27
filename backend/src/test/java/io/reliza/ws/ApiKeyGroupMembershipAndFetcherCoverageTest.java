/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.UpdateApiKeyGroupDto;

/**
 * The group service and its browser lane in the forms the third test round leaves open (TPF-91, mutation
 * round 5, the mutant tester's T-21 to T-24): a key held by several active groups gets every group's
 * permissions, the member details leave out a deleted key and a key of another organization, a null entry in
 * a permissions list is skipped, and a create and an update through the browser record the signed-in
 * administrator as the audit actor.
 */
public class ApiKeyGroupMembershipAndFetcherCoverageTest extends ApiKeyGroupTestBase {

	private boolean reads(io.reliza.common.CommonVariables.AuthHeaderParse ahp, Organization org, AgentBoardData bd)
			throws RelizaException {
		return authorizationService.boardPermission(ahp, org.getUuid(), bd.getUuid(), PermissionFunction.BOARD_READ, CallType.READ);
	}

	// T-21: a key in several active groups gets every group's permissions

	@Test
	public void aKeyHeldByFourActiveGroupsGetsEveryGroupsPermissions() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		List<AgentBoardData> boards = new ArrayList<>();
		for (int i = 0; i < 5; i++) boards.add(board(org));
		ApiKey key = freeform(org, "held");
		ApiKey bystander = freeform(org, "bystander");
		for (int i = 0; i < 4; i++) group(org, Set.of(key.getUuid()), boardRead(boards.get(i).getUuid()));
		ApiKeyGroupData inactive = group(org, Set.of(key.getUuid()), boardRead(boards.get(4).getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);
		group(org, Set.of(bystander.getUuid()), boardRead(boards.get(4).getUuid()));

		List<Set<UserPermission>> sets = apiKeyGroupService.permissionSetsForKey(key.getUuid(), org.getUuid());
		assertEquals(4, sets.size(), "one set per active group holding the key");
		Set<UUID> granted = sets.stream().flatMap(Set::stream).filter(p -> p.getScope() == PermissionScope.BOARD)
				.map(UserPermission::getObject).collect(Collectors.toSet());
		assertEquals(boards.subList(0, 4).stream().map(AgentBoardData::getUuid).collect(Collectors.toSet()), granted,
				"the four boards, each from its own group");
		for (int i = 0; i < 4; i++) {
			assertTrue(reads(as(key), org, boards.get(i)), "the key reads board " + i + " through its group");
		}
		assertTrue(!reads(as(key), org, boards.get(4)), "and not the board only an inactive group or a bystander's group grants");
	}

	@Test
	public void aKeyHeldByTwoActiveGroupsGetsBoth() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		ApiKey key = freeform(org, "held");
		group(org, Set.of(key.getUuid()), boardRead(a.getUuid()));
		group(org, Set.of(key.getUuid()), boardRead(b.getUuid()));

		assertEquals(2, apiKeyGroupService.permissionSetsForKey(key.getUuid(), org.getUuid()).size());
		assertTrue(reads(as(key), org, a));
		assertTrue(reads(as(key), org, b));
	}

	// T-22: the member details leave out a deleted key and a key of another organization

	@Test
	@SuppressWarnings("unchecked")
	public void theMemberDetailsLeaveOutADeletedKeyAndAKeyOfAnotherOrganization() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		ApiKey own = freeform(org, "own-notes");
		String foreignNotes = "foreign-notes-" + UUID.randomUUID();
		ApiKey foreign = freeform(other, foreignNotes);
		ApiKeyGroupData g = group(org, Set.of(own.getUuid()));
		plant(g, foreign.getUuid());                       // past the validation, as a direct write could
		plant(g, UUID.randomUUID());                       // a key that no longer resolves
		signInAs(member(org, PermissionType.ADMIN));

		ExecutionResult r = dgsQueryExecutor.execute("query($o: ID!) { apiKeyGroupsOfOrg(org: $o)"
				+ " { uuid apiKeyDetails { uuid notes declaredName } } }", Map.of("o", org.getUuid().toString()));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<Map<String, Object>> groups = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("apiKeyGroupsOfOrg");
		Map<String, Object> shown = groups.stream().filter(x -> g.getUuid().toString().equals(x.get("uuid"))).findFirst().orElseThrow();
		List<Map<String, Object>> details = (List<Map<String, Object>>) shown.get("apiKeyDetails");
		assertEquals(List.of(own.getUuid().toString()), details.stream().map(d -> (String) d.get("uuid")).toList(),
				"exactly the group's own live key");
		assertTrue(!details.toString().contains(foreignNotes), "the other organization's key notes are not shown");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theMemberDetailsShowEveryLiveMemberOfTheGroup() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Set<UUID> keys = new java.util.LinkedHashSet<>();
		for (int i = 0; i < 5; i++) keys.add(freeform(org, "member-" + i).getUuid());
		ApiKeyGroupData g = group(org, keys);
		ApiKeyGroupData one = group(org, Set.of(keys.iterator().next()));
		signInAs(member(org, PermissionType.ADMIN));

		ExecutionResult r = dgsQueryExecutor.execute("query($o: ID!) { apiKeyGroupsOfOrg(org: $o)"
				+ " { uuid apiKeyDetails { uuid } } }", Map.of("o", org.getUuid().toString()));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<Map<String, Object>> groups = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("apiKeyGroupsOfOrg");
		for (ApiKeyGroupData expected : List.of(g, one)) {
			Map<String, Object> shown = groups.stream().filter(x -> expected.getUuid().toString().equals(x.get("uuid"))).findFirst().orElseThrow();
			Set<String> details = ((List<Map<String, Object>>) shown.get("apiKeyDetails")).stream()
					.map(d -> (String) d.get("uuid")).collect(Collectors.toSet());
			assertEquals(stored(expected).getApiKeys().stream().map(UUID::toString).collect(Collectors.toSet()), details,
					"every live member is shown");
		}
	}

	@Test
	public void aDeleteThroughTheBrowserRecordsTheAdministratorAsTheActor() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		ApiKeyGroupData g = newGroup(org);                     // created with the test actor
		signInAs(admin);

		ExecutionResult deleted = gqlDelete(g.getUuid());
		assertTrue(deleted.getErrors().isEmpty(), deleted.getErrors().toString());
		assertEquals(io.reliza.common.CommonVariables.ApiKeyGroupStatus.INACTIVE, stored(g).getStatus());
		assertActor(g.getUuid(), admin, "a delete");
	}

	// T-23: a null entry in a permissions list is skipped

	@Test
	public void aNullEntryInAPermissionsListIsSkippedAtTheServiceAndInTheBrowser() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		ApiKeyGroupData g = newGroup(org);

		apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.permissions(Arrays.asList(null, boardRead(a.getUuid()))).build(), WU);
		Set<UserPermission> stored = stored(g).getOrgPermissions(org.getUuid());
		assertEquals(Set.of(a.getUuid()), stored.stream().map(UserPermission::getObject).collect(Collectors.toSet()),
				"the one real entry is stored");

		signInAs(member(org, PermissionType.ADMIN));
		Map<String, Object> input = new HashMap<>();
		input.put("groupId", g.getUuid().toString());
		List<Object> permissions = new ArrayList<>();
		permissions.add(null);
		permissions.add(Map.of("scope", "BOARD", "objectId", b.getUuid().toString(), "type", "READ_ONLY",
				"functions", List.of("BOARD_READ")));
		input.put("permissions", permissions);
		ExecutionResult r = gqlUpdate(input);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		assertEquals(Set.of(b.getUuid()), stored(g).getOrgPermissions(org.getUuid()).stream().map(UserPermission::getObject)
				.collect(Collectors.toSet()), "the browser's update replaced the set with its one real entry");
	}

	// T-24: the browser lane's create and update record the signed-in administrator

	@Test
	public void aCreateAndAnUpdateThroughTheBrowserRecordTheAdministratorAsTheActor() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		ApiKeyGroupData existing = newGroup(org);               // created with the test actor
		signInAs(admin);

		ExecutionResult created = gqlCreate(org, "browser-" + UUID.randomUUID());
		assertTrue(created.getErrors().isEmpty(), created.getErrors().toString());
		@SuppressWarnings("unchecked")
		UUID createdUuid = UUID.fromString((String) ((Map<String, Object>) ((Map<String, Object>) created.getData())
				.get("createApiKeyGroup")).get("uuid"));
		assertActor(createdUuid, admin, "a create");

		Map<String, Object> input = new HashMap<>();
		input.put("groupId", existing.getUuid().toString());
		input.put("description", "changed in the browser");
		ExecutionResult updated = gqlUpdate(input);
		assertTrue(updated.getErrors().isEmpty(), updated.getErrors().toString());
		assertActor(existing.getUuid(), admin, "an update");
	}

	private void assertActor(UUID group, UserData admin, String what) {
		ApiKeyGroup row = apiKeyGroupRepository.findById(group).orElseThrow();
		Map<String, Object> rd = row.getRecordData();
		assertEquals(admin.getUuid().toString(), String.valueOf(rd.get("lastUpdatedBy")), what + " names the administrator");
		assertNotNull(rd.get("createdType"));
		assertNotEquals("TEST", String.valueOf(rd.get("createdType")), what + " is not recorded as the test actor");
	}
}
