/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-tier free-heap guard for batch loops that allocate a lot per
 * iteration (DT vuln/violation fetches, SBOM reconciles, the metrics
 * compute, etc.).
 *
 * <p>At {@code gcThresholdFraction} of max heap free, the guard may force a
 * GC to reclaim transient per-iteration allocations. At
 * {@code abortThresholdFraction} free it signals abort so the caller can
 * {@code break} / {@code return} and let the next scheduled tick pick up
 * where it left off.
 *
 * <p>The backend runs ZGC. Under ZGC a heap with less than 40% free is the
 * normal resting state (the collector lets garbage accumulate until it
 * needs the room), and {@link Runtime#gc()} is a full major cycle the
 * calling thread waits for. Forcing one before every iteration of every
 * worker throttled the loops it was meant to protect, so the forced GC is
 * bounded:
 * <ul>
 * <li><b>Rate limit:</b> at most one forced GC per
 * {@link #DEFAULT_MIN_GC_INTERVAL} (system property
 * {@value #MIN_GC_INTERVAL_PROPERTY}, in seconds) across the whole process.
 * A thread inside the window does not call it; it re-measures and decides.</li>
 * <li><b>Skip when ineffective:</b> when the previous forced GC reclaimed
 * less than {@link #DEFAULT_INEFFECTIVE_GC_FRACTION} of max heap, the heap
 * is full of live data and another forced GC will not help, so none is
 * forced for {@link #DEFAULT_INEFFECTIVE_GC_BACKOFF}.</li>
 * </ul>
 *
 * <p>The abort decision reads the heap as it stood after the most recent
 * collection (the JVM's own or a forced one), not the instantaneous used
 * figure, which under ZGC includes garbage nobody has collected yet. When
 * the JVM does not report after-GC usage the guard falls back to the
 * instantaneous figure.
 *
 * <p>Logging: the GC line is INFO and written once per forced GC, by the
 * thread that forced it. The abort line is ERROR. Callers pass a separate
 * context for each (see {@link #checkAndMaybeGc(Logger, String, String, String)})
 * so a line about a GC does not claim the batch stopped.
 *
 * <p>Defaults ({@link #DEFAULT_GC_THRESHOLD_FRACTION} 40%,
 * {@link #DEFAULT_ABORT_THRESHOLD_FRACTION} 20%) cover the existing
 * call sites. Callers can override per-loop if needed.
 */
public final class HeapPressureGuard {

	public static final double DEFAULT_GC_THRESHOLD_FRACTION = 0.40;
	public static final double DEFAULT_ABORT_THRESHOLD_FRACTION = 0.20;

	/** Minimum time between two forced GCs, process-wide. */
	public static final Duration DEFAULT_MIN_GC_INTERVAL = Duration.ofSeconds(30);
	/** System property (seconds) overriding {@link #DEFAULT_MIN_GC_INTERVAL}. */
	public static final String MIN_GC_INTERVAL_PROPERTY = "rearm.heapGuard.minGcIntervalSeconds";
	/** A forced GC that reclaims less than this fraction of max heap counts as ineffective. */
	public static final double DEFAULT_INEFFECTIVE_GC_FRACTION = 0.10;
	/** How long no GC is forced after an ineffective one. */
	public static final Duration DEFAULT_INEFFECTIVE_GC_BACKOFF = Duration.ofMinutes(5);

	private static final Logger LOG = LoggerFactory.getLogger(HeapPressureGuard.class);
	private static final long MB = 1024L * 1024L;
	private static final long NEVER = Long.MIN_VALUE;

	/** Heap figures in bytes. A seam so the tests never depend on the real heap or call a real GC. */
	interface HeapReader {
		long maxBytes();
		long usedBytes();
		/** Heap used right after the most recent collection, or a negative value when unknown. */
		long usedAfterLastGcBytes();
	}

	/** Outcome of the last forced GC: when it ran and how much it reclaimed. */
	private record ForcedGc(long atMillis, long reclaimedBytes) {}

	private static final HeapPressureGuard INSTANCE = new HeapPressureGuard(Clock.systemUTC(),
			new JvmHeapReader(), () -> Runtime.getRuntime().gc(), minGcIntervalFromProperty(),
			DEFAULT_INEFFECTIVE_GC_FRACTION, DEFAULT_INEFFECTIVE_GC_BACKOFF);

	private final Clock clock;
	private final HeapReader heap;
	private final Runnable gc;
	private final long minGcIntervalMillis;
	private final double ineffectiveGcFraction;
	private final long ineffectiveGcBackoffMillis;
	/** Start time of the last forced GC; the CAS on it is what lets only one thread force a GC per window. */
	private final AtomicLong lastForcedGcAt = new AtomicLong(NEVER);
	private volatile ForcedGc lastForcedGc;

	HeapPressureGuard(Clock clock, HeapReader heap, Runnable gc, Duration minGcInterval,
			double ineffectiveGcFraction, Duration ineffectiveGcBackoff) {
		this.clock = clock;
		this.heap = heap;
		this.gc = gc;
		this.minGcIntervalMillis = minGcInterval.toMillis();
		this.ineffectiveGcFraction = ineffectiveGcFraction;
		this.ineffectiveGcBackoffMillis = ineffectiveGcBackoff.toMillis();
	}

	/**
	 * Check free-heap headroom with an optional, rate-limited forced GC. Logs to the
	 * caller-supplied {@link Logger} so log lines appear under the caller's logger name.
	 * {@code contextMessage} is appended to both the GC line and the abort line.
	 *
	 * @param log              caller's logger
	 * @param loopLabel        short label for log context (e.g.,
	 *                         {@code "DTrack sync"}, {@code "SBOM reconcile drain"})
	 * @param contextMessage   per-call context appended to log lines
	 *                         (e.g., {@code "before artifact <uuid> (43/365 done)"})
	 * @param gcThreshold      fraction of max heap free below which a GC may be forced
	 * @param abortThreshold   fraction of max heap free (after the last collection)
	 *                         below which we signal abort
	 * @return true if free heap after the last collection is below {@code abortThreshold}:
	 *         the caller should break/return its drain loop.
	 */
	public static boolean checkAndMaybeGc(Logger log, String loopLabel, String contextMessage,
			double gcThreshold, double abortThreshold) {
		return INSTANCE.check(log, loopLabel, contextMessage, contextMessage, gcThreshold, abortThreshold);
	}

	/**
	 * Overload using the default 40% GC / 20% abort thresholds.
	 */
	public static boolean checkAndMaybeGc(Logger log, String loopLabel, String contextMessage) {
		return checkAndMaybeGc(log, loopLabel, contextMessage,
				DEFAULT_GC_THRESHOLD_FRACTION, DEFAULT_ABORT_THRESHOLD_FRACTION);
	}

	/**
	 * Default thresholds, with a separate context for each line: {@code gcContext} (may be
	 * null) goes on the GC line, which is written while the loop carries on, and
	 * {@code abortContext} on the abort line only.
	 */
	public static boolean checkAndMaybeGc(Logger log, String loopLabel, String gcContext, String abortContext) {
		return INSTANCE.check(log, loopLabel, gcContext, abortContext,
				DEFAULT_GC_THRESHOLD_FRACTION, DEFAULT_ABORT_THRESHOLD_FRACTION);
	}

	boolean check(Logger log, String loopLabel, String gcContext, String abortContext,
			double gcThreshold, double abortThreshold) {
		long max = heap.maxBytes();
		long used = heap.usedBytes();
		if (max - used < (long) (max * gcThreshold)) {
			used = maybeForceGc(log, loopLabel, gcContext, gcThreshold, max, used);
		}
		long abortBelow = (long) (max * abortThreshold);
		if (max - used >= abortBelow) return false;
		// The instantaneous figure includes garbage not collected yet; decide on the heap as it
		// stood after the most recent collection. It cannot be above what is used now.
		long afterGc = heap.usedAfterLastGcBytes();
		long live = afterGc >= 0 ? Math.min(afterGc, used) : used;
		if (max - live >= abortBelow) return false;
		log.error("{} aborted -- free heap after the last GC below {}% threshold (free={} MB, used after last GC={} MB, used now={} MB, max={} MB). {}",
				loopLabel, (int) (abortThreshold * 100),
				(max - live) / MB, live / MB, used / MB, max / MB,
				null == abortContext ? "" : abortContext);
		return true;
	}

	/**
	 * Forces a GC unless one was forced within the interval or the last one was ineffective.
	 *
	 * @return heap used after this call: re-measured after a forced GC, else {@code used}
	 */
	private long maybeForceGc(Logger log, String loopLabel, String gcContext, double gcThreshold,
			long max, long used) {
		long now = clock.millis();
		long last = lastForcedGcAt.get();
		if (last != NEVER && now - last < minGcIntervalMillis) return used;
		ForcedGc previous = lastForcedGc;
		if (null != previous && previous.reclaimedBytes() < (long) (max * ineffectiveGcFraction)
				&& now - previous.atMillis() < ineffectiveGcBackoffMillis) {
			return used;
		}
		// Only the thread that wins the claim forces the GC; the others re-measure without one.
		if (!lastForcedGcAt.compareAndSet(last, now)) return heap.usedBytes();
		gc.run();
		long usedAfter = heap.usedBytes();
		lastForcedGc = new ForcedGc(now, Math.max(0, used - usedAfter));
		log.info("{}: free heap below {}% -- forced a GC (at most one per {} s process-wide). {}free={} MB -> {} MB, max={} MB.",
				loopLabel, (int) (gcThreshold * 100), minGcIntervalMillis / 1000,
				null == gcContext || gcContext.isEmpty() ? "" : gcContext + " ",
				(max - used) / MB, (max - usedAfter) / MB, max / MB);
		return usedAfter;
	}

	private static Duration minGcIntervalFromProperty() {
		String raw = System.getProperty(MIN_GC_INTERVAL_PROPERTY);
		if (null == raw || raw.isBlank()) return DEFAULT_MIN_GC_INTERVAL;
		try {
			long seconds = Long.parseLong(raw.trim());
			if (seconds >= 0) return Duration.ofSeconds(seconds);
			LOG.error("Ignoring negative {}={}, using {} s", MIN_GC_INTERVAL_PROPERTY, raw,
					DEFAULT_MIN_GC_INTERVAL.toSeconds());
		} catch (NumberFormatException e) {
			LOG.error("Ignoring unparseable {}={}, using {} s", MIN_GC_INTERVAL_PROPERTY, raw,
					DEFAULT_MIN_GC_INTERVAL.toSeconds(), e);
		}
		return DEFAULT_MIN_GC_INTERVAL;
	}

	/**
	 * The real heap. After-GC usage is the sum, over the heap memory pools, of each pool's usage as
	 * it stood after the most recent collection that touched it ({@link MemoryPoolMXBean#getCollectionUsage}).
	 * Only java.management is used: the backend image runs a jlink runtime without jdk.management, so
	 * com.sun.management (GarbageCollectorMXBean, GcInfo) is not there and must not be referenced.
	 */
	static final class JvmHeapReader implements HeapReader {
		private final List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
				.filter(p -> p.getType() == MemoryType.HEAP).toList();

		@Override
		public long maxBytes() {
			return Runtime.getRuntime().maxMemory();
		}

		@Override
		public long usedBytes() {
			Runtime rt = Runtime.getRuntime();
			return rt.totalMemory() - rt.freeMemory();
		}

		@Override
		public long usedAfterLastGcBytes() {
			try {
				long sum = 0;
				boolean any = false;
				for (MemoryPoolMXBean pool : heapPools) {
					MemoryUsage afterGc = pool.getCollectionUsage();
					if (null == afterGc) continue;
					sum += afterGc.getUsed();
					any = true;
				}
				return any ? sum : -1;
			} catch (RuntimeException | LinkageError e) {
				LOG.error("Could not read the heap usage after the last GC; using the current usage", e);
				return -1;
			}
		}
	}
}
