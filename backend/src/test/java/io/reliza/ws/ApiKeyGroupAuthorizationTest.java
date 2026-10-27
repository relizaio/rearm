/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.ApiKeyGroupStatus;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Audit;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateApiKeyGroupDto;

/**
 * API key groups on the key-authenticated path and the browser lane (TPF-91 architecture-5 section 5,
 * rows 1 to 10): a FREEFORM key gains its ACTIVE groups' grants on the next call and loses them on the
 * next call once it leaves, the group goes INACTIVE or the key is deleted; no other key type gains
 * anything; members and grant objects stay inside the organization; only an organization administrator
 * manages groups, and a sealed or unlicensed system answers before any group is looked up.
 */
public class ApiKeyGroupAuthorizationTest extends ApiKeyGroupTestBase {

	private boolean reads(AuthHeaderParse ahp, Organization org, AgentBoardData bd) throws RelizaException {
		return authorizationService.boardPermission(ahp, org.getUuid(), bd.getUuid(), PermissionFunction.BOARD_READ, CallType.READ);
	}

	private List<Audit> audit(UUID entity, TableName tn) {
		return auditService.getAuditForEntity(entity, tn, 1000, 0);
	}

	// ---------- rows 1 and 2: in the group gains, out of it loses, a key outside never gains ----------

	@Test
	public void aKeyGainsItsGroupsGrantOnTheNextCallAndLosesItOnceRemoved() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKey k2 = freeform(org, "k2 never added");
		AuthHeaderParse ahp = as(k1);   // built once: no re-exchange between the steps
		AuthHeaderParse outside = as(k2);
		assertFalse(reads(ahp, org, a), "no grant of its own and no group");
		assertFalse(reads(outside, org, a));

		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
		assertTrue(reads(ahp, org, a), "the group's BOARD_READ reaches its member on the same principal");
		assertFalse(reads(ahp, org, b), "the group grants board a only");
		assertFalse(reads(outside, org, a), "a key outside the group gains nothing");
		assertFalse(reads(outside, org, b));

		setMembers(g);
		assertFalse(reads(ahp, org, a), "removed: refused on the next call");
		assertFalse(reads(ahp, org, b));
		assertFalse(reads(outside, org, a));
	}

	// ---------- row 3: only FREEFORM keys join ----------

	@Test
	public void onlyFreeformKeysJoinAndARefusedListChangesNothing() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()));
		UserData owner = member(org, PermissionType.READ_ONLY);
		ApiKey userKey = userKey(org, owner);
		Component c = componentService.createComponent("akg_c_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		ApiKey componentKey = apiKeyService.createObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, org.getUuid(),
				UUID.randomUUID().toString(), "component", WU);

		for (ApiKey other : List.of(userKey, componentKey)) {
			RelizaException e = assertThrows(RelizaException.class, () -> setMembers(g, k1.getUuid(), other.getUuid()));
			assertTrue(e.getMessage().contains("Only FREEFORM keys"), e.getMessage());
			assertTrue(e.getMessage().contains(other.getUuid().toString()), e.getMessage());
			assertEquals(Set.of(k1.getUuid()), stored(g).getApiKeys(), "a refused list leaves the members unchanged");
		}
	}

	// ---------- row 4: the keyPermissions type guard ----------

	@Test
	public void aUserKeyPlantedInAGroupGainsNothingAndOtherTypesNeverReachTheUnion() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		UserData owner = member(org, PermissionType.READ_ONLY);
		teamGrantingRead(org, owner, a);
		assertTrue(authorizationService.boardPermission(owner, org.getUuid(), a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ),
				"precondition: the owner side allows BOARD_READ on a");
		ApiKey userKey = userKey(org, owner);
		apiKeyService.setPermissionsOnApiKey(userKey.getUuid(), PermissionType.READ_ONLY, List.of(), WU);
		ApiKeyGroupData g = group(org, Set.of(), boardRead(a.getUuid()));
		plant(g, userKey.getUuid());
		assertTrue(stored(g).getApiKeys().contains(userKey.getUuid()), "planted past the service");

		// Only the key side can refuse here: dropping the FREEFORM guard in keyPermissions turns this true.
		assertFalse(reads(as(userKey), org, a), "a USER key is never unioned with a group");
		assertFalse(reads(as(userKey), org, b));

		// Positive control: the same grant on the key's own row passes both sides.
		apiKeyService.setPermissionsOnApiKey(userKey.getUuid(), PermissionType.READ_ONLY,
				List.of(boardGrant(org, a.getUuid(), PermissionType.READ_ONLY, PermissionFunction.BOARD_READ)), WU);
		assertTrue(reads(as(userKey), org, a), "the owner side allows it; the earlier refusal was the key side's");
		assertFalse(reads(as(userKey), org, b));

		Component c = componentService.createComponent("akg_c_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		ApiKey componentKey = apiKeyService.createObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, org.getUuid(),
				UUID.randomUUID().toString(), "component", WU);
		plant(g, componentKey.getUuid());
		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> reads(as(componentKey), org, a));
		assertEquals("FREEFORM or USER API key required", e.getMessage());
	}

	// ---------- row 5: another organization's key and group ----------

	@Test
	public void anotherOrganizationsKeyIsUnknownAndItsGroupNotAuthorized() throws RelizaException {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org1.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey k1 = freeform(org1, "k1");
		ApiKey k3 = freeform(org2, "org2 key");
		ApiKeyGroupData g = group(org1, Set.of(k1.getUuid()));
		RelizaException foreign = assertThrows(RelizaException.class, () -> setMembers(g, k3.getUuid()));
		assertEquals("API key not found: " + k3.getUuid(), foreign.getMessage());
		UUID unknownKey = UUID.randomUUID();
		RelizaException unknown = assertThrows(RelizaException.class, () -> setMembers(g, unknownKey));
		assertEquals("API key not found: " + unknownKey, unknown.getMessage(), "the same text, only the uuid differs");
		assertEquals(Set.of(k1.getUuid()), stored(g).getApiKeys());

		ApiKeyGroupData g2 = group(org2, Set.of(k3.getUuid()));
		signInAs(member(org1, PermissionType.ADMIN));
		assertEquals("Not authorized", onlyError(gqlUpdateMembers(g2.getUuid())));
		assertEquals("Not authorized", onlyError(gqlUpdateMembers(UUID.randomUUID())));
		assertEquals("Not authorized", onlyError(gqlDelete(g2.getUuid())));
		assertEquals("Not authorized", onlyError(gqlDelete(UUID.randomUUID())));
		assertEquals(Set.of(k3.getUuid()), stored(g2).getApiKeys());
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(g2).getStatus());
	}

	// ---------- row 6: the admin gate and the writer ----------

	@Test
	public void onlyAnOrganizationAdminManagesGroupsAndARequestedKeyGainsOnceApproved() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKeyGroupData g = group(org, Set.of(), boardRead(a.getUuid()));
		UserData writer = member(org, PermissionType.READ_WRITE);
		ApiKey r = apiKeyService.requestFreeformApiKey(writer.getUuid(), org.getUuid(), "ci", PermissionType.NONE, List.of(), WU);

		signInAs(writer);
		assertEquals("Not authorized", onlyError(gqlUpdateMembers(g.getUuid(), r.getUuid())));
		assertEquals(Set.of(), stored(g).getApiKeys());
		assertEquals("Not authorized", onlyError(gqlCreate(org, "writer-" + UUID.randomUUID())));
		assertEquals("Not authorized", onlyError(gqlDelete(g.getUuid())));
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(g).getStatus());
		assertEquals("Not authorized", onlyError(gqlList(org)));

		signInAs(member(org, PermissionType.ADMIN));
		ExecutionResult added = gqlUpdateMembers(g.getUuid(), r.getUuid());
		assertTrue(added.getErrors().isEmpty(), added.getErrors().toString());
		assertTrue(stored(g).getApiKeys().contains(r.getUuid()));

		// The secret rule: a key with no secret has no token to present.
		assertFalse(authorizationService.boardPermission(apiKeyService.getApiKey(r.getUuid()).orElseThrow(), org.getUuid(),
				a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ));
		apiKeyService.resolveApiKeyRequest(r.getUuid(), true, null, WU);
		assertFalse(authorizationService.boardPermission(apiKeyService.getApiKey(r.getUuid()).orElseThrow(), org.getUuid(),
				a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ));
		assertTrue(reads(as(apiKeyService.getApiKey(r.getUuid()).orElseThrow()), org, a), "the group grant holds once approved");
	}

	// ---------- row 7: deactivation revokes, restore regrants ----------

	@Test
	@SuppressWarnings("unchecked")
	public void deactivatingAGroupRevokesOnTheNextCallAndRestoringRegrants() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		AuthHeaderParse ahp = as(k1);
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()), boardRead(a.getUuid()));
		assertTrue(reads(ahp, org, a));

		signInAs(member(org, PermissionType.ADMIN));
		ExecutionResult deleted = gqlDelete(g.getUuid());
		assertTrue(deleted.getErrors().isEmpty(), deleted.getErrors().toString());
		Map<String, Object> dto = (Map<String, Object>) ((Map<String, Object>) deleted.getData()).get("deleteApiKeyGroup");
		assertEquals("INACTIVE", dto.get("status"));
		assertEquals(List.of(k1.getUuid().toString()), dto.get("apiKeys"));
		assertFalse(reads(ahp, org, a), "an INACTIVE group grants nothing from the next call");
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(g).getStatus());
		assertEquals(Set.of(k1.getUuid()), stored(g).getApiKeys(), "deactivated, members kept");
		List<Map<String, Object>> listed = (List<Map<String, Object>>) ((Map<String, Object>) gqlList(org).getData()).get("apiKeyGroupsOfOrg");
		assertTrue(listed.stream().anyMatch(x -> g.getUuid().toString().equals(x.get("uuid")) && "INACTIVE".equals(x.get("status"))),
				listed.toString());

		RelizaException e = assertThrows(RelizaException.class, () -> apiKeyGroupService.createApiKeyGroup(
				CreateApiKeyGroupDto.builder().name(g.getName()).org(org.getUuid()).build(), WU));
		assertTrue(e.getMessage().contains("inactive group"), e.getMessage());

		restoreGroup(g);
		assertTrue(reads(ahp, org, a), "restored: granted again");
	}

	// ---------- row 8: grant objects of this org, and the system state before any lookup ----------

	@Test
	public void aGrantObjectMustBeABoardOfTheOrganization() throws RelizaException {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org1.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		AgentBoardData a = board(org1);
		AgentBoardData foreignBoard = board(org2);
		ApiKeyGroupData g = group(org1, Set.of(), boardRead(a.getUuid()));
		Set<UserPermission> before = stored(g).getOrgPermissions(org1.getUuid());
		signInAs(member(org1, PermissionType.ADMIN));
		for (UUID object : List.of(foreignBoard.getUuid(), UUID.randomUUID())) {
			ExecutionResult r = gqlUpdate(Map.of("groupId", g.getUuid().toString(), "permissions", List.of(Map.of(
					"scope", "BOARD", "objectId", object.toString(), "type", "READ_ONLY", "functions", List.of("BOARD_READ")))));
			assertTrue(onlyError(r).contains("is not a board of this organization"), r.getErrors().toString());
			assertEquals(before, stored(g).getOrgPermissions(org1.getUuid()), "nothing stored");
		}
	}

	@Test
	public void aSealedOrUnlicensedSystemAnswersBeforeAnyGroupIsLookedUp() throws RelizaException {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org1.getUuid().equals(org2.getUuid()),
				"Skipping: requires two distinct organizations (not available in CE)");
		ApiKey k1 = freeform(org1, "k1");
		ApiKeyGroupData g = group(org1, Set.of(k1.getUuid()));
		ApiKeyGroupData g2 = group(org2, Set.of());
		signInAs(member(org1, PermissionType.ADMIN));
		UUID unknown = UUID.randomUUID();

		licenseStatus.setSystemSealed(true);
		assertSystemStateFirst(org1, g, g2, unknown, "System is sealed");
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(false);
		assertSystemStateFirst(org1, g, g2, unknown, "License is invalid");
		licenseStatus.setLicenseValid(true);

		assertEquals(Set.of(k1.getUuid()), stored(g).getApiKeys());
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(g).getStatus());
		assertEquals(ApiKeyGroupStatus.ACTIVE, stored(g2).getStatus());
	}

	/** Every root field answers the system-state text, for an existing, an unknown and a foreign group alike. */
	private void assertSystemStateFirst(Organization org1, ApiKeyGroupData g, ApiKeyGroupData g2, UUID unknown, String text) {
		for (UUID target : List.of(g.getUuid(), unknown, g2.getUuid())) {
			for (ExecutionResult r : List.of(gqlUpdateMembers(target), gqlDelete(target))) {
				String m = onlyError(r);
				assertTrue(m.contains(text), m);
				assertFalse(m.contains("Not authorized"), "the system state is answered before the group is looked up: " + m);
			}
		}
		assertTrue(onlyError(gqlCreate(org1, "sealed-" + UUID.randomUUID())).contains(text));
		assertTrue(onlyError(gqlList(org1)).contains(text));
	}

	// ---------- row 9: audit ----------

	@Test
	public void everyGroupSaveIsAuditedAndTheKeyRowIsUntouched() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = group(org, Set.of(), boardRead(a.getUuid()));
		int n0 = audit(g.getUuid(), TableName.API_KEY_GROUPS).size();
		int k0 = audit(k1.getUuid(), TableName.API_KEYS).size();
		setMembers(g, k1.getUuid());
		List<Audit> rows = audit(g.getUuid(), TableName.API_KEY_GROUPS);
		assertEquals(n0 + 1, rows.size());
		assertTrue(reads(as(k1), org, a));
		assertFalse(reads(as(k1), org, board(org)));
		assertEquals(k0, audit(k1.getUuid(), TableName.API_KEYS).size(), "a membership change never writes the key row");
		for (Audit row : rows) {
			assertEquals(TableName.API_KEY_GROUPS, row.getEntityName());
			assertEquals("TEST", String.valueOf(row.getRevisionRecordData().get("createdType")), "WhoUpdated of WU");
			assertNull(row.getRevisionRecordData().get("lastUpdatedBy"));
		}
	}

	// ---------- row 10: a deleted key leaves its groups ----------

	@Test
	public void aDeletedKeyLeavesEveryGroupActiveOrNot() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey k1 = freeform(org, "k1");
		ApiKeyGroupData g = group(org, Set.of(k1.getUuid()));
		int n0 = audit(g.getUuid(), TableName.API_KEY_GROUPS).size();
		apiKeyService.deleteApiKey(k1.getUuid(), WU);
		assertFalse(stored(g).getApiKeys().contains(k1.getUuid()));
		assertEquals(n0 + 1, audit(g.getUuid(), TableName.API_KEY_GROUPS).size());
		assertTrue(apiKeyService.getApiKey(k1.getUuid()).isEmpty(), "a tombstone");

		ApiKey k = freeform(org, "in three groups");
		ApiKeyGroupData a1 = group(org, Set.of(k.getUuid()));
		ApiKeyGroupData a2 = group(org, Set.of(k.getUuid()));
		ApiKeyGroupData a3 = group(org, Set.of(k.getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(a3.getUuid(), WU);
		List<ApiKeyGroupData> three = List.of(a1, a2, a3);
		int before = three.stream().mapToInt(x -> audit(x.getUuid(), TableName.API_KEY_GROUPS).size()).sum();
		apiKeyService.deleteApiKey(k.getUuid(), WU);
		for (ApiKeyGroupData x : three) assertFalse(stored(x).getApiKeys().contains(k.getUuid()), x.getName());
		assertEquals(ApiKeyGroupStatus.INACTIVE, stored(a3).getStatus(), "the inactive group is swept and stays inactive");
		assertEquals(before + 3, three.stream().mapToInt(x -> audit(x.getUuid(), TableName.API_KEY_GROUPS).size()).sum(),
				"one audited save per group");
	}
}
