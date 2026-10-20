/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.service.UserGroupService;
import io.reliza.ws.saas.PerspectiveDataFetcher;

/**
 * TPF-15: the writers that attach to a perspective named by uuid refuse a deleted (ARCHIVED),
 * foreign or unknown one as "Perspective not found: <uuid>" and write nothing. Membership through
 * setPerspectivesOnComponent (fetcher and service); PERSPECTIVE grants through the real
 * updateUserGroup and setPermissionsOnFreeformApiKey mutations. Case numbers are those of the
 * TPF-15 architecture, section 4.3.
 */
class PerspectiveWritersIntegrationTest extends PerspectiveCreatePathsTestBase {

	private static final String PRODUCT_MESSAGE = "Only real perspectives can be assigned, not product-based ones: ";

	private static final String UPDATE_GROUP = """
			mutation g($in: UpdateUserGroupInput!) {
				updateUserGroup(userGroup: $in) { uuid }
			}""";

	private static final String KEY_PERMISSIONS = """
			mutation perms($key: ID!, $perms: [PermissionInput]) {
				setPermissionsOnFreeformApiKey(apiKeyUuid: $key, permissionType: READ_ONLY, permissions: $perms) { uuid }
			}""";

	@Autowired PerspectiveDataFetcher perspectiveDataFetcher;
	@Autowired UserGroupService userGroupService;
	@Autowired DgsQueryExecutor dgsQueryExecutor;

	// ---------- fixtures ----------

	/** A component of A attached to pA through the TPF-14 user path; its stored set is {pA}. */
	UUID componentOfA(World w) throws Exception {
		UUID c = userCreate(w, w.orgA(), w.pA(), "tpf15-" + tag()).getUuid();
		assertEquals(Set.of(w.pA()), perspectivesOf(c));
		return c;
	}

	UUID productOf(UUID org) throws RelizaException {
		return componentService.createComponent("tpf15-product-" + tag(), org, ComponentType.PRODUCT, "semver",
				"Branch.Micro", null, WU).getUuid();
	}

	Set<UUID> perspectivesOf(UUID component) {
		Set<UUID> s = getComponentService.getComponentData(component).orElseThrow().getPerspectives();
		return null == s ? Set.of() : s;
	}

	void assertRefused(Class<? extends Exception> type, String message, UUID component, List<UUID> perspectives) {
		Exception e = assertThrows(type, () -> perspectiveDataFetcher.setPerspectivesOnComponent(component, perspectives));
		if (null != message) assertEquals(message, e.getMessage());
	}

	UUID groupOf(UUID org) throws RelizaException {
		return userGroupService.createUserGroup(CreateUserGroupDto.builder().name("tpf15-group-" + tag()).org(org).build(), WU)
				.getUuid();
	}

	ExecutionResult grantOnGroup(UUID group, UUID... perspectives) {
		List<Map<String, Object>> perms = new java.util.ArrayList<>();
		for (UUID p : perspectives) {
			perms.add(Map.of("scope", "PERSPECTIVE", "objectId", p.toString(), "type", "READ_ONLY",
					"functions", List.of("RESOURCE")));
		}
		Map<String, Object> in = new HashMap<>();
		in.put("groupId", group.toString());
		in.put("status", "ACTIVE");
		in.put("permissions", perms);
		return dgsQueryExecutor.execute(UPDATE_GROUP, Map.of("in", in));
	}

	Set<UserPermission> perspectiveGrants(UUID group) {
		UserGroupData g = userGroupService.getUserGroupData(group).orElseThrow();
		return g.getOrgPermissions(g.getOrg()).stream().filter(p -> PermissionScope.PERSPECTIVE == p.getScope())
				.collect(java.util.stream.Collectors.toSet());
	}

	static void assertGraphQlRefused(ExecutionResult r, String text) {
		assertFalse(r.getErrors().isEmpty(), "the mutation must be refused");
		assertTrue(r.getErrors().toString().contains(text), r.getErrors().toString());
	}

	ExecutionResult grantOnKey(ApiKey key, UUID perspective) {
		return dgsQueryExecutor.execute(KEY_PERMISSIONS, Map.of("key", key.getUuid().toString(), "perms", List.of(Map.of(
				"scope", "PERSPECTIVE", "object", perspective.toString(), "type", "READ_ONLY",
				"functions", List.of("RESOURCE")))));
	}

	Set<UserPermission> keyPerspectiveGrants(World w) {
		return ApiKeyData.dataFromRecord(apiKeyService.getApiKey(w.freeformA().getUuid()).orElseThrow())
				.getPermissions(w.orgA()).getOrgPermissionsAsSet(w.orgA()).stream()
				.filter(p -> PermissionScope.PERSPECTIVE == p.getScope()).collect(java.util.stream.Collectors.toSet());
	}

	// ---------- membership: setPerspectivesOnComponent ----------

	@Test
	void case08_archived_isNotFoundAndTheSetIsUnchanged() throws Exception {
		World w = world();
		UUID c = componentOfA(w);
		assertRefused(RelizaException.class, NOT_FOUND + w.archivedA(), c, List.of(w.archivedA()));
		assertEquals(Set.of(w.pA()), perspectivesOf(c));
		assertFalse(members(w.archivedA()).contains(c));
	}

	@Test
	void case09_archivedAmongLive_refusesTheWholeCall() throws Exception {
		World w = world();
		UUID c = componentOfA(w);
		assertRefused(RelizaException.class, NOT_FOUND + w.archivedA(), c, List.of(w.pA(), w.archivedA()));
		assertEquals(Set.of(w.pA()), perspectivesOf(c));
	}

	@Test
	void case10_foreign_isNotFoundNotForbidden() throws Exception {
		World w = world();
		UUID c = componentOfA(w);
		assertRefused(RelizaException.class, NOT_FOUND + w.pB(), c, List.of(w.pB()));
		assertEquals(Set.of(w.pA()), perspectivesOf(c));
		assertFalse(members(w.pB()).contains(c));
	}

	@Test
	void case11_consistentForeignPair_isForbiddenByTheAuthorization() throws Exception {
		World w = world();
		UUID cB = componentService.createComponent("tpf15-b-" + tag(), w.orgB(), ComponentType.COMPONENT, "semver",
				"Branch.Micro", null, WU).getUuid();
		componentService.setPerspectives(cB, List.of(w.pB()), WU);
		signInAs(w.adminA());
		assertRefused(AccessDeniedException.class, null, cB, List.of(w.pB()));
		assertEquals(Set.of(w.pB()), perspectivesOf(cB));
	}

	@Test
	void case12_ownProduct_keepsTheProductMessage() throws Exception {
		World w = world();
		UUID c = componentOfA(w);
		UUID productA = productOf(w.orgA());
		assertRefused(RelizaException.class, PRODUCT_MESSAGE + productA, c, List.of(productA));
		assertEquals(Set.of(w.pA()), perspectivesOf(c));
	}

	@Test
	void case13_validSets_areStoredUnchanged() throws Exception {
		World w = world();
		UUID c = componentOfA(w);
		UUID pA2 = perspectiveService.createPerspective(w.orgA(), "tpf15-a2-" + tag(), WU).getUuid();

		perspectiveDataFetcher.setPerspectivesOnComponent(c, List.of(w.pA()));
		assertEquals(Set.of(w.pA()), perspectivesOf(c));

		perspectiveDataFetcher.setPerspectivesOnComponent(c, List.of(w.pA(), pA2));
		assertEquals(Set.of(w.pA(), pA2), perspectivesOf(c));
		assertTrue(members(pA2).contains(c));

		perspectiveDataFetcher.setPerspectivesOnComponent(c, List.of());
		assertEquals(Set.of(), perspectivesOf(c));
	}

	@Test
	void case14_theServiceRefusesOnItsOwn() throws Exception {
		World w = world();
		UUID c = componentOfA(w);
		for (UUID bad : List.of(w.archivedA(), w.pB())) {
			RelizaException e = assertThrows(RelizaException.class,
					() -> perspectiveService.setPerspectivesOnComponent(c, List.of(bad), WU));
			assertEquals(NOT_FOUND + bad, e.getMessage());
			assertEquals(Set.of(w.pA()), perspectivesOf(c));
		}
	}

	// ---------- grants: updateUserGroup ----------

	@Test
	void case15_groupGrantOnArchived_isRefusedAndTheGroupUnchanged() throws Exception {
		World w = world();
		UUID g = groupOf(w.orgA());
		UserGroupData before = userGroupService.getUserGroupData(g).orElseThrow();
		signInAs(w.adminA());
		assertGraphQlRefused(grantOnGroup(g, w.archivedA()), NOT_FOUND + w.archivedA());
		UserGroupData after = userGroupService.getUserGroupData(g).orElseThrow();
		assertTrue(perspectiveGrants(g).isEmpty());
		assertEquals(before.getName(), after.getName());
		assertEquals(before.getStatus(), after.getStatus());
	}

	@Test
	void case16_groupGrantArchivedNextToLive_storesNeither() throws Exception {
		World w = world();
		UUID g = groupOf(w.orgA());
		signInAs(w.adminA());
		assertGraphQlRefused(grantOnGroup(g, w.pA(), w.archivedA()), NOT_FOUND + w.archivedA());
		assertTrue(perspectiveGrants(g).isEmpty());
	}

	@Test
	void case17_groupGrantOnForeign_isNotFound() throws Exception {
		World w = world();
		UUID g = groupOf(w.orgA());
		signInAs(w.adminA());
		assertGraphQlRefused(grantOnGroup(g, w.pB()), NOT_FOUND + w.pB());
		assertTrue(perspectiveGrants(g).isEmpty());
	}

	@Test
	void case18_groupGrantOnUnknown_isNotFound() throws Exception {
		World w = world();
		UUID g = groupOf(w.orgA());
		UUID unknown = UUID.randomUUID();
		signInAs(w.adminA());
		assertGraphQlRefused(grantOnGroup(g, unknown), NOT_FOUND + unknown);
		assertTrue(perspectiveGrants(g).isEmpty());
	}

	@Test
	void case19_validGroupGrants_areStored() throws Exception {
		World w = world();
		UUID g = groupOf(w.orgA());
		signInAs(w.adminA());
		ExecutionResult ok = grantOnGroup(g, w.pA());
		assertTrue(ok.getErrors().isEmpty(), ok.getErrors().toString());
		UserPermission p = userGroupService.getUserGroupData(g).orElseThrow()
				.getPermission(PermissionScope.PERSPECTIVE, w.pA()).orElseThrow();
		assertEquals(PermissionType.READ_ONLY, p.getType());
		assertEquals(Set.of(PermissionFunction.RESOURCE), p.getFunctions());

		UUID productA = productOf(w.orgA());
		ExecutionResult product = grantOnGroup(g, productA);
		assertTrue(product.getErrors().isEmpty(), "a PERSPECTIVE grant may name a product-derived perspective: "
				+ product.getErrors());
		assertTrue(userGroupService.getUserGroupData(g).orElseThrow()
				.getPermission(PermissionScope.PERSPECTIVE, productA).isPresent());
		assertEquals(UserGroupStatus.ACTIVE, userGroupService.getUserGroupData(g).orElseThrow().getStatus());
	}

	@Test
	void case20_groupOfAnotherOrg_isForbidden() throws Exception {
		World w = world();
		UUID gB = groupOf(w.orgB());
		signInAs(w.adminA());
		ExecutionResult r = grantOnGroup(gB, w.pB());
		assertGraphQlRefused(r, "Not authorized");
		assertFalse(r.getErrors().toString().contains(NOT_FOUND), "refused by the ADMIN check, not the helper: "
				+ r.getErrors());
		assertTrue(perspectiveGrants(gB).isEmpty());
	}

	// ---------- grants: one key writer, the same helper line ----------

	@Test
	void case21_keyGrantOnArchived_isRefusedAndOnLiveStored() throws Exception {
		World w = world();
		signInAs(w.adminA());
		assertGraphQlRefused(grantOnKey(w.freeformA(), w.archivedA()), NOT_FOUND + w.archivedA());
		assertTrue(keyPerspectiveGrants(w).isEmpty());

		ExecutionResult ok = grantOnKey(w.freeformA(), w.pA());
		assertTrue(ok.getErrors().isEmpty(), ok.getErrors().toString());
		assertTrue(keyPerspectiveGrants(w).stream().anyMatch(p -> w.pA().equals(p.getObject())));
	}
}
