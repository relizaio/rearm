/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.TeaPublishLock.Lease;

/**
 * TEA-7, section 4.3 cases 24, 25, 28 and 30, plus the two database mutation guards of 4.3: what
 * the TEA act queue does when the act cannot finish now (a publish in progress elsewhere), when
 * the engine refuses it (noted once, cured by the next pass), for a product's cascade, when a
 * worker died holding its claim, when the pass rolls back, and when a request arrives while the
 * act runs. Acts run from the test thread; the hop is mocked (TeaOutputEventQueueIntegrationTestBase).
 */
public class TeaOutputEventRetryIntegrationTest extends TeaOutputEventQueueIntegrationTestBase {

	@Autowired TeaPublishLock teaPublishLock;
	@Autowired PlatformTransactionManager transactionManager;

	private TeaPublicationService engine() {
		return AopTestUtils.getUltimateTargetObject(teaPublicationService);
	}

	@AfterEach
	void restoreWait() {
		engine().setBackgroundWait(null);
		mergeGate = null;
		mergeGateRelease = null;
	}

	private boolean fencedInFuture(UUID release) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT (flow_control->>'teaActSkipUntil')::timestamptz > now() "
				+ "FROM rearm.releases WHERE uuid = ?", Boolean.class, release));
	}

	// ---------------------------------------------------------------- 24

	@Test
	void aPublishInProgressElsewhereIsRetriedLater() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Approved a = approvedWithPublishRule("pkg:generic/pump@1.0.0");
		UUID release = a.w().release();
		engine().setBackgroundWait(Duration.ofSeconds(1));
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGateRelease = release;
		mergeGate = gate;
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// another instance: no in-process lock, the lease alone, held open at the merge
			Future<TeaPublishResult> holder = pool.submit(() -> asRequest(release,
					() -> engine().publishLocked(new TeaPublishRequest(release, TeaPublishMode.PUBLISH, null, WU))));
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the holder never reached the merge");

			teaOutputEventService.runRequested(release);

			assertTrue(head(release).isEmpty(), "the worker wrote nothing");
			assertTrue(events(release, ReleaseUpdateScope.GUARD).isEmpty(), "a retry is not a refusal");
			FlowControl fc = teaAct(release);
			assertNotNull(fc.teaActRequestedAt());
			assertEquals(TeaActKind.PUBLISH, fc.teaActKind());
			assertEquals(a.publish().getUuid(), fc.teaActTrigger());
			assertEquals(1, fc.teaActFailureCount());
			assertTrue(fencedInFuture(release), "fenced: " + fc.teaActSkipUntil());
			assertNull(fc.teaActClaimedAt(), "the claim is released");
			assertFalse(pending(release), "the drain does not see it while the fence stands");

			gate.countDown();
			assertEquals(1, holder.get(120, TimeUnit.SECONDS).publication().getLatestVersion());
		} finally {
			gate.countDown();
			pool.shutdownNow();
		}
		shift(release, "teaActSkipUntil", "-30 minutes");
		assertTrue(pending(release));
		teaOutputEventService.runRequested(release);
		assertEquals(1, head(release).orElseThrow().getLatestVersion(), "UNCHANGED");
		assertTrue(noTeaActKey(release));
		assertTrue(events(release, ReleaseUpdateScope.GUARD).isEmpty());
	}

	@Test
	void aPublishWaitingOnAHideRepublishesWhatTheHideHid() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		approve(w.release(), entry);
		engine().setBackgroundWait(Duration.ofSeconds(1));

		// the hide's act holds the lease (another instance) while the worker tries
		try (Lease held = teaPublishLock.acquire(w.release(), Duration.ofSeconds(10))) {
			assertNotNull(held);
			teaOutputEventService.runRequested(w.release());
			assertEquals(1, teaAct(w.release()).teaActFailureCount());
		}
		teaPublicationService.hide(w.release(), "hidden by a person", WU);
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());

		shift(w.release(), "teaActSkipUntil", "-30 minutes");
		teaOutputEventService.runRequested(w.release());

		assertEquals(TeaPublicationState.PUBLISHED, head(w.release()).orElseThrow().getState());
		List<ReleaseUpdateEvent> tea = events(w.release(), ReleaseUpdateScope.TEA_PUBLICATION);
		ReleaseUpdateEvent last = tea.get(tea.size() - 1);
		assertEquals("HIDDEN", last.oldValue());
		assertEquals("PUBLISHED v1", last.newValue());
		assertTrue(noTeaActKey(w.release()));
	}

	// ---------------------------------------------------------------- 25

	@Test
	void aContentRefusalIsNotedOnceAndCuredByTheNextPass() throws Exception {
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE,
						TeaProductComponents.PUBLISH_WITH_PRODUCT).minimumLifecycle(ReleaseLifecycle.READY_TO_SHIP).build(),
				TeaCaller.ORG_ADMIN, WU);
		Approved a = approvedWithPublishRule("pkg:generic/pump@1.0.0");
		UUID release = a.w().release();

		teaOutputEventService.runRequested(release);

		assertTrue(head(release).isEmpty());
		assertTrue(noTeaActKey(release), "a content refusal ends the request");
		List<ReleaseUpdateEvent> guard = events(release, ReleaseUpdateScope.GUARD);
		assertEquals(1, guard.size());
		assertEquals(a.publish().getUuid(), guard.get(0).objectId());
		assertEquals("publish-on-approval", guard.get(0).oldValue());
		assertEquals("TEA_PUBLISH refused: LIFECYCLE_TOO_LOW", guard.get(0).newValue());
		assertTrue(guard.get(0).message().startsWith("Action 'publish-on-approval': "), guard.get(0).message());
		assertTrue(guard.get(0).message().contains("READY_TO_SHIP"), guard.get(0).message());

		ossReleaseService.processRelease(release);
		teaOutputEventService.runRequested(release);
		assertEquals(1, events(release, ReleaseUpdateScope.GUARD).size(), "the same refusal is noted once");

		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.READY_TO_SHIP, WU);
		teaOutputEventService.runRequested(release);
		assertEquals(1, head(release).orElseThrow().getLatestVersion(), "the next pass publishes");
		assertTrue(noTeaActKey(release));
	}

	@Test
	void aProfileConflictIsNotedNamingThePerspectives() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		String nameP = "tea7-p-" + UUID.randomUUID().toString().substring(0, 6);
		String nameQ = "tea7-q-" + UUID.randomUUID().toString().substring(0, 6);
		UUID p = perspectiveService.createPerspective(org.getUuid(), nameP, WU).getUuid();
		UUID q = perspectiveService.createPerspective(org.getUuid(), nameQ, WU).getUuid();
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.PERSPECTIVE, p, profileInput(TeaPublishing.ENABLED,
				TeaVisibility.PRIVATE, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT).build(),
				TeaCaller.ORG_ADMIN, WU);
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.PERSPECTIVE, q, profileInput(TeaPublishing.ENABLED,
				TeaVisibility.PRIVATE, TeaRawArtifacts.ALL, TeaProductComponents.PUBLISH_WITH_PRODUCT).build(),
				TeaCaller.ORG_ADMIN, WU);
		perspectiveService.setPerspectivesOnComponent(w.component(), List.of(p, q), TeaCaller.ORG_ADMIN, WU);
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);

		approve(w.release(), entry);
		assertTrue(queued(w.release()), "a conflict is the worker's to note, not a pre-filter skip");
		teaOutputEventService.runRequested(w.release());

		List<ReleaseUpdateEvent> guard = events(w.release(), ReleaseUpdateScope.GUARD);
		assertEquals(1, guard.size());
		assertEquals("TEA_PUBLISH refused: PROFILE_CONFLICT", guard.get(0).newValue());
		assertTrue(guard.get(0).message().contains(nameP) && guard.get(0).message().contains(nameQ),
				guard.get(0).message());
		assertTrue(noTeaActKey(w.release()));
	}

	// ---------------------------------------------------------------- 28

	@Test
	void aRuleOnAProductCascadesAndNotesARefusedChild() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World child = componentRelease("pkg:generic/pump@1.0.0");
		UUID product = product(List.of(child.release()));
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-product");
		configure(componentOf(product), rule("approved", approved(entry), publish), publish);
		approve(product, entry);

		teaOutputEventService.runRequested(product);

		TeaPublicationData ph = head(product).orElseThrow();
		assertEquals(1, ph.getLatestVersion());
		TeaPublicationData ch = head(child.release()).orElseThrow();
		assertEquals(TeaPublicationState.PUBLISHED, ch.getState());
		assertEquals(ph.getUuid(), ch.getCascadeOf());
		List<ReleaseUpdateEvent> childTea = events(child.release(), ReleaseUpdateScope.TEA_PUBLICATION);
		assertEquals("Action 'publish-product' fired by rule 'approved'", childTea.get(childTea.size() - 1).message());

		saveComponentProfile(child.component(), TeaPublishing.DISABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE);
		ossReleaseService.processRelease(product);
		teaOutputEventService.runRequested(product);

		assertEquals(1, head(product).orElseThrow().getLatestVersion(), "the product is UNCHANGED");
		List<ReleaseUpdateEvent> guard = events(product, ReleaseUpdateScope.GUARD);
		assertEquals(1, guard.size());
		String childName = getComponentService.getComponentData(child.component()).orElseThrow().getName();
		assertEquals("TEA_PUBLISH: 1 of 1 components refused: " + childName + " (PUBLISHING_DISABLED)",
				guard.get(0).newValue());
		assertTrue(noTeaActKey(product));
	}

	// ---------------------------------------------------------------- 30

	@Test
	void aCrashedWorkersClaimExpires() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID release = approvedWithPublishRule("pkg:generic/pump@1.0.0").w().release();

		assertEquals(1, teaOutputEventService.claimTx(release), "a worker claims it and dies");
		assertFalse(pending(release), "a live claim hides it from the drain");
		assertEquals(0, teaOutputEventService.claimTx(release), "and from a second claim");

		shift(release, "teaActClaimedAt", "-16 minutes");
		assertTrue(pending(release), "the lease expired");
		teaOutputEventService.runRequested(release);
		assertEquals(1, head(release).orElseThrow().getLatestVersion());
		assertTrue(noTeaActKey(release));
	}

	// ---------------------------------------------------------------- 4.3 mutation guards

	@Test
	void aPassThatRollsBackTakesItsRequestWithIt() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Approved a = approvedWithPublishRule("pkg:generic/pump@1.0.0");
		UUID release = a.w().release();
		teaOutputEventService.runRequested(release);
		assertTrue(noTeaActKey(release));

		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
			ossReleaseService.processRelease(release);
			assertNotNull(jdbcTemplate.queryForObject("SELECT flow_control->>'teaActRequestedAt' FROM rearm.releases "
					+ "WHERE uuid = ?", String.class, release), "the request is written inside the pass's transaction");
			throw new IllegalStateException("the pass's save failed after the request");
		}));

		assertTrue(noTeaActKey(release), "the request rolled back with the pass");
		assertEquals(1, triggerRows(release, a.publish().getUuid()).size());
	}

	@Test
	void aRequestArrivingDuringTheActSurvivesItsClearAndTheChainRunsIt() throws Exception {
		// round 1's lost-wakeup guard, amended by case 42 (round 2, T-3): the kept request is no
		// longer left for the drain; the same worker runs it before it returns.
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Approved a = approvedWithPublishRule("pkg:generic/pump@1.0.0");
		UUID release = a.w().release();
		long mergesBefore = mergeCountOf(release);
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGateRelease = release;
		mergeGate = gate;
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> worker = pool.submit(() -> {
				teaOutputEventService.runRequested(release);
				return null;
			});
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the act never reached the merge");
			String claimedAt = teaAct(release).teaActClaimedAt();
			assertNotNull(claimedAt);

			ossReleaseService.processRelease(release);
			assertTrue(requestedAfterClaim(release), "the request made during the act is newer than the claim");

			gate.countDown();
			worker.get(120, TimeUnit.SECONDS);
		} finally {
			gate.countDown();
			pool.shutdownNow();
		}
		assertEquals(1, head(release).orElseThrow().getLatestVersion(), "the second act answered UNCHANGED");
		assertEquals(mergesBefore + 2, mergeCountOf(release), "two acts ran, the second one chained");
		assertTrue(noTeaActKey(release), "nothing is left for the drain");
		assertFalse(pending(release));
	}

	// ---------------------------------------------------------------- 42, second half (round 2, T-3)

	@Test
	void aRequestFencedBeforeTheChainClaimsIsNotChained() throws Exception {
		// The fence a chained claim can meet is one written after the act's clear (the clear's
		// lost-wakeup branch drops a fence the kept request carried, case 34). It is written here
		// from the after-commit hop of the TEA_HIDES pass, the last thing before the next claim.
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID release = w.release();
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		configure(w.component(), List.of(rule("qa approved", approved(e1), p), rule("security disapproved",
				disapproved(e2), h)), p, h);
		approve(release, e1);
		assertTrue(queued(release));

		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGateRelease = release;
		mergeGate = gate;
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> worker = pool.submit(() -> {
				teaOutputEventService.runRequested(release);
				return null;
			});
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the act never reached the merge");
			voteSilently(release, e2, ApprovalState.DISAPPROVED);
			TransactionTemplate own = new TransactionTemplate(transactionManager);
			own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			doAnswer(inv -> {
				own.executeWithoutResult(st -> fence(release, "10 minutes", null));
				return null;
			}).when(teaActDispatcher).asyncRun(release);

			gate.countDown();
			worker.get(120, TimeUnit.SECONDS);
		} finally {
			gate.countDown();
			pool.shutdownNow();
		}
		assertEquals(TeaPublicationState.PUBLISHED, head(release).orElseThrow().getState(), "the hide was not chained");
		FlowControl fc = teaAct(release);
		assertEquals(TeaActKind.HIDE, fc.teaActKind(), "the TEA_HIDES pass requested the hide");
		assertNull(fc.teaActClaimedAt(), "and no claim was taken through the fence");
		assertTrue(fencedAhead(release));
		assertEquals(1, triggerRows(release, h.getUuid()).size());

		shift(release, "teaActSkipUntil", "-30 minutes");
		teaOutputEventService.runRequested(release);
		assertEquals(TeaPublicationState.HIDDEN, head(release).orElseThrow().getState());
		assertTrue(noTeaActKey(release));
	}
}
