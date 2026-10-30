/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.VcsType;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-71 T-11 (ADR-2 (a), (b), (c), ADR-9; F-1, F-5, F-6, F-25) against the test database: each
 * trigger arm, driven where it lives, empties the stored rows of the changed release and of the
 * products above it in the writer's transaction, and the fields no merge reads leave them alone.
 * Every case reads the rows at once: nothing runs after the commit.
 */
public class MergedSbomInvalidationTriggerIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final RebomOptions ROW = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, BomStructureType.FLAT)
			.withMergeVersion(ReleaseService.MERGE_VERSION);

	@Autowired private ReleaseRebomService releaseRebomService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private VcsRepositoryService vcsRepositoryService;

	private void row(UUID release) {
		releaseRebomService.setReboms(stored(release), List.of(new ReleaseBom(UUID.randomUUID(), ROW)), WU);
	}

	private List<ReleaseBom> rowsOf(UUID release) {
		return releaseRebomService.getReleaseBoms(stored(release));
	}

	private void assertEmptied(UUID... releases) {
		for (UUID r : releases) assertEquals(List.of(), rowsOf(r), "rows of " + r + " kept");
	}

	private void assertKept(UUID... releases) {
		for (UUID r : releases) assertEquals(1, rowsOf(r).size(), "rows of " + r + " emptied");
	}

	private ReleaseDto.ReleaseDtoBuilder update(UUID release) {
		return ReleaseDto.builder().uuid(release).org(org.getUuid());
	}

	private static RearmIdentifier purl(String value) {
		return new RearmIdentifier(RearmIdentifierType.PURL, value);
	}

	/** A DRAFT component release of a new component: the rig's createRelease shape, not moved on. */
	private UUID draftRelease() throws Exception {
		String slug = "score-71-" + UUID.randomUUID().toString().substring(0, 8);
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid()).name(slug).type(ComponentType.COMPONENT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(component, "main", true, WU).get();
		String version = "1.0." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component)
				.branch(branch.getUuid()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version).build(), WU).getUuid();
		releasesByVersion.put(version, release);
		return release;
	}

	/** A release of a new component on its base branch, created as {@code dto} adds to the defaults. */
	private ReleaseDto.ReleaseDtoBuilder onNewComponent(ComponentType type) throws Exception {
		String slug = "score-71-" + UUID.randomUUID().toString().substring(0, 8);
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid()).name(slug).type(type)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.getBaseBranchOfComponent(component).orElseThrow();
		return ReleaseDto.builder().component(component).branch(branch.getUuid()).org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.version("1.0." + Math.abs(UUID.randomUUID().hashCode() % 100000));
	}

	// (a)
	@Test
	void aPreferredPurlChangeEmptiesTheReleaseAndTheProductAbove() throws Exception {
		UUID r = componentRelease("pkg:generic/acme/x@1.0.0").release();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).identifiers(List.of(purl("pkg:generic/acme/p@1.0.0"))).build(), WU);
		assertEmptied(r, p);
	}

	// (b)
	@Test
	void aCpeOnlyChangeKeepsTheRows() throws Exception {
		UUID r = componentRelease("pkg:generic/acme/x@1.0.0").release();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).identifiers(List.of(new RearmIdentifier(RearmIdentifierType.CPE,
				"cpe:2.3:a:acme:x:1.0.0:*:*:*:*:*:*:*"))).build(), WU);
		assertKept(r, p);
	}

	// (c)
	@Test
	void theSamePurlsReorderedKeepTheRows() throws Exception {
		RearmIdentifier one = purl("pkg:generic/acme/one@1.0.0");
		RearmIdentifier two = purl("pkg:generic/acme/two@1.0.0");
		UUID r = ossReleaseService.createRelease(onNewComponent(ComponentType.COMPONENT)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).identifiers(List.of(one, two)).build(), WU).getUuid();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).identifiers(List.of(two, one)).build(), WU);
		assertKept(r, p);
	}

	// (d)
	@Test
	void anArtifactAttachedThroughTheListEmptiesTheRows() throws Exception {
		UUID r = componentRelease(null).release();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).artifacts(List.of(seedBom("t11d.cdx.json"))).build(), WU);
		assertEmptied(r, p);
	}

	// (e)
	@Test
	void theListEmptiedEmptiesTheRows() throws Exception {
		UUID r = componentRelease("pkg:generic/acme/e@1.0.0").release();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).artifacts(List.of()).build(), WU);
		assertEmptied(r, p);
	}

	// (f)
	@Test
	void anInboundDeliverableChangeOnADraftReleaseEmptiesTheRows() throws Exception {
		UUID r = draftRelease();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).inboundDeliverables(List.of(UUID.randomUUID())).build(), WU);
		assertEmptied(r, p);
	}

	// (g)
	@Test
	void aSourceCodeEntryChangeOnADraftReleaseEmptiesTheRows() throws Exception {
		UUID r = draftRelease();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ReleaseData rd = stored(r);
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/example/score-71-" + UUID.randomUUID(), VcsType.GIT, WU);
		UUID sce = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(rd.getBranch()).vcs(vcs)
				.commit("7171717171717171717171717171717171717171").organizationUuid(org.getUuid()).build(), WU).getUuid();
		ossReleaseService.updateRelease(update(r).sourceCodeEntry(sce).build(), WU);
		assertEmptied(r, p);
	}

	// (h)
	@Test
	void aDraftProductsParentReleaseChangeEmptiesItsRows() throws Exception {
		UUID child = componentRelease("pkg:generic/acme/h1@1.0.0").release();
		UUID q = ossReleaseService.createRelease(onNewComponent(ComponentType.PRODUCT).lifecycle(ReleaseLifecycle.DRAFT)
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(child))).build(), WU).getUuid();
		row(q);
		UUID other = componentRelease("pkg:generic/acme/h2@1.0.0").release();
		ossReleaseService.updateRelease(update(q)
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(other))).build(), WU);
		assertEmptied(q);
	}

	// (i)
	@Test
	void tagsAndNotesThroughUpdateReleaseTagsMetaKeepTheRows() throws Exception {
		UUID r = componentRelease("pkg:generic/acme/i@1.0.0").release();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateReleaseTagsMeta(update(r).notes("t11i")
				.tags(List.of(new TagRecord("score71", "i", Removable.YES))).build(), WU);
		assertKept(r, p);
	}

	// (j)
	@Test
	void notesAloneKeepTheRows() throws Exception {
		UUID r = componentRelease("pkg:generic/acme/j@1.0.0").release();
		UUID p = product(List.of(r));
		row(r);
		row(p);
		ossReleaseService.updateRelease(update(r).notes("x").build(), WU);
		assertKept(r, p);
	}

	// (k)
	@Test
	void aRebuildEmptiesTheRows() throws Exception {
		ReleaseDto first = onNewComponent(ComponentType.COMPONENT).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(List.of(seedBom("t11k-1.cdx.json"))).build();
		UUID r = ossReleaseService.createRelease(first, WU).getUuid();
		row(r);
		ossReleaseService.createRelease(ReleaseDto.builder().component(first.getComponent()).branch(first.getBranch())
				.org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version(first.getVersion()).artifacts(List.of(seedBom("t11k-2.cdx.json"))).build(), WU, true, false);
		assertEmptied(r);
	}

	// (l)
	@Test
	void aPendingCompletionEmptiesTheRows() throws Exception {
		ReleaseDto pending = onNewComponent(ComponentType.COMPONENT).lifecycle(ReleaseLifecycle.PENDING).build();
		UUID r = ossReleaseService.createRelease(pending, WU).getUuid();
		row(r);
		ossReleaseService.createRelease(ReleaseDto.builder().component(pending.getComponent()).branch(pending.getBranch())
				.org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version(pending.getVersion()).build(), WU);
		assertEmptied(r);
	}

	// (m)
	@Test
	void aProductComponentsSupplierChangeEmptiesItsReleasesAndTheProductsAbove() throws Exception {
		World x = componentRelease("pkg:generic/acme/m@1.0.0");
		UUID inner = product(List.of(x.release()));
		UUID outer = product(List.of(inner));
		UUID u = componentRelease("pkg:generic/acme/m2@1.0.0").release();
		row(x.release());
		row(inner);
		row(outer);
		row(u);
		componentService.updateComponent(ComponentDto.builder().uuid(stored(inner).getComponent())
				.supplierName("Acme Boards").build(), WU);
		assertEmptied(inner, outer);
		assertKept(x.release(), u);
	}

	// (n)
	@Test
	void aComponentComponentsSupplierChangeKeepsEveryRow() throws Exception {
		World x = componentRelease("pkg:generic/acme/n@1.0.0");
		UUID inner = product(List.of(x.release()));
		UUID outer = product(List.of(inner));
		row(x.release());
		row(inner);
		row(outer);
		componentService.updateComponent(ComponentDto.builder().uuid(x.component()).supplierName("Acme Boards").build(), WU);
		assertKept(x.release(), inner, outer);
	}

	// (o)
	@Test
	void theSameSupplierOrNoSupplierKeepsEveryRow() throws Exception {
		World x = componentRelease("pkg:generic/acme/o@1.0.0");
		UUID inner = product(List.of(x.release()));
		UUID outer = product(List.of(inner));
		UUID productComponent = stored(inner).getComponent();
		componentService.updateComponent(ComponentDto.builder().uuid(productComponent).supplierName("Acme Boards").build(), WU);
		row(x.release());
		row(inner);
		row(outer);
		componentService.updateComponent(ComponentDto.builder().uuid(productComponent).supplierName("Acme Boards").build(), WU);
		assertKept(x.release(), inner, outer);
		componentService.updateComponent(ComponentDto.builder().uuid(productComponent).supplierName(null).build(), WU);
		assertKept(x.release(), inner, outer);
	}

	// (p)
	@Test
	void theBulkIdentifierFillEmptiesTheFilledReleasesAndTheProductsAbove() throws Exception {
		World w = componentRelease(null);
		UUID c = w.component();
		UUID r1 = w.release();
		UUID r2 = ossReleaseService.createRelease(ReleaseDto.builder().component(c).branch(w.branch()).org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("1.1." + Math.abs(UUID.randomUUID().hashCode() % 100000)).build(), WU).getUuid();
		assertTrue(null == stored(r1).getIdentifiers() || stored(r1).getIdentifiers().isEmpty());
		assertTrue(null == stored(r2).getIdentifiers() || stored(r2).getIdentifiers().isEmpty());
		UUID p = product(List.of(r1));
		UUID u = componentRelease("pkg:generic/acme/pu@1.0.0").release();
		row(r1);
		row(r2);
		row(p);
		row(u);
		componentService.updateComponent(ComponentDto.builder().uuid(c)
				.identifiers(List.of(purl("pkg:generic/acme/t11p"))).build(), WU);
		assertKept(r1);

		ossReleaseService.updateComponentReleasesWithIdentifiers(c, WU);

		for (UUID filled : List.of(r1, r2)) {
			String version = stored(filled).getVersion();
			assertTrue(stored(filled).getIdentifiers().stream()
					.anyMatch(i -> ("pkg:generic/acme/t11p@" + version).equals(i.getIdValue())),
					stored(filled).getIdentifiers().toString());
		}
		assertEmptied(r1, r2, p);
		assertKept(u);
	}
}
