/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TPF-138 mutation round: what the per-minute enrichment tick of {@link SchedulingService} hands the
 * metrics compute. The recent lane's cap and window are two ints next to each other, so only the call
 * itself shows that each lands in its own parameter and that the cap is the recent lane's own 51, not
 * the normal batch.
 */
@ExtendWith(MockitoExtension.class)
class SchedulingServiceMetricsTickTest {

	@Mock private ReleaseService releaseService;
	@Mock private IntegrationService integrationService;
	@Mock private DataSource dataSource;
	@InjectMocks private SchedulingService schedulingService;

	@Test
	void theTickHandsTheComputeTheNormalBatchTheRecentCapAndTheRecentWindowInThatOrder() throws Exception {
		schedulerLockIsFree();
		when(integrationService.listOrgsWithDtrackIntegration()).thenReturn(List.of());
		ReflectionTestUtils.setField(schedulingService, "metricsComputeBatchLimit", 102);

		schedulingService.scheduleEnrichmentPull();

		verify(releaseService).computeMetricsForAllUnprocessedReleases(102,
				SchedulingService.METRICS_RECENT_BATCH_LIMIT, SchedulingService.METRICS_RECENT_WINDOW_MINUTES);
	}

	@Test
	void aConfiguredNormalBatchOfZeroStillRunsOneReleaseAndLeavesTheRecentLaneItsOwnCap() throws Exception {
		schedulerLockIsFree();
		when(integrationService.listOrgsWithDtrackIntegration()).thenReturn(List.of());
		ReflectionTestUtils.setField(schedulingService, "metricsComputeBatchLimit", 0);

		schedulingService.scheduleEnrichmentPull();

		verify(releaseService).computeMetricsForAllUnprocessedReleases(1,
				SchedulingService.METRICS_RECENT_BATCH_LIMIT, SchedulingService.METRICS_RECENT_WINDOW_MINUTES);
	}

	/** The tick's own scheduler lock ({@code pg_try_advisory_lock}) is granted. */
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
