/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;

/**
 * TPF-14: every path that creates a component inside a perspective refuses a deleted (ARCHIVED),
 * foreign or unknown perspective with one answer, "Perspective not found: uuid", before
 * authorization and before anything is written; a product of the caller's own organization keeps
 * its distinct refusal; a live perspective of the caller's organization still works, on the legacy
 * ORGANIZATION_RW branch as well.
 */
public class PerspectiveCreatePathsIntegrationTest extends PerspectiveCreatePathsTestBase {

	private static void assertNotFound(Exception e, UUID perspective) {
		assertEquals(RelizaException.class, e.getClass(), String.valueOf(e));
		assertEquals(NOT_FOUND + perspective, e.getMessage());
	}

	// ---------- archived perspective, each path ----------

	@Test
	public void userCreate_archivedPerspectiveIsRefusedAsNotFound() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> userCreate(w, w.orgA(), w.archivedA(), "tpf14-" + tag()));
		assertNotFound(e, w.archivedA());
		assertNothingWritten(w, before);
	}

	@Test
	public void progCreate_archivedPerspectiveIsRefusedAsNotFound() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> progCreate(
				as(w.freeformA(), "component", componentInput("tpf14-" + tag(), null, null)), w.archivedA()));
		assertNotFound(e, w.archivedA());
		assertNothingWritten(w, before);
	}

	@Test
	public void getNewVersion_archivedPerspectiveIsRefusedAsNotFound() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> getNewVersion(
				as(w.freeformA(), "newVersionInput", newVersionInput(vcsUri(), w.archivedA()))));
		assertNotFound(e, w.archivedA());
		assertNothingWritten(w, before);
	}

	@Test
	public void addRelease_archivedPerspectiveIsRefusedAsNotFound() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> addRelease(
				as(w.freeformA(), "release", releaseInput(vcsUri(), w.archivedA()))));
		assertNotFound(e, w.archivedA());
		assertNothingWritten(w, before);
	}

	// ---------- foreign perspective with an org key ----------

	@Test
	public void progCreate_foreignPerspectiveIsRefusedAsNotFoundWithOrgKey() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> progCreate(
				as(w.freeformA(), "component", componentInput("tpf14-" + tag(), null, null)), w.pB()));
		assertNotFound(e, w.pB());
		assertNothingWritten(w, before);
	}

	@Test
	public void getNewVersion_foreignPerspectiveIsRefusedAsNotFoundWithOrgKey() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> getNewVersion(
				as(w.freeformA(), "newVersionInput", newVersionInput(vcsUri(), w.pB()))));
		assertNotFound(e, w.pB());
		assertNothingWritten(w, before);
	}

	@Test
	public void addRelease_foreignPerspectiveIsRefusedAsNotFoundWithOrgKey() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> addRelease(
				as(w.freeformA(), "release", releaseInput(vcsUri(), w.pB()))));
		assertNotFound(e, w.pB());
		assertNothingWritten(w, before);
	}

	/** The legacy ORGANIZATION_RW branch, which on the base attached B's perspective to A's new component. */
	@Test
	public void getNewVersion_foreignPerspectiveIsRefusedAsNotFoundWithLegacyOrgKey() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> getNewVersion(
				basicOrg(w, "newVersionInput", newVersionInput(vcsUri(), w.pB()))));
		assertNotFound(e, w.pB());
		assertNothingWritten(w, before);
	}

	@Test
	public void addRelease_foreignPerspectiveIsRefusedAsNotFoundWithLegacyOrgKey() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> addRelease(
				basicOrg(w, "release", releaseInput(vcsUri(), w.pB()))));
		assertNotFound(e, w.pB());
		assertNothingWritten(w, before);
	}

	// ---------- foreign perspective with a user ----------

	@Test
	public void userCreate_foreignPerspectiveIsRefusedAsNotFoundWithUser() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> userCreate(w, w.orgA(), w.pB(), "tpf14-" + tag()));
		assertNotFound(e, w.pB());
		assertNothingWritten(w, before);
	}

	/** A consistent pair of B's passes the validation; authorization still refuses A's admin. Unchanged. */
	@Test
	public void userCreate_foreignOrganizationWithItsOwnPerspectiveIsForbidden() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		assertThrows(AccessDeniedException.class, () -> userCreate(w, w.orgB(), w.pB(), "tpf14-" + tag()));
		assertNothingWritten(w, before);
	}

	@Test
	public void userCreate_foreignOrganizationWithOwnPerspectiveIsRefusedAsNotFound() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> userCreate(w, w.orgB(), w.pA(), "tpf14-" + tag()));
		assertNotFound(e, w.pA());
		assertNothingWritten(w, before);
	}

	// ---------- products ----------

	@Test
	public void progCreate_foreignProductIsRefusedAsNotFound() throws Exception {
		World w = world();
		UUID productB = componentService.createComponent("tpf14-product-" + tag(), w.orgB(), ComponentType.PRODUCT,
				"semver", "Branch.Micro", null, WU).getUuid();
		Snapshot before = snapshot(w);
		Exception e = assertThrows(Exception.class, () -> progCreate(
				as(w.freeformA(), "component", componentInput("tpf14-" + tag(), null, null)), productB));
		assertNotFound(e, productB);
		assertNothingWritten(w, before);
	}

	@Test
	public void userCreate_ownProductIsRefusedWithTheProductMessage() throws Exception {
		World w = world();
		UUID productA = componentService.createComponent("tpf14-product-" + tag(), w.orgA(), ComponentType.PRODUCT,
				"semver", "Branch.Micro", null, WU).getUuid();
		Snapshot before = snapshot(w);
		RelizaException e = assertThrows(RelizaException.class,
				() -> userCreate(w, w.orgA(), productA, "tpf14-" + tag()));
		assertEquals("Only real perspectives can be assigned, not product-based ones: " + productA, e.getMessage());
		assertNothingWritten(w, before);
	}

	// ---------- a live perspective of the caller's organization still works ----------

	@Test
	public void userCreate_activePerspectiveStillWorks() throws Exception {
		World w = world();
		ComponentData cd = userCreate(w, w.orgA(), w.pA(), "tpf14-" + tag());
		assertEquals(w.orgA(), cd.getOrg());
		assertEquals(Set.of(w.pA()), cd.getPerspectives());
	}

	@Test
	public void progCreate_activePerspectiveStillWorks() throws Exception {
		World w = world();
		ComponentData cd = progCreate(as(w.freeformA(), "component", componentInput("tpf14-" + tag(), null, null)),
				w.pA());
		assertEquals(w.orgA(), cd.getOrg());
		assertEquals(Set.of(w.pA()), cd.getPerspectives());
	}

	@Test
	public void getNewVersion_activePerspectiveStillWorks() throws Exception {
		World w = world();
		Set<UUID> before = components(w.orgA());
		getNewVersion(as(w.freeformA(), "newVersionInput", newVersionInput(vcsUri(), w.pA())));
		ComponentData viaFreeform = createdSince(w.orgA(), before);
		assertEquals(Set.of(w.pA()), viaFreeform.getPerspectives());

		before = components(w.orgA());
		getNewVersion(basicOrg(w, "newVersionInput", newVersionInput(vcsUri(), w.pA())));
		ComponentData viaLegacy = createdSince(w.orgA(), before);
		assertEquals(Set.of(w.pA()), viaLegacy.getPerspectives());
		assertEquals(Set.of(viaFreeform.getUuid(), viaLegacy.getUuid()), members(w.pA()));
	}

	@Test
	public void addRelease_activePerspectiveStillWorks() throws Exception {
		World w = world();
		Set<UUID> before = components(w.orgA());
		addRelease(as(w.freeformA(), "release", releaseInput(vcsUri(), w.pA())));
		ComponentData viaFreeform = createdSince(w.orgA(), before);
		assertEquals(Set.of(w.pA()), viaFreeform.getPerspectives());

		before = components(w.orgA());
		addRelease(basicOrg(w, "release", releaseInput(vcsUri(), w.pA())));
		ComponentData viaLegacy = createdSince(w.orgA(), before);
		assertEquals(Set.of(w.pA()), viaLegacy.getPerspectives());
		assertEquals(Set.of(viaFreeform.getUuid(), viaLegacy.getUuid()), members(w.pA()));
	}
}
