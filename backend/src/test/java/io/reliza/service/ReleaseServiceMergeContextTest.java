/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-15 ADR-12 (T-24 to T-26): one merge context per reconcile and per export request. Each
 * (release, options) is merged at most once in it, however many products above it bundle it, and a
 * release met again while it is being unwound, which only a cycle in stored data does, is left out
 * of that one merge with an error log instead of recursing without end.
 */
class ReleaseServiceMergeContextTest extends ReleaseServiceMergeTestBase {

	private static final UUID SOURCE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f1");
	private static final UUID A = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f2");
	private static final UUID B = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f3");
	private static final UUID C = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f4");
	private static final UUID P1 = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f5");
	private static final UUID P2 = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f6");
	private static final UUID P3 = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f7");
	private static final UUID BARE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f8");
	private static final UUID Q = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000f9");
	private static final String SOURCE_VERSION = "3.0.0";
	private static final String P1_VERSION = "1.1.0";
	private static final String P2_VERSION = "2.2.0";
	private static final String P3_VERSION = "3.3.0";
	private static final String Q_VERSION = "4.4.0";

	private static final RebomOptions FLAT = rowOptions(BomStructureType.FLAT);
	private static final RebomOptions TOP_LEVEL_ONLY = new RebomOptions(ArtifactBelongsTo.RELEASE, true, false, null,
			BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION);

	private ReleaseData source;
	private ReleaseData a;
	private ReleaseData b;
	private ReleaseData c;
	private ReleaseData p1;
	private ReleaseData p2;
	private ReleaseData p3;
	private final Map<UUID, UUID> flatRowOf = new HashMap<>();

	/** What ReleaseService logged during the test, captured on its own logger. */
	private final List<LogEvent> logged = Collections.synchronizedList(new ArrayList<>());
	private Logger releaseServiceLog;
	private AbstractAppender capture;

	@BeforeEach
	void captureReleaseServiceLog() {
		releaseServiceLog = (Logger) LogManager.getLogger(ReleaseService.class);
		capture = new AbstractAppender("merge-context-capture-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				logged.add(event.toImmutable());
			}
		};
		capture.start();
		releaseServiceLog.addAppender(capture);
	}

	@AfterEach
	void releaseReleaseServiceLog() {
		releaseServiceLog.removeAppender(capture);
		capture.stop();
	}

	/**
	 * The run-3 three-level shape: P1 = {A, source}, P2 = {B, P1}, P3 = {C, P2}, each unwinding flat as
	 * {@code unwindReleaseDependencies} does (P3 to C, P2, B, P1, A, source). A, B and C are cached
	 * under {@code options}; the source release just got its first SBOM and has no row.
	 */
	private void threeLevels(RebomOptions... options) {
		source = withBom(SOURCE, SOURCE_VERSION, null);
		a = withBom(A, "1.0.0", null);
		b = withBom(B, "2.0.0", null);
		c = withBom(C, "5.0.0", null);
		for (ReleaseData r : List.of(a, b, c)) {
			for (RebomOptions o : options) {
				UUID row = UUID.randomUUID();
				storeRow(r.getUuid(), row, o);
				if (o.equals(FLAT)) flatRowOf.put(r.getUuid(), row);
			}
		}
		p1 = innerProductRelease(P1, P1_VERSION);
		p2 = innerProductRelease(P2, P2_VERSION);
		p3 = innerProductRelease(P3, P3_VERSION);
		unwinds(p1, a, source);
		unwinds(p2, b, p1, a, source);
		unwinds(p3, c, p2, b, p1, a, source);
	}

	/** How many times rebom merged each product, by product version and top-level-only flag. */
	private Map<String, Long> productMergeCounts() {
		return productMerges().stream().collect(Collectors.groupingBy(
				m -> m.options().version() + (Boolean.TRUE.equals(m.options().tldOnly()) ? " top-level-only" : ""),
				Collectors.counting()));
	}

	private UUID onlyMergeOf(String version) {
		List<Merge> of = merges.stream().filter(m -> version.equals(m.options().version())).toList();
		assertEquals(1, of.size(), merges.toString());
		return of.get(0).merged();
	}

	private List<String> cycleErrors() {
		synchronized (logged) {
			return logged.stream()
					.filter(e -> e.getLevel() == Level.ERROR)
					.map(e -> e.getMessage().getFormattedMessage())
					.filter(m -> m.startsWith("Release graph cycle")).toList();
		}
	}

	// T-24 (a): three levels, one option set: each product merged once, the changed release once
	@Test
	void aReconcileMergesEachProductAboveTheReleaseOnce() {
		threeLevels(FLAT);
		storeRow(P1, UUID.randomUUID(), FLAT);
		storeRow(P2, UUID.randomUUID(), FLAT);
		storeRow(P3, UUID.randomUUID(), FLAT);
		productsAbove(source, p1, p2, p3);

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(Map.of(P1_VERSION, 1L, P2_VERSION, 1L, P3_VERSION, 1L), productMergeCounts());
		assertEquals(1, componentMerges().size(), componentMerges().toString());
		UUID sourceMerge = onlyMergeOf(SOURCE_VERSION);
		UUID p1Merge = onlyMergeOf(P1_VERSION);
		UUID p2Merge = onlyMergeOf(P2_VERSION);
		Merge p3Merge = productMerges().stream().filter(m -> P3_VERSION.equals(m.options().version())).findFirst().orElseThrow();
		// The outer merges take the inner products and the release as this reconcile merged them.
		assertEquals(List.of(flatRowOf.get(B), p1Merge, flatRowOf.get(A), sourceMerge),
				productMerges().stream().filter(m -> m.merged().equals(p2Merge)).findFirst().orElseThrow().bomIds());
		assertEquals(List.of(flatRowOf.get(C), p2Merge, flatRowOf.get(B), p1Merge, flatRowOf.get(A), sourceMerge),
				p3Merge.bomIds());
		assertEquals(List.of(new ReleaseBom(p1Merge, FLAT)), rows.get(P1));
		assertEquals(List.of(new ReleaseBom(p2Merge, FLAT)), rows.get(P2));
		assertEquals(List.of(new ReleaseBom(p3Merge.merged(), FLAT)), rows.get(P3));
		assertEquals(List.of(new ReleaseBom(sourceMerge, FLAT)), rows.get(SOURCE));
	}

	// T-24 (b): two option sets (FLAT on every product, top-level-only on P3 too): eight merges,
	// each (product, option set) and the changed release under each option set once
	@Test
	void aReconcileMergesEachProductOncePerOptionSet() {
		threeLevels(FLAT, TOP_LEVEL_ONLY);
		storeRow(P1, UUID.randomUUID(), FLAT);
		storeRow(P2, UUID.randomUUID(), FLAT);
		storeRow(P3, UUID.randomUUID(), FLAT);
		storeRow(P3, UUID.randomUUID(), TOP_LEVEL_ONLY);
		productsAbove(source, p1, p2, p3);

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(8, merges.size(), merges.toString());
		assertEquals(Map.of(P1_VERSION, 1L, P2_VERSION, 1L, P3_VERSION, 1L,
				P1_VERSION + " top-level-only", 1L, P2_VERSION + " top-level-only", 1L, P3_VERSION + " top-level-only", 1L),
				productMergeCounts());
		assertEquals(Map.of(false, 1L, true, 1L), componentMerges().stream().collect(
				Collectors.groupingBy(m -> Boolean.TRUE.equals(m.options().tldOnly()), Collectors.counting())));
		componentMerges().forEach(m -> assertEquals(SOURCE_VERSION, m.options().version()));
	}

	// ADR-12 cost line, the run-3 diamond: left = {A, source}, right = {B, source}, top = {left, right}
	// (unwinding flat). Four merges: each product once and the changed release once
	@Test
	void aReconcileOverADiamondMergesEachProductOnce() {
		threeLevels(FLAT);
		ReleaseData left = p1;
		ReleaseData right = p2;
		ReleaseData top = p3;
		unwinds(right, b, source);
		unwinds(top, left, a, source, right, b);
		storeRow(P1, UUID.randomUUID(), FLAT);
		storeRow(P2, UUID.randomUUID(), FLAT);
		storeRow(P3, UUID.randomUUID(), FLAT);
		productsAbove(source, left, right, top);

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(4, merges.size(), merges.toString());
		assertEquals(Map.of(P1_VERSION, 1L, P2_VERSION, 1L, P3_VERSION, 1L), productMergeCounts());
		UUID sourceMerge = onlyMergeOf(SOURCE_VERSION);
		assertEquals(List.of(onlyMergeOf(P1_VERSION), flatRowOf.get(A), sourceMerge, onlyMergeOf(P2_VERSION), flatRowOf.get(B)),
				productMerges().stream().filter(m -> P3_VERSION.equals(m.options().version())).findFirst().orElseThrow().bomIds());
	}

	// ADR-11 with ADR-12: products that bundle the release side by side, none inside another, are each
	// rebuilt; the memo makes the nested ones of T-24 come out whichever product is walked, so only
	// side-by-side products show that the walk visits every located product
	@Test
	void productsThatBundleTheReleaseSideBySideAreEachRebuilt() {
		threeLevels(FLAT);
		unwinds(p2, b, source);
		storeRow(P1, UUID.randomUUID(), FLAT);
		storeRow(P2, UUID.randomUUID(), FLAT);
		productsAbove(source, p1, p2);

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(Map.of(P1_VERSION, 1L, P2_VERSION, 1L), productMergeCounts());
		assertEquals(1, componentMerges().size(), componentMerges().toString());
		assertEquals(List.of(new ReleaseBom(onlyMergeOf(P1_VERSION), FLAT)), rows.get(P1));
		assertEquals(List.of(new ReleaseBom(onlyMergeOf(P2_VERSION), FLAT)), rows.get(P2));
	}

	// ADR-12 key (departure D-19): rows of a product stored under an older merge version are the same
	// merge as its current rows, so the product and the changed release are merged once, not per row
	@Test
	void aProductsOlderMergeVersionRowIsTheSameMergeAsItsCurrentRow() {
		threeLevels(FLAT);
		storeRow(P1, UUID.randomUUID(), FLAT.withMergeVersion(ReleaseService.MERGE_VERSION - 1));
		storeRow(P1, UUID.randomUUID(), FLAT);
		productsAbove(source, p1);

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(Map.of(P1_VERSION, 1L), productMergeCounts());
		assertEquals(1, componentMerges().size(), componentMerges().toString());
	}

	// ADR-12 memo on the unforced path where no row serves (departure D-20): a coverage-filtered export
	// stores nothing, so without the memo each nested product and each release was merged once per path
	@Test
	void aCoverageFilteredExportOfNestedProductsMergesEachReleaseOnce() throws Exception {
		threeLevels(FLAT);

		UUID exported = svc.getReleaseBomId(P3, false, false, false, ArtifactBelongsTo.RELEASE,
				BomStructureType.FLAT, WU, List.of(ArtifactCoverageType.TEST));

		assertEquals(Map.of(P1_VERSION, 1L, P2_VERSION, 1L, P3_VERSION, 1L), productMergeCounts());
		assertEquals(Map.of(SOURCE_VERSION, 1L, "1.0.0", 1L, "2.0.0", 1L, "5.0.0", 1L), componentMerges().stream()
				.collect(Collectors.groupingBy(m -> m.options().version(), Collectors.counting())));
		assertEquals(onlyMergeOf(P3_VERSION), exported);
		assertEquals(Map.of(), rowWrites, "a filtered merge is never stored (ADR-10)");
	}

	// ADR-12 memo of an empty result (departure D-21): a bundled product that yields no BOM is unwound
	// once per reconcile, not once per product above it
	@Test
	void aBundledProductWithoutAnyBomIsUnwoundOncePerReconcile() {
		source = withBom(SOURCE, SOURCE_VERSION, null);
		ReleaseData bare = componentRelease(BARE, "0.1.0");
		ReleaseData q = innerProductRelease(Q, Q_VERSION);
		p1 = innerProductRelease(P1, P1_VERSION);
		p2 = innerProductRelease(P2, P2_VERSION);
		unwinds(q, bare);
		unwinds(p1, q, bare, source);
		unwinds(p2, p1, q, bare, source);
		storeRow(P1, UUID.randomUUID(), FLAT);
		storeRow(P2, UUID.randomUUID(), FLAT);
		productsAbove(source, p1, p2);

		svc.reconcileMergedSbomRoutine(source, WU);

		verify(sharedReleaseService, times(1)).unwindReleaseDependencies(q);
		assertEquals(Map.of(P1_VERSION, 1L, P2_VERSION, 1L), productMergeCounts());
		// Q is still listed as a release without an SBOM in both product merges.
		productMerges().forEach(m -> assertTrue(m.options().missingSbomComponents().stream()
				.anyMatch(missing -> missing.releaseUuid().equals(Q.toString())), m.toString()));
	}

	/** A legacy two-product cycle: P1 and P2 each unwind to the other and the source release. */
	private void twoProductCycle() {
		source = withBom(SOURCE, SOURCE_VERSION, null);
		p1 = innerProductRelease(P1, P1_VERSION);
		p2 = innerProductRelease(P2, P2_VERSION);
		unwinds(p1, p2, source);
		unwinds(p2, p1, source);
	}

	// T-25: a reconcile over a legacy cycle returns, merges each product at most once, logs the cycle
	// and still fans out to the per-component reconcile
	@Test
	void aReconcileOverALegacyProductCycleReturns() {
		twoProductCycle();
		storeRow(P1, UUID.randomUUID(), FLAT);
		storeRow(P2, UUID.randomUUID(), FLAT);
		productsAbove(source, p1, p2);

		assertDoesNotThrow(() -> svc.reconcileMergedSbomRoutine(source, WU));

		productMergeCounts().forEach((product, count) -> assertTrue(count <= 1, productMergeCounts().toString()));
		assertEquals(1, componentMerges().size(), componentMerges().toString());
		List<String> cycleErrors = cycleErrors();
		assertEquals(1, cycleErrors.size(), logged.toString());
		assertTrue(cycleErrors.get(0).contains(P1.toString()) && cycleErrors.get(0).contains(P2.toString()), cycleErrors.get(0));
		// The re-entered product is left out, not listed as a release without an SBOM.
		productMerges().forEach(m -> assertNull(m.options().missingSbomComponents(), m.toString()));
		verify(sbomComponentService).requestReconcile(SOURCE);
		verify(sbomComponentService).requestReconcile(P1);
		verify(sbomComponentService).requestReconcile(P2);
	}

	// T-26: the unforced export of a product in a legacy cycle, with no rows, returns its merge too
	@Test
	void anExportOfAProductInALegacyCycleReturns() throws Exception {
		twoProductCycle();

		UUID exported = assertDoesNotThrow(() -> svc.getReleaseBomId(P1, false, false, false,
				ArtifactBelongsTo.RELEASE, BomStructureType.FLAT, WU, null));

		assertNotNull(exported);
		assertEquals(onlyMergeOf(P1_VERSION), exported);
		assertEquals(1, componentMerges().size(), componentMerges().toString());
		Map<UUID, Long> byInput = productMerges().get(0).bomIds().stream()
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		assertEquals(Map.of(componentMerges().get(0).merged(), 2L), byInput,
				"P1 merges the source release and P2, which passes the source release's BOM through");
		List<String> cycleErrors = cycleErrors();
		assertEquals(1, cycleErrors.size(), logged.toString());
		assertTrue(cycleErrors.get(0).contains(P1.toString()) && cycleErrors.get(0).contains(P2.toString()), cycleErrors.get(0));
	}
}
