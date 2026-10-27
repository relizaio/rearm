/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseRebom;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.RebomService.BomStructureType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * SCORE-71 T-14 (ADR-1) against the test database: the invalidation statement empties the stored
 * rows of the release and of every release of its organization above it, transitively, moves the
 * revision of every record it touches, already empty or not, and touches nothing else.
 */
public class ReleaseRebomInvalidationIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static final RebomOptions ROW = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, BomStructureType.FLAT)
			.withMergeVersion(ReleaseService.MERGE_VERSION);

	@Autowired private ReleaseRebomService releaseRebomService;
	@Autowired private PlatformTransactionManager transactionManager;
	@PersistenceContext private EntityManager em;

	private void row(UUID release) {
		releaseRebomService.setReboms(stored(release), List.of(new ReleaseBom(UUID.randomUUID(), ROW)), WU);
	}

	private List<ReleaseBom> rowsOf(UUID release) {
		return releaseRebomService.getReleaseBoms(stored(release));
	}

	private ReleaseRebom record(UUID release) {
		return releaseRebomService.getReleaseRebom(org.getUuid(), release).orElseThrow();
	}

	private void assertEmptied(UUID... releases) {
		for (UUID r : releases) assertEquals(List.of(), rowsOf(r), "rows of " + r + " kept");
	}

	private void assertKept(UUID... releases) {
		for (UUID r : releases) assertEquals(1, rowsOf(r).size(), "rows of " + r + " emptied");
	}

	private record Tree(UUID a, UUID s, UUID u, UUID p1, UUID p2, UUID p3, UUID q) {}

	private Tree tree() throws Exception {
		UUID a = componentRelease("pkg:generic/t14/a@1.0.0").release();
		UUID s = componentRelease("pkg:generic/t14/s@1.0.0").release();
		UUID u = componentRelease("pkg:generic/t14/u@1.0.0").release();
		UUID p1 = product(List.of(a, s));
		UUID p2 = product(List.of(p1));
		UUID p3 = product(List.of(p2));
		UUID q = product(List.of(s));
		Tree t = new Tree(a, s, u, p1, p2, p3, q);
		for (UUID r : List.of(a, s, u, p1, p2, p3, q)) row(r);
		return t;
	}

	// (a)
	@Test
	void theReleaseAndEveryProductAboveItLoseTheirRowsAndNothingElseDoes() throws Exception {
		Tree t = tree();
		ZonedDateTime before = ZonedDateTime.now().minusSeconds(1);
		assertEquals(4, releaseService.invalidateMergedSbomRows(stored(t.a())));
		assertEmptied(t.a(), t.p1(), t.p2(), t.p3());
		assertKept(t.s(), t.u(), t.q());
		assertEquals(1, record(t.a()).getRevision());
		assertFalse(record(t.a()).getLastUpdatedDate().isBefore(before));
	}

	// (b)
	@Test
	void aSecondCallMovesTheRevisionsAgainAndEmptiesNothingMore() throws Exception {
		Tree t = tree();
		assertEquals(4, releaseService.invalidateMergedSbomRows(stored(t.a())));
		ZonedDateTime before = ZonedDateTime.now().minusSeconds(1);
		// the same four existing records are touched again: there is no "> 0" predicate (F-27)
		assertEquals(4, releaseService.invalidateMergedSbomRows(stored(t.a())));
		assertEmptied(t.a(), t.p1(), t.p2(), t.p3());
		assertKept(t.s(), t.u(), t.q());
		assertEquals(2, record(t.a()).getRevision());
		assertFalse(record(t.a()).getLastUpdatedDate().isBefore(before));
	}

	// (c)
	@Test
	void aReleaseWithoutARecordIsNoError() throws Exception {
		UUID w = componentRelease("pkg:generic/t14/c@1.0.0").release();
		assertEquals(0, releaseService.invalidateMergedSbomRows(stored(w)));
		assertEquals(List.of(), rowsOf(w));
	}

	// (d)
	@Test
	void theComponentFormSeedsEveryReleaseOfTheComponent() throws Exception {
		UUID c1 = product(List.of(componentRelease("pkg:generic/t14/d1@1.0.0").release()));
		ReleaseData c1d = stored(c1);
		UUID c = c1d.getComponent();
		UUID c2 = ossReleaseService.createRelease(ReleaseDto.builder().component(c).branch(c1d.getBranch())
				.org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("2.1." + Math.abs(UUID.randomUUID().hashCode() % 100000))
				.parentReleases(List.of(ParentRelease.minimalParentReleaseFactory(
						componentRelease("pkg:generic/t14/d2@1.0.0").release())))
				.build(), WU).getUuid();
		UUID top = product(List.of(c1));
		UUID v = product(List.of(componentRelease("pkg:generic/t14/v@1.0.0").release()));
		for (UUID r : List.of(c1, c2, top, v)) row(r);
		assertEquals(3, releaseService.invalidateMergedSbomRowsOfComponent(getComponentService.getComponentData(c).orElseThrow()));
		assertEmptied(c1, c2, top);
		assertKept(v);
	}

	// (e)
	@Test
	void anEmptySeedListRunsNothing() {
		assertEquals(0, releaseRebomService.invalidateRowsOfAndAbove(org.getUuid(), List.of()));
	}

	// (f)
	@Test
	void anotherOrganizationsUuidMatchesNothing() throws Exception {
		Organization other = testInitializer.obtainOrganization();
		UUID w = componentRelease("pkg:generic/t14/f@1.0.0").release();
		row(w);
		// Credited to the three organization predicates removed together. Each alone is backed by the
		// other two: with the seed's removed, w enters "above" but the UPDATE's own org predicate
		// (other) excludes its record; with the UPDATE's removed, the seed admits nothing; the
		// recursive term's cannot add a release of another organization while the seed is empty.
		assertEquals(0, releaseRebomService.invalidateRowsOfAndAbove(other.getUuid(), List.of(w)));
		assertKept(w);
	}

	// (g)
	@Test
	void aProductSeededThroughTwoPathsIsCountedOnce() throws Exception {
		UUID a = componentRelease("pkg:generic/t14/g@1.0.0").release();
		UUID p = product(List.of(a));
		UUID d = product(List.of(a, p));
		for (UUID r : List.of(a, p, d)) row(r);
		// a count, credited with no mutant: under UNION ALL the acyclic diamond still counts 3 (F-24)
		assertEquals(3, releaseService.invalidateMergedSbomRows(stored(a)));
		assertEmptied(a, p, d);
	}

	// (h)
	@Test
	void aCycleAboveTheReleaseEndsTheWalk() throws Exception {
		UUID a = componentRelease("pkg:generic/t14/h@1.0.0").release();
		UUID p = product(List.of(a));
		UUID q = product(List.of(p));
		// p's parents closed into a cycle past checkCircularDependency: saveRelease runs no cycle check
		Release pr = sharedReleaseService.getRelease(p).orElseThrow();
		ReleaseData pd = ReleaseData.dataFromRecord(pr);
		pd.setParentReleases(List.of(ParentRelease.minimalParentReleaseFactory(a), ParentRelease.minimalParentReleaseFactory(q)));
		ossReleaseService.saveRelease(pr, pd, WU, false);
		for (UUID r : List.of(a, p, q)) row(r);
		// under SET LOCAL statement_timeout, so a walk that never ends fails instead of hanging
		Integer n = new TransactionTemplate(transactionManager).execute(s -> {
			em.createNativeQuery("SET LOCAL statement_timeout = '5s'").executeUpdate();
			return releaseService.invalidateMergedSbomRows(stored(a));
		});
		assertEquals(3, n);
		assertEmptied(a, p, q);
	}
}
