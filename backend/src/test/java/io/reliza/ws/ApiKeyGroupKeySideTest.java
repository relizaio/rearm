/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
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
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;

/**
 * combinedApiKeyOrgPermissions (TPF-123 architecture-3 section 5, rows 1 to 7): the rows are the set every check
 * enforces, each naming its sources; inactive groups are not counted; a USER key answers its own set; only an
 * organization administrator reads it, foreign and unknown keys answer alike, and the system state comes first.
 */
public class ApiKeyGroupKeySideTest extends ApiKeyGroupKeySideTestBase {

	private static final String NOT_AUTHORIZED = "Not authorized";

	// ---------- row 1: no groups ----------

	@Test
	public void aKeyInNoGroupAnswersItsOwnRowWithAnOwnSource() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		ApiKey k = freeform(org, "k");
		ownOrgWide(k, PermissionType.READ_ONLY);

		ExecutionResult r = gqlCombined(org.getUuid(), k.getUuid());

		Map<String, Object> answer = combined(r);
		assertEquals(k.getUuid().toString(), answer.get("apiKeyUuid"));
		assertEquals(org.getUuid().toString(), answer.get("org"));
		assertEquals(1, rows(r).size(), rows(r).toString());
		Map<String, Object> only = row(r, "ORGANIZATION", org.getUuid());
		assertEquals("READ_ONLY", only.get("type"));
		assertEquals(List.of(), only.get("functions"));
		List<Map<String, Object>> src = sources(only);
		assertEquals(1, src.size(), src.toString());
		assertEquals("OWN", src.get(0).get("kind"));
		assertNull(src.get(0).get("groupUuid"));
		assertNull(src.get(0).get("groupName"));
		assertEquals("READ_ONLY", src.get(0).get("type"));
		assertSameAsEnforced(k, org, r);
	}

	// ---------- row 2: one ACTIVE group ----------

	@Test
	public void oneActiveGroupAddsItsRowsAsGroupSourcesOrganizationWideFirst() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		AgentBoardData a = board(org);
		ApiKey k = freeform(org, "k");
		ownOrgWide(k, PermissionType.READ_ONLY);
		ApiKeyGroupData g = group(org, Set.of(k.getUuid()), boardRead(a.getUuid()), orgWide(org, PermissionType.READ_WRITE));

		ExecutionResult r = gqlCombined(org.getUuid(), k.getUuid());

		assertEquals(2, rows(r).size(), rows(r).toString());
		assertEquals("ORGANIZATION", rows(r).get(0).get("scope"));
		assertEquals("BOARD", rows(r).get(1).get("scope"));
		Map<String, Object> orgRow = row(r, "ORGANIZATION", org.getUuid());
		assertEquals("READ_WRITE", orgRow.get("type"));
		List<Map<String, Object>> orgSources = sources(orgRow);
		assertEquals(2, orgSources.size(), orgSources.toString());
		assertEquals("OWN", orgSources.get(0).get("kind"), "OWN first");
		assertEquals("READ_ONLY", orgSources.get(0).get("type"));
		assertEquals("GROUP", orgSources.get(1).get("kind"));
		assertEquals(g.getUuid().toString(), orgSources.get(1).get("groupUuid"));
		assertEquals(g.getName(), orgSources.get(1).get("groupName"));
		assertEquals("READ_WRITE", orgSources.get(1).get("type"));
		Map<String, Object> boardRow = row(r, "BOARD", a.getUuid());
		assertEquals("READ_ONLY", boardRow.get("type"));
		assertEquals(List.of("BOARD_READ"), boardRow.get("functions"));
		List<Map<String, Object>> boardSources = sources(boardRow);
		assertEquals(1, boardSources.size(), boardSources.toString());
		assertEquals("GROUP", boardSources.get(0).get("kind"));
		assertEquals(g.getUuid().toString(), boardSources.get(0).get("groupUuid"));
		assertEquals("READ_ONLY", boardSources.get(0).get("type"));
		assertEquals(List.of("BOARD_READ"), boardSources.get(0).get("functions"));

		assertTrue(reads(as(fresh(k)), org, a), "the enforcement side of the same fixture");
		List<ApiKeyGroupData> holding = apiKeyGroupService.activeGroupsHoldingKey(k.getUuid(), org.getUuid());
		assertEquals(1, holding.size());
		assertEquals(g.getUuid(), holding.get(0).getUuid());
		assertSameAsEnforced(k, org, r);
	}

	// ---------- row 3: an INACTIVE group is not counted ----------

	@Test
	public void anInactiveGroupIsNotCountedUntilRestored() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		AgentBoardData b = board(org);
		ApiKey k = freeform(org, "k");
		ownOrgWide(k, PermissionType.READ_ONLY);
		ApiKeyGroupData g2 = group(org, Set.of(k.getUuid()), boardRead(b.getUuid()));
		apiKeyGroupService.deactivateApiKeyGroup(g2.getUuid(), WU);

		ExecutionResult inactive = gqlCombined(org.getUuid(), k.getUuid());
		assertFalse(hasRow(inactive, "BOARD", b.getUuid()), rows(inactive).toString());
		assertTrue(rows(inactive).stream().flatMap(m -> sources(m).stream())
				.noneMatch(s -> g2.getUuid().toString().equals(s.get("groupUuid"))), rows(inactive).toString());
		assertFalse(reads(as(fresh(k)), org, b));
		assertSameAsEnforced(k, org, inactive);

		restoreGroup(g2);
		ExecutionResult restored = gqlCombined(org.getUuid(), k.getUuid());
		List<Map<String, Object>> src = sources(row(restored, "BOARD", b.getUuid()));
		assertEquals(1, src.size(), src.toString());
		assertEquals("GROUP", src.get(0).get("kind"));
		assertEquals(g2.getUuid().toString(), src.get(0).get("groupUuid"));
		assertTrue(reads(as(fresh(k)), org, b));
		assertSameAsEnforced(k, org, restored);
	}

	// ---------- row 4: several groups ----------

	@Test
	public void severalGroupsEachNameWhatTheyContribute() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		AgentBoardData a = board(org);
		ApiKey k4 = freeform(org, "k4");
		ApiKeyGroupData g1 = group(org, Set.of(k4.getUuid()), orgWide(org, PermissionType.READ_ONLY, PermissionFunction.RESOURCE));
		ApiKeyGroupData g2 = group(org, Set.of(k4.getUuid()), orgWide(org, PermissionType.READ_WRITE, PermissionFunction.ARTIFACT_DOWNLOAD));
		ApiKeyGroupData g3 = group(org, Set.of(k4.getUuid()), boardRead(a.getUuid()));

		ExecutionResult r = gqlCombined(org.getUuid(), k4.getUuid());

		assertEquals(2, rows(r).size(), rows(r).toString());
		assertEquals("ORGANIZATION", rows(r).get(0).get("scope"));
		assertEquals("BOARD", rows(r).get(1).get("scope"));
		Map<String, Object> orgRow = row(r, "ORGANIZATION", org.getUuid());
		assertEquals("READ_WRITE", orgRow.get("type"));
		assertEquals(Set.of("RESOURCE", "ARTIFACT_DOWNLOAD"), strings(orgRow.get("functions")));
		Set<List<Object>> orgSources = new HashSet<>();
		for (Map<String, Object> s : sources(orgRow)) {
			assertEquals("GROUP", s.get("kind"), "no OWN entry: the key has no own grants");
			orgSources.add(List.of(s.get("groupUuid"), s.get("groupName"), s.get("type"), strings(s.get("functions"))));
		}
		assertEquals(2, sources(orgRow).size());
		assertEquals(Set.of(
				List.of(g1.getUuid().toString(), g1.getName(), "READ_ONLY", Set.of("RESOURCE")),
				List.of(g2.getUuid().toString(), g2.getName(), "READ_WRITE", Set.of("ARTIFACT_DOWNLOAD"))), orgSources);
		List<Map<String, Object>> boardSources = sources(row(r, "BOARD", a.getUuid()));
		assertEquals(1, boardSources.size(), boardSources.toString());
		assertEquals("GROUP", boardSources.get(0).get("kind"));
		assertEquals(g3.getUuid().toString(), boardSources.get(0).get("groupUuid"));
		assertEquals("READ_ONLY", row(r, "BOARD", a.getUuid()).get("type"));
		assertEquals(3, rows(r).stream().mapToInt(m -> sources(m).size()).sum(), "three groups, three sources");
		assertTrue(rows(r).stream().allMatch(m -> sources(m).size() >= 1));
		assertSameAsEnforced(k4, org, r);
	}

	// ---------- row 5: a USER key answers its own set ----------

	@Test
	public void aUserKeyAnswersItsOwnStoredSetAndNoGroup() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		signInAs(member(org, PermissionType.ADMIN));
		AgentBoardData a = board(org);
		UserData owner = member(org, PermissionType.READ_WRITE);
		ApiKey u = userKey(org, owner);
		ownOrgWide(u, PermissionType.READ_WRITE);
		ApiKeyGroupData g = group(org, Set.of(), boardRead(a.getUuid()));
		plant(g, u.getUuid());

		ExecutionResult r = gqlCombined(org.getUuid(), u.getUuid());

		assertEquals(1, rows(r).size(), rows(r).toString());
		Map<String, Object> only = row(r, "ORGANIZATION", org.getUuid());
		assertEquals("READ_WRITE", only.get("type"));
		assertEquals(1, sources(only).size());
		assertEquals("OWN", sources(only).get(0).get("kind"));
		assertFalse(hasRow(r, "BOARD", a.getUuid()));
		assertTrue(rows(r).stream().flatMap(m -> sources(m).stream()).noneMatch(s -> "GROUP".equals(s.get("kind"))));
		assertSameAsEnforced(u, org, r);
	}

	// ---------- row 6: cross-org and unknown, uniformly ----------

	@Test
	public void foreignAndUnknownKeysAndNonAdminsAnswerNotAuthorized() throws RelizaException {
		Organization org1 = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		Assumptions.assumeTrue(!org1.getUuid().equals(org2.getUuid()), "two organizations (CE has one)");
		ApiKey k1 = freeform(org1, "k1");
		ApiKey k3 = freeform(org2, "k3");
		ApiKeyGroupData g = group(org1, Set.of());
		int ng = groupAudits(g);
		signInAs(member(org1, PermissionType.ADMIN));

		assertRefused(gqlCombined(org1.getUuid(), k3.getUuid()), COMBINED_FIELD, NOT_AUTHORIZED);
		assertRefused(gqlCombined(org1.getUuid(), UUID.randomUUID()), COMBINED_FIELD, NOT_AUTHORIZED);
		assertRefused(gqlCombined(org2.getUuid(), k3.getUuid()), COMBINED_FIELD, NOT_AUTHORIZED);
		assertRefused(gqlSetGroups(org1.getUuid(), k3.getUuid()), SET_GROUPS_FIELD, NOT_AUTHORIZED);
		assertRefused(gqlSetGroups(org1.getUuid(), UUID.randomUUID()), SET_GROUPS_FIELD, NOT_AUTHORIZED);

		signInAs(member(org1, PermissionType.READ_WRITE));
		assertRefused(gqlCombined(org1.getUuid(), k1.getUuid()), COMBINED_FIELD, NOT_AUTHORIZED);
		assertRefused(gqlSetGroups(org1.getUuid(), k1.getUuid(), g.getUuid()), SET_GROUPS_FIELD, NOT_AUTHORIZED);
		assertTrue(stored(g).getApiKeys().isEmpty());
		assertEquals(ng, groupAudits(g));
	}

	// ---------- row 7: system state first ----------

	@Test
	public void aSealedOrUnlicensedSystemAnswersTheStateForEveryKey() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization org2 = testInitializer.obtainOrganization();
		ApiKey k = freeform(org, "k");
		ApiKey k3 = freeform(org2, "k3");
		UUID ghost = UUID.randomUUID();
		signInAs(member(org, PermissionType.ADMIN));

		licenseStatus.setSystemSealed(true);
		try {
			for (String message : sixAnswers(org, k, k3, ghost)) {
				assertTrue(message.contains("System is sealed"), message);
				assertFalse(message.contains(NOT_AUTHORIZED), "the system state is answered before the key is looked up: " + message);
			}
		} finally {
			licenseStatus.setSystemSealed(false);
		}
		licenseStatus.setLicenseValid(false);
		try {
			for (String message : sixAnswers(org, k, k3, ghost)) {
				assertEquals("License is invalid or expired. Please upload a valid license.", message);
			}
		} finally {
			licenseStatus.setLicenseValid(true);
		}
	}

	private List<String> sixAnswers(Organization org, ApiKey k, ApiKey k3, UUID ghost) {
		return List.of(
				onlyError(gqlCombined(org.getUuid(), k.getUuid())),
				onlyError(gqlCombined(org.getUuid(), k3.getUuid())),
				onlyError(gqlCombined(org.getUuid(), ghost)),
				onlyError(gqlSetGroups(org.getUuid(), k.getUuid())),
				onlyError(gqlSetGroups(org.getUuid(), k3.getUuid())),
				onlyError(gqlSetGroups(org.getUuid(), ghost)));
	}
}
