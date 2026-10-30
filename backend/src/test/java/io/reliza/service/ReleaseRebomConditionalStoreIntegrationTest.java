/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseRebomService.RowsRead;

/**
 * SCORE-71 T-15 (ADR-11; F-23, F-27) against the test database through the real ReleaseService: a
 * merge in flight when an invalidation commits stores nothing, because the store is conditioned on
 * the record revision the request first read; the invalidation moves that revision on every record
 * it touches, already empty or not, so the rebuild of an emptied record is fenced too.
 */
public class ReleaseRebomConditionalStoreIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final RebomOptions ROW = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, BomStructureType.FLAT)
			.withMergeVersion(ReleaseService.MERGE_VERSION);

	@Autowired private ReleaseRebomService releaseRebomService;
	@Autowired private AuditService auditService;

	private List<ReleaseBom> rowsOf(UUID release) {
		return releaseRebomService.getReleaseBoms(stored(release));
	}

	private ReleaseBom rowIn(UUID release, MissingSbomMode mode) {
		List<ReleaseBom> of = rowsOf(release).stream()
				.filter(rb -> rb.rebomMergeOptions().effectiveMissingSbomMode() == mode).toList();
		assertEquals(1, of.size(), rowsOf(release).toString());
		return of.get(0);
	}

	private int revisionOf(UUID release) {
		return releaseRebomService.getReleaseRebom(org.getUuid(), release).orElseThrow().getRevision();
	}

	private UUID export(UUID release) throws Exception {
		return releaseService.getReleaseBomId(release, false, false, false, null, BomStructureType.FLAT, WU, null);
	}

	private record Rig(UUID a, UUID b, UUID product, UUID first, UUID second) {}

	/** (a)'s sequence: the product's merge runs while an invalidation below it commits. */
	private Rig mergeInFlightAcrossAnInvalidation() throws Exception {
		UUID a = componentRelease("pkg:generic/t15/a@1.0.0").release();
		UUID b = componentRelease("pkg:generic/t15/b@1.0.0").release();
		UUID product = product(List.of(a, b));
		UUID first = export(product);
		assertEquals(0, revisionOf(product));
		// the product's record made a miss without touching a's or b's: a row of an older merge version
		releaseRebomService.setReboms(stored(product),
				List.of(new ReleaseBom(UUID.randomUUID(), ROW.withMergeVersion(ReleaseService.MERGE_VERSION - 1))), WU);
		assertEquals(1, revisionOf(product));
		AtomicInteger fired = new AtomicInteger();
		doAnswer(inv -> {
			if (fired.getAndIncrement() == 0) releaseService.invalidateMergedSbomRows(stored(a));
			return mergeAnswer.answer(inv);
		}).when(rebomService).mergeAndStoreBoms(anyList(), any(), any());
		UUID second = asRequest(product, () -> export(product));
		assertEquals(1, fired.get(), "one merge: a and b were served from their rows");
		doAnswer(mergeAnswer).when(rebomService).mergeAndStoreBoms(anyList(), any(), any());
		return new Rig(a, b, product, first, second);
	}

	// (a)
	@Test
	void aMergeInFlightAcrossAnInvalidationStoresNothing() throws Exception {
		Rig rig = mergeInFlightAcrossAnInvalidation();
		assertNotEquals(rig.first(), rig.second(), "the merge ran and its document is served");
		assertEquals(List.of(), rowsOf(rig.product()), "the row built from the former state was stored");
		assertEquals(List.of(), rowsOf(rig.a()));
		assertEquals(1, rowsOf(rig.b()).size());
		assertEquals(2, revisionOf(rig.product()), "the refused write moved nothing");
	}

	// (b)
	@Test
	void theNextRequestRebuildsAndStores() throws Exception {
		Rig rig = mergeInFlightAcrossAnInvalidation();
		UUID third = export(rig.product());
		assertNotEquals(rig.second(), third);
		assertEquals(third, rowIn(rig.product(), MissingSbomMode.PLACEHOLDER).rebomId());
		assertEquals(1, rowsOf(rig.a()).size(), "a is rebuilt inside the product's generation");
		assertEquals(3, revisionOf(rig.product()));
	}

	// (c)
	@Test
	void aFirstStoreInsertsOneRecord() throws Exception {
		UUID c = componentRelease("pkg:generic/t15/c@1.0.0").release();
		assertFalse(releaseRebomService.readRows(stored(c)).seenRecord());
		export(c);
		RowsRead read = releaseRebomService.readRows(stored(c));
		assertTrue(read.seenRecord());
		assertEquals(0, read.revision());
		assertEquals(1, read.rows().size());
		assertTrue(releaseRebomService.getReleaseRebom(org.getUuid(), c).isPresent());
	}

	// (d)
	@Test
	void aStoreAgainstAMovedRevisionIsRefused() throws Exception {
		UUID d = componentRelease("pkg:generic/t15/d@1.0.0").release();
		releaseRebomService.setReboms(stored(d), List.of(new ReleaseBom(UUID.randomUUID(), ROW)), WU);
		RowsRead seen = releaseRebomService.readRows(stored(d));
		assertEquals(0, seen.revision());
		assertEquals(1, seen.rows().size());
		assertEquals(1, releaseService.invalidateMergedSbomRows(stored(d)));

		List<ReleaseBom> rows = List.of(new ReleaseBom(UUID.randomUUID(), ROW));
		assertEquals(Optional.empty(), releaseRebomService.storeRowsIfUnchanged(stored(d), rows, seen, WU));
		assertEquals(List.of(), rowsOf(d));
		assertEquals(1, revisionOf(d));

		RowsRead seen2 = releaseRebomService.readRows(stored(d));
		assertEquals(1, seen2.revision());
		assertEquals(List.of(), seen2.rows());
		Optional<RowsRead> next = releaseRebomService.storeRowsIfUnchanged(stored(d), rows, seen2, WU);
		assertTrue(next.isPresent());
		assertEquals(2, next.get().revision());
		assertEquals(1, rowsOf(d).size());
		assertEquals(2, revisionOf(d));
		assertTrue(auditService.getAuditRevision(seen2.record(), TableName.RELEASE_REBOMS, 1).isPresent(),
				"the state replaced is audited");
	}

	// (e)
	@Test
	void aSecondChangeDuringTheRebuildOfAnEmptiedRecordIsNotWrittenOver() throws Exception {
		UUID a = componentRelease("pkg:generic/t15/ea@1.0.0").release();
		UUID b = componentRelease("pkg:generic/t15/eb@1.0.0").release();
		UUID product = product(List.of(a, b));
		UUID first = export(product);
		assertEquals(0, revisionOf(product));
		assertEquals(2, releaseService.invalidateMergedSbomRows(stored(a)));
		assertEquals(1, revisionOf(product));
		assertEquals(List.of(), rowsOf(product));

		// the second change fires inside the product's merge, not a's (merges map to releases by version)
		String productVersion = stored(product).getVersion();
		AtomicInteger fired = new AtomicInteger();
		doAnswer(inv -> {
			RebomOptions o = inv.getArgument(1);
			if (productVersion.equals(o.version()) && fired.getAndIncrement() == 0) {
				releaseService.invalidateMergedSbomRows(stored(b));
			}
			return mergeAnswer.answer(inv);
		}).when(rebomService).mergeAndStoreBoms(anyList(), any(), any());
		UUID second = asRequest(product, () -> export(product));

		assertNotEquals(first, second, "the merge ran and its document is served");
		assertEquals(1, fired.get());
		assertEquals(List.of(), rowsOf(product), "the row built with b's former node was stored");
		assertEquals(List.of(), rowsOf(b));
		assertEquals(1, rowsOf(a).size(), "a is rebuilt and stored inside the product's generation");
		assertEquals(2, revisionOf(product), "moved by the second invalidation; the refused write moved nothing");
	}
}
