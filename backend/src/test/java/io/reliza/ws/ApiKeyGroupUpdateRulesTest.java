/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Audit;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.common.Utils;

/**
 * What an API key group update, a denied write and the rows around a group must do (TPF-91, mutation round 2,
 * the mutant tester's T-1 to T-9). Every case is behaviour of the shipped code seen from outside: who is
 * refused, what an update keeps and what it replaces, which row is skipped and who the audit names.
 */
public class ApiKeyGroupUpdateRulesTest extends ApiKeyGroupTestBase {

	private boolean reads(AuthHeaderParse ahp, Organization org, AgentBoardData bd) throws RelizaException {
		return authorizationService.boardPermission(ahp, org.getUuid(), bd.getUuid(), PermissionFunction.BOARD_READ, CallType.READ);
	}

	private List<Audit> audit(UUID entity, TableName tn) {
		return auditService.getAuditForEntity(entity, tn, 1000, 0);
	}

	// T-1: a denied create leaves no group behind

	@Test
	public void aDeniedCreateLeavesNoGroupBehind() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.READ_WRITE));
		String name = "denied-" + UUID.randomUUID();

		assertTrue(onlyError(gqlCreate(org, name)).contains("Not authorized"));
		assertTrue(apiKeyGroupService.getAllApiKeyGroupsByOrganization(org.getUuid()).stream()
				.noneMatch(g -> name.equals(g.getName())), "the refused create saved nothing");
	}

	// T-2: an update of an INACTIVE group keeps it INACTIVE

	@Test
	public void anUpdateOfAnInactiveGroupKeepsItInactiveAndItsMembersWithoutAccess() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
		assertTrue(reads(as(k1), org, a));
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		assertFalse(reads(as(k1), org, a));

		apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.name("renamed-" + UUID.randomUUID()).build(), WU);
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus(), "a rename does not re-activate a group");
		assertFalse(reads(as(k1), org, a), "and does not re-grant its permissions to the members");
	}

	// T-3: another organization's key of any type is "not found" before its type is read

	@Test
	public void anotherOrganizationsNonFreeformKeyAnswersNotFound() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		ApiKeyGroupData g = group(org, Set.of());
		ApiKey foreign = userKey(other, member(other, PermissionType.READ_WRITE));

		RelizaException refused = assertThrows(RelizaException.class, () -> setMembers(g, foreign.getUuid()));
		assertTrue(refused.getMessage().startsWith("API key not found: "), refused.getMessage());
		assertFalse(refused.getMessage().contains("FREEFORM"), "the key's type is not disclosed: " + refused.getMessage());
	}

	// T-4: an empty permissions list clears the grants

	@Test
	public void anEmptyPermissionsListClearsTheGroupsGrants() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
		assertTrue(reads(as(k1), org, a));

		apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.permissions(List.of()).build(), WU);
		assertFalse(reads(as(k1), org, a), "the administrator who sends [] revokes the group's grants");
	}

	// T-5: an approvals-only update keeps the organization-wide level

	@Test
	public void anApprovalsOnlyUpdateKeepsTheOrganizationWideLevel() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = group(org, Set.of(), orgWide(org, PermissionType.READ_WRITE));

		ApiKeyGroupData updated = apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder()
				.groupId(g.getUuid()).approvals(List.of("QA")).build(), WU);
		UserPermission orgWide = updated.getPermission(PermissionScope.ORGANIZATION, org.getUuid()).orElseThrow();
		assertEquals(PermissionType.READ_WRITE, orgWide.getType(), "the level the group holds is not lowered");
		assertTrue(orgWide.getApprovals().contains("QA"));
	}

	// T-6: an organization that does not exist is refused on an operational system

	@Test
	public void anOrganizationThatDoesNotExistIsNotAuthorizedAndLeavesNoRow() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		UUID ghost = UUID.randomUUID();

		assertTrue(onlyError(dgsQueryExecutor.execute(LIST, Map.of("o", ghost.toString()))).contains("Not authorized"));
		assertTrue(onlyError(dgsQueryExecutor.execute(CREATE, Map.of("g", Map.of("name", "ghost-" + UUID.randomUUID(),
				"org", ghost.toString())))).contains("Not authorized"));
		assertTrue(apiKeyGroupService.getAllApiKeyGroupsByOrganization(ghost).isEmpty());
	}

	// T-7: a rename onto the name of an inactive group is refused

	@Test
	public void aRenameOntoAnInactiveGroupsNameIsRefused() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData inactive = newGroup(org);
		apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);
		ApiKeyGroupData other = newGroup(org);

		RelizaException refused = assertThrows(RelizaException.class, () -> apiKeyGroupService.updateApiKeyGroup(
				UpdateApiKeyGroupDto.builder().groupId(other.getUuid()).name(inactive.getName()).build(), WU));
		assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
	}

	// T-8: an unreadable group row costs only itself

	@Test
	public void anUnreadableGroupRowIsSkippedAndAHealthyGroupStillGrants() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
		ApiKeyGroupData donor = newGroup(org);
		ApiKeyGroup bad = new ApiKeyGroup();
		bad.setUuid(UUID.randomUUID());
		bad.setSchemaVersion(1);
		ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(apiKeyGroupRepository.findById(donor.getUuid()).orElseThrow());
		d.addApiKey(k1.getUuid());
		Map<String, Object> badData = new java.util.HashMap<>(Utils.dataToRecord(d));
		badData.put("name", "unreadable-" + UUID.randomUUID());
		bad.setRecordData(badData);
		apiKeyGroupRepository.save(bad);
		try {
			assertTrue(reads(as(k1), org, a), "the key keeps the grant of its healthy group");
			assertFalse(apiKeyGroupService.getAllApiKeyGroupsByOrganization(org.getUuid()).isEmpty(),
					"the list still answers");
		} finally {
			apiKeyGroupRepository.delete(bad);
		}
	}

	// T-9: the audit names who deactivated a group and who swept a deleted key out of it

	@Test
	public void theAuditNamesTheActorOfADeactivationAndOfTheDeletionSweep() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		restoreGroup(g);
		for (Audit row : audit(g.getUuid(), TableName.API_KEY_GROUPS)) {
			assertEquals("TEST", String.valueOf(row.getRevisionRecordData().get("createdType")), "the deactivation names its caller");
		}

		ApiKey k2 = freeform(org, "k2");
		ApiKeyGroupData h = group(org, Set.of(k2.getUuid()));
		apiKeyService.deleteApiKey(k2.getUuid(), WU);
		setMembers(h, k1.getUuid());
		for (Audit row : audit(h.getUuid(), TableName.API_KEY_GROUPS)) {
			assertEquals("TEST", String.valueOf(row.getRevisionRecordData().get("createdType")), "the deletion sweep names its caller");
		}
	}

	// T-10: a member edit or a permission edit of an INACTIVE group keeps it inactive too

	@Test
	public void aMemberEditAndAPermissionEditOfAnInactiveGroupKeepItInactive() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKey k2 = freeform(org, "k2");
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);

		setMembers(g, k1.getUuid(), k2.getUuid());
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus(), "a member edit does not re-activate a group");
		assertFalse(reads(as(k2), org, a));
		setPermissions(g, boardRead(a.getUuid()), boardRead(b.getUuid()));
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus(), "a permission edit does not re-activate a group");
		assertFalse(reads(as(k1), org, a));
		assertFalse(reads(as(k1), org, b));
	}

	// T-11: an approvals-only update keeps the level at any level and creates no organization-wide grant

	@Test
	public void anApprovalsOnlyUpdateKeepsAnAdminLevelAndGrantsNothingToAGroupWithoutOne() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData admin = group(org, Set.of(), orgWide(org, PermissionType.ADMIN));
		ApiKeyGroupData updated = apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder()
				.groupId(admin.getUuid()).approvals(List.of("QA")).build(), WU);
		assertEquals(PermissionType.ADMIN, updated.getPermission(PermissionScope.ORGANIZATION, org.getUuid()).orElseThrow().getType());

		AgentBoardData a = board(org);
		ApiKeyGroupData plain = group(org, Set.of(), boardRead(a.getUuid()));
		ApiKeyGroupData withApprovals = apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder()
				.groupId(plain.getUuid()).approvals(List.of("QA")).build(), WU);
		assertEquals(PermissionType.NONE, withApprovals.getPermission(PermissionScope.ORGANIZATION, org.getUuid())
				.map(UserPermission::getType).orElse(PermissionType.NONE), "approval roles alone grant no organization-wide access");
	}

	// T-12: unreadable rows of two kinds, ahead of the healthy group, cost only themselves

	@Test
	public void unreadableRowsOfTwoKindsAheadOfAHealthyGroupAreSkipped() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData donor = newGroup(org);
		ApiKeyGroupData donorData = donor;
		ApiKeyGroup unsupported = new ApiKeyGroup();
		unsupported.setUuid(UUID.randomUUID());
		unsupported.setSchemaVersion(1);
		ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(apiKeyGroupRepository.findById(donorData.getUuid()).orElseThrow());
		d.addApiKey(k1.getUuid());
		Map<String, Object> unsupportedData = new java.util.HashMap<>(Utils.dataToRecord(d));
		unsupportedData.put("name", "unsupported-" + UUID.randomUUID());
		unsupported.setRecordData(unsupportedData);
		ApiKeyGroup garbled = new ApiKeyGroup();
		garbled.setUuid(UUID.randomUUID());
		Map<String, Object> garbledData = new java.util.HashMap<>(Utils.dataToRecord(d));
		garbledData.put("name", "garbled-" + UUID.randomUUID());
		garbledData.put("status", 42);
		garbled.setRecordData(garbledData);
		apiKeyGroupRepository.save(unsupported);
		apiKeyGroupRepository.save(garbled);
		try {
			group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
			assertTrue(reads(as(k1), org, a), "the healthy group, created after both bad rows, still grants");
			assertTrue(apiKeyGroupService.getAllApiKeyGroupsByOrganization(org.getUuid()).stream()
					.anyMatch(g -> g.getApiKeys().contains(k1.getUuid())), "and the list still answers with it");
		} finally {
			apiKeyGroupRepository.delete(unsupported);
			apiKeyGroupRepository.delete(garbled);
		}
	}

	// T-13: an update that sends neither name nor description keeps both

	@Test
	public void anUpdateThatOmitsTheNameAndTheDescriptionKeepsThem() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = apiKeyGroupService.createApiKeyGroup(io.reliza.model.dto.CreateApiKeyGroupDto.builder()
				.name("named-" + UUID.randomUUID()).description("described").org(org.getUuid()).build(), WU);
		ApiKey k1 = freeform(org, "k1");

		ApiKeyGroupData updated = setMembers(g, k1.getUuid());
		assertEquals(g.getName(), updated.getName());
		assertEquals("described", updated.getDescription());
		assertEquals(g.getName(), stored(g).getName());
	}

	// T-14: every member is validated, whatever order they come in (a fixed order, the refused key last)

	@Test
	public void aRefusedMemberAfterAnAcceptedOneIsStillRefusedAndNothingIsWritten() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey accepted = freeform(org, "accepted");
		ApiKey refused = userKey(org, member(org, PermissionType.READ_WRITE));
		ApiKeyGroupData g = group(org, Set.of());

		RelizaException e = assertThrows(RelizaException.class, () -> apiKeyGroupService.updateApiKeyGroup(
				UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
						.apiKeys(new java.util.LinkedHashSet<>(List.of(accepted.getUuid(), refused.getUuid()))).build(), WU));
		assertTrue(e.getMessage().contains("Only FREEFORM keys can join"), e.getMessage());
		assertTrue(stored(g).getApiKeys().isEmpty(), "a refused list writes nothing");
	}
}
