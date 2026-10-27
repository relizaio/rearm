/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.FlowControl;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.ws.SchedulingConfig;

/**
 * TPF-68: a product release whose compute ends incomplete because of a child, once its backoff has
 * reached the hourly cap, re-queues each child that is settled (outside every metrics finder pool),
 * unfenced and still without firstScanned -- the state in which nothing else would ever recompute
 * that child, whatever its lifecycle (the child's own compute decides whether it can heal). Below
 * the cap, and for a fenced, in-pool or scanned child, nothing is touched.
 *
 * <p>Scheduling is off so no scheduler tick computes a fixture behind the test's back (the same
 * context as ProductForeignParentMetricsTest). {@code childNudges} lives on the shared Spring bean, so
 * every case asserts a delta, never an absolute.
 */
@TestPropertySource(properties = SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false")
public class ProductChildNudgeIntegrationTest extends ReleaseScanSelfHealTestBase {

	private static final double CHILD_FIRST_SCANNED_EPOCH = 1_700_000_000.0;
	private static final String NUDGE = "[METRICS-NUDGE]";

	/** Minimal in-memory appender, as in ProductForeignParentMetricsTest: only level and message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private CapturingAppender computeLog;

	@BeforeEach
	public void attachAppender() {
		computeLog = new CapturingAppender();
		computeLog.start();
		computeLogger().addAppender(computeLog);
	}

	@AfterEach
	public void detachAppender() {
		computeLogger().removeAppender(computeLog);
		computeLog.stop();
	}

	private static org.apache.logging.log4j.core.Logger computeLogger() {
		return (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseMetricsComputeService.class);
	}

	// ---- 4.2: the nudge fires at the cap and not before ----

	@Test
	public void belowTheCap_theSettledUnscannedChildIsNotNudged() {
		Organization org = testInitializer.obtainOrganization();
		UUID c = settledUnscannedChild(org);
		Instant seeded = lastUpdated(c);
		UUID p = productOver(org, c, 10);
		long before = childNudges();

		compute(p);

		Release product = reload(p);
		assertNull(firstScanned(product), "the product still waits on its unscanned child");
		assertEquals(11, product.getFlowControl().metricsComputeFailureCount(),
				"the product is fenced for its 11th incomplete attempt");
		assertNotNull(product.getFlowControl().metricsComputeSkipUntil());
		assertFalse(eligibleByUpdate(c), "below the cap the child stays settled, outside the pool");
		assertEquals(seeded, lastUpdated(c), "below the cap the child row is not touched");
		assertTrue(nudgeLines(p).isEmpty(), "no " + NUDGE + " line below the cap: " + nudgeLines(p));
		assertEquals(before, childNudges(), "childNudges does not move below the cap");
	}

	@Test
	public void atTheCap_theSettledUnscannedChildIsNudged_andTheNextTickHealsTheProduct() {
		Organization org = testInitializer.obtainOrganization();
		UUID c = settledUnscannedChild(org);
		UUID p = productOver(org, c, 11);
		long before = childNudges();

		compute(p);

		Release product = reload(p);
		assertNull(firstScanned(product));
		assertEquals(12, product.getFlowControl().metricsComputeFailureCount(),
				"the product is fenced for its 12th incomplete attempt, the first one at the cap");
		assertNudged(c);
		List<String> lines = nudgeLines(p);
		assertEquals(1, lines.size(), "exactly one " + NUDGE + " line for the one nudge: " + lines);
		assertTrue(lines.get(0).contains(c.toString()), "the line names the child: " + lines.get(0));
		assertEquals(before + 1, childNudges(), "childNudges rose by exactly one");

		// 4.2 case 3: the next tick's BY_UPDATE finder selects the child (the finder's exact
		// predicate, not the LIMIT-bounded list); its compute anchors firstScanned (no BOM) and the
		// child-change push re-queues the product with its fence gone.
		assertTrue(eligibleByUpdate(c), "the next BY_UPDATE tick selects the nudged child");
		compute(c);
		assertNotNull(firstScanned(reload(c)), "the child's own compute anchors firstScanned");
		assertTrue(eligibleByUpdate(p), "the child-change push puts the product back into BY_UPDATE");
		assertFenceCleared(reload(p));
	}

	@Test
	public void atTheCapAnHourLater_theChildIsNudgedAgain_untilItIsScanned() {
		Organization org = testInitializer.obtainOrganization();
		UUID c = settledUnscannedChild(org);
		UUID p = productOver(org, c, 11);
		long first = childNudges();
		compute(p);
		assertEquals(first + 1, childNudges(), "case 2's nudge");

		// The child settles again without firstScanned (not via its own compute, which would anchor
		// it): the product, still at the cap with count 12, nudges it once more.
		releaseRepository.touchLastScanned(c);
		assertFalse(eligibleByUpdate(c), "sanity: the child settled again");
		FlowControl hourLater = reload(p).getFlowControl();
		assertEquals(12, hourLater.metricsComputeFailureCount(), "the second compute reads count 12");
		assertTrue(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(hourLater), "still at the cap");
		long second = childNudges();
		compute(p);
		assertEquals(13, reload(p).getFlowControl().metricsComputeFailureCount());
		assertNudged(c);
		assertEquals(2, nudgeLines(p).size(), "one more " + NUDGE + " line: " + nudgeLines(p));
		assertEquals(second + 1, childNudges(), "childNudges rose by exactly one again");

		// Scanned and settled: the product completes and nothing is nudged.
		markScanned(c, CHILD_FIRST_SCANNED_EPOCH);
		releaseRepository.touchLastScanned(c);
		Instant settled = lastUpdated(c);
		long third = childNudges();
		compute(p);
		assertEquals(third, childNudges(), "a scanned child is never nudged");
		assertEquals(2, nudgeLines(p).size(), "no new " + NUDGE + " line: " + nudgeLines(p));
		assertEquals(settled, lastUpdated(c), "the scanned child row is not touched");
		Release product = reload(p);
		assertNotNull(firstScanned(product), "every child is scanned, so the product completes");
		assertFenceCleared(product);
	}

	// ---- 4.3 rows 1 to 3: children that are not nudged ----

	@Test
	public void atTheCap_aFencedChildSettledFirst_isNotNudged() {
		Organization org = testInitializer.obtainOrganization();
		UUID c = fencedSettledChild(org);
		assertFalse(eligibleByUpdate(c), "sanity: fenced");
		Instant seeded = lastUpdated(c);
		UUID p = productOver(org, c, 11);
		long before = childNudges();

		compute(p);

		assertNotNudged(p, c, seeded, before);
		assertFalse(eligibleByUpdate(c), "still fenced, still out of the pool");
		assertNotNull(reload(c).getFlowControl().metricsComputeSkipUntil(), "the child's fence is left alone");
		assertNull(firstScanned(reload(p)), "the product keeps waiting");
	}

	@Test
	public void atTheCap_anInPoolChildSettledFirst_isNotNudged() {
		Organization org = testInitializer.obtainOrganization();
		UUID c = inPoolSettledChild(org);
		assertTrue(eligibleByUpdate(c), "sanity: back in the BY_UPDATE pool");
		Instant seeded = lastUpdated(c);
		UUID p = productOver(org, c, 11);
		long before = childNudges();

		compute(p);

		assertNotNudged(p, c, seeded, before);
		assertTrue(eligibleByUpdate(c), "the queued child stays queued, not reordered");
	}

	@Test
	public void atTheCap_aScannedChild_letsTheProductCompleteInstead() {
		Organization org = testInitializer.obtainOrganization();
		UUID c = scannedSettledChild(org);
		Instant seeded = lastUpdated(c);
		UUID p = productOver(org, c, 11);
		long before = childNudges();

		compute(p);

		assertNotNudged(p, c, seeded, before);
		Release product = reload(p);
		assertNotNull(firstScanned(product), "the product completes");
		assertFenceCleared(product);
	}

	// ---- 4.3 row 4a: a child the nudge cannot heal is nudged, settles again without a push, and is
	// nudged again next hour ----

	@Test
	public void atTheCap_aCancelledChildWithNoBom_isNudgedAndSettlesAgainWithoutAPush() {
		unhealableChildIsNudgedAndSettlesAgain("CANCELLED");
	}

	@Test
	public void atTheCap_aPendingChildWithNoBom_isNudgedAndSettlesAgainWithoutAPush() {
		unhealableChildIsNudgedAndSettlesAgain("PENDING");
	}

	@Test
	public void atTheCap_aDraftChildWithNoBom_isNudgedAndSettlesAgainWithoutAPush() {
		unhealableChildIsNudgedAndSettlesAgain("DRAFT");
	}

	/**
	 * A child below ASSEMBLED with no BOM can never derive firstScanned (the no-BOM anchor is
	 * lifecycle-gated), yet the nudge touches it: the product cannot tell a healable child from an
	 * unhealable one without the child's own artifact pass, so it selects by scan state only and the
	 * child's compute decides. That compute writes nothing new and settles through
	 * touchReleaseLastScanned without pushing the product, so the cost is one touch per hour per stuck
	 * product: the product's fence and count are untouched, and the next hourly compute nudges again.
	 */
	private void unhealableChildIsNudgedAndSettlesAgain(String lifecycle) {
		Organization org = testInitializer.obtainOrganization();
		UUID c = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		setLifecycle(c, lifecycle);
		compute(c); // persisted metrics as on a production row: a fresh compute's DTO equals them
		releaseRepository.touchLastScanned(c);
		assertFalse(eligibleByUpdate(c), "sanity: settled");
		assertNull(firstScanned(reload(c)), "sanity: unscanned");
		UUID p = productOver(org, c, 11);
		long before = childNudges();

		compute(p);

		assertNudged(c);
		List<String> lines = nudgeLines(p);
		assertEquals(1, lines.size(), "exactly one " + NUDGE + " line: " + lines);
		assertTrue(lines.get(0).contains(c.toString()), "the line names the child: " + lines.get(0));
		assertEquals(before + 1, childNudges(), "childNudges rose by exactly one");
		Release product = reload(p);
		assertNull(firstScanned(product), "the product stays incomplete");
		assertNotNull(product.getFlowControl().metricsComputeSkipUntil(), "and fenced");
		assertEquals(12, product.getFlowControl().metricsComputeFailureCount());
		FlowControl productFenceAfterNudge = product.getFlowControl();

		// The child's own tick, still below ASSEMBLED: nothing to derive, settles again, no push.
		assertEquals(lifecycle, lifecycleOf(c));
		compute(c);
		Release child = reload(c);
		assertNull(firstScanned(child), "the child cannot derive firstScanned");
		assertFalse(eligibleByUpdate(c), "the child settled again through touchReleaseLastScanned");
		FlowControl childFc = child.getFlowControl();
		assertTrue(childFc == null || (childFc.metricsComputeSkipUntil() == null && childFc.metricsComputeFailureCount() == null),
				"the child is not fenced (the lifecycle gate): " + childFc);
		assertEquals(productFenceAfterNudge, reload(p).getFlowControl(),
				"the child's compute did not push the product: its fence and count are unchanged");

		// An hour later the product, still at the cap, nudges once more: the bounded hourly cost.
		long second = childNudges();
		compute(p);
		assertEquals(13, reload(p).getFlowControl().metricsComputeFailureCount());
		assertNudged(c);
		assertEquals(2, nudgeLines(p).size(), "one more " + NUDGE + " line: " + nudgeLines(p));
		assertEquals(second + 1, childNudges(), "childNudges rose by exactly one again");
	}

	// ---- 4.3 row 4b: the incident shape, a child below ASSEMBLED whose BOM scan landed without the
	// re-queue, is nudged and heals end to end ----

	@Test
	public void atTheCap_aDraftChildWithAScannedBomAndAMissedRequeue_isNudgedAndHeals() {
		childWithAScannedBomAndAMissedRequeueIsNudgedAndHeals("DRAFT");
	}

	@Test
	public void atTheCap_aCancelledChildWithAScannedBomAndAMissedRequeue_isNudgedAndHeals() {
		childWithAScannedBomAndAMissedRequeueIsNudgedAndHeals("CANCELLED");
	}

	/**
	 * The artifact merge that derives firstScanned from a scanned BOM has no lifecycle term, so a
	 * child in any lifecycle heals from a touch alone; a lifecycle filter on the nudge candidates
	 * would leave this product waiting for ever.
	 */
	private void childWithAScannedBomAndAMissedRequeueIsNudgedAndHeals(String lifecycle) {
		Organization org = testInitializer.obtainOrganization();
		UUID c = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		UUID bom = saveUnscannedBomArtifact(org.getUuid());
		attachArtifact(c, bom);
		setLifecycle(c, lifecycle);
		compute(c);
		Release settledUnscanned = reload(c);
		assertNotNull(lastScanned(settledUnscanned), "sanity: settled below ASSEMBLED with the BOM unscanned");
		assertNull(firstScanned(settledUnscanned), "sanity: unscanned");
		FlowControl seededFc = settledUnscanned.getFlowControl();
		assertTrue(seededFc == null || seededFc.metricsComputeSkipUntil() == null, "sanity: not fenced: " + seededFc);
		assertFalse(eligibleByUpdate(c), "sanity: out of the pool");

		// The scan lands on the artifact row only: none of SharedArtifactService's release touches run.
		markBomScanned(bom, 2);
		releaseRepository.touchLastScanned(c);
		assertFalse(eligibleByUpdate(c), "sanity: the missed re-queue leaves the child settled");
		assertNull(firstScanned(reload(c)), "sanity: the child has not derived firstScanned");
		assertNotNull(artifactRepository.findById(bom).orElseThrow().getMetrics().get("firstScanned"),
				"sanity: the BOM itself is scanned");
		UUID p = productOver(org, c, 11);
		long before = childNudges();

		compute(p);

		assertNudged(c);
		List<String> lines = nudgeLines(p);
		assertEquals(1, lines.size(), "exactly one " + NUDGE + " line: " + lines);
		assertTrue(lines.get(0).contains(c.toString()), "the line names the child: " + lines.get(0));
		assertEquals(before + 1, childNudges(), "childNudges rose by exactly one");
		Release product = reload(p);
		assertNull(firstScanned(product), "the product is still incomplete until the child computes");
		assertNotNull(product.getFlowControl().metricsComputeSkipUntil(), "and fenced");
		assertEquals(12, product.getFlowControl().metricsComputeFailureCount());

		// The child's own tick, still in its lifecycle: the artifact merge derives firstScanned and the
		// child-change push puts the product back into BY_UPDATE with its fence gone.
		assertEquals(lifecycle, lifecycleOf(c));
		compute(c);
		assertNotNull(firstScanned(reload(c)), "the scanned BOM anchors the child's firstScanned");
		assertTrue(eligibleByUpdate(p), "the child-change push re-queues the product");
		assertFenceCleared(reload(p));

		compute(p);
		Release healed = reload(p);
		assertNotNull(firstScanned(healed), "the product completes");
		assertFenceCleared(healed);
		assertEquals(1, nudgeLines(p).size(), "no new " + NUDGE + " line: " + nudgeLines(p));
		assertEquals(before + 1, childNudges(), "childNudges does not move once the child is scanned");
	}

	private String lifecycleOf(UUID releaseUuid) {
		return (String) reload(releaseUuid).getRecordData().get("lifecycle");
	}

	/**
	 * 4.3.5, the drift pin: findSettledUnscannedAmong selects exactly the rows the BY_UPDATE finder
	 * leaves out that have no firstScanned and no live fence. Fails if either predicate is reworded
	 * alone.
	 */
	@Test
	public void findSettledUnscannedAmong_isTheNegationOfTheFinderPredicate() {
		Organization org = testInitializer.obtainOrganization();
		UUID settled = settledUnscannedChild(org);
		UUID fenced = fencedSettledChild(org);
		UUID inPool = inPoolSettledChild(org);
		UUID scanned = scannedSettledChild(org);

		assertAligned(settled, true);
		assertAligned(fenced, false);
		assertAligned(inPool, false);
		assertAligned(scanned, false);
		assertEquals(List.of(settled),
				releaseRepository.findSettledUnscannedAmong(List.of(settled, fenced, inPool, scanned)),
				"over the four together only the settled unscanned child is selected");
	}

	private void assertAligned(UUID c, boolean expected) {
		Release row = releaseRepository.findById(c).orElseThrow();
		boolean selected = releaseRepository.findSettledUnscannedAmong(List.of(c)).contains(c);
		boolean derived = !eligibleByUpdate(c) && firstScanned(row) == null && fenceIsNullOrPast(row);
		assertEquals(derived, selected, "the nudge query and the finder predicate disagree on " + c);
		assertEquals(expected, selected, "selection of " + c);
	}

	// ---- fixtures: each child satisfies every earlier conjunct of findSettledUnscannedAmong before a
	// later one decides (firstScanned IS NULL and lastScanned IS NOT NULL first), so a fenced or in-pool
	// child is settled with touchLastScanned BEFORE its fence or its pool state is applied ----

	/** ASSEMBLED, no BOM, no firstScanned, settled (lastScanned = last_updated_date = now()), no fence. */
	private UUID settledUnscannedChild(Organization org) {
		UUID c = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		releaseRepository.touchLastScanned(c);
		assertFalse(eligibleByUpdate(c), "sanity: settled");
		assertNull(firstScanned(reload(c)), "sanity: unscanned");
		assertNotNull(lastScanned(reload(c)), "sanity: lastScanned stamped");
		return c;
	}

	/** Settled first, then fenced: the only shape in which the fence conjunct decides. */
	private UUID fencedSettledChild(Organization org) {
		UUID c = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		releaseRepository.touchLastScanned(c);
		releaseRepository.recordMetricsComputeIncomplete(c, 3600);
		return c;
	}

	/** Settled first, then returned to the pool by a later touch (an artifact scan's re-queue). */
	private UUID inPoolSettledChild(Organization org) {
		UUID c = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		releaseRepository.touchLastScanned(c);
		releaseRepository.touchForMetricsRecompute(c);
		return c;
	}

	private UUID scannedSettledChild(Organization org) {
		UUID c = saveScanPendingRelease(org.getUuid(), new ArrayList<>()).getUuid();
		markScanned(c, CHILD_FIRST_SCANNED_EPOCH);
		releaseRepository.touchLastScanned(c);
		return c;
	}

	/**
	 * A product over the one child, with {@code failures} incomplete attempts already recorded and the
	 * last fence just lapsed: the state in which the finder hands a waiting product back to the compute.
	 */
	private UUID productOver(Organization org, UUID child, int failures) {
		Release p = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child));
		String lapsed = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).format(PG_TIMESTAMPTZ);
		p.setFlowControl(new FlowControl(null, null, null, null, null, null, null, null, lapsed, failures,
				null, null, null, null, null, null, null, null));
		return releaseRepository.save(p).getUuid();
	}

	private void compute(UUID releaseUuid) {
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(reload(releaseUuid));
	}

	private Release reload(UUID releaseUuid) {
		return releaseRepository.findById(releaseUuid).orElseThrow();
	}

	private Instant lastUpdated(UUID releaseUuid) {
		return reload(releaseUuid).getLastUpdatedDate().toInstant();
	}

	private long childNudges() {
		return ((AtomicLong) ReflectionTestUtils.getField(releaseMetricsComputeService, "childNudges")).get();
	}

	private void assertNudged(UUID c) {
		Release child = reload(c);
		Instant lastScannedAt = epochInstant(((Number) lastScanned(child)).doubleValue());
		assertTrue(child.getLastUpdatedDate().toInstant().isAfter(lastScannedAt),
				"the nudged child's last_updated_date moved past its lastScanned");
		assertTrue(eligibleByUpdate(c), "the nudged child is back in the BY_UPDATE pool");
		assertFenceCleared(child);
		assertNull(firstScanned(child), "the nudge only re-queues; the child's own compute derives");
	}

	private void assertNotNudged(UUID p, UUID c, Instant seeded, long nudgesBefore) {
		assertTrue(nudgeLines(p).isEmpty(), "no " + NUDGE + " line: " + nudgeLines(p));
		assertEquals(seeded, lastUpdated(c), "the child's last_updated_date is not moved");
		assertEquals(nudgesBefore, childNudges(), "childNudges does not move");
	}

	private static void assertFenceCleared(Release r) {
		FlowControl fc = r.getFlowControl();
		assertTrue(fc == null || (fc.metricsComputeSkipUntil() == null && fc.metricsComputeFailureCount() == null),
				"the fence and its failure count are dropped: " + fc);
	}

	private List<String> nudgeLines(UUID productUuid) {
		synchronized (computeLog.events) {
			return computeLog.events.stream()
					.filter(e -> e.getLevel() == Level.INFO)
					.map(e -> e.getMessage().getFormattedMessage())
					.filter(msg -> msg.contains(NUDGE) && msg.contains(productUuid.toString()))
					.collect(Collectors.toList());
		}
	}

	private static Instant epochInstant(double epochSeconds) {
		long micros = (long) Math.floor(epochSeconds * 1_000_000d);
		return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
	}

	/** Postgres timestamptz text, the shape recordMetricsComputeIncomplete stores (e.g. 2026-10-22 03:00:00.12+00). */
	private static final DateTimeFormatter PG_TIMESTAMPTZ = new DateTimeFormatterBuilder()
			.appendPattern("yyyy-MM-dd HH:mm:ss")
			.optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
			.appendOffset("+HH:mm", "Z")
			.toFormatter();

	private static boolean fenceIsNullOrPast(Release r) {
		FlowControl fc = r.getFlowControl();
		if (fc == null || fc.metricsComputeSkipUntil() == null) return true;
		return OffsetDateTime.parse(fc.metricsComputeSkipUntil(), PG_TIMESTAMPTZ).toInstant().isBefore(Instant.now());
	}
}
