/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.FlowControl;

/**
 * TPF-68 unit half, no Spring: the backoff-cap boundary at which a stuck product starts nudging its
 * children, and the childNudges field of the hourly [METRICS-STALLED] gauge line (reported, then
 * reset on emit). A fresh {@code new ReleaseMetricsComputeService()} per case, as in
 * MetricsLossProvenanceTest.
 */
class ProductChildNudgeGaugeTest {

	private static FlowControl fcWithFailures(int failureCount) {
		return ReleaseScanSelfHealTestBase.fcWithFailures(failureCount);
	}

	@Test
	void theCapIsFirstReachedAtTheSmallestCountWhoseIntervalIsTheCap() {
		assertFalse(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(null), "first attempt: grace");
		assertFalse(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(fcWithFailures(4)), "grace (0s)");
		assertFalse(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(fcWithFailures(5)), "60s");
		assertFalse(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(fcWithFailures(10)), "1920s");
		assertTrue(ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(10)) < 3600,
				"the count just below the boundary is fenced for less than the hourly cap; if the backoff "
				+ "constants change, the boundary (11 = grace 5 + 6 doublings of 60s to reach 3600s) moves "
				+ "with them and this case names the derivation");
		assertEquals(3600, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(11)));
		assertTrue(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(fcWithFailures(11)),
				"11 prior failures: this attempt is fenced for the cap, the first nudging attempt");
		assertTrue(ReleaseMetricsComputeService.isAtMetricsComputeBackoffCap(fcWithFailures(500)),
				"the cap holds for arbitrarily large counts");
	}

	/** Minimal in-memory appender, as in ProductForeignParentMetricsTest: only level and message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@Test
	@SuppressWarnings("unchecked")
	void theStallLineReportsChildNudgesAndResetsThemOnEmit() {
		ReleaseMetricsComputeService fresh = new ReleaseMetricsComputeService();
		((AtomicLong) ReflectionTestUtils.getField(fresh, "childNudges")).incrementAndGet();
		AtomicReference<Instant> marker =
				(AtomicReference<Instant>) ReflectionTestUtils.getField(fresh, "lastStallReport");
		UUID stalled = UUID.randomUUID();

		CapturingAppender capture = new CapturingAppender();
		capture.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseMetricsComputeService.class);
		logger.addAppender(capture);
		try {
			marker.set(Instant.now().minusSeconds(7200));
			ReflectionTestUtils.invokeMethod(fresh, "recordStalledCompute", stalled, 24);
			List<String> first = stallLines(capture);
			assertEquals(1, first.size(), "one stall line once the window has elapsed: " + first);
			assertTrue(first.get(0).contains("childNudges=1"), "the nudges are reported: " + first.get(0));

			marker.set(Instant.now().minusSeconds(7200));
			ReflectionTestUtils.invokeMethod(fresh, "recordStalledCompute", stalled, 25);
			List<String> second = stallLines(capture);
			assertEquals(2, second.size(), "a second line after the next window: " + second);
			assertTrue(second.get(1).contains("childNudges=0"), "the counter was reset on emit: " + second.get(1));
		} finally {
			logger.removeAppender(capture);
			capture.stop();
		}
	}

	private static List<String> stallLines(CapturingAppender capture) {
		synchronized (capture.events) {
			return capture.events.stream()
					.filter(e -> e.getLevel() == Level.ERROR)
					.map(e -> e.getMessage().getFormattedMessage())
					.filter(msg -> msg.contains("[METRICS-STALLED]"))
					.collect(Collectors.toList());
		}
	}
}
