/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.service.TeaProfileService;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.AuditRepository;
import io.reliza.ws.saas.PerspectiveDataFetcher;

/**
 * TPF-40: addPerspectiveToComponent adds one perspective to a component and keeps its others, with
 * the authorization and the refusals of setPerspectivesOnComponent (TPF-15's not-found answers before
 * the ADMIN-on-component authorization). Rows 1 to 11 of the TPF-40 architecture, section 4.1; the
 * race rows 12 to 14 are in io.reliza.service.PerspectiveAddConcurrencyIntegrationTest.
 */
class PerspectiveAddIntegrationTest extends PerspectiveCreatePathsTestBase {

	private static final String PRODUCT_MESSAGE = "Only real perspectives can be assigned, not product-based ones: ";

	private static final String ADD = """
			mutation add($c: ID!, $p: ID!) {
				addPerspectiveToComponent(componentUuid: $c, perspectiveUuid: $p) { uuid perspectiveDetails { uuid } }
			}""";

	@Autowired PerspectiveDataFetcher perspectiveDataFetcher;
	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired AuditRepository auditRepository;

	// ---------- fixtures ----------

	/**
	 * The world, a component c of A whose stored set is {pA} (created by adminA, who stays signed in),
	 * a second live perspective pA2 and a product of A, a READ_WRITE member of A, and a component cB of
	 * B in pB with a second live perspective pB2 of B.
	 */
	record Fixture(World w, UUID c, UUID pA2, UUID productA, UserData member, UUID cB, UUID pB2) {}

	Fixture fixture() throws Exception {
		World w = world();
		UUID c = userCreate(w, w.orgA(), w.pA(), "tpf40-" + tag()).getUuid();
		assertEquals(Set.of(w.pA()), set(c));
		UUID pA2 = perspectiveService.createPerspective(w.orgA(), "tpf40-a2-" + tag(), WU).getUuid();
		UUID productA = componentService.createComponent("tpf40-product-" + tag(), w.orgA(), ComponentType.PRODUCT,
				"semver", "Branch.Micro", null, WU).getUuid();
		String t = tag();
		User u = userService.createUser("TPF-40 " + t, "tpf40-" + t + "@perspective.io", true, List.of(w.orgA()),
				"tpf40-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), w.orgA(), PermissionScope.ORGANIZATION, w.orgA(),
				PermissionType.READ_WRITE, List.of(), null, WU);
		UserData member = userService.getUserData(u.getUuid()).orElseThrow();
		UUID cB = componentService.createComponent("tpf40-b-" + tag(), w.orgB(), ComponentType.COMPONENT, "semver",
				"Branch.Micro", null, WU).getUuid();
		componentService.setPerspectives(cB, List.of(w.pB()), WU);
		UUID pB2 = perspectiveService.createPerspective(w.orgB(), "tpf40-b2-" + tag(), WU).getUuid();
		return new Fixture(w, c, pA2, productA, member, cB, pB2);
	}

	ComponentData add(UUID component, UUID perspective) throws RelizaException {
		return perspectiveDataFetcher.addPerspectiveToComponent(component, perspective);
	}

	Set<UUID> set(UUID component) {
		Set<UUID> s = getComponentService.getComponentData(component).orElseThrow().getPerspectives();
		return null == s ? Set.of() : s;
	}

	static Set<UUID> perspectivesOf(ComponentData answer) {
		return null == answer.getPerspectives() ? Set.of() : answer.getPerspectives();
	}

	int revision(UUID component) {
		return getComponentService.getComponent(component).orElseThrow().getRevision();
	}

	int audits(UUID component) {
		return auditRepository.retrieveAuditRecordsForEntity(TableName.COMPONENTS.toString(), component.toString(),
				"1000", "0").size();
	}

	/** What "nothing written" compares: the stored set, the revision and the audit row count. */
	record Written(Set<UUID> set, int revision, int audits) {}

	Written written(UUID component) {
		return new Written(Set.copyOf(set(component)), revision(component), audits(component));
	}

	void assertRefused(Class<? extends Exception> type, String message, UUID component, UUID perspective) {
		Written before = written(component);
		Exception e = assertThrows(type, () -> add(component, perspective));
		if (null != message) assertEquals(message, e.getMessage());
		assertEquals(before, written(component), "a refused add wrote to the component");
	}

	// ---------- rows ----------

	@Test
	void row01_addKeepsTheOtherPerspective() throws Exception {
		Fixture f = fixture();
		Written before = written(f.c());
		ComponentData answer = add(f.c(), f.pA2());
		assertEquals(Set.of(f.w().pA(), f.pA2()), perspectivesOf(answer));
		assertEquals(Set.of(f.w().pA(), f.pA2()), set(f.c()));
		assertTrue(members(f.w().pA()).contains(f.c()));
		assertTrue(members(f.pA2()).contains(f.c()));
		assertEquals(before.revision() + 1, revision(f.c()));
		assertEquals(before.audits() + 1, audits(f.c()));
	}

	@Test
	void row02_addOfAStoredPerspective_writesNothing() throws Exception {
		Fixture f = fixture();
		Written before = written(f.c());
		ComponentData answer = add(f.c(), f.w().pA());
		assertEquals(Set.of(f.w().pA()), perspectivesOf(answer));
		assertEquals(before, written(f.c()));
	}

	@Test
	void row03_archived_isNotFound() throws Exception {
		Fixture f = fixture();
		assertRefused(RelizaException.class, NOT_FOUND + f.w().archivedA(), f.c(), f.w().archivedA());
		assertFalse(members(f.w().archivedA()).contains(f.c()));
	}

	@Test
	void row04_foreign_isNotFound() throws Exception {
		Fixture f = fixture();
		assertRefused(RelizaException.class, NOT_FOUND + f.w().pB(), f.c(), f.w().pB());
		assertFalse(members(f.w().pB()).contains(f.c()));
	}

	@Test
	void row05_unknown_isNotFound() throws Exception {
		Fixture f = fixture();
		UUID u = UUID.randomUUID();
		assertRefused(RelizaException.class, NOT_FOUND + u, f.c(), u);
	}

	@Test
	void row06_ownProduct_keepsTheProductMessage() throws Exception {
		Fixture f = fixture();
		assertRefused(RelizaException.class, PRODUCT_MESSAGE + f.productA(), f.c(), f.productA());
	}

	@Test
	void row07_withoutAdminOnTheComponent_isForbidden_evenForANoOp() throws Exception {
		Fixture f = fixture();
		signInAs(f.member());
		assertRefused(AccessDeniedException.class, null, f.c(), f.pA2());
		assertRefused(AccessDeniedException.class, null, f.c(), f.w().pA());
		assertFalse(members(f.pA2()).contains(f.c()));
	}

	@Test
	void row08_consistentForeignPair_isForbiddenByTheAuthorization() throws Exception {
		Fixture f = fixture();
		signInAs(f.w().adminA());
		assertRefused(AccessDeniedException.class, null, f.cB(), f.pB2());
		assertEquals(Set.of(f.w().pB()), set(f.cB()));
	}

	@Test
	void row09_notFoundAnswersComeBeforeTheAuthorization() throws Exception {
		Fixture f = fixture();
		signInAs(f.member());
		assertRefused(RelizaException.class, NOT_FOUND + f.w().archivedA(), f.c(), f.w().archivedA());
		assertRefused(RelizaException.class, NOT_FOUND + f.w().pB(), f.c(), f.w().pB());
		UUID v = UUID.randomUUID();
		RelizaException e = assertThrows(RelizaException.class, () -> add(v, f.pA2()));
		assertEquals("Component not found", e.getMessage());
		assertFalse(members(f.pA2()).contains(f.c()));
	}

	@Test
	void row10_theServiceRefusesOnItsOwn() throws Exception {
		Fixture f = fixture();
		SecurityContextHolder.clearContext(); // nobody signed in: the service alone
		for (UUID bad : List.of(f.w().archivedA(), f.w().pB())) {
			Written before = written(f.c());
			RelizaException e = assertThrows(RelizaException.class,
					() -> perspectiveService.addPerspectiveToComponent(f.c(), bad, TeaProfileService.TeaCaller.ORG_ADMIN, WU));
			assertEquals(NOT_FOUND + bad, e.getMessage());
			assertEquals(before, written(f.c()));
		}
		UUID v = UUID.randomUUID();
		Written before = written(f.c());
		RelizaException e = assertThrows(RelizaException.class,
				() -> perspectiveService.addPerspectiveToComponent(v, f.w().pA(), TeaProfileService.TeaCaller.ORG_ADMIN, WU));
		assertEquals("Component not found: " + v, e.getMessage());
		assertEquals(before, written(f.c()));
	}

	@Test
	void row11_theSchemaServesTheField() throws Exception {
		Fixture f = fixture();
		ExecutionResult first = dgsQueryExecutor.execute(ADD, Map.of("c", f.c().toString(), "p", f.pA2().toString()));
		assertTrue(first.getErrors().isEmpty(), first.getErrors().toString());
		Map<String, Object> data = first.getData();
		@SuppressWarnings("unchecked")
		Map<String, Object> added = (Map<String, Object>) data.get("addPerspectiveToComponent");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> details = (List<Map<String, Object>>) added.get("perspectiveDetails");
		assertEquals(Set.of(f.w().pA().toString(), f.pA2().toString()),
				details.stream().map(d -> (String) d.get("uuid")).collect(Collectors.toSet()));

		ExecutionResult second = dgsQueryExecutor.execute(ADD,
				Map.of("c", f.c().toString(), "p", f.w().archivedA().toString()));
		assertFalse(second.getErrors().isEmpty(), "an add of an archived perspective must be refused");
		assertTrue(second.getErrors().toString().contains(NOT_FOUND + f.w().archivedA()), second.getErrors().toString());
		assertEquals(Set.of(f.w().pA(), f.pA2()), set(f.c()));
	}
}
