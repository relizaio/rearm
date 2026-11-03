/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Audit;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;

/**
 * Helpers of the key-side API key group tests (TPF-123 architecture-3 section 5): the combinedApiKeyOrgPermissions
 * read and the setApiKeyGroupsOfKey mutation. Split by topic over this base (the 500-line rule): the read and its
 * gates, the membership mutation, and the row locks and rollback.
 */
abstract class ApiKeyGroupKeySideTestBase extends ApiKeyGroupTestBase {

	static final String COMBINED_FIELD = "combinedApiKeyOrgPermissions";
	static final String SET_GROUPS_FIELD = "setApiKeyGroupsOfKey";

	/** ApiKeyGroupAuthorizationTest's reads: the enforcement side, BOARD_READ on the board through the key. */
	boolean reads(AuthHeaderParse ahp, Organization org, AgentBoardData bd) throws RelizaException {
		return authorizationService.boardPermission(ahp, org.getUuid(), bd.getUuid(), PermissionFunction.BOARD_READ, CallType.READ);
	}

	/** The key as stored now, so a principal carries its current grants. */
	ApiKey fresh(ApiKey k) {
		return apiKeyService.getApiKey(k.getUuid()).orElseThrow();
	}

	List<Audit> audit(UUID entity, TableName tn) {
		return auditService.getAuditForEntity(entity, tn, 1000, 0);
	}

	int groupAudits(ApiKeyGroupData g) {
		return audit(g.getUuid(), TableName.API_KEY_GROUPS).size();
	}

	int keyAudits(ApiKey k) {
		return audit(k.getUuid(), TableName.API_KEYS).size();
	}

	/** ApiKeyGroupMembershipAndFetcherCoverageTest's actor check on the stored group row. */
	void assertActor(UUID group, UserData admin, String what) {
		ApiKeyGroup row = apiKeyGroupRepository.findById(group).orElseThrow();
		Map<String, Object> rd = row.getRecordData();
		assertEquals(admin.getUuid().toString(), String.valueOf(rd.get("lastUpdatedBy")), what + " names the administrator");
		assertNotNull(rd.get("createdType"));
		assertNotEquals("TEST", String.valueOf(rd.get("createdType")), what + " is not recorded as the test actor");
	}

	void ownOrgWide(ApiKey k, PermissionType type) throws RelizaException {
		apiKeyService.setPermissionsOnApiKey(k.getUuid(), type, List.of(), WU);
	}

	// ---------- reading the answers ----------

	@SuppressWarnings("unchecked")
	static Map<String, Object> combined(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		return (Map<String, Object>) data.get(COMBINED_FIELD);
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> rows(ExecutionResult r) {
		return (List<Map<String, Object>>) combined(r).get("permissions");
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> sources(Map<String, Object> row) {
		return (List<Map<String, Object>>) row.get("sources");
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> groupsAnswered(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> data = r.getData();
		return (List<Map<String, Object>>) data.get(SET_GROUPS_FIELD);
	}

	/** The one row of this scope and object, which must exist. */
	static Map<String, Object> row(ExecutionResult r, String scope, UUID object) {
		List<Map<String, Object>> found = rows(r).stream()
				.filter(m -> scope.equals(m.get("scope")) && object.toString().equals(m.get("object"))).toList();
		assertEquals(1, found.size(), scope + " " + object + " in " + rows(r));
		return found.get(0);
	}

	static boolean hasRow(ExecutionResult r, String scope, UUID object) {
		return rows(r).stream().anyMatch(m -> scope.equals(m.get("scope")) && object.toString().equals(m.get("object")));
	}

	@SuppressWarnings("unchecked")
	static Set<String> strings(Object list) {
		return null == list ? Set.of() : new HashSet<>((List<String>) list);
	}

	/** A refused call: exactly one error with this text, and null for the field. */
	static void assertRefused(ExecutionResult r, String field, String message) {
		assertEquals(message, onlyError(r));
		Map<String, Object> data = r.getData();
		assertNotNull(data, "the field answers null, not a missing data object");
		assertNull(data.get(field), String.valueOf(data));
	}

	/** The query's rows are exactly the set enforcement computes for the key (the display IS the enforcement set). */
	void assertSameAsEnforced(ApiKey k, Organization org, ExecutionResult r) {
		ApiKey stored = apiKeyService.getApiKey(k.getUuid()).orElseThrow();
		Set<UserPermission> enforced = authorizationService.effectiveStoredKeyPermissions(stored.getUuid(),
				stored.getObjectType(), ApiKeyData.dataFromRecord(stored), org.getUuid());
		Set<List<Object>> expected = enforced.stream()
				.map(p -> List.<Object>of(p.getScope().name(), String.valueOf(p.getObject()), p.getType().name(),
						p.getFunctions().stream().map(Enum::name).collect(Collectors.toSet())))
				.collect(Collectors.toSet());
		Set<List<Object>> answered = rows(r).stream()
				.map(m -> List.<Object>of(m.get("scope"), String.valueOf(m.get("object")), m.get("type"), strings(m.get("functions"))))
				.collect(Collectors.toSet());
		assertEquals(expected, answered, "the display is the enforcement set");
		assertEquals(enforced.size(), rows(r).size(), "one row per enforced entry");
	}
}
