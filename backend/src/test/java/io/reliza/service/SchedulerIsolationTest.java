/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssAnalyticsMetricsService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * One failing unit must not end the tick for everything behind it.
 *
 * <p>Both tests poison the work itself with a {@code StackOverflowError} rather than an exception,
 * because that is what actually happened: the per-org and per-release loops caught {@code Exception}
 * only, so an {@code Error} left the loop entirely -- every organization after the bad one was
 * skipped on every tick for ten hours, and in the metrics batch the poison release was re-picked at
 * the head of every batch because the backoff was recorded only inside the same catch.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SchedulerIsolationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SchedulingService schedulingService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private javax.sql.DataSource dataSource;

	@MockitoSpyBean private OssAnalyticsMetricsService ossAnalyticsMetricsService;
	@MockitoSpyBean private AnalyticsMetricsService analyticsMetricsService;
	@MockitoSpyBean private ReleaseMetricsComputeService releaseMetricsComputeService;

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	/** Minimal in-memory appender -- the point of the test is the level and the org id, nothing else. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private CapturingAppender analyticsLog;

	@BeforeEach
	public void attachAppenders() {
		// Per logger name: adding an appender to a log4j2 logger creates a LoggerConfig for exactly
		// that name, so an appender on the root (or on a parent) sees none of these events.
		analyticsLog = attach(OssAnalyticsMetricsService.class);
	}

	@AfterEach
	public void detachAppenders() {
		detach(OssAnalyticsMetricsService.class, analyticsLog);
	}

	private static CapturingAppender attach(Class<?> loggerOwner) {
		CapturingAppender appender = new CapturingAppender();
		appender.start();
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner)).addAppender(appender);
		return appender;
	}

	private static void detach(Class<?> loggerOwner, CapturingAppender appender) {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner)).removeAppender(appender);
		appender.stop();
	}

	/**
	 * Polls, because the application context under test runs its own schedulers: a background tick
	 * that happens to hold this lock while the test starts is not the condition under test.
	 */
	private boolean awaitAdvisoryLockFree(AdvisoryLockKey alk, int seconds) {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			if (tryAdvisoryLock(alk)) return true;
			try {
				Thread.sleep(250);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return tryAdvisoryLock(alk);
	}

	private boolean tryAdvisoryLock(AdvisoryLockKey alk) {
		try (Connection conn = dataSource.getConnection();
				PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
			stmt.setLong(1, alk.getQueryVal());
			try (ResultSet rs = stmt.executeQuery()) {
				rs.next();
				return rs.getBoolean(1);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
		// Connection closed on the way out, which drops the lock with the session.
	}

	private UUID release(Organization org, String version) throws RelizaException {
		Component c = componentService.createComponent("sched_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
	}

	@Test
	public void oneOrgThrowingAnErrorDoesNotEndTheAnalyticsTick() throws RelizaException {
		// Two orgs with data, so the daily pass has something to do on both sides of the failure.
		release(testInitializer.obtainOrganization(), "1.0.0");
		release(testInitializer.obtainOrganization(), "1.0.0");

		// Poison the FIRST org the pass reaches, whichever it is: everything computed after it is
		// then proof of isolation regardless of the order listAllOrganizationData returns.
		AtomicReference<UUID> poisoned = new AtomicReference<>();
		List<UUID> attempted = new LinkedList<>();
		doAnswer(inv -> {
			UUID org = inv.getArgument(0);
			synchronized (attempted) { attempted.add(org); }
			if (poisoned.compareAndSet(null, org)) {
				throw new StackOverflowError("simulated cyclic component graph for org " + org);
			}
			return inv.callRealMethod();
		}).when(analyticsMetricsService).computeActualAnalyticsMetricsDataForOrg(any(UUID.class),
				any(ZonedDateTime.class));

		ossAnalyticsMetricsService.computeAndRecordAnalyticsMetricsForAllOrgs();

		assertTrue(null != poisoned.get(), "the pass never reached an organization");
		int after = attempted.size() - attempted.indexOf(poisoned.get()) - 1;
		assertTrue(after > 0, "the tick stopped at the failing org -- " + attempted.size()
				+ " org(s) attempted, none after " + poisoned.get());
		assertTrue(analyticsLog.events.stream()
				.anyMatch(e -> Level.WARN == e.getLevel()
						&& e.getMessage().getFormattedMessage().contains(poisoned.get().toString())),
				"the failure summary must be at WARN and name the failed org -- an INFO line is "
						+ "filtered out, which is why this was invisible for ten hours");
	}

	@Test
	public void aFailingScheduledAnalyticsRunReturnsAndStillReleasesItsLock() {
		// A concurrent background tick of this same scheduler would make the assertion below
		// meaningless, so wait it out first.
		assertTrue(awaitAdvisoryLockFree(AdvisoryLockKey.REFRESH_TODAY_ANALYTICS, 120),
				"a background tick held the lock for the whole wait -- test setup, not the fix");

		// Poisoned at the level the wrapper itself calls -- the per-org loop inside has its own
		// guard, so an Error from one org never reaches this one.
		doAnswer(inv -> { throw new StackOverflowError("simulated"); })
				.when(ossAnalyticsMetricsService).refreshTodayAnalyticsForChangedOrgs();

		// The call itself must return: before the guard, an Error walked out of here into Spring,
		// which logged "Unexpected error occurred in scheduled task" -- no scheduler name, nothing
		// to grep for, which is how a dead tick went unnoticed for ten hours. The named log line
		// the guard now writes is asserted on the batch path below, where the logger is capturable.
		schedulingService.refreshTodayAnalytics();


		// From a different connection: the scheduler holds this as a session-level advisory lock on
		// its own connection, so acquiring it here is only possible once that session let go.
		assertTrue(awaitAdvisoryLockFree(AdvisoryLockKey.REFRESH_TODAY_ANALYTICS, 10),
				"the advisory lock outlived the failing tick, so every later tick would be skipped");
	}

	@Test
	public void aReleaseWhoseMetricsComputeThrowsAnErrorIsFenced() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = release(org, "1.0.0");

		List<UUID> attempted = new LinkedList<>();
		doAnswer(inv -> {
			Release r = inv.getArgument(0);
			synchronized (attempted) { attempted.add(r.getUuid()); }
			throw new StackOverflowError("simulated cyclic component graph for release " + r.getUuid());
		}).when(releaseMetricsComputeService).computeReleaseMetricsOnRescan(any(Release.class));

		releaseService.computeMetricsForAllUnprocessedReleases(50);

		// Snapshot under the lock. The batch runs a parallel pass, so a worker still finishing as
		// the assertions start would otherwise throw ConcurrentModificationException out of the
		// iteration below -- a flake in the test, not a finding about the code.
		List<UUID> seen;
		synchronized (attempted) { seen = List.copyOf(attempted); }
		assertFalse(seen.isEmpty(), "no release was picked up -- the fixture release "
				+ releaseUuid + " did not qualify for the metrics batch");
		for (UUID attemptedUuid : seen) {
			Release r = sharedReleaseService.getRelease(attemptedUuid).orElseThrow();
			assertTrue(null != r.getFlowControl() && null != r.getFlowControl().metricsComputeFailureCount()
					&& r.getFlowControl().metricsComputeFailureCount() > 0,
					"release " + attemptedUuid + " threw an Error and was not fenced, so it is re-picked"
							+ " at the head of every batch -- that is the maxAttempts=2845 shape");
		}
	}
}
