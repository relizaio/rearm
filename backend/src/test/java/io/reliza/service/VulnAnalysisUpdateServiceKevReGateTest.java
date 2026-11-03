/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TPF-131 (architecture round 6, 6.2 rows 5 and 6): the KEV re-gate issues ONE statement per
 * organization for the whole newly-KEV list, recomputes each release once, and queues an
 * organization whose statement failed for the next KEV sync tick instead of only logging it.
 * Mocks injected by reflection as {@code KevCatalogSyncServiceTest} does.
 */
class VulnAnalysisUpdateServiceKevReGateTest {

	private SharedReleaseService sharedReleaseService;
	private ReleaseService releaseService;
	private VulnAnalysisUpdateService service;

	@BeforeEach
	void wireMocks() throws Exception {
		sharedReleaseService = mock(SharedReleaseService.class);
		releaseService = mock(ReleaseService.class);
		service = new VulnAnalysisUpdateService();
		inject("sharedReleaseService", sharedReleaseService);
		inject("releaseService", releaseService);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = VulnAnalysisUpdateService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(service, value);
	}

	@Test
	void oneStatementForAllIdsAndEachReleaseRecomputedOnce() {
		UUID org = UUID.randomUUID();
		UUID r1 = UUID.randomUUID();
		UUID r2 = UUID.randomUUID();
		UUID r3 = UUID.randomUUID();
		List<String> ids = List.of("CVE-A", "CVE-B", "CVE-C");
		when(sharedReleaseService.findReleaseUuidsWithAnyVulnerabilityAnyLocation(org, ids))
				.thenReturn(List.of(r1, r2, r1, r3));

		service.recomputeReleasesForNewlyKev(ids, org);

		verify(sharedReleaseService, times(1)).findReleaseUuidsWithAnyVulnerabilityAnyLocation(org, ids);
		verify(sharedReleaseService, never()).findReleaseUuidsWithVulnerabilityAnyLocation(any(), any());
		verify(releaseService, times(1)).computeReleaseMetrics(r1, false);
		verify(releaseService, times(1)).computeReleaseMetrics(r2, false);
		verify(releaseService, times(1)).computeReleaseMetrics(r3, false);
		verify(releaseService, times(3)).computeReleaseMetrics(any(), anyBoolean());
		assertEquals(Map.of(), service.drainKevReGateRetries());
	}

	@Test
	void anEmptyOrNullIdListOrANullOrgCallsNothing() {
		UUID org = UUID.randomUUID();
		service.recomputeReleasesForNewlyKev(List.of(), org);
		service.recomputeReleasesForNewlyKev(null, org);
		service.recomputeReleasesForNewlyKev(List.of("CVE-A"), null);
		verifyNoInteractions(sharedReleaseService, releaseService);
	}

	@Test
	void aFailedStatementQueuesTheOrgForTheNextTickAndTheDrainClearsIt() {
		UUID o1 = UUID.randomUUID();
		when(sharedReleaseService.findReleaseUuidsWithAnyVulnerabilityAnyLocation(eq(o1), any()))
				.thenThrow(new IllegalStateException("canceling statement due to user request"));

		service.recomputeReleasesForNewlyKev(List.of("A", "B", "C"), o1);

		verify(releaseService, never()).computeReleaseMetrics(any(), anyBoolean());
		assertEquals(Map.of(o1, Set.of("A", "B", "C")), service.drainKevReGateRetries());
		assertEquals(Map.of(), service.drainKevReGateRetries(), "the drain takes and clears");
	}

	@Test
	void twoFailuresOfOneOrgMergeIntoOneSet() {
		UUID o1 = UUID.randomUUID();
		UUID o2 = UUID.randomUUID();
		when(sharedReleaseService.findReleaseUuidsWithAnyVulnerabilityAnyLocation(eq(o1), any()))
				.thenThrow(new IllegalStateException("simulated"));
		when(sharedReleaseService.findReleaseUuidsWithAnyVulnerabilityAnyLocation(eq(o2), any()))
				.thenReturn(List.of());

		service.recomputeReleasesForNewlyKev(List.of("A", "B"), o1);
		service.recomputeReleasesForNewlyKev(List.of("B", "C"), o1);
		service.recomputeReleasesForNewlyKev(List.of("D"), o2);

		assertEquals(Map.of(o1, Set.of("A", "B", "C")), service.drainKevReGateRetries(),
				"one merged set for o1, nothing for the org whose statement succeeded");
	}
}
