/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionType;

/**
 * setApiKeyGroupsOfKey (TPF-123 architecture-3 section 5, rows 8 to 12; the rollback case of row 12 is in
 * ApiKeyGroupKeySideConcurrencyTest, which carries the spy): the key joins and leaves groups as a desired set,
 * one audit row per changed group with the administrator as actor and none on a no-op, FREEFORM keys only,
 * the whole group list validated first, and never an add to an INACTIVE group.
 */
public class ApiKeyGroupKeySideMembershipTest extends ApiKeyGroupKeySideTestBase {

	private static final String NOT_AUTHORIZED = "Not authorized";

	private static List<String> uuidsOf(List<Map<String, Object>> answered) {
		return answered.stream().map(m -> String.valueOf(m.get("uuid"))).toList();
	}

	private static List<String> byName(ApiKeyGroupData... groups) {
		return List.of(groups).stream().sorted(Comparator.comparing(ApiKeyGroupData::getName, String.CASE_INSENSITIVE_ORDER))
				.map(g -> g.getUuid().toString()).toList();
	}

	// ---------- rows 8 and 9: add and remove, then the same set again is a no-op ----------

	@Test
	public void theKeyJoinsAndLeavesGroupsAsADesiredSetAndTheSameSetAgainWritesNothing() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		AgentBoardData c = board(org);
		AgentBoardData d = board(org);
		ApiKey k = freeform(org, "k");
		ApiKey k0 = freeform(org, "k0");
		ApiKeyGroupData g1 = group(org, Set.of(k.getUuid(), k0.getUuid()), boardRead(d.getUuid()));
		ApiKeyGroupData g2 = group(org, Set.of(), orgWide(org, PermissionType.READ_WRITE));
		ApiKeyGroupData g3 = group(org, Set.of(), boardRead(c.getUuid()));
		int n1 = groupAudits(g1), n2 = groupAudits(g2), n3 = groupAudits(g3), nk = keyAudits(k);
		var g2Permissions = stored(g2).getOrgPermissions(org.getUuid());
		signInAs(admin);

		List<Map<String, Object>> answered = groupsAnswered(gqlSetGroups(org.getUuid(), k.getUuid(), g2.getUuid(), g3.getUuid()));

		assertEquals(byName(g2, g3), uuidsOf(answered), "G2 and G3, by name");
		for (Map<String, Object> m : answered) assertTrue(strings(m.get("apiKeys")).contains(k.getUuid().toString()), m.toString());
		assertFalse(stored(g1).hasApiKey(k.getUuid()));
		assertTrue(stored(g2).hasApiKey(k.getUuid()));
		assertTrue(stored(g3).hasApiKey(k.getUuid()));
		assertEquals(Set.of(k0.getUuid()), stored(g1).getApiKeys(), "the other member is untouched");
		assertEquals(g2Permissions, stored(g2).getOrgPermissions(org.getUuid()), "the group's grants are untouched");
		assertEquals(n1 + 1, groupAudits(g1));
		assertEquals(n2 + 1, groupAudits(g2));
		assertEquals(n3 + 1, groupAudits(g3));
		assertEquals(nk, keyAudits(k), "the key row is never written");
		assertActor(g1.getUuid(), admin, "the set");
		assertActor(g2.getUuid(), admin, "the set");
		assertActor(g3.getUuid(), admin, "the set");
		assertTrue(reads(as(fresh(k)), org, c));
		assertFalse(reads(as(fresh(k)), org, d));

		// row 9: the same desired set again
		List<Map<String, Object>> again = groupsAnswered(gqlSetGroups(org.getUuid(), k.getUuid(), g2.getUuid(), g3.getUuid()));
		assertEquals(byName(g2, g3), uuidsOf(again));
		assertEquals(n1 + 1, groupAudits(g1), "a no-op writes nothing");
		assertEquals(n2 + 1, groupAudits(g2), "a no-op writes nothing");
		assertEquals(n3 + 1, groupAudits(g3), "a no-op writes nothing");
	}

	// ---------- row 10: FREEFORM only, and a deleted key ----------

	@Test
	public void onlyFreeformKeysJoinAndOnlyFreeformAndUserKeysAreAnswered() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UserData admin = member(org, PermissionType.ADMIN);
		ApiKey k = freeform(org, "k");
		ApiKeyGroupData g1 = group(org, Set.of());
		ApiKey u = userKey(org, member(org, PermissionType.READ_WRITE));
		ApiKey instance = apiKeyService.createObjectApiKey(UUID.randomUUID(), ApiTypeEnum.INSTANCE, org.getUuid(), null, "instance", WU);
		ApiKey component = apiKeyService.createObjectApiKey(UUID.randomUUID(), ApiTypeEnum.COMPONENT, org.getUuid(), null, "component", WU);
		int ng = groupAudits(g1);
		signInAs(admin);

		assertRefused(gqlSetGroups(org.getUuid(), u.getUuid(), g1.getUuid()), SET_GROUPS_FIELD,
				"Only FREEFORM keys can join an API key group: " + u.getUuid());
		assertRefused(gqlSetGroups(org.getUuid(), instance.getUuid(), g1.getUuid()), SET_GROUPS_FIELD,
				"Only FREEFORM keys can join an API key group: " + instance.getUuid());
		assertRefused(gqlCombined(org.getUuid(), instance.getUuid()), COMBINED_FIELD,
				"Only FREEFORM and USER keys carry a permission set: " + instance.getUuid());
		assertRefused(gqlCombined(org.getUuid(), component.getUuid()), COMBINED_FIELD,
				"Only FREEFORM and USER keys carry a permission set: " + component.getUuid());
		signInAs(member(org, PermissionType.READ_WRITE));
		assertRefused(gqlCombined(org.getUuid(), instance.getUuid()), COMBINED_FIELD, NOT_AUTHORIZED);
		signInAs(admin);
		combined(gqlCombined(org.getUuid(), u.getUuid()));   // USER is in the allowlist
		assertTrue(stored(g1).getApiKeys().isEmpty());
		assertEquals(ng, groupAudits(g1));

		apiKeyService.deleteApiKey(k.getUuid(), WU);
		assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid()), SET_GROUPS_FIELD, NOT_AUTHORIZED);
		assertTrue(stored(g1).getApiKeys().isEmpty());
		assertEquals(ng, groupAudits(g1));
		assertRefused(gqlCombined(org.getUuid(), k.getUuid()), COMBINED_FIELD, NOT_AUTHORIZED);
		assertTrue(stored(g1).getApiKeys().isEmpty());
		assertEquals(ng, groupAudits(g1));
	}

	// ---------- row 11: group uuids validated whole, uniformly ----------

	@Test
	public void anUnknownOrForeignGroupRefusesTheWholeList() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org.getUuid().equals(org2.getUuid()), "two organizations (CE has one)");
		ApiKey k = freeform(org, "k");
		ApiKeyGroupData g1 = group(org, Set.of(k.getUuid()));
		ApiKeyGroupData g2 = group(org, Set.of());
		ApiKeyGroupData gOrg2 = group(org2, Set.of());
		int nG1 = groupAudits(g1), nG2 = groupAudits(g2), nOrg2 = groupAudits(gOrg2);
		signInAs(member(org, PermissionType.ADMIN));

		UUID random = UUID.randomUUID();
		assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid(), random), SET_GROUPS_FIELD,
				"API key group not found: " + random);
		assertTrue(stored(g1).hasApiKey(k.getUuid()));
		assertEquals(List.of(g1.getUuid()), holdingK(org, k));
		assertEquals(nG1, groupAudits(g1));

		assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid(), gOrg2.getUuid()), SET_GROUPS_FIELD,
				"API key group not found: " + gOrg2.getUuid());
		assertTrue(stored(gOrg2).getApiKeys().isEmpty());
		assertEquals(nOrg2, groupAudits(gOrg2));
		assertTrue(stored(g1).hasApiKey(k.getUuid()));
		assertEquals(nG1, groupAudits(g1));

		UUID another = UUID.randomUUID();
		assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g2.getUuid(), another), SET_GROUPS_FIELD,
				"API key group not found: " + another);
		assertFalse(stored(g2).hasApiKey(k.getUuid()), "the whole list is checked before any lock");
		assertEquals(nG2, groupAudits(g2));
		assertTrue(stored(g1).hasApiKey(k.getUuid()));
		assertEquals(nG1, groupAudits(g1));
	}

	private List<UUID> holdingK(Organization org, ApiKey k) {
		return apiKeyGroupService.getAllApiKeyGroupsByOrganization(org.getUuid()).stream()
				.filter(g -> g.hasApiKey(k.getUuid())).map(ApiKeyGroupData::getUuid).toList();
	}

	// ---------- row 12: the inactive-group rule (the rollback case is in the concurrency class) ----------

	@Test
	public void aKeyIsNeverAddedToAnInactiveGroupButMayStayInOrLeaveOne() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData e = board(org);
		ApiKey k = freeform(org, "k");
		ApiKeyGroupData g1 = group(org, Set.of(k.getUuid()));
		ApiKeyGroupData g4 = group(org, Set.of());
		apiKeyGroupService.deactivateApiKeyGroup(g4.getUuid(), WU);
		int n1 = groupAudits(g1), n4 = groupAudits(g4);
		signInAs(member(org, PermissionType.ADMIN));

		assertRefused(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid(), g4.getUuid()), SET_GROUPS_FIELD,
				"API key group is inactive: " + g4.getUuid());
		assertEquals(List.of(g1.getUuid()), holdingK(org, k));
		assertEquals(n1, groupAudits(g1));
		assertEquals(n4, groupAudits(g4));

		ApiKeyGroupData g5 = group(org, Set.of(k.getUuid()), boardRead(e.getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(g5.getUuid(), WU);
		int n5 = groupAudits(g5);
		List<Map<String, Object>> kept = groupsAnswered(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid(), g5.getUuid()));
		assertTrue(stored(g5).hasApiKey(k.getUuid()), "keeping a key in an inactive group is allowed");
		assertEquals(n5, groupAudits(g5), "and writes nothing");
		assertEquals(byName(g1, g5), uuidsOf(kept), "any status, by name");
		assertEquals("INACTIVE", kept.stream().filter(m -> g5.getUuid().toString().equals(m.get("uuid")))
				.findFirst().orElseThrow().get("status"));

		List<Map<String, Object>> left = groupsAnswered(gqlSetGroups(org.getUuid(), k.getUuid(), g1.getUuid()));
		assertFalse(stored(g5).hasApiKey(k.getUuid()), "removal from an inactive group is allowed");
		assertEquals(n5 + 1, groupAudits(g5), "and audited");
		assertEquals(List.of(g1.getUuid().toString()), uuidsOf(left));
		restoreGroup(g5);
		assertFalse(reads(as(fresh(k)), org, e), "a restore grants nothing to a key that left");
	}

	/** The query answers the key with its group's rows once the mutation added it (the two fields agree). */
	@Test
	public void theReadSeesTheMembershipTheMutationWrote() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		ApiKey k = freeform(org, "k");
		ApiKeyGroupData g = group(org, Set.of(), boardRead(a.getUuid()));
		signInAs(member(org, PermissionType.ADMIN));

		groupsAnswered(gqlSetGroups(org.getUuid(), k.getUuid(), g.getUuid()));
		ExecutionResult r = gqlCombined(org.getUuid(), k.getUuid());
		assertEquals(g.getUuid().toString(), sources(row(r, "BOARD", a.getUuid())).get(0).get("groupUuid"));
		assertSameAsEnforced(k, org, r);
	}
}
