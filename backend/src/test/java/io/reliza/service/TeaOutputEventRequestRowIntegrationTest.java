/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.TeaPublishLock.Lease;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;

/**
 * TEA-7, round 3, case 43 (T-9 point 1): a further PUBLISH request on a row whose publish is
 * pending keeps the publish's retry fence and failure count; only a HIDE drops them (case 39).
 * Without it, every matching NORMAL pass would reset a fenced publish's backoff and its cap.
 * Round 2 cases 33 to 35 (T-4: the claim's fence, the lost-wakeup clear) and 39 (T-2: a publish
 * never overwrites a pending hide) are the same row's rules and live here too.
 */
public class TeaOutputEventRequestRowIntegrationTest extends TeaOutputEventQueueIntegrationTestBase {

	@Autowired PlatformTransactionManager transactionManager;

	@Test
	void aPublishRequestKeepsTheFenceAndTheCount() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID release = w.release();
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		approve(release, entry);
		assertTrue(queued(release), "the approval queued a publish");
		fence(release, "60 seconds", 2);
		FlowControl before = teaAct(release);
		Thread.sleep(20);

		ossReleaseService.processRelease(release);

		FlowControl after = teaAct(release);
		assertEquals(TeaActKind.PUBLISH, after.teaActKind());
		assertEquals(before.teaActSkipUntil(), after.teaActSkipUntil(), "the fence is kept");
		assertEquals(2, after.teaActFailureCount(), "the failure count is kept");
		assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT (flow_control->>'teaActRequestedAt')::timestamptz "
				+ "> CAST(? AS timestamptz) FROM rearm.releases WHERE uuid = ?", Boolean.class, before.teaActRequestedAt(),
				release)), "the request is stamped again: " + before.teaActRequestedAt() + " then " + after.teaActRequestedAt());
		assertTrue(fencedAhead(release));
		assertEquals(0, teaOutputEventService.claimTx(release), "the fence still stands");
	}

	// ---------------------------------------------------------------- 33 to 35 (round 2, T-4)

	@Test
	void theClaimHonoursTheRetryFence() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		UUID release = approvedWithPublishRule("pkg:generic/pump@1.0.0").w().release();
		fence(release, "60 seconds", null);

		assertEquals(0, teaOutputEventService.claimTx(release), "fenced");
		teaOutputEventService.runRequested(release);
		assertTrue(head(release).isEmpty(), "nothing ran through the fence");
		assertNull(teaAct(release).teaActClaimedAt());
		assertTrue(queued(release));

		shift(release, "teaActSkipUntil", "-30 minutes");
		assertEquals(1, teaOutputEventService.claimTx(release), "the fence has passed");
	}

	@Test
	void theLostWakeupClearStartsTheKeptRequestClean() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Approved a = approvedWithPublishRule("pkg:generic/pump@1.0.0");
		UUID release = a.w().release();
		assertEquals(1, teaOutputEventService.claimTx(release));
		fence(release, "5 minutes", 3);
		assertEquals(1, releaseRepository.requestTeaAct(release, "PUBLISH", a.publish().getUuid().toString(),
				"publish-on-approval", "Action 'publish-on-approval' fired by rule 'approved' again"));
		assertTrue(requestedAfterClaim(release));

		teaOutputEventService.clearTx(release);

		FlowControl fc = teaAct(release);
		assertNotNull(fc.teaActRequestedAt(), "the newer request is kept");
		assertEquals(TeaActKind.PUBLISH, fc.teaActKind());
		assertEquals(a.publish().getUuid(), fc.teaActTrigger());
		assertEquals("publish-on-approval", fc.teaActTriggerName());
		assertEquals("Action 'publish-on-approval' fired by rule 'approved' again", fc.teaActComment());
		assertNull(fc.teaActClaimedAt());
		assertNull(fc.teaActSkipUntil(), "no stale fence");
		assertNull(fc.teaActFailureCount(), "no stale failure count");
	}

	@Test
	void aPassThatBeganBeforeTheClaimStillSurvivesTheClear() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		Approved a = approvedWithPublishRule("pkg:generic/pump@1.0.0");
		UUID release = a.w().release();
		CountDownLatch began = new CountDownLatch(1);
		CountDownLatch claimed = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> pass = pool.submit(() -> {
				new TransactionTemplate(transactionManager).executeWithoutResult(st -> {
					// the transaction's clock is fixed from here on: now() is this instant
					jdbcTemplate.queryForObject("SELECT now()", java.sql.Timestamp.class);
					began.countDown();
					try {
						assertTrue(claimed.await(60, TimeUnit.SECONDS));
					} catch (InterruptedException e) {
						throw new IllegalStateException(e);
					}
					releaseRepository.requestTeaAct(release, "PUBLISH", a.publish().getUuid().toString(),
							"publish-on-approval", "late pass");
				});
				return null;
			});
			assertTrue(began.await(60, TimeUnit.SECONDS));
			assertEquals(1, teaOutputEventService.claimTx(release));
			Thread.sleep(10);
			claimed.countDown();
			pass.get(60, TimeUnit.SECONDS);
		} finally {
			claimed.countDown();
			pool.shutdownNow();
		}
		assertTrue(requestedAfterClaim(release), "stamped when written, not when the pass began");

		teaOutputEventService.clearTx(release);
		assertTrue(queued(release), "the lost-wakeup branch keeps the late pass's request");
		assertEquals("late pass", teaAct(release).teaActComment());
	}

	// ---------------------------------------------------------------- 39 (round 2, T-2)

	@Test
	void aPublishNeverOverwritesAPendingHide() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		publish(w.release(), TeaPublishMode.PUBLISH);
		UUID entry = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "hide");
		configure(w.component(), rule("approved", approved(entry), p), p, h);
		assertEquals(1, releaseRepository.requestTeaAct(w.release(), "HIDE", h.getUuid().toString(), "hide",
				"Action 'hide' fired by rule 'disapproved'"));

		approve(w.release(), entry);

		assertTrue(triggerRows(w.release(), p.getUuid()).isEmpty(), "the publish was not requested, so not fired");
		FlowControl fc = teaAct(w.release());
		assertEquals(TeaActKind.HIDE, fc.teaActKind(), "the row still holds the hide");
		assertEquals(h.getUuid(), fc.teaActTrigger());
		assertEquals("Action 'hide' fired by rule 'disapproved'", fc.teaActComment());

		teaOutputEventService.runRequested(w.release());
		assertEquals(TeaPublicationState.HIDDEN, head(w.release()).orElseThrow().getState());
		assertTrue(noTeaActKey(w.release()));
	}

	@Test
	void aHideSupersedesAFencedPendingPublish() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "hide-on-disapproval");
		configure(w.component(), rule("disapproved", disapproved(entry), h), h);
		assertEquals(1, releaseRepository.requestTeaAct(w.release(), "PUBLISH", UUID.randomUUID().toString(),
				"publish-on-approval", "Action 'publish-on-approval' fired by rule 'approved'"));
		fence(w.release(), "10 minutes", 2);

		vote(w.release(), entry, ApprovalState.DISAPPROVED);

		FlowControl fc = teaAct(w.release());
		assertEquals(TeaActKind.HIDE, fc.teaActKind(), "a pending publish is something to hide");
		assertEquals(h.getUuid(), fc.teaActTrigger());
		assertNull(fc.teaActSkipUntil(), "the publish's fence is dropped");
		assertNull(fc.teaActFailureCount(), "and its failure count");
		assertEquals(1, triggerRows(w.release(), h.getUuid()).size());

		teaOutputEventService.runRequested(w.release());
		assertTrue(head(w.release()).isEmpty(), "nothing was ever exposed");
		assertTrue(noTeaActKey(w.release()));
		assertTrue(events(w.release(), ReleaseUpdateScope.GUARD).isEmpty(), "nothing to hide is no refusal");
	}
}
