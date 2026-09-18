package io.reliza.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.ws.App;

/**
 * Guards the repair sweep's kill switch, which is a safety control rather than a convenience: disabling the
 * sweep for longer than its lookback window makes any v3 row a live emit dropped meanwhile permanent (see
 * {@link SchedulingService#repairFindingChangeV3}). A switch that failed to disable would be bad; one that
 * failed to RE-enable would be worse, so both directions are asserted.
 *
 * <p>The flags are driven with {@link ReflectionTestUtils} rather than {@code @SpringBootTest(properties=)}
 * so both cases share ONE context -- the property is bound at startup, so a per-value context would mean two
 * full boots to exercise one branch.
 *
 * <p>{@code @MockitoBean}, deliberately NOT {@code @MockitoSpyBean}: a spy would run the REAL sweep, which
 * walks every recently re-scanned release in the shared {@code rearm-test-pg}, writes v3 rows other tests
 * assert on, and can vacuously v3-certify their orgs. It would also make the assertion depend on winning the
 * {@code BACKFILL_FINDING_CHANGE_V3} advisory lock, so lock contention would surface as a kill-switch
 * regression. The mock keeps this a test of the switch and nothing else.
 */
@SpringBootTest(classes = {App.class})
public class RepairSweepKillSwitchTest {

	@MockitoBean private FindingChangeEventBackfillService backfillService;
	@Autowired private SchedulingService schedulingService;

	private void setSweep(boolean enabled, int lookbackDays) {
		ReflectionTestUtils.setField(schedulingService, "findingChangeRepairEnabled", enabled);
		ReflectionTestUtils.setField(schedulingService, "findingChangeRepairLookbackDays", lookbackDays);
	}

	@Test
	void disabledSweepDoesNotTouchTheStore() {
		setSweep(false, 2);
		schedulingService.repairFindingChangeV3();
		verify(backfillService, never()).repairSweepV3(anyInt());
	}

	@Test
	void enabledSweepRunsWithTheConfiguredLookback() {
		setSweep(true, 3);
		schedulingService.repairFindingChangeV3();
		// Pins the lookback wiring too: it was a hard-coded 2 before it became tunable, and an override that
		// was silently ignored would leave an operator believing they had widened the window.
		verify(backfillService).repairSweepV3(3);
	}
}
