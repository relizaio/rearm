/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomStatus;
import io.reliza.exceptions.MergedSbomUnavailableException.Reason;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.MissingSbomReason;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseService.PublicationMergeRequest;

/**
 * TEA-15 (architecture 3.1 to 3.4, 4.1 cases 1 to 11): the missing-SBOM mode of a product merge. The TEA
 * publication merges under OMIT and leaves a component release without an SBOM out; the export keeps the
 * placeholder; the two documents are cached apart and neither row ever serves the other mode's request.
 */
class ReleaseServiceMissingSbomModeMergeTest extends ReleaseServiceMergeTestBase {

	private static final UUID WITH_BOM = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000a3");
	private static final UUID BARE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000a2");
	private static final UUID INNER = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000a4");
	private static final UUID P2 = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000a5");
	private static final String P2_VERSION = "2.2.0";
	private static final MissingSbomMode OMIT_MODE = MissingSbomMode.OMIT;
	private static final BomStructureType FLAT = BomStructureType.FLAT;
	private static final RebomOptions OMIT = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, FLAT)
			.withMissingSbomMode(OMIT_MODE);

	/** What ReleaseService logged during the test, captured on its own logger. */
	private final List<LogEvent> logged = Collections.synchronizedList(new ArrayList<>());
	private Logger releaseServiceLog;
	private AbstractAppender capture;
	private Level previousLevel;

	@BeforeEach
	void wirePublicationAndCaptureLog() {
		// The publication reads the merged document and applies the export metadata seam; both are mocked
		// here, so only the merge and the cache are under test (as ReleaseServicePublicationMergeTest does).
		when(rebomService.findBomByIdJson(any(), any()))
				.thenAnswer(inv -> Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}"));
		ReflectionTestUtils.setField(svc, "supportInjectionService", mock(SupportInjectionService.class));

		releaseServiceLog = (Logger) LogManager.getLogger(ReleaseService.class);
		capture = new AbstractAppender("missing-sbom-mode-capture-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				logged.add(event.toImmutable());
			}
		};
		capture.start();
		releaseServiceLog.addAppender(capture);
		// Attach first, then open the logger to INFO: the INFO line of the publication is under test, and a
		// level set before the first appender is reset when the logger gets its own configuration.
		previousLevel = releaseServiceLog.getLevel();
		Configurator.setLevel(ReleaseService.class.getName(), Level.INFO);
	}

	@AfterEach
	void releaseLog() {
		Configurator.setLevel(ReleaseService.class.getName(), previousLevel);
		releaseServiceLog.removeAppender(capture);
		capture.stop();
	}

	// ---- drivers ----

	/** An unforced OMIT request in a fresh context, as the publication's merge is (but unforced). */
	private UUID omit() throws Exception {
		return svc.matchOrGenerateSingleBomForRelease(product, OMIT, false, null, WU, null, null).orElseThrow();
	}

	/**
	 * The TEA publication merge. Its sources are {RELEASE} (a singleton set is the single-value filter), so
	 * its options are the plain request's and the base's mocks need no deliverables or source code entries.
	 */
	private void publish() throws Exception {
		svc.generateMergedBomForPublication(product, new PublicationMergeRequest(java.util.Set.of(ArtifactBelongsTo.RELEASE),
				false, false, FLAT, null, ExportMetadataOptions.callerSilent()), WU);
	}

	/** The internal export of the same knobs: a placeholder request. */
	private UUID export() throws Exception {
		return svc.getReleaseBomId(PRODUCT_RELEASE, false, false, false, ArtifactBelongsTo.RELEASE, FLAT, WU, null);
	}

	private void withBomAndBare() {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), componentRelease(BARE, "3.0.0"));
	}

	private void reset() {
		rows.clear();
		merges.clear();
		rowWrites.clear();
	}

	private static MissingSbomComponent missing(UUID release, String version, MissingSbomReason reason) {
		return new MissingSbomComponent(release.toString(), "component", version, "library", "Example Org",
				"Example Org", null, null, null, null, reason);
	}

	private static RebomOptions omitRow() {
		return rowOptions(FLAT).withMissingSbomMode(OMIT_MODE);
	}

	private List<String> messages(Level level, String prefix) {
		synchronized (logged) {
			return logged.stream().filter(e -> e.getLevel() == level)
					.map(e -> e.getMessage().getFormattedMessage())
					.filter(m -> m.startsWith(prefix)).toList();
		}
	}

	private List<String> omittedLines() {
		return messages(Level.INFO, "TEA publication of release");
	}

	// 1
	@Test
	void anOmitMergeLeavesTheBareChildOutAndSendsNoPlaceholder() throws Exception {
		withBomAndBare();

		UUID served = omit();

		assertEquals(1, productMerges().size(), merges.toString());
		Merge productMerge = productMerges().get(0);
		assertEquals(productMerge.merged(), served);
		assertEquals(1, componentMerges().size(), merges.toString());
		assertEquals(List.of(componentMerges().get(0).merged()), productMerge.bomIds(), "the bare child is no input");
		assertNull(productMerge.options().missingSbomComponents(), "no placeholder is sent");
		String sent = Utils.OM.writeValueAsString(productMerge.options());
		assertFalse(sent.contains("missingSbomComponents"), sent);
		assertFalse(sent.contains("missingSbomMode"), sent);
		assertEquals(List.of(new ReleaseBom(served, omitRow())), rows.get(PRODUCT_RELEASE));
		String row = Utils.OM.writeValueAsString(rows.get(PRODUCT_RELEASE).get(0));
		assertTrue(row.contains("\"missingSbomMode\":\"OMIT\""), row);
	}

	// 2
	@Test
	void thePlaceholderExportOfTheSameProductListsTheBareChild() throws Exception {
		withBomAndBare();

		UUID served = export();

		assertEquals(1, productMerges().size(), merges.toString());
		assertEquals(List.of(missing(BARE, "3.0.0", MissingSbomReason.NO_SBOM_ARTIFACT)),
				productMerges().get(0).options().missingSbomComponents());
		assertEquals(List.of(new ReleaseBom(served, rowOptions(FLAT))), rows.get(PRODUCT_RELEASE));
		String row = Utils.OM.writeValueAsString(rows.get(PRODUCT_RELEASE).get(0));
		assertFalse(row.contains("missingSbomMode"), row);
	}

	// 3
	@Test
	void theTwoDocumentsAreCachedUnderDifferentKeysAndEachServesItsOwn() throws Exception {
		withBomAndBare();

		UUID firstOmit = omit();
		UUID firstExport = export();
		UUID secondOmit = omit();
		UUID secondExport = export();

		assertEquals(2, productMerges().size(), "one product merge per mode, none on the repeats: " + merges);
		assertNotEquals(firstOmit, firstExport);
		assertEquals(firstOmit, secondOmit, "the OMIT row serves the OMIT request");
		assertEquals(firstExport, secondExport, "the placeholder row serves the export");
		List<ReleaseBom> stored = rows.get(PRODUCT_RELEASE);
		assertEquals(2, stored.size(), stored.toString());
		assertEquals(List.of(new ReleaseBom(firstOmit, omitRow()), new ReleaseBom(firstExport, rowOptions(FLAT))), stored);
		assertEquals(2, rowWrites.get(PRODUCT_RELEASE));
	}

	// 4
	@Test
	void aPlaceholderRowIsNeverServedToAnOmitRequestNorAnOmitRowToAnExport() throws Exception {
		withBomAndBare();
		UUID cached = UUID.randomUUID();
		storeRow(PRODUCT_RELEASE, cached, rowOptions(FLAT));

		UUID omitted = omit();

		assertNotEquals(cached, omitted, "a placeholder row does not serve an OMIT request");
		assertEquals(1, productMerges().size(), merges.toString());
		assertEquals(List.of(new ReleaseBom(cached, rowOptions(FLAT)), new ReleaseBom(omitted, omitRow())),
				rows.get(PRODUCT_RELEASE), "the placeholder row is kept beside the new OMIT row");

		reset();
		UUID cachedOmit = UUID.randomUUID();
		storeRow(PRODUCT_RELEASE, cachedOmit, omitRow());

		UUID exported = export();

		assertNotEquals(cachedOmit, exported, "an OMIT row does not serve the export");
		assertEquals(1, productMerges().size(), merges.toString());
		assertEquals(List.of(new ReleaseBom(cachedOmit, omitRow()), new ReleaseBom(exported, rowOptions(FLAT))),
				rows.get(PRODUCT_RELEASE), "the OMIT row is kept beside the new placeholder row");
	}

	// 5
	@Test
	void aRowStoredBeforeTheModeExistedIsNotServedToEitherModeAndIsReplacedOnce() throws Exception {
		withBomAndBare();
		UUID old = UUID.randomUUID();
		storeRow(PRODUCT_RELEASE, old, rowOptions(FLAT).withMergeVersion(9));

		UUID exported = export();

		assertNotEquals(old, exported);
		assertEquals(List.of(new ReleaseBom(exported, rowOptions(FLAT))), rows.get(PRODUCT_RELEASE),
				"the row at 9 is replaced by the export's row at MERGE_VERSION, without the field");
		UUID omitted = omit();
		assertNotEquals(old, omitted);
		assertEquals(List.of(new ReleaseBom(exported, rowOptions(FLAT)), new ReleaseBom(omitted, omitRow())),
				rows.get(PRODUCT_RELEASE));

		reset();
		storeRow(PRODUCT_RELEASE, old, rowOptions(FLAT).withMergeVersion(9));

		UUID omittedFirst = omit();

		assertNotEquals(old, omittedFirst);
		assertEquals(List.of(new ReleaseBom(old, rowOptions(FLAT).withMergeVersion(9)),
				new ReleaseBom(omittedFirst, omitRow())), rows.get(PRODUCT_RELEASE),
				"the old row is not the OMIT request's row, so it stays");
		UUID exportedNext = export();
		assertNotEquals(old, exportedNext, "a row at 9 serves no request");
		assertEquals(List.of(new ReleaseBom(omittedFirst, omitRow()), new ReleaseBom(exportedNext, rowOptions(FLAT))),
				rows.get(PRODUCT_RELEASE), "the next export replaces the old row");
	}

	// 6
	@Test
	void aChildsPlaceholderRowIsNotReadByAnOmitMergeAndItsOmitRowIsNotReadByAnExport() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null));
		UUID childCached = UUID.randomUUID();
		storeRow(WITH_BOM, childCached, rowOptions(FLAT));

		omit();

		assertEquals(1, componentMerges().size(), "the child's placeholder row does not serve an OMIT merge: " + merges);
		UUID childOmit = componentMerges().get(0).merged();
		assertEquals(List.of(childOmit), productMerges().get(0).bomIds());
		assertEquals(List.of(new ReleaseBom(childCached, rowOptions(FLAT)), new ReleaseBom(childOmit, omitRow())),
				rows.get(WITH_BOM), "the child's OMIT row is stored beside its placeholder row");

		export();

		assertEquals(1, componentMerges().size(), "the export is served by the child's placeholder row: " + merges);
		assertEquals(List.of(childCached), productMerges().get(1).bomIds());

		// The mirror: the child holds only its OMIT row.
		reset();
		UUID childCachedOmit = UUID.randomUUID();
		storeRow(WITH_BOM, childCachedOmit, omitRow());

		export();

		assertEquals(1, componentMerges().size(), "the child's OMIT row does not serve the export: " + merges);
		UUID childPlaceholder = componentMerges().get(0).merged();
		assertEquals(List.of(childPlaceholder), productMerges().get(0).bomIds());
		assertEquals(List.of(new ReleaseBom(childCachedOmit, omitRow()), new ReleaseBom(childPlaceholder, rowOptions(FLAT))),
				rows.get(WITH_BOM));

		omit();

		assertEquals(1, componentMerges().size(), "the OMIT merge is served by the child's OMIT row: " + merges);
		assertEquals(List.of(childCachedOmit), productMerges().get(1).bomIds());
	}

	// 7
	@Test
	void aProductWithNoSbomAnywhereAnswersNoSbomArtifactsUnderOmit() throws Exception {
		unwindsTo(componentRelease(BARE, "3.0.0"));

		MergedSbomUnavailableException e = assertThrows(MergedSbomUnavailableException.class, this::publish);

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertEquals(1, e.children().size(), e.children().toString());
		assertEquals(BARE, e.children().get(0).release());
		assertEquals(ChildBomStatus.NO_SBOM, e.children().get(0).status());
		assertTrue(e.getMessage().startsWith("No SBOMs found: product release product 26.10.35"), e.getMessage());
		assertEquals(List.of(), merges);
		assertEquals(java.util.Map.of(), rowWrites, "no row written");
	}

	// 7, the sibling: the product's own SBOM is merged alone under the product root
	@Test
	void aProductsOwnSbomIsMergedAloneWhenItsOnlyChildIsBareUnderOmit() throws Exception {
		unwindsTo(componentRelease(BARE, "3.0.0"));
		UUID artifact = UUID.randomUUID();
		UUID ownBom = UUID.randomUUID();
		ArtifactData ad = mock(ArtifactData.class);
		when(ad.getInternalBom()).thenReturn(new InternalBom(ownBom, ArtifactBelongsTo.RELEASE));
		when(ad.getTags()).thenReturn(List.of());
		when(artifactService.getArtifactData(artifact)).thenReturn(java.util.Optional.of(ad));
		when(product.getArtifacts()).thenReturn(new ArrayList<>(List.of(artifact)));

		publish();

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(1, productMerges().size(), merges.toString());
		assertEquals(List.of(ownBom), productMerges().get(0).bomIds());
		assertNull(productMerges().get(0).options().missingSbomComponents());
	}

	// 8
	@Test
	void aFailedChildIsChildMergeFailedUnderOmitToo() throws Exception {
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), componentRelease(BARE, "3.0.0"));
		List<List<UUID>> attempts = Collections.synchronizedList(new ArrayList<>());
		doAnswer(inv -> {
			attempts.add(List.copyOf(inv.<List<UUID>>getArgument(0)));
			throw new RelizaException("rebom merge call failed: down");
		}).when(rebomService).mergeAndStoreBoms(anyList(), any(), any());
		ReleaseService.MergeContext ctx = new ReleaseService.MergeContext();

		MergedSbomUnavailableException e = assertThrows(MergedSbomUnavailableException.class,
				() -> svc.matchOrGenerateSingleBomForRelease(product, OMIT, false, null, WU, null, null, ctx));

		assertEquals(Reason.CHILD_MERGE_FAILED, e.reason());
		assertTrue(e.children().stream().anyMatch(c -> c.release().equals(WITH_BOM) && c.status() == ChildBomStatus.FAILED),
				e.children().toString());
		assertEquals(1, attempts.size(), "the child's merge only; no product merge: " + attempts);
		assertTrue(rows.isEmpty(), "nothing cached: " + rows);
		assertTrue(rowWrites.isEmpty(), rowWrites.toString());
		assertTrue(ctx.omitted.isEmpty(), "a FAILED child is never left out: the whole merge is refused");

		// A second request in the same context is refused the same way. A failed merge is not recorded in
		// the context (SCORE-15 ADR-12 records results, and a throw is none), so the child is tried again;
		// still no product merge and no row.
		MergedSbomUnavailableException again = assertThrows(MergedSbomUnavailableException.class,
				() -> svc.matchOrGenerateSingleBomForRelease(product, OMIT, false, null, WU, null, null, ctx));
		assertEquals(Reason.CHILD_MERGE_FAILED, again.reason());
		assertEquals(2, attempts.size(), attempts.toString());
		attempts.forEach(ids -> assertEquals(1, ids.size(), "only component merges were attempted: " + attempts));
		assertTrue(rowWrites.isEmpty(), rowWrites.toString());
	}

	// 9
	@Test
	void aCycleEntryIsLeftOutUnderOmitAsUnderPlaceholder() throws Exception {
		ReleaseData source = withBom(WITH_BOM, "3.0.0", null);
		ReleaseData p2 = innerProductRelease(P2, P2_VERSION);
		unwindsTo(p2, source);
		unwinds(p2, product, source);
		ReleaseService.MergeContext ctx = new ReleaseService.MergeContext();

		UUID served = svc.matchOrGenerateSingleBomForRelease(product, OMIT, false, null, WU, null, null, ctx).orElseThrow();

		assertEquals(2, productMerges().size(), merges.toString());
		assertEquals(1, componentMerges().size(), merges.toString());
		UUID sourceMerge = componentMerges().get(0).merged();
		Merge p2Merge = productMerges().stream().filter(m -> P2_VERSION.equals(m.options().version())).findFirst().orElseThrow();
		assertEquals(List.of(sourceMerge), p2Merge.bomIds(), "the cycle entry is no input of P2");
		Merge outer = productMerges().stream().filter(m -> m.merged().equals(served)).findFirst().orElseThrow();
		assertEquals(List.of(p2Merge.merged(), sourceMerge), outer.bomIds());
		productMerges().forEach(m -> assertNull(m.options().missingSbomComponents(), m.toString()));
		assertTrue(ctx.omitted.isEmpty(), "a cycle entry is not a release without an SBOM: " + ctx.omitted);
		assertEquals(1, messages(Level.ERROR, "Release graph cycle").size(), logged.toString());
	}

	// 10
	@Test
	void thePublicationLogsTheOmittedChildrenOnce() throws Exception {
		withBomAndBare();

		publish();

		List<String> lines = omittedLines();
		assertEquals(1, lines.size(), logged.toString());
		assertTrue(lines.get(0).contains(BARE.toString()), lines.get(0));
		assertTrue(lines.get(0).contains("component 3.0.0"), lines.get(0));
		assertTrue(lines.get(0).contains(": 1 component releases"), lines.get(0));

		logged.clear();
		export();

		assertEquals(List.of(), omittedLines(), "the export leaves nothing out and logs no such line");
	}

	// 10, TEA-15 F-9: the labels are org-member text and go through LogSafe.text
	@Test
	void theOmittedLabelsAreLoggedSafely() throws Exception {
		ReleaseData bare = componentRelease(BARE, "3.0.0\nFORGED line");
		unwindsTo(withBom(WITH_BOM, "1.0.0", null), bare);

		publish();

		List<String> lines = omittedLines();
		assertEquals(1, lines.size(), logged.toString());
		assertFalse(lines.get(0).contains("\n"), lines.get(0));
		assertTrue(lines.get(0).contains("component 3.0.0?FORGED line (" + BARE + ")"), lines.get(0));
	}

	// 11
	@Test
	void theRebomRequestNeverCarriesTheMode() throws Exception {
		withBomAndBare();

		omit();

		merges.forEach(m -> assertNull(m.options().missingSbomMode(), "the 15-argument request carries no mode: " + m));
	}

	// TEA-15 F-8 (security review round 4): an outer publication never consumes a nested product's
	// placeholder row, the one route by which a placeholder could reach a public document
	@Test
	void aNestedProductsPlaceholderRowIsNeverConsumedByAnOuterPublish() throws Exception {
		ReleaseData withBom = withBom(WITH_BOM, "1.0.0", null);
		ReleaseData bare = componentRelease(BARE, "3.0.0");
		ReleaseData inner = innerProductRelease(INNER, "1.1.0");
		unwinds(inner, withBom, bare);
		unwindsTo(inner, withBom, bare);
		UUID innerPlaceholder = UUID.randomUUID();
		storeRow(INNER, innerPlaceholder, rowOptions(FLAT));

		publish();

		assertEquals(2, productMerges().size(), "the inner product is merged under OMIT, then the outer: " + merges);
		Merge innerMerge = productMerges().get(0);
		Merge outerMerge = productMerges().get(1);
		assertEquals("1.1.0", innerMerge.options().version());
		assertFalse(outerMerge.bomIds().contains(innerPlaceholder), "the inner placeholder row is never consumed");
		assertEquals(List.of(innerMerge.merged(), componentMerges().get(0).merged()), outerMerge.bomIds());
		productMerges().forEach(m -> assertNull(m.options().missingSbomComponents(),
				"no placeholder at any depth: " + m));
		assertEquals(List.of(new ReleaseBom(innerPlaceholder, rowOptions(FLAT)), new ReleaseBom(innerMerge.merged(), omitRow())),
				rows.get(INNER), "the inner product's OMIT row is written beside its placeholder row");
		List<String> lines = omittedLines();
		assertEquals(1, lines.size(), logged.toString());
		assertTrue(lines.get(0).contains(": 1 component releases"), "a release left out at two levels is named once: "
				+ lines.get(0));
	}
}
