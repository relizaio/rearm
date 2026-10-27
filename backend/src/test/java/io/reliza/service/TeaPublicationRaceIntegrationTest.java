/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;

import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * TEA-5, against the test database: concurrent acts on one release are a sequence. Round 2
 * case 71 (T-2): a hide racing a re-publish in this process, and three concurrent hides. Round 3
 * (T-6): another process is a second thread calling {@code publishLocked} or {@code hideLocked},
 * which skips the in-process lock and takes the release's lease, exactly as another instance
 * does; case 88 (case 71's other half), 85 and 86. Round 4 (T-8): each thread of 85, 86 and 88
 * runs as a request, inside its own {@code asRequest} persistence context.
 */
public class TeaPublicationRaceIntegrationTest extends TeaPublicationIntegrationTestBase {

	private static String describe(ReleaseUpdateEvent e) {
		return e.rua() + " " + e.oldValue() + " -> " + e.newValue();
	}

	/** The engine bean itself, so its package-private seams run on the real instance. */
	private TeaPublicationService engine() {
		return AopTestUtils.getUltimateTargetObject(teaPublicationService);
	}

	private static TeaPublishRequest request(World w, TeaPublishMode mode) {
		return new TeaPublishRequest(w.release(), mode, null, WU);
	}

	private int releaseRevision(UUID release) {
		return sharedReleaseService.getRelease(release).orElseThrow().getRevision();
	}

	private List<String> teaEvents(UUID release) {
		return events(release, ReleaseUpdateScope.TEA_PUBLICATION).stream().map(TeaPublicationRaceIntegrationTest::describe)
				.toList();
	}

	// ---------------------------------------------------------------- 88 (case 71's another-process half)

	@Test
	void aHideFromAnotherProcessDuringARepublishWaitsForItAndHidesWhatItWrote() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGate = gate;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<TeaPublishResult> republish = pool.submit(() -> asRequest(w.release(),
					() -> publish(w.release(), TeaPublishMode.REPUBLISH)));
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the re-publish never reached the merge");
			// another instance: no in-process lock, the lease alone
			Future<TeaPublicationData> hide = pool.submit(() -> asRequest(w.release(),
					() -> engine().hideLocked(w.release(), null, WU)));
			Thread.sleep(1500);
			assertFalse(hide.isDone(), "the hide must wait for the re-publish on the release's lease");
			gate.countDown();

			TeaPublishResult r = republish.get(120, TimeUnit.SECONDS);
			assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
			assertEquals(2, r.collection().getVersion());
			TeaPublicationData hidden = hide.get(60, TimeUnit.SECONDS);
			assertEquals(TeaPublicationState.HIDDEN, hidden.getState());
			assertEquals(2, hidden.getLatestVersion());
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		assertEquals(List.of("ADDED null -> v1", "CHANGED v1 -> v2", "REMOVED PUBLISHED v2 -> HIDDEN"),
				teaEvents(w.release()));

		mergedPurls.get(w.release()).add("pkg:generic/gauge@3.0.0");
		assertEquals(3, publish(w.release(), TeaPublishMode.REPUBLISH).collection().getVersion());
		assertEquals(3, collectionRepository.listByPublication(
				teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow().getUuid()).size());
	}

	// ---------------------------------------------------------------- 85

	@Test
	void twoFirstPublishesAsTwoProcessesWriteOnePublication() throws Exception {
		assertTwoFirstPublishes(TeaPublishMode.PUBLISH);
	}

	@Test
	void aFirstPublishAndAPublishOrRepublishAsTwoProcessesWriteOnePublication() throws Exception {
		assertTwoFirstPublishes(TeaPublishMode.PUBLISH_OR_REPUBLISH);
	}

	private void assertTwoFirstPublishes(TeaPublishMode secondMode) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		int revision = releaseRevision(w.release());
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGate = gate;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<TeaPublishResult> first = pool.submit(() -> asRequest(w.release(),
					() -> engine().publishLocked(request(w, TeaPublishMode.PUBLISH))));
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the first publish never reached the merge");
			Future<TeaPublishResult> second = pool.submit(() -> asRequest(w.release(),
					() -> engine().publishLocked(request(w, secondMode))));
			Thread.sleep(1500);
			assertFalse(second.isDone(), "the second process must wait on the release's lease");
			gate.countDown();

			TeaPublishResult r = first.get(120, TimeUnit.SECONDS);
			assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
			assertEquals(1, r.collection().getVersion());
			if (TeaPublishMode.PUBLISH == secondMode) {
				ExecutionException e = assertThrows(ExecutionException.class, () -> second.get(120, TimeUnit.SECONDS));
				assertTrue(e.getCause() instanceof TeaPublishRefusedException, String.valueOf(e.getCause()));
				assertEquals(TeaPublishRefusal.ALREADY_PUBLISHED, ((TeaPublishRefusedException) e.getCause()).reason());
			} else {
				assertEquals(TeaPublishOutcome.UNCHANGED, second.get(120, TimeUnit.SECONDS).outcome());
			}
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow();
		assertEquals(1, head.getLatestVersion());
		assertEquals(1, collectionRepository.listByPublication(head.getUuid()).size());
		ReleaseData rd = stored(w.release());
		assertEquals(List.of(TeaAggregates.defaultAggregate(head)), rd.getSyntheticArtifacts());
		assertEquals(1, uploaded.size(), "one aggregate uploaded");
		verify(rebomService, times(1)).uploadSbom(any(), any(), any(), any(), any());
		assertEquals(1, events(w.release(), ReleaseUpdateScope.SYNTHETIC_ARTIFACT).size());
		assertEquals(List.of("ADDED null -> v1"), teaEvents(w.release()));
		assertEquals(revision + 1, releaseRevision(w.release()), "one release write per act");
	}

	// ---------------------------------------------------------------- 86

	@Test
	void twoRepublishesAsTwoProcessesAfterOneChangeWriteOneVersion() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGate = gate;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<TeaPublishOutcome> outcomes = new ArrayList<>();
		try {
			Future<TeaPublishResult> first = pool.submit(() -> asRequest(w.release(),
					() -> engine().publishLocked(request(w, TeaPublishMode.REPUBLISH))));
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the first re-publish never reached the merge");
			Future<TeaPublishResult> second = pool.submit(() -> asRequest(w.release(),
					() -> engine().publishLocked(request(w, TeaPublishMode.REPUBLISH))));
			Thread.sleep(1500);
			assertFalse(second.isDone(), "the second process must wait on the release's lease");
			gate.countDown();
			outcomes.add(first.get(120, TimeUnit.SECONDS).outcome());
			outcomes.add(second.get(120, TimeUnit.SECONDS).outcome());
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		assertEquals(List.of(TeaPublishOutcome.REPUBLISHED, TeaPublishOutcome.UNCHANGED), outcomes);
		TeaPublicationData head = teaPublicationService.getPublicationOfRelease(w.release()).orElseThrow();
		assertEquals(2, head.getLatestVersion());
		assertEquals(2, collectionRepository.listByPublication(head.getUuid()).size());
		ArtifactData aggregate = artifactService.getArtifactData(TeaAggregates.defaultAggregate(head)).orElseThrow();
		assertEquals("2", aggregate.getVersion());
		assertEquals(1, aggregate.getPreviousVersions().size());
		// the first publish and one bump; the second process uploaded nothing (the bump keeps the serial)
		verify(rebomService, times(2)).uploadSbom(any(), any(), any(), any(), any());
		assertEquals(1, uploaded.size());
		assertEquals(List.of("ADDED null -> v1", "CHANGED v1 -> v2"), teaEvents(w.release()));
	}

	@Test
	void aHideInThisProcessWaitsForTheRepublishAndHidesWhatItWrote() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		mergedPurls.get(w.release()).add("pkg:generic/valve@2.0.0");
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGate = gate;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<TeaPublishResult> republish = pool.submit(() -> publish(w.release(), TeaPublishMode.REPUBLISH));
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the re-publish never reached the merge");
			Future<TeaPublicationData> hide = pool.submit(() -> teaPublicationService.hide(w.release(), null, WU));
			Thread.sleep(300);
			assertFalse(hide.isDone(), "the hide must wait for the re-publish in this process");
			gate.countDown();

			assertEquals(TeaPublishOutcome.REPUBLISHED, republish.get(120, TimeUnit.SECONDS).outcome());
			TeaPublicationData hidden = hide.get(60, TimeUnit.SECONDS);
			assertEquals(TeaPublicationState.HIDDEN, hidden.getState());
			assertEquals(2, hidden.getLatestVersion());
		} finally {
			mergeGate = null;
			gate.countDown();
			pool.shutdownNow();
		}
		assertEquals(List.of("ADDED null -> v1", "CHANGED v1 -> v2", "REMOVED PUBLISHED v2 -> HIDDEN"),
				teaEvents(w.release()));
	}

	@Test
	void threeConcurrentHidesWriteOneEventAndOneHeadRevision() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		int revision = publicationRepository.findByRelease(w.release()).orElseThrow().getRevision();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			List<Future<TeaPublicationData>> futures = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return teaPublicationService.hide(w.release(), null, WU);
				}));
			}
			start.countDown();
			for (Future<TeaPublicationData> f : futures) {
				assertEquals(TeaPublicationState.HIDDEN, f.get(60, TimeUnit.SECONDS).getState());
			}
		} finally {
			pool.shutdownNow();
		}
		TeaPublication head = publicationRepository.findByRelease(w.release()).orElseThrow();
		assertEquals(revision + 1, head.getRevision());
		assertEquals(1, teaEvents(w.release()).stream().filter(e -> e.startsWith("REMOVED")).count());
	}
}
