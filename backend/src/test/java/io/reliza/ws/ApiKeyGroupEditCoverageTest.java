/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto.UpdateApiKeyGroupDtoBuilder;

/**
 * What the group update keeps and refuses, in the forms the second test round leaves open (TPF-91,
 * mutation round 4, the mutant tester's T-15 to T-20): every kind of edit of an INACTIVE group, an
 * approvals-only update at every organization-wide level and with every approvals value, an unreadable
 * row read first and in the key-deletion sweep, the name and the description kept or replaced by each
 * other update, and the member list validated at every position, in a fixed order.
 */
public class ApiKeyGroupEditCoverageTest extends ApiKeyGroupTestBase {

	private boolean reads(AuthHeaderParse ahp, Organization org, AgentBoardData bd) throws RelizaException {
		return authorizationService.boardPermission(ahp, org.getUuid(), bd.getUuid(), PermissionFunction.BOARD_READ, CallType.READ);
	}

	private ApiKeyGroupData update(ApiKeyGroupData g, Consumer<UpdateApiKeyGroupDtoBuilder> fields) throws RelizaException {
		UpdateApiKeyGroupDtoBuilder b = UpdateApiKeyGroupDto.builder().groupId(g.getUuid());
		fields.accept(b);
		return apiKeyGroupService.updateApiKeyGroup(b.build(), WU);
	}

	private Set<String> approvalsOf(ApiKeyGroupData g, Organization org) {
		return g.getPermission(PermissionScope.ORGANIZATION, org.getUuid())
				.map(p -> null == p.getApprovals() ? new HashSet<String>() : new HashSet<String>(p.getApprovals()))
				.orElse(new HashSet<>());
	}

	private PermissionType levelOf(ApiKeyGroupData g, Organization org) {
		return g.getPermission(PermissionScope.ORGANIZATION, org.getUuid()).map(UserPermission::getType).orElse(null);
	}

	// T-15: no kind of edit of an INACTIVE group re-activates it

	@Test
	public void noEditOfAnInactiveGroupReActivatesItOrGivesItsMembersAccess() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKey k2 = freeform(org, "k2");
		ApiKey k3 = freeform(org, "k3");
		Map<String, Consumer<UpdateApiKeyGroupDtoBuilder>> edits = new LinkedHashMap<>();
		edits.put("a description", f -> f.description("described-" + UUID.randomUUID()));
		edits.put("a name", f -> f.name("renamed-" + UUID.randomUUID()));
		edits.put("approvals", f -> f.approvals(List.of("QA")));
		edits.put("empty approvals", f -> f.approvals(List.of()));
		edits.put("an empty member list", f -> f.apiKeys(Set.of()));
		edits.put("a shorter member list", f -> f.apiKeys(Set.of(k1.getUuid())));
		edits.put("a longer member list", f -> f.apiKeys(Set.of(k1.getUuid(), k2.getUuid(), k3.getUuid())));
		edits.put("another member list", f -> f.apiKeys(Set.of(k3.getUuid())));
		edits.put("an empty permission list", f -> f.permissions(List.of()));
		edits.put("another permission list", f -> f.permissions(List.of(boardRead(b.getUuid()))));
		edits.put("members and permissions", f -> f.apiKeys(Set.of(k3.getUuid())).permissions(List.of(boardRead(b.getUuid()))));
		edits.put("a name and a description", f -> f.name("both-" + UUID.randomUUID()).description("both"));
		edits.put("a name and members", f -> f.name("named-" + UUID.randomUUID()).apiKeys(Set.of(k1.getUuid())));
		edits.put("approvals and permissions", f -> f.approvals(List.of("QA")).permissions(List.of(boardRead(b.getUuid()))));
		edits.put("everything but the status", f -> f.name("all-" + UUID.randomUUID()).description("all").apiKeys(Set.of(k3.getUuid()))
				.permissions(List.of(boardRead(b.getUuid()))).approvals(List.of("QA", "DEV")));
		edits.put("an explicit INACTIVE", f -> f.status(ApiKeyGroupStatus.INACTIVE));

		for (Map.Entry<String, Consumer<UpdateApiKeyGroupDtoBuilder>> edit : edits.entrySet()) {
			ApiKeyGroupData g = group(org, Set.of(k1.getUuid(), k2.getUuid()), boardRead(a.getUuid()));
			assertTrue(reads(as(k1), org, a), "precondition: the active group grants " + edit.getKey());
			apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
			assertFalse(reads(as(k1), org, a));

			update(g, edit.getValue());
			assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus(), edit.getKey() + " re-activated the group");
			assertFalse(reads(as(k1), org, a), edit.getKey() + " gave the first member the group's grants back");
			assertFalse(reads(as(k2), org, a), edit.getKey() + " gave the second member the group's grants back");
			assertFalse(reads(as(k3), org, a), edit.getKey() + " gave a member added by it the group's grants");
		}

		// a group with no members at all
		ApiKeyGroupData empty = group(org, Set.of(), boardRead(a.getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(empty.getUuid(), WU);
		for (Map.Entry<String, Consumer<UpdateApiKeyGroupDtoBuilder>> edit : edits.entrySet()) {
			update(empty, edit.getValue());
			assertEquals(ApiKeyGroupStatus.INACTIVE, stored(empty).getStatus(), edit.getKey() + " re-activated a group without members");
		}
		// and the restore still works, by status ACTIVE alone
		restoreGroup(empty);
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(empty).getStatus());
	}

	// T-16 and T-17: an approvals-only update keeps the organization-wide level and stores the roles it is given

	@Test
	public void anApprovalsOnlyUpdateKeepsTheLevelAtEveryLevel() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		for (PermissionType level : List.of(PermissionType.NONE, PermissionType.ESSENTIAL_READ, PermissionType.READ_ONLY,
				PermissionType.READ_WRITE, PermissionType.ADMIN)) {
			ApiKeyGroupData g = group(org, Set.of(), orgWide(org, level));
			assertEquals(level, levelOf(g, org), "precondition: the group is created at " + level);
			ApiKeyGroupData updated = update(g, f -> f.approvals(List.of("QA", "DEV")));
			assertEquals(level, levelOf(updated, org), "an approvals-only update changed " + level);
			assertEquals(level, levelOf(stored(g), org), "and stored the change of " + level);
			assertEquals(Set.of("QA", "DEV"), approvalsOf(stored(g), org));
		}
	}

	@Test
	public void theApprovalsOfAnApprovalsOnlyUpdateAreStoredWholeAndCanBeCleared() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = group(org, Set.of(), orgWide(org, PermissionType.READ_ONLY));

		update(g, f -> f.approvals(List.of("QA", "DEV", "OPS")));
		assertEquals(Set.of("QA", "DEV", "OPS"), approvalsOf(stored(g), org), "every role is stored");
		update(g, f -> f.approvals(List.of("QA")));
		assertEquals(Set.of("QA"), approvalsOf(stored(g), org), "the roles sent replace the roles stored");
		update(g, f -> f.approvals(List.of()));
		assertEquals(Set.of(), approvalsOf(stored(g), org), "an empty list clears the roles");
		assertEquals(PermissionType.READ_ONLY, levelOf(stored(g), org));
	}

	@Test
	public void approvalsSentToAGroupWithoutAnOrganizationWideEntryAreStoredAtNone() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKeyGroupData g = group(org, Set.of(), boardRead(a.getUuid()));
		assertTrue(g.getPermission(PermissionScope.ORGANIZATION, org.getUuid()).isEmpty(), "precondition: no organization-wide entry");

		ApiKeyGroupData updated = update(g, f -> f.approvals(List.of("QA", "DEV")));
		assertEquals(Set.of("QA", "DEV"), approvalsOf(updated, org), "the roles are stored");
		assertEquals(PermissionType.NONE, levelOf(updated, org), "at level NONE");
		assertEquals(Set.of("QA", "DEV"), approvalsOf(stored(g), org));
		assertTrue(stored(g).getPermission(PermissionScope.BOARD, a.getUuid()).isPresent(), "and the board grant is kept");
	}

	@Test
	public void permissionsAndApprovalsSentTogetherStoreTheNewLevelAndTheRoles() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = group(org, Set.of(), orgWide(org, PermissionType.READ_ONLY));

		ApiKeyGroupData updated = update(g, f -> f.permissions(List.of(orgWide(org, PermissionType.ADMIN)))
				.approvals(List.of("QA", "DEV")));
		assertEquals(PermissionType.ADMIN, levelOf(updated, org), "the level just sent, not the stored one");
		assertEquals(Set.of("QA", "DEV"), approvalsOf(updated, org));
		assertEquals(PermissionType.ADMIN, levelOf(stored(g), org));
		assertEquals(Set.of("QA", "DEV"), approvalsOf(stored(g), org));

		ApiKeyGroupData lowered = update(g, f -> f.permissions(List.of(orgWide(org, PermissionType.READ_ONLY)))
				.approvals(List.of("OPS")));
		assertEquals(PermissionType.READ_ONLY, levelOf(lowered, org));
		assertEquals(Set.of("OPS"), approvalsOf(lowered, org));

		ApiKeyGroupData fromNone = update(g, f -> f.permissions(List.of(orgWide(org, PermissionType.READ_WRITE)))
				.approvals(List.of("QA")));
		assertEquals(PermissionType.READ_WRITE, levelOf(fromNone, org));
		assertEquals(Set.of("QA"), approvalsOf(fromNone, org));
	}

	// T-18: an unreadable row costs only itself, read first and in the key-deletion sweep

	private ApiKeyGroup plantUnreadable(Organization org, UUID member) {
		ApiKeyGroupData d = ApiKeyGroupData.apiKeyGroupDataFactory(CreateApiKeyGroupDto.builder()
				.name("unreadable-" + UUID.randomUUID()).org(org.getUuid()).build());
		d.addApiKey(member);
		ApiKeyGroup bad = new ApiKeyGroup();
		bad.setUuid(UUID.randomUUID());
		bad.setSchemaVersion(1);
		bad.setRecordData(new HashMap<>(Utils.dataToRecord(d)));
		return apiKeyGroupRepository.save(bad);
	}

	@Test
	public void anUnreadableRowReadFirstCostsOnlyItselfInEveryRead() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();      // no group yet: the bad row is the organization's first
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroup bad = plantUnreadable(org, k1.getUuid());
		try {
			ApiKeyGroupData healthy = group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
			assertTrue(reads(as(k1), org, a), "the by-key read skips the unreadable row it returns first");
			List<ApiKeyGroupData> all = apiKeyGroupService.getAllApiKeyGroupsByOrganization(org.getUuid());
			assertEquals(List.of(healthy.getUuid()), all.stream().map(ApiKeyGroupData::getUuid).toList(),
					"the list holds the healthy group only");
			assertEquals(List.of(healthy.getUuid()), apiKeyGroupService.getApiKeyGroupsByOrganization(org.getUuid()).stream()
					.map(ApiKeyGroupData::getUuid).toList(), "and so does the active list");
			assertEquals(1, apiKeyGroupService.permissionSetsForKey(k1.getUuid(), org.getUuid()).size());
		} finally {
			apiKeyGroupRepository.delete(bad);
		}
	}

	@Test
	public void deletingAKeyWorksWhileAGroupRowIsUnreadableAndCleansTheReadableGroups() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k1 = freeform(org, "k1");
		ApiKey k2 = freeform(org, "k2");
		ApiKeyGroup bad = plantUnreadable(org, k2.getUuid());
		try {
			ApiKeyGroupData active = group(org, Set.of(k1.getUuid(), k2.getUuid()));
			ApiKeyGroupData inactive = group(org, Set.of(k2.getUuid()));
			apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);

			apiKeyService.deleteApiKey(k2.getUuid(), WU);
			assertEquals(Set.of(k1.getUuid()), stored(active).getApiKeys(), "the active group lost the deleted key");
			assertEquals(Set.of(), stored(inactive).getApiKeys(), "so did the inactive one");
		} finally {
			apiKeyGroupRepository.delete(bad);
		}
	}

	// T-19: what an update that does not name them keeps, and what an empty value replaces

	@Test
	public void everyOtherUpdateKeepsTheNameAndTheDescription() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder()
				.name("named-" + UUID.randomUUID()).description("described").org(org.getUuid()).build(), WU);
		String name = g.getName();

		Map<String, Consumer<UpdateApiKeyGroupDtoBuilder>> edits = new LinkedHashMap<>();
		edits.put("permissions", f -> f.permissions(List.of(boardRead(a.getUuid()))));
		edits.put("approvals", f -> f.approvals(List.of("QA")));
		edits.put("a status", f -> f.status(ApiKeyGroupStatus.INACTIVE));
		edits.put("members", f -> f.apiKeys(Set.of(k1.getUuid())));
		edits.put("a restore", f -> f.status(ApiKeyGroupStatus.ACTIVE));
		edits.put("permissions and approvals", f -> f.permissions(List.of(orgWide(org, PermissionType.READ_ONLY))).approvals(List.of("QA")));
		for (Map.Entry<String, Consumer<UpdateApiKeyGroupDtoBuilder>> edit : edits.entrySet()) {
			ApiKeyGroupData updated = update(g, edit.getValue());
			assertEquals(name, updated.getName(), "the name after " + edit.getKey());
			assertEquals("described", updated.getDescription(), "the description after " + edit.getKey());
			assertEquals(name, stored(g).getName(), "the stored name after " + edit.getKey());
			assertEquals("described", stored(g).getDescription(), "the stored description after " + edit.getKey());
		}

		ApiKeyGroupData renamed = update(g, f -> f.name("renamed-" + UUID.randomUUID()));
		assertEquals("described", renamed.getDescription(), "a rename keeps the description");
		assertEquals("described", stored(g).getDescription());
		String newName = stored(g).getName();
		ApiKeyGroupData described = update(g, f -> f.description("another"));
		assertEquals(newName, described.getName(), "a new description keeps the name");
		assertEquals("another", stored(g).getDescription());
	}

	@Test
	public void anEmptyDescriptionOrNameIsAValueNotAnOmission() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder()
				.name("named-" + UUID.randomUUID()).description("described").org(org.getUuid()).build(), WU);

		update(g, f -> f.description(""));
		assertEquals("", stored(g).getDescription(), "an empty description clears the stored one: only null keeps it");
		assertEquals(g.getName(), stored(g).getName());

		update(g, f -> f.description("again"));
		assertEquals("again", stored(g).getDescription());
		update(g, f -> f.name(""));
		assertEquals("", stored(g).getName(), "an empty name is a name, not an omission");
		assertEquals("again", stored(g).getDescription());
	}

	// T-20: every member is validated at every position, in a fixed order

	private enum Fault { WRONG_TYPE, FOREIGN_ORG, UNKNOWN, NULL }

	private static List<UUID> list(UUID... uuids) {
		return Arrays.asList(uuids);
	}

	@Test
	public void aRefusedMemberIsRefusedFirstInTheMiddleAndLastWhateverTheReason() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		ApiKey ok1 = freeform(org, "ok1");
		ApiKey ok2 = freeform(org, "ok2");
		ApiKey ok3 = freeform(org, "ok3");
		ApiKeyGroupData g = group(org, new LinkedHashSet<>(List.of(ok1.getUuid())));
		Set<UUID> before = stored(g).getApiKeys();

		for (Fault fault : Fault.values()) {
			for (int length = 1; length <= 4; length++) {
				for (int position = 0; position < length; position++) {
					UUID refused = switch (fault) {
						case WRONG_TYPE -> userKey(org, member(org, PermissionType.READ_ONLY)).getUuid();
						case FOREIGN_ORG -> freeform(other, "foreign").getUuid();
						case UNKNOWN -> UUID.randomUUID();
						case NULL -> null;
					};
					List<UUID> accepted = list(ok1.getUuid(), ok2.getUuid(), ok3.getUuid(), freeform(org, "ok4").getUuid());
					List<UUID> members = new ArrayList<>(accepted.subList(0, length - 1));
					members.add(position, refused);
					String where = fault + " at " + position + " of " + length;

					RelizaException e = assertThrows(RelizaException.class, () -> apiKeyGroupService.updateApiKeyGroup(
							UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).apiKeys(new LinkedHashSet<>(members)).build(), WU), where);
					String expected = fault == Fault.WRONG_TYPE ? "Only FREEFORM keys can join an API key group: " + refused
							: "API key not found: " + refused;
					assertEquals(expected, e.getMessage(), where);
					assertEquals(before, stored(g).getApiKeys(), "a refused list writes nothing: " + where);
				}
			}
		}
		assertNotNull(stored(g));
	}

	@Test
	public void aListOfSeveralAcceptedMembersIsAccepted() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		List<UUID> keys = new ArrayList<>();
		for (int i = 0; i < 4; i++) keys.add(freeform(org, "k" + i).getUuid());
		ApiKeyGroupData g = group(org, Set.of());
		ApiKeyGroupData updated = apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.apiKeys(new LinkedHashSet<>(keys)).build(), WU);
		assertEquals(new HashSet<>(keys), updated.getApiKeys());
		assertEquals(new HashSet<>(keys), stored(g).getApiKeys());
	}
}
