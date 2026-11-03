/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;

import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.SchedulingConfig;

/**
 * TPF-138 mutation round: the recent lane's finder runs the way the normal lane's does (design 4.1 and
 * 4.5.7), in a transaction with JIT off and a 10 second statement timeout, because it runs on the shared
 * scheduler thread. The settings are read inside the finder call, on the connection the finder uses; the
 * normal lane's finder is read the same way as the control that shows the probe sees the settings.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@TestPropertySource(properties = SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false")
public class MetricsRecentLaneFinderSettingsTest {

	@Autowired private ReleaseService releaseService;
	@Autowired private EntityManager entityManager;

	@MockitoSpyBean private ReleaseMetricsComputeService releaseMetricsComputeService;

	private Object releaseServiceTarget;
	private Object realRepository;

	@BeforeEach
	void setUp() {
		doAnswer(inv -> false).when(releaseMetricsComputeService).computeReleaseMetricsOnRescan(any(UUID.class));
		releaseServiceTarget = AopTestUtils.getUltimateTargetObject(releaseService);
		realRepository = ReflectionTestUtils.getField(releaseServiceTarget, "repository");
	}

	@AfterEach
	void tearDown() {
		ReflectionTestUtils.setField(releaseServiceTarget, "repository", realRepository);
		Mockito.reset(releaseMetricsComputeService);
	}

	@Test
	void bothLanesFindersRunWithJitOffAndATenSecondStatementTimeout() {
		ReleaseRepository real = (ReleaseRepository) realRepository;
		ReleaseRepository probing = Mockito.mock(ReleaseRepository.class, AdditionalAnswers.delegatesTo(real));
		List<String> recent = new ArrayList<>();
		List<String> normal = new ArrayList<>();
		doAnswer(inv -> {
			recent.add(settings());
			return real.findReleasesForMetricsComputeRecent(inv.getArgument(0), inv.getArgument(1));
		}).when(probing).findReleasesForMetricsComputeRecent(anyInt(), anyInt());
		doAnswer(inv -> {
			normal.add(settings());
			return real.findReleasesForMetricsComputeByUpdate(inv.getArgument(0));
		}).when(probing).findReleasesForMetricsComputeByUpdate(anyInt());
		ReflectionTestUtils.setField(releaseServiceTarget, "repository", probing);

		releaseService.computeMetricsForAllUnprocessedReleases(102, SchedulingService.METRICS_RECENT_BATCH_LIMIT,
				SchedulingService.METRICS_RECENT_WINDOW_MINUTES);

		assertEquals(List.of("off/10s"), normal, "control: the normal lane's finder runs with jit off and a 10 s timeout");
		assertEquals(List.of("off/10s"), recent, "the recent lane's finder runs with jit off and a 10 s timeout");
	}

	@Test
	void theTickHandsEachFinderTheWindowAndTheLimitItWasGiven() {
		ReleaseRepository real = (ReleaseRepository) realRepository;
		ReleaseRepository probing = Mockito.mock(ReleaseRepository.class, AdditionalAnswers.delegatesTo(real));
		List<List<Integer>> recent = new ArrayList<>();
		List<List<Integer>> normal = new ArrayList<>();
		doAnswer(inv -> {
			int window = inv.getArgument(0);
			int cap = inv.getArgument(1);
			recent.add(List.of(window, cap));
			return real.findReleasesForMetricsComputeRecent(window, cap);
		}).when(probing).findReleasesForMetricsComputeRecent(anyInt(), anyInt());
		doAnswer(inv -> {
			int limit = inv.getArgument(0);
			normal.add(List.of(limit));
			return real.findReleasesForMetricsComputeByUpdate(limit);
		}).when(probing).findReleasesForMetricsComputeByUpdate(anyInt());
		ReflectionTestUtils.setField(releaseServiceTarget, "repository", probing);

		releaseService.computeMetricsForAllUnprocessedReleases(5, 3, 7);
		releaseService.computeMetricsForAllUnprocessedReleases(102, SchedulingService.METRICS_RECENT_BATCH_LIMIT,
				SchedulingService.METRICS_RECENT_WINDOW_MINUTES);

		assertEquals(List.of(List.of(7, 3), List.of(SchedulingService.METRICS_RECENT_WINDOW_MINUTES,
				SchedulingService.METRICS_RECENT_BATCH_LIMIT)), recent,
				"the recent finder is asked for (window, cap) as the tick was given them");
		assertEquals(List.of(List.of(5), List.of(102)), normal,
				"the normal finder is asked for the whole limit the tick was given, whatever the recent lane took");
	}

	private String settings() {
		return (String) entityManager.createNativeQuery(
				"SELECT current_setting('jit') || '/' || current_setting('statement_timeout')").getSingleResult();
	}
}
