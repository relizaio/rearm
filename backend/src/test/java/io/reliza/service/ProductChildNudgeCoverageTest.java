/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.FlowControl;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.ws.SchedulingConfig;

/**
 * What a product at the backoff cap nudges and what it leaves alone (TPF-68, mutation round, the mutant
 * tester's T-2 to T-6): every stuck child and not only the first, nothing for a product that is not
 * scannable, nothing of another organization, a child of the external-components organization, and a child
 * whose lastScanned is strictly after its last update. Built on the rig of
 * {@link ProductChildNudgeIntegrationTest}; scheduling is off so no tick computes a fixture mid-case.
 */
@TestPropertySource(properties = SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false")
public class ProductChildNudgeCoverageTest extends ReleaseScanSelfHealTestBase {

	private static final DateTimeFormatter PG_TIMESTAMPTZ = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd HH:mm:ss")
			.optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
			.appendOffset("+HH:mm", "Z").toFormatter();

	/** A child with no firstScanned that has settled: lastScanned and last_updated_date from one now(). */
	private UUID settledUnscanned(UUID org) {
		UUID c = saveScanPendingRelease(org, new ArrayList<>()).getUuid();
		releaseRepository.touchLastScanned(c);
		return c;
	}

	private List<Map<String, Object>> parents(UUID... children) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (UUID c : children) out.addAll(parentReleasesOf(c));
		return out;
	}

	/** A product of the organization over these children, at {@code failures} incomplete attempts, its fence lapsed. */
	private UUID product(UUID org, int failures, UUID... children) {
		Release p = saveScanPendingRelease(org, parents(children));
		String lapsed = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).format(PG_TIMESTAMPTZ);
		p.setFlowControl(new FlowControl(null, null, null, null, null, null, null, null, lapsed, failures,
				null, null, null, null, null, null, null, null));
		return releaseRepository.save(p).getUuid();
	}

	private void compute(UUID release) {
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(releaseRepository.findById(release).orElseThrow());
	}

	private long nudges() {
		return ((AtomicLong) ReflectionTestUtils.getField(releaseMetricsComputeService, "childNudges")).get();
	}

	private Instant updated(UUID release) {
		return releaseRepository.findById(release).orElseThrow().getLastUpdatedDate().toInstant();
	}

	@Test
	public void everyStuckChildIsNudgedAndAScannedOneIsLeftAlone() {
		Organization org = testInitializer.obtainOrganization();
		UUID a = settledUnscanned(org.getUuid());
		UUID b = settledUnscanned(org.getUuid());
		UUID scanned = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		markScanned(scanned, 1_700_000_000.0);
		releaseRepository.touchLastScanned(scanned);
		Instant scannedBefore = updated(scanned);
		UUID p = product(org.getUuid(), 11, a, scanned, b);
		long before = nudges();

		compute(p);
		assertTrue(eligibleByUpdate(a), "the first stuck child is back in the pool");
		assertTrue(eligibleByUpdate(b), "and so is the second");
		assertFalse(eligibleByUpdate(scanned));
		assertEquals(scannedBefore, updated(scanned), "the scanned child is not touched");
		assertEquals(before + 2, nudges(), "one nudge per stuck child");
	}

	@Test
	public void aProductThatIsNotScannableNeverNudges() {
		for (String lifecycle : new String[] {"CANCELLED", "DRAFT", "PENDING", "REJECTED"}) {
			Organization org = testInitializer.obtainOrganization();
			UUID child = settledUnscanned(org.getUuid());
			UUID p = product(org.getUuid(), 11, child);
			setLifecycle(p, lifecycle);
			Instant childBefore = updated(child);
			long before = nudges();

			compute(p);
			assertEquals(childBefore, updated(child), lifecycle + ": the child is untouched");
			assertFalse(eligibleByUpdate(child), lifecycle);
			assertEquals(before, nudges(), lifecycle + ": nothing counted");
		}
	}

	@Test
	public void aChildOfAnotherOrganizationIsNeverTouched() {
		Organization own = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		UUID ownChild = settledUnscanned(own.getUuid());
		UUID foreign = settledUnscanned(other.getUuid());
		Instant foreignBefore = updated(foreign);
		UUID p = product(own.getUuid(), 11, ownChild, foreign);
		long before = nudges();

		compute(p);
		assertTrue(eligibleByUpdate(ownChild), "the own child is nudged");
		assertEquals(foreignBefore, updated(foreign), "the other organization's release is not touched");
		assertFalse(eligibleByUpdate(foreign));
		assertEquals(before + 1, nudges());
	}

	@Test
	public void aChildOfTheExternalComponentsOrganizationIsNudged() {
		Organization org = testInitializer.obtainOrganization();
		UUID external = settledUnscanned(new UUID(0, 0));
		UUID p = product(org.getUuid(), 11, external);
		long before = nudges();

		compute(p);
		assertTrue(eligibleByUpdate(external), "the shared external-components child is nudged too");
		assertEquals(before + 1, nudges());
	}

	@Test
	public void aChildSettledStrictlyAfterItsLastUpdateIsNudged() throws InterruptedException {
		Organization org = testInitializer.obtainOrganization();
		UUID child = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		releaseRepository.touchLastScanned(child);
		Release r = releaseRepository.findById(child).orElseThrow();
		Map<String, Object> metrics = new HashMap<>(r.getMetrics());
		metrics.put("lastScanned", ((Number) metrics.get("lastScanned")).doubleValue() + 1.0);
		r.setMetrics(metrics);
		releaseRepository.save(r);
		assertFalse(eligibleByUpdate(child), "settled: its last update is before its lastScanned");
		UUID p = product(org.getUuid(), 11, child);
		long before = nudges();
		Thread.sleep(1300);

		compute(p);
		assertTrue(eligibleByUpdate(child), "the usual state after a compute that stamps lastScanned in Java");
		assertEquals(before + 1, nudges());
	}

	@Test
	public void aRejectedChildIsNudgedLikeAnyOther() {
		Organization org = testInitializer.obtainOrganization();
		UUID child = settledUnscanned(org.getUuid());
		setLifecycle(child, "REJECTED");
		UUID p = product(org.getUuid(), 11, child);
		long before = nudges();

		compute(p);
		assertTrue(eligibleByUpdate(child), "the child's lifecycle does not decide whether it is nudged");
		assertEquals(before + 1, nudges());
	}
}
