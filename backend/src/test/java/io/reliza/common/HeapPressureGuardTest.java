/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

/**
 * The guard's three rules, on a fake heap and a fake clock: no real GC is ever called. Max heap is
 * 1000 MB throughout, so the 40% GC threshold is 400 MB free and the 20% abort threshold 200 MB
 * free.
 */
class HeapPressureGuardTest {

	private static final long MB = 1024L * 1024L;
	private static final long MAX = 1000 * MB;

	/** A heap whose figures the test sets; the fake GC moves {@code used} to {@code usedAfterGc}. */
	private static final class FakeHeap implements HeapPressureGuard.HeapReader {
		volatile long used;
		volatile long usedAfterLastGc = -1;
		volatile long usedAfterGc;

		@Override public long maxBytes() { return MAX; }
		@Override public long usedBytes() { return used; }
		@Override public long usedAfterLastGcBytes() { return usedAfterLastGc; }
	}

	private static final class FakeClock extends Clock {
		volatile Instant now = Instant.parse("2026-10-30T00:00:00Z");

		void advance(Duration d) { now = now.plus(d); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	private final FakeHeap heap = new FakeHeap();
	private final FakeClock clock = new FakeClock();
	private final AtomicInteger gcCalls = new AtomicInteger();
	private final Logger log = mock(Logger.class);

	private HeapPressureGuard guard(Runnable gc) {
		return new HeapPressureGuard(clock, heap, gc, Duration.ofSeconds(30), 0.10, Duration.ofMinutes(5));
	}

	/** A GC that reclaims down to {@code heap.usedAfterGc} and records it as the last collection. */
	private HeapPressureGuard guard() {
		return guard(() -> {
			gcCalls.incrementAndGet();
			heap.used = heap.usedAfterGc;
			heap.usedAfterLastGc = heap.usedAfterGc;
		});
	}

	private boolean check(HeapPressureGuard g) {
		return g.check(log, "test loop", null, "remaining retried next tick.", 0.40, 0.20);
	}

	@Test
	void plentyOfFreeHeapNeitherCollectsNorAborts() {
		HeapPressureGuard g = guard();
		heap.used = 500 * MB;
		assertFalse(check(g));
		assertEquals(0, gcCalls.get());
		verify(log, never()).info(anyString(), any(Object[].class));
	}

	@Test
	void atMostOneForcedGcPerInterval() {
		HeapPressureGuard g = guard();
		heap.used = 700 * MB;
		heap.usedAfterGc = 300 * MB;
		assertFalse(check(g));
		assertEquals(1, gcCalls.get(), "below 40% free: one forced GC");

		// Garbage builds up again within the window: no second GC, the caller carries on.
		heap.used = 750 * MB;
		clock.advance(Duration.ofSeconds(29));
		assertFalse(check(g));
		assertFalse(check(g));
		assertEquals(1, gcCalls.get(), "a second GC inside the 30 s window is not forced");

		clock.advance(Duration.ofSeconds(1));
		assertFalse(check(g));
		assertEquals(2, gcCalls.get(), "once the window has passed a GC may be forced again");
	}

	@Test
	void concurrentCallersForceOneGcAndLogItOnce() throws Exception {
		CountDownLatch inGc = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		HeapPressureGuard g = guard(() -> {
			gcCalls.incrementAndGet();
			inGc.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			heap.used = 300 * MB;
			heap.usedAfterLastGc = 300 * MB;
		});
		heap.used = 700 * MB;
		int threads = 3;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CyclicBarrier start = new CyclicBarrier(threads);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return check(g);
				}));
			}
			assertTrue(inGc.await(10, TimeUnit.SECONDS), "one caller forces a GC");
			release.countDown();
			for (Future<Boolean> f : results) assertFalse(f.get(10, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, gcCalls.get(), "the other callers re-measure instead of forcing their own GC");
		verify(log, times(1)).info(anyString(), any(Object[].class));
	}

	@Test
	void anIneffectiveGcIsNotRepeated() {
		HeapPressureGuard g = guard();
		heap.used = 700 * MB;
		heap.usedAfterGc = 650 * MB; // reclaims 50 MB: 5% of max, under the 10% bar
		assertFalse(check(g));
		assertEquals(1, gcCalls.get());

		clock.advance(Duration.ofSeconds(31));
		assertFalse(check(g));
		assertEquals(1, gcCalls.get(), "the heap is full of live data; another forced GC would not help");

		clock.advance(Duration.ofMinutes(5));
		assertFalse(check(g));
		assertEquals(2, gcCalls.get(), "after the backoff the guard tries again");
	}

	@Test
	void anEffectiveGcDoesNotBlockTheNextOne() {
		HeapPressureGuard g = guard();
		heap.used = 700 * MB;
		heap.usedAfterGc = 550 * MB; // reclaims 150 MB: 15% of max
		check(g);
		heap.used = 700 * MB;
		clock.advance(Duration.ofSeconds(30));
		check(g);
		assertEquals(2, gcCalls.get());
	}

	@Test
	void abortDecidesOnTheHeapAfterTheLastCollection() {
		// No GC is due (one ran inside the window), and the instantaneous figure is under the
		// abort line, but most of it is garbage: after the last collection the heap was half free.
		HeapPressureGuard g = guard();
		heap.used = 700 * MB;
		heap.usedAfterGc = 300 * MB;
		check(g);
		assertEquals(1, gcCalls.get());

		heap.used = 900 * MB;
		heap.usedAfterLastGc = 500 * MB;
		assertFalse(check(g), "100 MB free now, but 500 MB free after the last GC: carry on");
		verify(log, never()).error(anyString(), any(Object[].class));

		heap.usedAfterLastGc = 850 * MB;
		assertTrue(check(g), "150 MB free after the last GC: under 20%, abort");
		assertEquals(1, gcCalls.get(), "still no second forced GC inside the window");
	}

	@Test
	void abortFallsBackToTheCurrentMeasureWhenAfterGcUsageIsUnknown() {
		HeapPressureGuard g = guard(() -> gcCalls.incrementAndGet()); // reclaims nothing, reports nothing
		heap.used = 900 * MB;
		assertTrue(check(g));
		assertEquals(1, gcCalls.get());
	}

	@Test
	void theGcLineCarriesNoAbortContextAndTheAbortLineDoes() {
		HeapPressureGuard g = guard();
		heap.used = 700 * MB;
		heap.usedAfterGc = 300 * MB;
		assertFalse(check(g));
		ArgumentCaptor<Object[]> infoArgs = ArgumentCaptor.forClass(Object[].class);
		verify(log, times(1)).info(anyString(), infoArgs.capture());
		for (Object a : infoArgs.getValue()) {
			assertFalse(String.valueOf(a).contains("retried next tick"),
					"the batch continues after a GC, so the GC line must not say it is retried: " + a);
		}

		heap.used = 900 * MB;
		heap.usedAfterLastGc = 900 * MB;
		assertTrue(check(g));
		ArgumentCaptor<Object[]> errorArgs = ArgumentCaptor.forClass(Object[].class);
		verify(log, times(1)).error(anyString(), errorArgs.capture());
		Object[] args = errorArgs.getValue();
		assertEquals("remaining retried next tick.", args[args.length - 1]);
	}

	@Test
	void theJvmReaderReportsPlausibleFigures() {
		HeapPressureGuard.JvmHeapReader jvm = new HeapPressureGuard.JvmHeapReader();
		assertTrue(jvm.maxBytes() > 0);
		assertTrue(jvm.usedBytes() > 0 && jvm.usedBytes() <= jvm.maxBytes());
		long afterGc = jvm.usedAfterLastGcBytes();
		assertTrue(afterGc == -1 || (afterGc >= 0 && afterGc <= jvm.maxBytes()),
				"after-GC usage is unknown (-1) or within the heap: " + afterGc);
	}
}
