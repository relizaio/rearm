/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanState;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanTally;

/**
 * TPF-129 architecture 6.1 row 1: the BOM tally the metrics compute persists at release
 * metrics.bomScan and the CEL activation reads. One classifier (isScanInput) over the gathered
 * artifacts; the state is derived from the two counts; the JSON carries the counts and the reasons.
 */
class BomScanTallyTest {

	private static ArtifactData artifact(ArtifactType type, ZonedDateTime firstScanned,
			NoMatchableComponentsReason marker) {
		ArtifactData ad = new ArtifactData();
		ad.setType(type);
		DependencyTrackIntegration metrics = new DependencyTrackIntegration();
		metrics.setFirstScanned(firstScanned);
		if (null != marker) {
			metrics.setNotScannable(new ArtifactNotScannable(marker, ZonedDateTime.now(), 1, 0, 0, 1));
		}
		ad.setMetrics(metrics);
		return ad;
	}

	private static ArtifactData bom(ZonedDateTime firstScanned) {
		return artifact(ArtifactType.BOM, firstScanned, null);
	}

	private static ArtifactData markedBom(NoMatchableComponentsReason reason) {
		return artifact(ArtifactType.BOM, null, reason);
	}

	@Test
	void noArtifactsIsTheEmptyTally() {
		BomScanTally t = ReleaseMetricsComputeService.tallyBomScan(List.of());
		assertEquals(BomScanTally.EMPTY, t);
		assertEquals(BomScanState.NO_BOMS, t.state());
		assertEquals(BomScanState.NO_BOMS, BomScanTally.EMPTY.state());
	}

	@Test
	void scannedAndPendingBomsAreBothScannable() {
		BomScanTally t = ReleaseMetricsComputeService.tallyBomScan(List.of(bom(ZonedDateTime.now()), bom(null)));
		assertEquals(new BomScanTally(2, 0, List.of()), t);
		assertEquals(BomScanState.ALL_SCANNABLE, t.state());
	}

	@Test
	void aMarkedAndAScanInputBomAreMixed() {
		BomScanTally t = ReleaseMetricsComputeService.tallyBomScan(
				List.of(markedBom(NoMatchableComponentsReason.NO_PURL_OR_CPE), bom(null)));
		assertEquals(new BomScanTally(1, 1, List.of(NoMatchableComponentsReason.NO_PURL_OR_CPE)), t);
		assertEquals(BomScanState.SOME_NOT_SCANNABLE, t.state());
	}

	@Test
	void allMarkedIsAllNotScannableWithDistinctReasonsInEnumOrder() {
		BomScanTally t = ReleaseMetricsComputeService.tallyBomScan(List.of(
				markedBom(NoMatchableComponentsReason.ALL_TERMINAL),
				markedBom(NoMatchableComponentsReason.NO_COMPONENTS),
				markedBom(NoMatchableComponentsReason.NO_COMPONENTS)));
		assertEquals(new BomScanTally(0, 3, List.of(NoMatchableComponentsReason.NO_COMPONENTS,
				NoMatchableComponentsReason.ALL_TERMINAL)), t);
		assertEquals(BomScanState.ALL_NOT_SCANNABLE, t.state());
	}

	@Test
	void nonBomArtifactsCountNothing() {
		List<ArtifactData> mixed = List.of(markedBom(NoMatchableComponentsReason.NO_PURL_OR_CPE), bom(null));
		List<ArtifactData> withOthers = new ArrayList<>(mixed);
		withOthers.add(artifact(ArtifactType.VEX, null, null));
		withOthers.add(artifact(ArtifactType.ATTESTATION, ZonedDateTime.now(), null));
		assertEquals(ReleaseMetricsComputeService.tallyBomScan(mixed),
				ReleaseMetricsComputeService.tallyBomScan(withOthers));
	}

	@Test
	void aMarkedBomThatAlsoHasFirstScannedCountsAsNotScannable() {
		ArtifactData legacy = artifact(ArtifactType.BOM, ZonedDateTime.now(), NoMatchableComponentsReason.ONLY_ROOT);
		assertEquals(new BomScanTally(0, 1, List.of(NoMatchableComponentsReason.ONLY_ROOT)),
				ReleaseMetricsComputeService.tallyBomScan(List.of(legacy)), "the marker wins");
	}

	@Test
	void theIterationOrderDoesNotChangeTheTally() {
		ArtifactData a = markedBom(NoMatchableComponentsReason.NO_PURL_OR_CPE);
		ArtifactData b = markedBom(NoMatchableComponentsReason.NO_COMPONENTS);
		ArtifactData c = bom(null);
		assertEquals(ReleaseMetricsComputeService.tallyBomScan(List.of(a, b, c)),
				ReleaseMetricsComputeService.tallyBomScan(List.of(c, b, a)));
	}

	@Test
	@SuppressWarnings("unchecked")
	void theJsonCarriesTheCountsAndTheReasonsButNoState() {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		BomScanTally tally = new BomScanTally(1, 1, List.of(NoMatchableComponentsReason.NO_PURL_OR_CPE));
		rmd.setBomScan(tally);
		Map<String, Object> json = Utils.OM.convertValue(rmd, Map.class);
		Map<String, Object> bomScan = (Map<String, Object>) json.get("bomScan");
		assertEquals(Map.of("scannableBoms", 1, "notScannableBoms", 1, "notScannableReasons", List.of("NO_PURL_OR_CPE")),
				bomScan, "two ints and the reason names, no state key");
		ReleaseMetricsDto back = Utils.OM.readValue(Utils.OM.writeValueAsString(rmd), ReleaseMetricsDto.class);
		assertEquals(tally, back.getBomScan());
	}

	@Test
	@SuppressWarnings("unchecked")
	void noTallyWritesNoKeyAndAnArtifactNeverCarriesOne() {
		Map<String, Object> release = Utils.OM.convertValue(new ReleaseMetricsDto(), Map.class);
		assertFalse(release.containsKey("bomScan"));
		Map<String, Object> artifact = Utils.OM.convertValue(new DependencyTrackIntegration(), Map.class);
		assertFalse(artifact.containsKey("bomScan"));
	}

	@Test
	void storedShapesReadNullTolerantly() {
		assertNull(Utils.OM.readValue("{\"bomScan\": null}", ReleaseMetricsDto.class).getBomScan());
		BomScanTally noList = Utils.OM.readValue(
				"{\"bomScan\": {\"scannableBoms\": 1, \"notScannableBoms\": 0}}", ReleaseMetricsDto.class).getBomScan();
		assertNull(noList.notScannableReasons(), "a stored tally without the list reads with a null list");
		assertTrue(noList.reasonsOrEmpty().isEmpty(), "the activation reads it as empty");
		assertEquals(BomScanState.ALL_SCANNABLE, noList.state());
	}
}
