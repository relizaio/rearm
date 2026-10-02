/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The synthetic DTrack paths of {@link SchedulingService}: the per-minute tick
 * leaves an org to a resync holding its cycle lock, and the daily resync keeps
 * its sync time when it had to skip an org.
 */
@ExtendWith(MockitoExtension.class)
class SchedulingServiceSyntheticTickTest {

	private static final UUID ORG = UUID.fromString("7f1c0a00-0000-4000-8000-000000000001");
	private static final UUID OTHER_ORG = UUID.fromString("7f1c0a00-0000-4000-8000-000000000002");

	@Mock private SyntheticSbomService syntheticSbomService;
	@Mock private SyntheticOrgLock syntheticOrgLock;
	@Mock private IntegrationService integrationService;
	@Mock private SystemInfoService systemInfoService;
	@Mock private DataSource dataSource;
	@InjectMocks private SchedulingService schedulingService;

	@Test
	void theTickLeavesAnOrgAloneWhileAResyncHoldsItsCycleLock() {
		when(syntheticOrgLock.tryAcquire(ORG)).thenReturn(null);

		schedulingService.runSyntheticTickForOrg(ORG);

		// Only the read-only stall report: a holder that never finishes must not
		// also silence the report of the stall it causes.
		verify(syntheticSbomService).reportFanOutStallIfAny(ORG);
		verifyNoMoreInteractions(syntheticSbomService);
	}

	@Test
	void theTickRunsTheOrgUnderItsCycleLockAndReleasesIt() {
		SyntheticOrgLock.Lease lease = mock(SyntheticOrgLock.Lease.class);
		when(syntheticOrgLock.tryAcquire(ORG)).thenReturn(lease);

		schedulingService.runSyntheticTickForOrg(ORG);

		InOrder order = inOrder(syntheticSbomService, lease);
		order.verify(syntheticSbomService).ingestOrgBuckets(ORG);
		order.verify(syntheticSbomService).fanOutOrg(ORG);
		order.verify(lease).close();
		order.verify(syntheticSbomService).reportFanOutStallIfAny(ORG);
	}

	@Test
	void theDailyResyncKeepsItsSyncTimeWhenAnOrgWasSkippedOnItsLock() throws Exception {
		schedulerLockIsFree();
		ZonedDateTime since = ZonedDateTime.now().minusDays(1);
		when(systemInfoService.getLastDtrackSync()).thenReturn(since);
		when(integrationService.listOrgsWithDtrackIntegration()).thenReturn(List.of(ORG, OTHER_ORG));
		when(syntheticSbomService.resyncOrg(ORG, since)).thenReturn(true);
		when(syntheticSbomService.resyncOrg(OTHER_ORG, since)).thenReturn(false);

		schedulingService.scheduleSyntheticDtrackDailyResync();

		verify(syntheticSbomService).resyncOrg(ORG, since);
		verify(syntheticSbomService).resyncOrg(OTHER_ORG, since);
		verify(systemInfoService, never()).setLastDtrackSync(any());
	}

	@Test
	void theDailyResyncAdvancesItsSyncTimeWhenEveryOrgRan() throws Exception {
		schedulerLockIsFree();
		ZonedDateTime since = ZonedDateTime.now().minusDays(1);
		when(systemInfoService.getLastDtrackSync()).thenReturn(since);
		when(integrationService.listOrgsWithDtrackIntegration()).thenReturn(List.of(ORG));
		when(syntheticSbomService.resyncOrg(ORG, since)).thenReturn(true);

		schedulingService.scheduleSyntheticDtrackDailyResync();

		verify(systemInfoService).setLastDtrackSync(any());
	}

	/** The daily job's own scheduler lock ({@code pg_try_advisory_lock}) is granted. */
	private void schedulerLockIsFree() throws Exception {
		Connection conn = mock(Connection.class);
		PreparedStatement stmt = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(conn);
		when(conn.prepareStatement(anyString())).thenReturn(stmt);
		when(stmt.executeQuery()).thenReturn(rs);
		when(rs.getBoolean(eq(1))).thenReturn(true);
	}
}
