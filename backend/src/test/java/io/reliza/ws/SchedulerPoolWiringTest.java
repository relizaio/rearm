/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Regression test for the silent single-threading of every {@code @Scheduled}
 * job, and for the {@code @Async} default-executor resolution (see
 * {@link App#taskScheduler(int)} / {@link App#defaultAsyncExecutor(int, int, int)}).
 *
 * <p>The app declares a {@code ScheduledExecutorService} bean (for
 * {@code ReleaseFinalizerService}), which makes Spring Boot's
 * {@code TaskSchedulingAutoConfiguration} back off -- so
 * {@code spring.task.scheduling.pool.size} configured nothing, and
 * {@code @Scheduled} work fell back to a local single-thread default.
 * Observed in prod as all 21 scheduled jobs serialized onto one generic
 * {@code pool-N-thread-1} thread: a ~40-min finding_change_events boot
 * backfill froze the release-metrics tick (releases stuck in "Scan pending"),
 * SBOM reconciles, and notification dispatch, with zero errors logged.
 *
 * <p>Pins the fix: an explicit {@link TaskScheduler} bean (which
 * {@code @Scheduled} resolution prefers over any
 * {@code ScheduledExecutorService}), sized from the property this test
 * overrides -- proving property-to-bean binding independent of config-file
 * pickup -- with the {@code scheduling-} thread prefix that lets a thread
 * dump prove which pool scheduled work runs on. Also pins the deliberate
 * {@code @Async} default for prod: the bounded {@code @Primary}
 * {@code defaultAsyncExecutor} ({@code async-} threads, CallerRunsPolicy) --
 * NOT auto-integrate's DiscardPolicy pool and NOT an unbounded
 * {@code SimpleAsyncTaskExecutor}. The actual {@code @Async} dispatch cannot
 * be probed here: the test classpath's {@code TestAsyncConfig} deliberately
 * pins test-context {@code @Async} to a synchronous executor (its
 * {@code taskExecutor} name is what the two-primaries ambiguity falls back
 * to), so this test asserts the prod bean's configuration instead.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class},
		properties = "spring.task.scheduling.pool.size=5")
public class SchedulerPoolWiringTest {

	@Autowired
	private TaskScheduler taskScheduler;

	@Autowired
	@Qualifier("defaultAsyncExecutor")
	private ThreadPoolTaskExecutor defaultAsyncExecutor;

	// Qualified: the context carries a second ScheduledExecutorService
	// (dgsScheduledExecutorService, from Netflix DGS). ReleaseFinalizerService
	// resolves the ambiguity by field name; this test does it explicitly.
	@Autowired
	@Qualifier("scheduledExecutorService")
	private ScheduledExecutorService releaseFinalizerExecutor;

	@Test
	public void taskSchedulerBean_isMultiThreadedPool_withDiagnosticThreadNames() {
		ThreadPoolTaskScheduler pool = assertInstanceOf(ThreadPoolTaskScheduler.class, taskScheduler,
				"@Scheduled must run on an explicit ThreadPoolTaskScheduler, not a "
				+ "single-thread fallback");
		// 5 comes from this test's property override -- proves the bean binds
		// spring.task.scheduling.pool.size (yaml default 3) rather than
		// hardcoding, independent of config-file pickup. Core size is the
		// configured value; getPoolSize() would report live threads.
		assertEquals(5, pool.getScheduledThreadPoolExecutor().getCorePoolSize(),
				"pool size must bind spring.task.scheduling.pool.size; a silent fallback "
				+ "re-creates the every-scheduled-job-serialized-behind-one-slow-job incident");
		// Thread naming is load-bearing for ops: a thread dump grep for
		// '"scheduling-' is the documented way to verify scheduled work is on
		// this pool (the incident dump had NO scheduling-* threads at all).
		assertEquals("scheduling-", pool.getThreadNamePrefix());
	}

	@Test
	public void defaultAsyncExecutor_isBoundedWithBackpressure() {
		assertEquals(4, defaultAsyncExecutor.getCorePoolSize());
		assertEquals(8, defaultAsyncExecutor.getMaxPoolSize());
		assertEquals("async-", defaultAsyncExecutor.getThreadNamePrefix());
		// CallerRunsPolicy: overflow degrades to caller backpressure -- never the
		// silent drops of a DiscardPolicy, never thread-per-task fan-out.
		assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class,
				defaultAsyncExecutor.getThreadPoolExecutor().getRejectedExecutionHandler());
	}

	@Test
	public void finalizerExecutor_remainsItsOwnDedicatedBean() {
		// The ScheduledExecutorService bean must still exist for
		// ReleaseFinalizerService -- the fix routes @Scheduled AWAY from it
		// without breaking its actual consumer.
		assertNotNull(releaseFinalizerExecutor);
	}
}
