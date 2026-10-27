/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl;
import io.reliza.model.FlowControl.TeaActKind;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.saas.TeaOutputEventService;

/**
 * TEA-7, section 4.3 cases 29 and 31: the after-commit hop end to end, on the real bounded
 * {@code teaActExecutor} (TestAsyncConfig replaces only the primary executor), and the switch
 * that pauses it. The worker is spied to see which thread runs it and what it saw then.
 */
public class TeaOutputEventHopIntegrationTest extends TeaOutputEventIntegrationTestBase {

	@MockitoSpyBean TeaOutputEventService spiedTeaOutputEventService;

	/** What the worker saw when it started, per release. */
	record Seen(String thread, boolean inTransaction, boolean requestVisible) {}

	private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();

	private void watchWorker() {
		doAnswer(inv -> {
			UUID release = inv.getArgument(0);
			// first entry only: the drain may hand the same release over again
			seen.putIfAbsent(release, new Seen(Thread.currentThread().getName(),
					TransactionSynchronizationManager.isActualTransactionActive(),
					null != jdbcTemplate.queryForObject("SELECT flow_control->>'teaActRequestedAt' FROM rearm.releases "
							+ "WHERE uuid = ?", String.class, release)));
			return inv.callRealMethod();
		}).when(spiedTeaOutputEventService).runRequested(any());
	}

	private void setEnabled(boolean on) {
		Object target = AopTestUtils.getUltimateTargetObject(spiedTeaOutputEventService);
		ReflectionTestUtils.setField(target, "enabled", on);
		if (target != spiedTeaOutputEventService) {
			try {
				ReflectionTestUtils.setField(spiedTeaOutputEventService, "enabled", on);
			} catch (RuntimeException e) {
				// the spy is the target itself on some proxy shapes; the target is what reads it
			}
		}
	}

	@AfterEach
	void switchBackOn() {
		setEnabled(true);
		mergeGate = null;
		mergeGateRelease = null;
	}

	private TeaPublicationData awaitPublished(UUID release) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			var h = head(release);
			if (h.isPresent() && TeaPublicationState.PUBLISHED == h.get().getState()) return h.get();
			Thread.sleep(250);
		}
		throw new AssertionError("release " + release + " was not published within 30 seconds");
	}

	// ---------------------------------------------------------------- 29

	@Test
	void anApprovalPublishesThroughTheHopWithoutAnyoneRunningTheWorker() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		watchWorker();

		approve(w.release(), entry);

		TeaPublicationData p = awaitPublished(w.release());
		assertEquals(1, p.getLatestVersion());
		Seen s = seen.get(w.release());
		assertNotNull(s, "the worker ran");
		assertTrue(s.thread().startsWith("tea-act-"), "on the TEA act executor, not " + s.thread());
		assertFalse(s.inTransaction(), "outside any transaction");
		assertTrue(s.requestVisible(), "after the approval's transaction committed: its request was readable");
		awaitNoTeaActKey(w.release());
	}

	private void awaitNoTeaActKey(UUID release) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (!noTeaActKey(release) && System.currentTimeMillis() < deadline) Thread.sleep(250);
		assertTrue(noTeaActKey(release), "the request was cleared");
	}

	// ---------------------------------------------------------------- 31

	@Test
	void theSwitchPausesTheHopAndTheDrainAndTheQueueRunsWhenItIsBackOn() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID entry = approvalEntry();
		ReleaseOutputEvent publish = teaAction(EventType.TEA_PUBLISH, "publish-on-approval");
		configure(w.component(), rule("approved", approved(entry), publish), publish);
		watchWorker();
		setEnabled(false);

		approve(w.release(), entry);
		assertTrue(queued(w.release()), "the request is recorded while switched off");
		spiedTeaOutputEventService.drainPending(10000);
		Thread.sleep(1500);
		verify(spiedTeaOutputEventService, never()).runRequested(w.release());
		assertTrue(head(w.release()).isEmpty());

		setEnabled(true);
		spiedTeaOutputEventService.drainPending(10000);

		verify(spiedTeaOutputEventService, timeout(30_000)).runRequested(w.release());
		assertEquals(1, awaitPublished(w.release()).getLatestVersion());
		assertTrue(seen.get(w.release()).thread().startsWith("tea-act-"), "the drain hands over, it does not run acts");
		awaitNoTeaActKey(w.release());
	}

	private TeaPublicationData awaitHidden(UUID release) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			var h = head(release);
			if (h.isPresent() && TeaPublicationState.HIDDEN == h.get().getState()) return h.get();
			Thread.sleep(250);
		}
		throw new AssertionError("release " + release + " was not hidden within 30 seconds: " + head(release));
	}

	private List<String> teaEvents(UUID release) {
		return events(release, ReleaseUpdateScope.TEA_PUBLICATION).stream()
				.map(e -> e.rua() + " " + e.oldValue() + " -> " + e.newValue() + " [" + e.message() + "]").toList();
	}

	// ---------------------------------------------------------------- 40 (round 2, T-3)

	@Test
	void aHideArrivingDuringThePublishRunsRightAfterIt() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID release = w.release();
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		configure(w.component(), List.of(rule("qa approved", approved(e1), p),
				rule("security disapproved", disapproved(e2), h)), p, h);
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGateRelease = release;
		mergeGate = gate;
		try {
			approve(release, e1);
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the hop never reached the merge");
			assertNotNull(teaAct(release).teaActClaimedAt(), "the hop holds the claim");

			vote(release, e2, ApprovalState.DISAPPROVED);

			FlowControl fc = teaAct(release);
			assertEquals(TeaActKind.HIDE, fc.teaActKind(), "the hide is requested over the claimed publish");
			assertTrue(requestedAfterClaim(release));
		} finally {
			gate.countDown();
		}

		awaitHidden(release);
		assertEquals(List.of("ADDED null -> v1 [Action 'p' fired by rule 'qa approved']",
				"REMOVED PUBLISHED v1 -> HIDDEN [Action 'h' fired by rule 'security disapproved']"), teaEvents(release));
		verify(spiedTeaOutputEventService, never()).drainPending(anyInt());
		awaitNoTeaActKey(release);
	}

	// ---------------------------------------------------------------- 41 (round 2, T-3)

	@Test
	void aPublishThatChangedTheHeadFiresAHideThatWasArmed() throws Exception {
		// The hide rule starts to match while the publish runs, with no pass of its own that could
		// act (the vote is written to the row without one): the TEA_HIDES pass after the publish
		// is what fires it. With the round 2 veto, a disapproval pass before the approval would
		// withhold the publish itself, so the design's literal order cannot publish first.
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID release = w.release();
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		ReleaseOutputEvent promote = ReleaseOutputEvent.builder().uuid(UUID.randomUUID()).name("promote")
				.type(EventType.RELEASE_LIFECYCLE_CHANGE).toReleaseLifecycle(ReleaseLifecycle.READY_TO_SHIP).build();
		configure(w.component(), List.of(rule("qa approved", approved(e1), p),
				rule("security disapproved", disapproved(e2), h, promote)), p, h, promote);
		CountDownLatch gate = new CountDownLatch(1);
		mergeEntered = new CountDownLatch(1);
		mergeGateRelease = release;
		mergeGate = gate;
		try {
			approve(release, e1);
			assertTrue(mergeEntered.await(60, TimeUnit.SECONDS), "the hop never reached the merge");
			voteSilently(release, e2, ApprovalState.DISAPPROVED);
		} finally {
			gate.countDown();
		}

		awaitHidden(release);
		assertEquals(1, triggerRows(release, h.getUuid()).size(), "the TEA_HIDES pass fired the armed hide");
		assertTrue(triggerRows(release, promote.getUuid()).isEmpty(), "and nothing else");
		assertEquals(ReleaseLifecycle.ASSEMBLED, stored(release).getLifecycle());
		assertEquals(2, teaEvents(release).size(), "ADDED then REMOVED");
		awaitNoTeaActKey(release);
	}

	// ---------------------------------------------------------------- 46 (round 3, T-7)

	@Autowired TeaPublishLock teaPublishLock;

	private void awaitClaim(UUID release) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			FlowControl fc = teaAct(release);
			if (null != fc && null != fc.teaActClaimedAt()) return;
			Thread.sleep(100);
		}
		throw new AssertionError("the hop never claimed the publish of " + release);
	}

	private long publishedOutboxRows(UUID release) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.notification_outbox_events WHERE event_type = "
				+ "'RELEASE_TEA_PUBLISHED' AND record_data::text LIKE ?", Long.class, "%" + release + "%");
	}

	@Test
	void aPublishSupersededWhileWaitingOnTheLeaseRunsTheHideInstead() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID release = w.release();
		UUID e1 = approvalEntry();
		UUID e2 = approvalEntry();
		ReleaseOutputEvent p = teaAction(EventType.TEA_PUBLISH, "p");
		ReleaseOutputEvent h = teaAction(EventType.TEA_HIDE, "h");
		configure(w.component(), List.of(rule("qa approved", approved(e1), p),
				rule("security disapproved", disapproved(e2), h)), p, h);
		// another instance's act holds the release's lease on its own connection
		TeaPublishLock.Lease held = teaPublishLock.acquire(release, Duration.ofSeconds(10));
		assertNotNull(held, "the test holds the lease");
		try {
			approve(release, e1);
			awaitClaim(release);
			assertEquals(TeaActKind.PUBLISH, teaAct(release).teaActKind(), "the hop claimed the publish and waits");

			vote(release, e2, ApprovalState.DISAPPROVED);

			assertEquals(TeaActKind.HIDE, teaAct(release).teaActKind(), "the hide overwrote the claimed publish");
			assertTrue(requestedAfterClaim(release));
		} finally {
			held.close();
		}

		awaitNoTeaActKey(release);
		assertTrue(head(release).isEmpty(), "nothing was published");
		assertTrue(teaEvents(release).isEmpty(), "no TEA_PUBLICATION event");
		assertEquals(0L, publishedOutboxRows(release), "no RELEASE_TEA_PUBLISHED notification");
		assertEquals(1, triggerRows(release, h.getUuid()).size(), "the hide's TRIGGER row");
		assertTrue(events(release, ReleaseUpdateScope.GUARD).stream()
				.noneMatch(e -> String.valueOf(e.message()).contains("before publishing")
						|| String.valueOf(e.newValue()).contains("refused")),
				"the act noted nothing: a superseded publish is cleared quietly");
		verify(spiedTeaOutputEventService, never()).drainPending(anyInt());
	}

	// ---------------------------------------------------------------- 48 (round 3, T-8)

	@Test
	void aCascadeChildWhoseHideRuleMatchedFirstIsHiddenAfterTheProductsAct() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World child = componentRelease("pkg:generic/pump@1.0.0");
		World control = componentRelease("pkg:generic/valve@1.0.0");
		UUID product = product(List.of(child.release(), control.release()));
		UUID k = approvalEntry();
		UUID k2 = approvalEntry();
		UUID pe = approvalEntry();
		ReleaseOutputEvent kh = teaAction(EventType.TEA_HIDE, "kh");
		ReleaseInputEvent kidRule = rule("kid disapproved", disapproved(k), kh);
		configure(child.component(), kidRule, kh);
		ReleaseOutputEvent k2h = teaAction(EventType.TEA_HIDE, "k2h");
		configure(control.component(), rule("control disapproved", disapproved(k2), k2h), k2h);
		ReleaseOutputEvent pp = teaAction(EventType.TEA_PUBLISH, "pp");
		configure(componentOf(product), rule("product approved", approved(pe), pp), pp);

		// the C2 replay: the child's own pass finds nothing to hide and leaves 'kh' armed
		vote(child.release(), k, ApprovalState.DISAPPROVED);
		assertTrue(head(child.release()).isEmpty());
		assertTrue(triggerRows(child.release(), kh.getUuid()).isEmpty(), "'kh' armed, not fired");
		// an ordinary action on the same rule that never fired: the TEA_HIDES pass must not fire it
		ReleaseOutputEvent promote = ReleaseOutputEvent.builder().uuid(UUID.randomUUID()).name("promote")
				.type(EventType.RELEASE_LIFECYCLE_CHANGE).toReleaseLifecycle(ReleaseLifecycle.READY_TO_SHIP).build();
		kidRule.setOutputEvents(java.util.Set.of(kh.getUuid(), promote.getUuid()));
		configure(child.component(), kidRule, kh, promote);

		approve(product, pe);

		TeaPublicationData hidden = awaitHidden(child.release());
		assertNotNull(hidden.getCascadeOf(), "the cascade published the child");
		List<String> childTea = teaEvents(child.release());
		assertEquals("REMOVED PUBLISHED v1 -> HIDDEN [Action 'kh' fired by rule 'kid disapproved']",
				childTea.get(childTea.size() - 1));
		assertEquals(1, triggerRows(child.release(), kh.getUuid()).size());
		assertTrue(triggerRows(child.release(), promote.getUuid()).isEmpty(), "only the never-fired hide fires");
		assertEquals(ReleaseLifecycle.ASSEMBLED, stored(child.release()).getLifecycle());
		TeaPublicationData ph = awaitPublished(product);
		assertEquals(1, ph.getLatestVersion());
		assertEquals(TeaPublicationState.PUBLISHED, head(control.release()).orElseThrow().getState(),
				"the control child's hide rule does not match");
		awaitNoTeaActKey(child.release());
		awaitNoTeaActKey(product);

		// a further publish of the product skips the hidden child
		UUID sbom = seedBom("tea-7-48-" + UUID.randomUUID() + ".cdx.json");
		mergedPurls.get(product).add("pkg:generic/gauge@3.0.0");
		releaseService.addArtifact(sbom, product, WU);
		long deadline = System.currentTimeMillis() + 30_000;
		while (head(product).orElseThrow().getLatestVersion() < 2 && System.currentTimeMillis() < deadline) {
			Thread.sleep(250);
		}
		assertEquals(2, head(product).orElseThrow().getLatestVersion(), "the product is REPUBLISHED");
		assertEquals(TeaPublicationState.HIDDEN, head(child.release()).orElseThrow().getState(), "the child stays hidden");
		assertEquals(1, triggerRows(child.release(), kh.getUuid()).size());
		assertEquals(TeaPublicationState.PUBLISHED, head(control.release()).orElseThrow().getState());
		awaitNoTeaActKey(product);
	}
}
