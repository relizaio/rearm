/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * SCORE-71 T-5 (ADR-1, ADR-2 (a), ADR-5; SCORE-69 ADR-10 as merged) against the test database: an
 * identifier change or a dropped SBOM on a component release empties its stored rows and the
 * product's in the writer's transaction, and the next publication or export rebuilds them from the
 * current state. Asserts merge ids (as sets: the merge input order is not fixed) and options only,
 * never aggregate content (F-2): the mocked merge answers a fixed document per release.
 */
public class TeaPublicationStaleRowIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final String RENAMED = "pkg:generic/a-renamed@1.0.0";

	@Autowired private ReleaseRebomService releaseRebomService;

	private static TeaChildPublishResult childOf(TeaPublishResult r, UUID release) {
		return r.children().stream().filter(c -> release.equals(c.release())).findFirst().orElseThrow();
	}

	private List<ReleaseBom> rowsOf(UUID release) {
		return releaseRebomService.getReleaseBoms(stored(release));
	}

	private ReleaseBom rowIn(UUID release, MissingSbomMode mode) {
		List<ReleaseBom> of = rowsOf(release).stream()
				.filter(rb -> rb.rebomMergeOptions().effectiveMissingSbomMode() == mode).toList();
		assertEquals(1, of.size(), rowsOf(release).toString());
		return of.get(0);
	}

	private record Rig(World a, World b, UUID product) {}

	private Rig productOfAAndB() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World a = componentRelease("pkg:generic/a@1.0.0");
		World b = componentRelease("pkg:generic/b@1.0.0");
		UUID product = product(List.of(a.release(), b.release()));
		return new Rig(a, b, product);
	}

	private MergeCall newestMergeOf(UUID release) {
		List<MergeCall> of = mergesOf(release);
		assertFalse(of.isEmpty(), "no merge of " + release);
		return of.get(of.size() - 1);
	}

	private UUID export(UUID release) throws Exception {
		return releaseService.getReleaseBomId(release, false, false, false, null, BomStructureType.FLAT, WU, null);
	}

	private void renameA(Rig rig) throws Exception {
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(rig.a().release()).org(org.getUuid())
				.identifiers(List.of(new RearmIdentifier(RearmIdentifierType.PURL, RENAMED))).build(), WU);
	}

	@Test
	void anIdentifierChangeEmptiesTheReleasesAndTheProductsRowsAndTheNextPublicationRebuildsThemUnderTheNewPurl()
			throws Exception {
		Rig rig = productOfAAndB();
		UUID a = rig.a().release();
		UUID b = rig.b().release();
		UUID product = rig.product();
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(product, TeaPublishMode.PUBLISH).outcome());
		UUID aRow = rowIn(a, MissingSbomMode.OMIT).rebomId();
		UUID bRow = rowIn(b, MissingSbomMode.OMIT).rebomId();
		UUID before = rowIn(product, MissingSbomMode.OMIT).rebomId();
		int aMergesBefore = mergesOf(a).size();
		int productMergesBefore = mergesOf(product).size();

		renameA(rig);

		assertEquals(List.of(), rowsOf(a), "the renamed release's rows are emptied at once");
		assertEquals(List.of(), rowsOf(product), "the product above it loses its rows at once");
		assertEquals(List.of(bRow), rowsOf(b).stream().map(ReleaseBom::rebomId).toList(),
				"a sibling is not above the renamed release");

		publish(product, TeaPublishMode.REPUBLISH);

		List<MergeCall> aNewMerges = mergesOf(a).subList(aMergesBefore, mergesOf(a).size());
		assertFalse(aNewMerges.isEmpty(), "a was rebuilt by the re-publish");
		aNewMerges.forEach(m -> assertEquals(RENAMED, m.options().purl(), m.toString()));
		UUID aNew = aNewMerges.get(0).id();
		MergeCall productMerge = newestMergeOf(product);
		assertEquals(Set.of(aNew, bRow), Set.copyOf(productMerge.ids()), "the product merges a's fresh row and b's stored one");
		assertNotEquals(before, rowIn(product, MissingSbomMode.OMIT).rebomId());
		assertEquals(newestMergeOf(a).id(), rowIn(a, MissingSbomMode.OMIT).rebomId());
		mergesOf(product).subList(productMergesBefore, mergesOf(product).size())
				.forEach(m -> assertFalse(m.ids().contains(aRow), "a product merge after the update names a's stale row"));
	}

	@Test
	void aDroppedSbomEmptiesTheReleasesAndTheProductsRowsAndTheNextPublicationLeavesItOut() throws Exception {
		Rig rig = productOfAAndB();
		UUID a = rig.a().release();
		UUID b = rig.b().release();
		UUID product = rig.product();
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(product, TeaPublishMode.PUBLISH).outcome());
		UUID aRow = rowIn(a, MissingSbomMode.OMIT).rebomId();
		UUID bRow = rowIn(b, MissingSbomMode.OMIT).rebomId();
		int bMergesBefore = mergesOf(b).size();

		// the artifact list emptied, as the UI's artifact list does
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(b).org(org.getUuid()).artifacts(List.of()).build(), WU);

		assertEquals(List.of(), rowsOf(b), "the release that dropped its SBOM loses its rows at once");
		assertEquals(List.of(), rowsOf(product), "the product above it loses its rows at once");
		assertEquals(List.of(aRow), rowsOf(a).stream().map(ReleaseBom::rebomId).toList());

		TeaPublishResult r = publish(product, TeaPublishMode.REPUBLISH);

		MergeCall productMerge = newestMergeOf(product);
		assertFalse(productMerge.ids().contains(bRow), "the stale OMIT row of b is served: " + productMerge);
		assertEquals(List.of(aRow), productMerge.ids());
		assertEquals(bMergesBefore, mergesOf(b).size(), "a component merge with no inputs never calls rebom");
		assertEquals(List.of(), rowsOf(b), "nothing is stored for a release with nothing to merge");
		TeaChildPublishResult refused = childOf(r, b);
		assertEquals(TeaChildOutcome.REFUSED, refused.outcome());
		assertEquals(TeaPublishRefusal.NO_SBOM_ARTIFACTS, refused.refusal());
		assertNotEquals(TeaChildOutcome.REFUSED, childOf(r, a).outcome());
	}

	@Test
	void theNextExportOfAProductWhoseRowsWereEmptiedRebuildsItFromTheNestedReleasesFreshRow() throws Exception {
		Rig rig = productOfAAndB();
		UUID a = rig.a().release();
		UUID b = rig.b().release();
		UUID product = rig.product();

		UUID first = export(product);
		UUID aP = rowIn(a, MissingSbomMode.PLACEHOLDER).rebomId();
		UUID bP = rowIn(b, MissingSbomMode.PLACEHOLDER).rebomId();
		assertEquals(Set.of(aP, bP), Set.copyOf(newestMergeOf(product).ids()));
		assertEquals(first, export(product), "served from the product's row");
		assertEquals(1, mergesOf(product).size());

		renameA(rig);

		UUID second = export(product);
		assertNotEquals(first, second, "the export after the rename is served from the stale row");
		assertEquals(2, mergesOf(product).size());
		MergeCall aNewest = newestMergeOf(a);
		assertEquals(RENAMED, aNewest.options().purl());
		assertEquals(Set.of(aNewest.id(), bP), Set.copyOf(newestMergeOf(product).ids()));
		assertEquals(second, rowIn(product, MissingSbomMode.PLACEHOLDER).rebomId());
		assertTrue(rowsOf(a).stream().anyMatch(rb -> aNewest.id().equals(rb.rebomId())));
	}
}
