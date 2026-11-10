/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.reliza.model.ComponentData.ComponentType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.reliza.common.CommonVariables.ApiKeyGroupKind;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.repositories.ApiKeyGroupRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * ApiKeyGroupService (TPF-91 architecture-5 row 11): names unique over active and inactive groups of an
 * organization, null fields keep the stored value, the by-key read returns only the ACTIVE groups of
 * the key's organization holding it, and the deletion sweep reaches inactive groups and no other
 * organization's.
 */
@SpringBootTest(classes = {App.class})
public class ApiKeyGroupServiceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private ApiKeyGroupService apiKeyGroupService;
	@Autowired private ApiKeyGroupRepository apiKeyGroupRepository;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ExternalApiKeyService externalApiKeyService;
	@Autowired private ComponentService componentService;
	@Autowired private TestInitializer testInitializer;

	private ApiKeyGroupData create(Organization org, String name) throws RelizaException {
		return apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder().name(name).org(org.getUuid()).build(), WU);
	}

	private ApiKeyGroupData update(UpdateApiKeyGroupDto.UpdateApiKeyGroupDtoBuilder b) throws RelizaException {
		return apiKeyGroupService.updateApiKeyGroup(b.build(), WU);
	}

	private ApiKey freeform(Organization org) {
		return apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "akg service", WU);
	}

	private static UserGroupPermissionDto boardRead(UUID board) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.BOARD).objectId(board)
				.type(PermissionType.READ_ONLY).functions(List.of(PermissionFunction.BOARD_READ)).build();
	}

	private ApiKeyGroupData stored(ApiKeyGroupData g) {
		return apiKeyGroupService.getApiKeyGroupData(g.getUuid()).orElseThrow();
	}

	/** The key's uuid written into the row directly, past the service's member validation. */
	private void plant(ApiKeyGroupData g, UUID keyUuid) {
		ApiKeyGroup row = apiKeyGroupRepository.findById(g.getUuid()).orElseThrow();
		ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(row);
		d.addApiKey(keyUuid);
		row.setRecordData(Utils.dataToRecord(d));
		apiKeyGroupRepository.save(row);
	}

	@Test
	public void aNameIsUniqueOverActiveAndInactiveGroupsOfTheOrganization() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String active = "akg-active-" + UUID.randomUUID();
		create(org, active);
		RelizaException taken = assertThrows(RelizaException.class, () -> create(org, active));
		assertTrue(taken.getMessage().contains("already exists"), taken.getMessage());

		String inactive = "akg-inactive-" + UUID.randomUUID();
		ApiKeyGroupData g = create(org, inactive);
		apiKeyGroupService.deactivateApiKeyGroup(g.getUuid(), WU);
		RelizaException restoreIt = assertThrows(RelizaException.class, () -> create(org, inactive));
		assertTrue(restoreIt.getMessage().contains("inactive group"), restoreIt.getMessage());

		ApiKeyGroupData other = create(org, "akg-other-" + UUID.randomUUID());
		RelizaException rename = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(other.getUuid()).name(active)));
		assertTrue(rename.getMessage().contains("already exists"), rename.getMessage());

		// a restore that would land on a name another group holds is refused; the plain restore is not
		RelizaException restore = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(g.getUuid()).name(active).status(ApiKeyGroupStatus.ACTIVE)));
		assertTrue(restore.getMessage().contains("already exists"), restore.getMessage());
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus());
		assertEquals(ApiKeyGroupStatus.ACTIVE, update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.status(ApiKeyGroupStatus.ACTIVE)).getStatus());
	}

	@Test
	public void theSameNameInAnotherOrganizationIsAllowed() throws RelizaException {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org1.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		String name = "akg-cross-" + UUID.randomUUID();
		create(org1, name);
		assertEquals(name, create(org2, name).getName());
	}

	@Test
	public void nullFieldsKeepTheStoredValueAndAnUnknownGroupIsNotFound() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k = freeform(org);
		UUID board = UUID.randomUUID();
		ApiKeyGroupData g = create(org, "akg-keep-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).apiKeys(Set.of(k.getUuid())).permissions(List.of(boardRead(board))));
		ApiKeyGroupData before = stored(g);
		ApiKeyGroupData after = update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).description("only this"));
		assertEquals("only this", after.getDescription());
		assertEquals(before.getName(), after.getName());
		assertEquals(Set.of(k.getUuid()), after.getApiKeys());
		assertEquals(before.getOrgPermissions(org.getUuid()), after.getOrgPermissions(org.getUuid()));
		assertEquals(ApiKeyGroupStatus.ACTIVE, after.getStatus());

		RelizaException e = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(UUID.randomUUID()).description("x")));
		assertTrue(e.getMessage().contains("API key group not found"), e.getMessage());
	}

	@Test
	public void permissionSetsForKeyReadsOnlyTheActiveGroupsOfTheKeysOrganizationHoldingIt() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey k = freeform(org);
		UUID b1 = UUID.randomUUID();
		ApiKeyGroupData holding = create(org, "akg-holding-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(holding.getUuid()).apiKeys(Set.of(k.getUuid())).permissions(List.of(boardRead(b1))));
		ApiKeyGroupData inactive = create(org, "akg-inactive-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(inactive.getUuid()).apiKeys(Set.of(k.getUuid()))
				.permissions(List.of(boardRead(UUID.randomUUID()))));
		apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);
		ApiKeyGroupData notHolding = create(org, "akg-not-holding-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(notHolding.getUuid()).permissions(List.of(boardRead(UUID.randomUUID()))));
		ApiKeyGroupData foreign = create(org2, "akg-foreign-" + UUID.randomUUID());
		plant(foreign, k.getUuid());

		List<Set<UserPermission>> sets = apiKeyGroupService.permissionSetsForKey(k.getUuid(), org.getUuid(), ApiKeyGroupKind.INTERNAL);
		assertEquals(List.of(stored(holding).getOrgPermissions(org.getUuid())), sets,
				"the inactive group, the group not holding the key and the other organization's group are absent");
	}

	// ---------- FDAW-37 row 10: the kind, the membership rules, the grants and the bounds ----------

	private ApiKeyGroupData customer(Organization org) throws RelizaException {
		return apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder().name("akg-customer-" + UUID.randomUUID())
				.org(org.getUuid()).kind(ApiKeyGroupKind.CUSTOMER).build(), WU);
	}

	private ApiKey external(Organization org) throws RelizaException {
		return externalApiKeyService.create(org.getUuid(), "akg-external-" + UUID.randomUUID(), null, WU);
	}

	private UUID component(Organization org) throws RelizaException {
		return componentService.createComponent("akg_c_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU).getUuid();
	}

	private static UserGroupPermissionDto grant(PermissionScope scope, UUID object, PermissionType type) {
		return UserGroupPermissionDto.builder().scope(scope).objectId(object).type(type).build();
	}

	/** The refusal text of the update, and the group unchanged by it. */
	private void refusedUpdate(ApiKeyGroupData g, String text, UpdateApiKeyGroupDto.UpdateApiKeyGroupDtoBuilder b) {
		ApiKeyGroupData before = stored(g);
		RelizaException e = assertThrows(RelizaException.class, () -> update(b.groupId(g.getUuid())));
		assertEquals(text, e.getMessage());
		ApiKeyGroupData after = stored(g);
		assertEquals(before.getName(), after.getName());
		assertEquals(before.getDescription(), after.getDescription());
		assertEquals(before.getApiKeys(), after.getApiKeys());
		assertEquals(before.getOrgPermissions(g.getOrg()), after.getOrgPermissions(g.getOrg()));
	}

	@Test
	public void aRowWithNoKindReadsInternalAndTheKindIsSetAtCreateAndNeverChanges() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKeyGroupData g = create(org, "akg-kind-" + UUID.randomUUID());
		assertEquals(ApiKeyGroupKind.INTERNAL, g.getKind(), "a create without a kind");
		assertEquals(ApiKeyGroupKind.INTERNAL, ApiKeyGroupData.toWebDto(g).getKind());
		ApiKeyGroup row = apiKeyGroupRepository.findById(g.getUuid()).orElseThrow();
		Map<String, Object> noKind = new HashMap<>(row.getRecordData());
		noKind.remove("kind");
		row.setRecordData(noKind);
		apiKeyGroupRepository.save(row);
		assertEquals(ApiKeyGroupKind.INTERNAL, stored(g).getKind(), "a stored row with no kind");

		ApiKeyGroupData c = customer(org);
		assertEquals(ApiKeyGroupKind.CUSTOMER, stored(c).getKind());
		assertEquals(ApiKeyGroupKind.CUSTOMER, ApiKeyGroupData.toWebDto(stored(c)).getKind());
		assertEquals(ApiKeyGroupKind.CUSTOMER, update(UpdateApiKeyGroupDto.builder().groupId(c.getUuid()).description("kept")).getKind());
		assertEquals(ApiKeyGroupKind.CUSTOMER, stored(c).getKind(), "the merge copies the stored kind");
		assertTrue(Arrays.stream(UpdateApiKeyGroupDto.class.getDeclaredFields()).noneMatch(f -> "kind".equals(f.getName())),
				"an update has no way to name a kind");
	}

	@Test
	public void eachKindAdmitsItsOneKeyTypeOnUpdate() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ff = freeform(org);
		ApiKey ext = external(org);
		ApiKeyGroupData internal = create(org, "akg-int-" + UUID.randomUUID());
		ApiKeyGroupData cust = customer(org);

		refusedUpdate(cust, "Only external keys can join a customer group: " + ff.getUuid(),
				UpdateApiKeyGroupDto.builder().apiKeys(Set.of(ff.getUuid())));
		refusedUpdate(internal, "Only FREEFORM keys can join an API key group: " + ext.getUuid(),
				UpdateApiKeyGroupDto.builder().apiKeys(Set.of(ext.getUuid())));
		assertEquals(Set.of(ext.getUuid()), update(UpdateApiKeyGroupDto.builder().groupId(cust.getUuid()).apiKeys(Set.of(ext.getUuid()))).getApiKeys());
		assertEquals(Set.of(ff.getUuid()), update(UpdateApiKeyGroupDto.builder().groupId(internal.getUuid()).apiKeys(Set.of(ff.getUuid()))).getApiKeys());
		UUID unknown = UUID.randomUUID();
		refusedUpdate(cust, "API key not found: " + unknown, UpdateApiKeyGroupDto.builder().apiKeys(Set.of(unknown)));
	}

	@Test
	public void aCustomerGroupsGrantsAreNormalizedAsAnExternalKeysAndCarryNoApprovals() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID c = component(org);
		ApiKeyGroupData g = customer(org);

		ApiKeyGroupData updated = update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).approvals(List.of("QA")).permissions(List.of(
				UserGroupPermissionDto.builder().scope(PermissionScope.COMPONENT).objectId(c).type(PermissionType.READ_ONLY)
						.functions(List.of(PermissionFunction.RESOURCE)).approvals(List.of("SEC")).build(),
				grant(PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY))));
		assertEquals(2, updated.getOrgPermissions(org.getUuid()).size(), "both grants, and no organization-wide entry for the approvals");
		for (UserPermission p : stored(g).getOrgPermissions(org.getUuid())) {
			assertEquals(PermissionType.READ_ONLY, p.getType());
			assertEquals(Set.of(PermissionFunction.TEA_READ), p.getFunctions());
			assertTrue(p.getApprovals().isEmpty(), p.toString());
		}

		refusedUpdate(g, "A customer group carries TEA grants only", UpdateApiKeyGroupDto.builder().approvals(List.of("QA")));
		refusedUpdate(g, "A customer group carries TEA grants only", UpdateApiKeyGroupDto.builder().approvals(List.of("QA")).permissions(List.of()));
		refusedUpdate(g, "The grant COMPONENT " + c + " has type READ_WRITE; an external key is READ_ONLY only",
				UpdateApiKeyGroupDto.builder().permissions(List.of(grant(PermissionScope.COMPONENT, c, PermissionType.READ_WRITE))));
		UUID board = UUID.randomUUID();
		refusedUpdate(g, "The grant BOARD " + board + " has scope BOARD; an external key is granted at ORGANIZATION, PERSPECTIVE or COMPONENT scope only",
				UpdateApiKeyGroupDto.builder().permissions(List.of(boardRead(board))));
		UUID unknown = UUID.randomUUID();
		refusedUpdate(g, "The grant COMPONENT " + unknown + " names a component that does not exist",
				UpdateApiKeyGroupDto.builder().permissions(List.of(grant(PermissionScope.COMPONENT, unknown, PermissionType.READ_ONLY))));

		// an empty approvals list and no permissions: nothing refused, nothing written to the grants
		ApiKeyGroupData kept = update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).approvals(List.of()));
		assertEquals(stored(g).getOrgPermissions(org.getUuid()), kept.getOrgPermissions(org.getUuid()));
		assertEquals(2, kept.getOrgPermissions(org.getUuid()).size());
	}

	@Test
	public void aForeignComponentIsRefusedAsAGrantOfACustomerGroup() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(other.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		UUID foreign = component(other);
		refusedUpdate(customer(org), "The grant COMPONENT " + foreign + " names a component of another organization",
				UpdateApiKeyGroupDto.builder().permissions(List.of(grant(PermissionScope.COMPONENT, foreign, PermissionType.READ_ONLY))));
	}

	@Test
	public void aSuppliedNameOrDescriptionIsBoundedBeforeAnyRowIsRead() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		// an unknown group and a null organization fail on the first read: the bound's text proves it came first
		Map<String, String> names = Map.of("x".repeat(513), "name is longer than 512 characters",
				"bell\u0007", "name holds a control character", "zero\u200Bwidth", "name holds a control character");
		for (Map.Entry<String, String> bad : names.entrySet()) {
			for (ApiKeyGroupKind kind : ApiKeyGroupKind.values()) {
				RelizaException atCreate = assertThrows(RelizaException.class, () -> apiKeyGroupService.createApiKeyGroup(
						CreateApiKeyGroupDto.builder().name(bad.getKey()).kind(kind).build(), WU));
				assertEquals(bad.getValue(), atCreate.getMessage());
			}
			RelizaException atUpdate = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
					.groupId(UUID.randomUUID()).name(bad.getKey())));
			assertEquals(bad.getValue(), atUpdate.getMessage());
		}
		String over = "x".repeat(2049);
		RelizaException longAtCreate = assertThrows(RelizaException.class, () -> apiKeyGroupService.createApiKeyGroup(
				CreateApiKeyGroupDto.builder().name("n").description(over).build(), WU));
		assertEquals("description is longer than 2048 characters", longAtCreate.getMessage());
		RelizaException longAtUpdate = assertThrows(RelizaException.class, () -> update(UpdateApiKeyGroupDto.builder()
				.groupId(UUID.randomUUID()).description(over)));
		assertEquals("description is longer than 2048 characters", longAtUpdate.getMessage());
		RelizaException blank = assertThrows(RelizaException.class, () -> apiKeyGroupService.createApiKeyGroup(
				CreateApiKeyGroupDto.builder().name("  ").build(), WU));
		assertEquals("name is required", blank.getMessage());
		RelizaException none = assertThrows(RelizaException.class, () -> apiKeyGroupService.createApiKeyGroup(
				CreateApiKeyGroupDto.builder().build(), WU));
		assertEquals("name is required", none.getMessage());

		// on real rows of both kinds, the longest values pass and the refused ones change nothing
		for (ApiKeyGroupData g : List.of(create(org, "akg-bound-" + UUID.randomUUID()), customer(org))) {
			refusedUpdate(g, "name is longer than 512 characters", UpdateApiKeyGroupDto.builder().name("x".repeat(513)));
			refusedUpdate(g, "description is longer than 2048 characters", UpdateApiKeyGroupDto.builder().description(over));
			refusedUpdate(g, "name is required", UpdateApiKeyGroupDto.builder().name("   "));
			String longest = UUID.randomUUID() + "y".repeat(476);
			assertEquals(longest, update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).name(longest)
					.description("z".repeat(2048))).getName());
		}
	}

	@Test
	public void aSuppliedFieldIsStrippedABlankDescriptionClearsAndAKeptValueIsNotChecked() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String name = "akg-strip-" + UUID.randomUUID();
		ApiKeyGroupData g = apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder().name("  " + name + "\n")
				.description("  described ").org(org.getUuid()).build(), WU);
		assertEquals(name, stored(g).getName());
		assertEquals("described", stored(g).getDescription());
		RelizaException taken = assertThrows(RelizaException.class, () -> create(org, " " + name + " "));
		assertTrue(taken.getMessage().contains("already exists"), "the conflict check compares the stripped name: " + taken.getMessage());

		assertEquals("edited", update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).description(" edited\t")).getDescription());
		assertNull(update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).description("   ")).getDescription(), "a supplied blank clears");
		assertNull(stored(g).getDescription());
		assertEquals(name, stored(g).getName());

		// a stored name past the bound: a save that does not send it passes and keeps it
		ApiKey k = freeform(org);
		ApiKeyGroup row = apiKeyGroupRepository.findById(g.getUuid()).orElseThrow();
		Map<String, Object> longName = new HashMap<>(row.getRecordData());
		String stored600 = UUID.randomUUID() + "n".repeat(564);
		longName.put("name", stored600);
		row.setRecordData(longName);
		apiKeyGroupRepository.save(row);
		ApiKeyGroupData kept = update(UpdateApiKeyGroupDto.builder().groupId(g.getUuid()).apiKeys(Set.of(k.getUuid())));
		assertEquals(stored600, kept.getName());
		assertEquals(Set.of(k.getUuid()), kept.getApiKeys());
	}

	@Test
	public void setGroupsOfKeyChecksEveryDesiredGroupBeforeAnyLockAndTouchesOnlyTheKeysKind() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey ff = freeform(org);
		ApiKey ext = external(org);
		ApiKeyGroupData internal = create(org, "akg-set-i-" + UUID.randomUUID());
		ApiKeyGroupData c1 = customer(org);
		ApiKeyGroupData c2 = customer(org);
		update(UpdateApiKeyGroupDto.builder().groupId(internal.getUuid()).apiKeys(Set.of(ff.getUuid())));
		update(UpdateApiKeyGroupDto.builder().groupId(c1.getUuid()).apiKeys(Set.of(ext.getUuid())));
		long revisionOfC2 = apiKeyGroupRepository.findById(c2.getUuid()).orElseThrow().getRevision();

		RelizaException mixed = assertThrows(RelizaException.class, () -> apiKeyGroupService.setGroupsOfKey(ext.getUuid(), org.getUuid(),
				List.of(c2.getUuid(), internal.getUuid()), WU));
		assertEquals("Only FREEFORM keys can join an API key group: " + ext.getUuid(), mixed.getMessage());
		assertEquals(Set.of(), stored(c2).getApiKeys(), "the CUSTOMER group listed first is not written");
		assertEquals(revisionOfC2, apiKeyGroupRepository.findById(c2.getUuid()).orElseThrow().getRevision());
		assertEquals(Set.of(ext.getUuid()), stored(c1).getApiKeys());

		RelizaException wrongSide = assertThrows(RelizaException.class, () -> apiKeyGroupService.setGroupsOfKey(ff.getUuid(), org.getUuid(),
				List.of(c1.getUuid()), WU));
		assertEquals("Only external keys can join a customer group: " + ff.getUuid(), wrongSide.getMessage());
		assertEquals(Set.of(ff.getUuid()), stored(internal).getApiKeys());

		// the EXTERNAL key moves between CUSTOMER groups; the answer and the writes stay on its side
		plant(internal, ext.getUuid());
		List<ApiKeyGroupData> moved = apiKeyGroupService.setGroupsOfKey(ext.getUuid(), org.getUuid(), List.of(c2.getUuid()), WU);
		assertEquals(List.of(c2.getUuid()), moved.stream().map(ApiKeyGroupData::getUuid).toList(), "groups of the key's kind only");
		assertEquals(Set.of(), stored(c1).getApiKeys());
		assertEquals(Set.of(ext.getUuid()), stored(c2).getApiKeys());
		assertEquals(Set.of(ff.getUuid(), ext.getUuid()), stored(internal).getApiKeys(), "a group of the other kind is not touched");

		// and the FREEFORM key's empty desired list leaves a planted CUSTOMER membership alone
		plant(c1, ff.getUuid());
		assertTrue(apiKeyGroupService.setGroupsOfKey(ff.getUuid(), org.getUuid(), List.of(), WU).isEmpty());
		assertEquals(Set.of(ext.getUuid()), stored(internal).getApiKeys());
		assertEquals(Set.of(ff.getUuid()), stored(c1).getApiKeys());
	}

	@Test
	public void theDeletionSweepReachesInactiveGroupsAndNoOtherOrganization() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey k = freeform(org);
		ApiKeyGroupData active = create(org, "akg-sweep-a-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(active.getUuid()).apiKeys(Set.of(k.getUuid())));
		ApiKeyGroupData inactive = create(org, "akg-sweep-i-" + UUID.randomUUID());
		update(UpdateApiKeyGroupDto.builder().groupId(inactive.getUuid()).apiKeys(Set.of(k.getUuid())));
		apiKeyGroupService.deactivateApiKeyGroup(inactive.getUuid(), WU);
		ApiKeyGroupData foreign = create(org2, "akg-sweep-f-" + UUID.randomUUID());
		plant(foreign, k.getUuid());

		apiKeyGroupService.removeKeyFromAllGroups(k.getUuid(), org.getUuid(), WU);
		assertFalse(stored(active).getApiKeys().contains(k.getUuid()));
		assertFalse(stored(inactive).getApiKeys().contains(k.getUuid()));
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(inactive).getStatus());
		assertEquals(Set.of(k.getUuid()), stored(foreign).getApiKeys(), "another organization's group is untouched");
	}
}
