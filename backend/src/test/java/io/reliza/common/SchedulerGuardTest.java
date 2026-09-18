/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * The guard exists because every scheduler loop caught {@code Exception}, and the thing that
 * actually took the analytics tick down for ten hours was a {@code StackOverflowError} -- an
 * {@code Error}, which walked straight through all of them.
 */
public class SchedulerGuardTest {

	@Test
	public void aSucceedingUnitReportsSuccess() {
		AtomicBoolean ran = new AtomicBoolean();
		assertTrue(SchedulerGuard.runIsolated("unit", () -> ran.set(true)));
		assertTrue(ran.get());
	}

	@Test
	public void anExceptionIsContainedAndReportedAsFailure() {
		assertFalse(SchedulerGuard.runIsolated("unit", () -> {
			throw new IllegalStateException("boom");
		}));
	}

	@Test
	public void aStackOverflowIsContainedAndReportedAsFailure() {
		// The case the per-org catches missed. Survivable by the time it is caught: the stack has
		// already unwound, so the next unit in the batch starts from a healthy one.
		assertFalse(SchedulerGuard.runIsolated("unit", SchedulerGuardTest::infiniteRecursion));
	}

	@Test
	public void outOfMemoryIsRethrownRatherThanSwallowed() {
		// Not survivable: continuing the batch after it would just produce more of them, with the
		// original cause buried under whatever fails next.
		assertThrows(OutOfMemoryError.class, () -> SchedulerGuard.runIsolated("unit", () -> {
			throw new OutOfMemoryError("simulated");
		}));
	}

	private static void infiniteRecursion() {
		infiniteRecursion();
	}
}
