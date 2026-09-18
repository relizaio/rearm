/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.common;

import lombok.extern.slf4j.Slf4j;

/**
 * Run one unit of a scheduled batch so that its failure stays its own.
 *
 * <p>Every scheduler loop in this codebase caught {@code Exception}, which is the right instinct
 * and the wrong type. A {@code StackOverflowError} raised deep inside one organization's analytics
 * walked through the per-org catch, through the outer catch, and out of the scheduled method --
 * so every organization queued behind it was skipped, on every tick, until somebody noticed. The
 * only trace was Spring's generic "Unexpected error occurred in scheduled task".
 *
 * <p>An {@code Error} is not automatically fatal to the process. A {@code StackOverflowError} is
 * survivable by the time it is caught: the stack that overflowed has already unwound. An
 * {@code OutOfMemoryError} is not, and is rethrown rather than pretended away -- catching it would
 * leave the JVM crippled and the batch reporting success.
 */
@Slf4j
public final class SchedulerGuard {

	private SchedulerGuard() {}

	@FunctionalInterface
	public interface ThrowingRunnable {
		void run() throws Exception;
	}

	/**
	 * @param unit what failed, in terms the log reader can act on -- an org uuid, a release uuid
	 * @return true when the body completed, false when it failed and was contained
	 * @throws OutOfMemoryError always rethrown; nothing after it can be trusted
	 */
	public static boolean runIsolated(String unit, ThrowingRunnable body) {
		try {
			body.run();
			return true;
		} catch (OutOfMemoryError oom) {
			log.error("Out of memory while processing {}; aborting the batch", unit, oom);
			throw oom;
		} catch (Throwable t) {
			log.error("Scheduled work failed for {} -- continuing with the rest of the batch", unit, t);
			return false;
		}
	}
}
