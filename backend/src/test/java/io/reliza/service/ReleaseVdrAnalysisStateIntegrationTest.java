/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VdrAnalysisState;
import io.reliza.model.VdrMetadataProperty;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-2 (B2), design T16, against the test database: the analysis state of a release through
 * {@link ReleaseService#vdrAnalysisStateOf}, and the marker the LIVE exports' producers
 * ({@link ReleaseService#generateVdr} / {@link ReleaseService#generateCdxVex}, deterministic serial)
 * write without refusing. Every scan is the artifact stamp followed by the production compute; no
 * release's metrics are written directly. A product over an SBOM-less child carries the child's no-BOM
 * anchor as its own firstScanned and must still not read COMPLETED.
 */
public class ReleaseVdrAnalysisStateIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final ZonedDateTime EARLIER = ZonedDateTime.now().minusDays(2).truncatedTo(ChronoUnit.SECONDS);
	private static final ZonedDateTime LATER = ZonedDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);

	private UUID scannedLeaf(ZonedDateTime at) throws Exception {
		World w = componentRelease("pkg:generic/fdaw2-" + UUID.randomUUID().toString().substring(0, 8) + "@1.0.0");
		BomScanFixtures.markBomScanned(artifactRepository, w.inventory(), at);
		releaseService.computeReleaseMetrics(w.release(), true);
		return w.release();
	}

	private UUID pendingLeaf() throws Exception {
		return componentRelease("pkg:generic/fdaw2-" + UUID.randomUUID().toString().substring(0, 8) + "@1.0.0").release();
	}

	private UUID bomLessLeaf() throws Exception {
		UUID release = componentRelease(null).release();
		releaseService.computeReleaseMetrics(release, true);
		return release;
	}

	private UUID computedProduct(List<UUID> children) throws Exception {
		UUID product = product(children);
		releaseService.computeReleaseMetrics(product, true);
		return product;
	}

	private VdrAnalysisState.Derived stateOf(UUID release) {
		return releaseService.vdrAnalysisStateOf(stored(release));
	}

	private static String property(String document, VdrMetadataProperty name) {
		JsonNode props = Utils.OM.readTree(document).path("metadata").path("properties");
		for (JsonNode p : props) {
			if (name.toString().equals(p.path("name").asString())) return p.path("value").asString();
		}
		return null;
	}

	@Test
	void a_aLeafAwaitingItsScanIsNotRefused_andBothDocumentsSaySo() throws Exception {
		UUID release = pendingLeaf();
		VdrAnalysisState.Derived d = stateOf(release);
		assertEquals(VdrAnalysisState.Reason.SCAN_PENDING, d.reason());
		assertEquals(1, d.pendingReleases());
		for (String doc : List.of(releaseService.generateVdr(stored(release), false, null, null),
				releaseService.generateCdxVex(stored(release), false, false, null, null))) {
			assertEquals("NOT_ANALYSED", property(doc, VdrMetadataProperty.VDR_ANALYSIS_STATE));
			assertEquals("SCAN_PENDING", property(doc, VdrMetadataProperty.VDR_ANALYSIS_REASON));
			assertNull(property(doc, VdrMetadataProperty.VDR_ANALYSIS_COMPLETED_AT));
			assertNotNull(property(doc, VdrMetadataProperty.VDR_KEV_CATALOG_SOURCE));
		}
	}

	@Test
	void b_aProductOverAnSbomLessChildIsNoSbom_thoughItsStoredFirstScannedIsTheAnchor() throws Exception {
		UUID product = computedProduct(List.of(bomLessLeaf()));
		assertNotNull(stored(product).getMetrics().getFirstScanned(), "the rollup carries the child's anchor");
		VdrAnalysisState.Derived d = stateOf(product);
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(VdrAnalysisState.Reason.NO_SBOM, d.reason());
		String vdr = releaseService.generateVdr(stored(product), false, null, null);
		assertEquals("NOT_ANALYSED", property(vdr, VdrMetadataProperty.VDR_ANALYSIS_STATE));
		assertEquals("NO_SBOM", property(vdr, VdrMetadataProperty.VDR_ANALYSIS_REASON));
		assertNull(property(vdr, VdrMetadataProperty.VDR_ANALYSIS_COMPLETED_AT));
	}

	@Test
	void c_aProductOverAScannedAndAnSbomLessChildIsPartial_completedAtTheScan() throws Exception {
		UUID product = computedProduct(List.of(scannedLeaf(EARLIER), bomLessLeaf()));
		VdrAnalysisState.Derived d = stateOf(product);
		assertEquals(VdrAnalysisState.PARTIAL, d.state());
		assertEquals(1, d.analysedReleases());
		assertEquals(1, d.notAnalysedReleases());
		assertEquals(EARLIER.toInstant(), d.completedAt().toInstant());
		String vex = releaseService.generateCdxVex(stored(product), false, false, null, null);
		assertEquals("PARTIAL", property(vex, VdrMetadataProperty.VDR_ANALYSIS_STATE));
		assertEquals(EARLIER.toInstant(), Instant.parse(property(vex, VdrMetadataProperty.VDR_ANALYSIS_COMPLETED_AT)));
		assertEquals("1", property(vex, VdrMetadataProperty.VDR_ANALYSIS_RELEASES_ANALYSED));
		assertEquals("1", property(vex, VdrMetadataProperty.VDR_ANALYSIS_RELEASES_NOT_ANALYSED));
	}

	@Test
	void d_aProductOverTwoScannedChildrenIsCompletedAtTheLaterScan() throws Exception {
		UUID product = computedProduct(List.of(scannedLeaf(EARLIER), scannedLeaf(LATER)));
		VdrAnalysisState.Derived d = stateOf(product);
		assertEquals(VdrAnalysisState.COMPLETED, d.state());
		assertEquals(LATER.toInstant(), d.completedAt().toInstant());
		String vdr = releaseService.generateVdr(stored(product), false, null, null);
		assertEquals("COMPLETED", property(vdr, VdrMetadataProperty.VDR_ANALYSIS_STATE));
		assertEquals(LATER.toInstant(), Instant.parse(property(vdr, VdrMetadataProperty.VDR_ANALYSIS_COMPLETED_AT)));
	}

	@Test
	void e_aProductOverAPendingAndAScannedChildIsScanPending() throws Exception {
		UUID product = computedProduct(List.of(pendingLeaf(), scannedLeaf(EARLIER)));
		VdrAnalysisState.Derived d = stateOf(product);
		assertEquals(VdrAnalysisState.Reason.SCAN_PENDING, d.reason());
		assertEquals(1, d.pendingReleases());
		assertEquals(2, d.totalReleases());
	}

	@Test
	void g_aLeafSharedByTwoSubProductsCountsOnce_andTheSecondSubProductIsNoLeaf() throws Exception {
		UUID shared = scannedLeaf(EARLIER);
		UUID product = computedProduct(List.of(computedProduct(List.of(shared)), computedProduct(List.of(shared))));
		VdrAnalysisState.Derived d = stateOf(product);
		assertEquals(VdrAnalysisState.COMPLETED, d.state(), d.toString());
		assertEquals(1, d.analysedReleases());
		assertEquals(0, d.notAnalysedReleases());
		assertEquals(1, d.totalReleases());
		assertEquals(EARLIER.toInstant(), d.completedAt().toInstant());
	}

	@Test
	void f_aProductWhoseOnlyChildIsADocumentRoundIsALeaf_noSbom() throws Exception {
		Component doc = componentService.createBoardDocumentComponent(CreateComponentDto.builder()
				.name("fdaw2-doc-" + UUID.randomUUID()).organization(org.getUuid())
				.type(ComponentType.COMPONENT).kind(ComponentKind.BOARD_DOCUMENT)
				.versionSchema("Micro").featureBranchVersioning("Branch.Micro").build(), WU);
		Branch branch = branchService.getBaseBranchOfComponent(doc.getUuid()).orElseThrow();
		UUID docRelease = ossReleaseService.createRelease(ReleaseDto.builder().component(doc.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("1." + Math.abs(UUID.randomUUID().hashCode() % 100000))
				.status(ReleaseData.ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		UUID product = product(List.of(docRelease));
		VdrAnalysisState.Derived d = stateOf(product);
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(VdrAnalysisState.Reason.NO_SBOM, d.reason());
		assertEquals(1, d.totalReleases(), "the product itself, as a leaf");
	}
}
