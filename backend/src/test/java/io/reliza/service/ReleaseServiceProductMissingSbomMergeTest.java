/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.MissingSbomReason;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.ReleaseNodeFacts;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-15 (T-2, T-3, ADR-1, ADR-5): a product merge sends rebom the component releases that yield
 * no BOM, sorted by release uuid, with why; with none it sends exactly the request it sent before.
 * One BOM is served as is only when nothing is missing; no BOM at all still fails.
 */
class ReleaseServiceProductMissingSbomMergeTest extends ReleaseServiceMergeTestBase {

	// Named so that uuid order differs from the order the product unwinds them in.
	private static final UUID WITH_BOM = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000c3");
	private static final UUID SECOND_WITH_BOM = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000c4");
	private static final UUID BARE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000c2");
	private static final UUID EXCLUDED = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000c1");

	/** The merge ids rebom answered for the component releases own merges, in merge order. */
	private List<UUID> componentMergeIds() {
		return componentMerges().stream().map(Merge::merged).toList();
	}

	/** The id rebom answered for the one product-level merge. */
	private UUID productMerge() {
		assertEquals(1, productMerges().size(), productMerges().toString());
		return productMerges().get(0).merged();
	}

	private UUID request(List<ArtifactCoverageType> excludeCoverageTypes) throws Exception {
		return svc.getReleaseBomId(PRODUCT_RELEASE, false, false, false, ArtifactBelongsTo.RELEASE, BomStructureType.FLAT,
				WU, excludeCoverageTypes);
	}

	/** The options of the one product-level merge request, the one that keeps every input root. */
	private RebomOptions productMergeRequest() {
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(componentMerges().size() + 1)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		List<RebomOptions> productMerges = sent.getAllValues().stream()
				.filter(o -> RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT == o.rootComponentMergeMode()).toList();
		assertEquals(1, productMerges.size(), sent.getAllValues().toString());
		return productMerges.get(0);
	}

	private static MissingSbomComponent missing(UUID release, String version, MissingSbomReason reason) {
		return new MissingSbomComponent(release.toString(), "component", version, "library", "Example Org",
				"Example Org", null, null, null, null, reason);
	}

	// T-2
	@Test
	void aProductWhoseReleasesAllYieldABomSendsTheRequestItSentBefore() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), withBom(SECOND_WITH_BOM, "2.0.0", null));

		UUID served = request(null);

		assertEquals(productMerge(), served);

		RebomOptions sent = productMergeRequest();
		assertEquals(new RebomOptions("product", "Example Org", "26.10.35", ArtifactBelongsTo.RELEASE, null, false, false,
				null, BomStructureType.FLAT, "sent from ReArm", StripBom.TRUE, "", "", null,
				RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT).withMergeVersion(ReleaseService.MERGE_VERSION)
				// SCORE-30: and the product release's facts, the organization as its supplier
				.withReleaseNode(new ReleaseNodeFacts("Example Org", List.of(), null, null)), sent);
		assertNull(sent.missingSbomComponents());
		assertFalse(Utils.OM.writeValueAsString(sent).contains("missingSbomComponents"));
		verify(rebomService).mergeAndStoreBoms(eq(componentMergeIds()), any(), eq(ORG));
	}

	// T-3
	@Test
	void aMixedProductListsTheBareAndTheExcludedReleasesSortedByUuidWithWhy() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), componentRelease(BARE, "3.0.0"),
				withBom(EXCLUDED, "4.0.0", ArtifactCoverageType.DEV), withBom(SECOND_WITH_BOM, "2.0.0", null));

		UUID served = request(List.of(ArtifactCoverageType.DEV));

		assertEquals(productMerge(), served);

		RebomOptions sent = productMergeRequest();
		assertEquals(List.of(missing(EXCLUDED, "4.0.0", MissingSbomReason.COVERAGE_EXCLUDED), missing(BARE, "3.0.0", MissingSbomReason.NO_SBOM_ARTIFACT)),
				sent.missingSbomComponents());
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), sent.mergeVersion());
		assertEquals(2, componentMerges().size());
		verify(rebomService).mergeAndStoreBoms(eq(componentMergeIds()), any(), eq(ORG));
	}

	// T-3, ADR-5
	@Test
	void oneBomAndOnePlaceholderAreMergedSoTheProductRootCanDependOnBoth() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), componentRelease(BARE, "3.0.0"));

		UUID served = request(null);

		assertEquals(productMerge(), served);

		RebomOptions sent = productMergeRequest();
		assertEquals(List.of(missing(BARE, "3.0.0", MissingSbomReason.NO_SBOM_ARTIFACT)), sent.missingSbomComponents());
		verify(rebomService).mergeAndStoreBoms(eq(List.of(componentMergeIds().get(0))), any(), eq(ORG));
	}

	// T-3, ADR-5: unchanged
	@Test
	void oneBomAndNothingMissingIsStillServedAsIs() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null));

		UUID served = request(null);

		assertEquals(List.of(served), componentMergeIds(), "the component's own merge, with no product merge");
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), any(), any());
	}

	// T-3, scope: a document of unknowns only is not an SBOM of anything
	@Test
	void noBomAtAllStillFailsWhateverIsMissing() {
		unwindsTo(componentRelease(BARE, "3.0.0"), withBom(EXCLUDED, "4.0.0", ArtifactCoverageType.DEV));

		RelizaException e = assertThrows(RelizaException.class, () -> request(List.of(ArtifactCoverageType.DEV)));

		assertEquals("No SBOMs found!", e.getMessage());
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	// T-13: the list is not a cache key and never lands on the stored row
	@Test
	void theStoredProductRowIsTheRequestUnderTheCurrentRulesWithoutTheList() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), componentRelease(BARE, "3.0.0"));

		request(null);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).setReboms(eq(product), stored.capture(), eq(WU));
		assertEquals(List.of(new ReleaseBom(productMerge(), new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null,
				BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION).withReleaseNodeFactsKey(FACTS_KEY))),
				stored.getValue());
		assertNull(stored.getValue().get(0).rebomMergeOptions().missingSbomComponents());
	}
}
