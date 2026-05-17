/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import org.slf4j.Logger;

/**
 * Two-tier free-heap guard for batch loops that allocate a lot per
 * iteration (DT vuln/violation fetches, SBOM reconciles, etc.).
 *
 * <p>At {@code gcThresholdFraction} of max heap free, request a GC to
 * reclaim transient per-iteration allocations from the previous step
 * before they push the next iteration into the abort window. At
 * {@code abortThresholdFraction} (after the optional GC), signal abort
 * so the caller can {@code break} / {@code return} and let the next
 * scheduled tick pick up where it left off.
 *
 * <p>The GC hint is a {@link Runtime#gc()} call — non-blocking
 * guarantee-wise but G1 normally honors it for a young+mixed
 * collection, which is exactly what we want for short-lived
 * per-iteration objects (parsed JSON payloads, response DTOs, etc.).
 *
 * <p>Defaults ({@link #DEFAULT_GC_THRESHOLD_FRACTION} 40%,
 * {@link #DEFAULT_ABORT_THRESHOLD_FRACTION} 20%) cover the existing
 * call sites. Callers can override per-loop if needed.
 */
public final class HeapPressureGuard {

	public static final double DEFAULT_GC_THRESHOLD_FRACTION = 0.40;
	public static final double DEFAULT_ABORT_THRESHOLD_FRACTION = 0.20;

	private HeapPressureGuard() {}

	/**
	 * Check free-heap headroom with optional GC hint. Logs to the
	 * caller-supplied {@link Logger} so log lines appear under the
	 * caller's logger name.
	 *
	 * @param log              caller's logger
	 * @param loopLabel        short label for log context (e.g.,
	 *                         {@code "DTrack sync"}, {@code "SBOM reconcile drain"})
	 * @param contextMessage   per-call context appended to log lines
	 *                         (e.g., {@code "before artifact <uuid> (43/365 done)"})
	 * @param gcThreshold      fraction of max heap below which we
	 *                         request a GC before re-measuring
	 * @param abortThreshold   fraction of max heap below which we
	 *                         signal abort
	 * @return true if free heap is below {@code abortThreshold} after
	 *         the optional GC — the caller should break/return its
	 *         drain loop.
	 */
	public static boolean checkAndMaybeGc(Logger log, String loopLabel, String contextMessage,
			double gcThreshold, double abortThreshold) {
		Runtime rt = Runtime.getRuntime();
		long maxHeap = rt.maxMemory();
		long freeHeap = maxHeap - (rt.totalMemory() - rt.freeMemory());
		if (freeHeap < (long) (maxHeap * gcThreshold)) {
			long freeBeforeMb = freeHeap / (1024L * 1024L);
			rt.gc();
			freeHeap = maxHeap - (rt.totalMemory() - rt.freeMemory());
			log.info("{}: free heap below {}% — requested GC. {} free={} MB → {} MB, max={} MB.",
					loopLabel, (int) (gcThreshold * 100), contextMessage,
					freeBeforeMb,
					freeHeap / (1024L * 1024L),
					maxHeap / (1024L * 1024L));
		}
		if (freeHeap < (long) (maxHeap * abortThreshold)) {
			log.error("{} aborted — free heap below {}% threshold (free={} MB, total={} MB, max={} MB). {}",
					loopLabel, (int) (abortThreshold * 100),
					freeHeap / (1024L * 1024L),
					rt.totalMemory() / (1024L * 1024L),
					maxHeap / (1024L * 1024L),
					contextMessage);
			return true;
		}
		return false;
	}

	/**
	 * Overload using the default 40% GC / 20% abort thresholds.
	 */
	public static boolean checkAndMaybeGc(Logger log, String loopLabel, String contextMessage) {
		return checkAndMaybeGc(log, loopLabel, contextMessage,
				DEFAULT_GC_THRESHOLD_FRACTION, DEFAULT_ABORT_THRESHOLD_FRACTION);
	}
}
