/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.TeaPublicationService.TeaActLane;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * TEA-5 round 4, case 94 (T-9): every wait an act makes draws on one budget, 30 seconds for the
 * interactive lane and five minutes for the background lane; the in-process lock is taken on it
 * and never unbounded, and a cascade's children wait on what the product left of it.
 */
class TeaPublicationWaitBudgetTest extends TeaPublicationServiceTestBase {

	private ExecutorService holder;

	@AfterEach
	void stopHolder() {
		if (null != holder) holder.shutdownNow();
		Thread.interrupted();
	}

	/** Holds the in-process lock of {@code release} on another thread until the returned latch opens. */
	private CountDownLatch holdInProcessLock(UUID release) throws Exception {
		ReentrantLock lock = ReflectionTestUtils.invokeMethod(service, "releaseLock", release);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		holder = Executors.newSingleThreadExecutor();
		holder.submit(() -> {
			lock.lock();
			try {
				held.countDown();
				done.await(30, TimeUnit.SECONDS);
			} finally {
				lock.unlock();
			}
			return null;
		});
		assertTrue(held.await(5, TimeUnit.SECONDS));
		return done;
	}

	private void assertNothingReadOrAcquired() throws Exception {
		verify(teaPublishLock, never()).acquire(any(), any());
		verify(publications, never()).findByRelease(any());
		verify(shared, never()).getRelease(any(UUID.class));
		verify(componentRepository, never()).findById(any());
		assertNothingWritten();
	}

	@Test
	void anInteractiveActWaitsForTheInProcessLockOnlyAsLongAsItsBudget() throws Exception {
		service.setInteractiveWait(Duration.ofMillis(200));
		CountDownLatch done = holdInProcessLock(release.getUuid());
		try {
			TeaPublishRefusedException e = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> assertThrows(TeaPublishRefusedException.class, () -> publish(TeaPublishMode.PUBLISH)));
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, e.reason());
			assertTrue(e.getMessage().contains("it did not finish within 200 ms"), e.getMessage());
			TeaPublishRefusedException h = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> assertThrows(TeaPublishRefusedException.class,
							() -> service.hide(release.getUuid(), null, WU)));
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, h.reason());
			assertNothingReadOrAcquired();
		} finally {
			done.countDown();
		}
	}

	@Test
	void aBackgroundActWaitsOnItsOwnBudget() throws Exception {
		service.setBackgroundWait(Duration.ofMillis(200));
		CountDownLatch done = holdInProcessLock(release.getUuid());
		try {
			TeaPublishRefusedException e = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> assertThrows(TeaPublishRefusedException.class, () -> service.publish(new TeaPublishRequest(
							release.getUuid(), TeaPublishMode.PUBLISH, null, WU, TeaActLane.BACKGROUND))));
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, e.reason());
			assertTrue(e.getMessage().contains("it did not finish within 200 ms"), e.getMessage());
			TeaPublishRefusedException h = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> assertThrows(TeaPublishRefusedException.class,
							() -> service.hide(release.getUuid(), null, WU, TeaActLane.BACKGROUND)));
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, h.reason());
			assertNothingReadOrAcquired();
		} finally {
			done.countDown();
		}
	}

	@Test
	void aFourArgumentRequestIsInteractive() {
		assertEquals(TeaActLane.INTERACTIVE,
				new TeaPublishRequest(release.getUuid(), TeaPublishMode.PUBLISH, null, WU).lane());
		assertEquals(Duration.ofSeconds(30), TeaPublicationService.INTERACTIVE_WAIT);
		assertEquals(Duration.ofMinutes(5), TeaPublicationService.BACKGROUND_WAIT);
	}

	/**
	 * The product's lease answers at once; a child's lease, asked with time left, answers when
	 * that time is used up; asked with none left (one try), it is held elsewhere. The first child
	 * spends the budget; the rest are refused after one try each.
	 */
	@Test
	void aCascadesChildrenWaitOnWhatTheProductLeftOfTheBudget() throws Exception {
		service.setInteractiveWait(Duration.ofSeconds(1));
		TeaProfileData p = profile(TeaVisibility.PRIVATE);
		p.setProductComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		ComponentData valve = component("valve", ComponentType.COMPONENT);
		ComponentData gauge = component("gauge", ComponentType.COMPONENT);
		ReleaseData second = release(valve, ReleaseLifecycle.ASSEMBLED, List.of());
		ReleaseData third = release(gauge, ReleaseLifecycle.ASSEMBLED, List.of());
		resolutions.put(valve.getUuid(), resolvedFromOrg(profile));
		resolutions.put(gauge.getUuid(), resolvedFromOrg(profile));
		documents.put(second.getUuid(), bom("pkg:generic/valve@1"));
		documents.put(third.getUuid(), bom("pkg:generic/gauge@1"));
		ReleaseData product = productOver("bundle", resolvedFromOrg(p),
				List.of(release.getUuid(), second.getUuid(), third.getUuid()));
		when(teaPublishLock.acquire(any(UUID.class), any(Duration.class))).thenAnswer(inv -> {
			UUID r = inv.getArgument(0);
			Duration maxWait = inv.getArgument(1);
			if (product.getUuid().equals(r)) return lease(r);
			if (maxWait.isZero()) return null;
			Thread.sleep(maxWait.toMillis() + 20);
			return lease(r);
		});

		TeaPublishResult r = assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> publishRelease(product.getUuid(), TeaPublishMode.PUBLISH));

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(3, r.children().size());
		assertEquals(TeaChildOutcome.PUBLISHED, r.children().get(0).outcome());
		for (int i = 1; i < 3; ++i) {
			assertEquals(TeaChildOutcome.REFUSED, r.children().get(i).outcome());
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, r.children().get(i).refusal());
			assertTrue(r.children().get(i).detail().contains("it did not finish within 1 second"),
					r.children().get(i).detail());
		}
		verify(teaPublishLock, times(1)).acquire(eq(second.getUuid()), eq(Duration.ZERO));
		verify(teaPublishLock, times(1)).acquire(eq(third.getUuid()), eq(Duration.ZERO));
		verify(teaPublishLock, times(1)).acquire(eq(second.getUuid()), any());
		verify(teaPublishLock, times(1)).acquire(eq(third.getUuid()), any());
		verify(releaseService, never()).generateMergedBomForPublication(
				org.mockito.ArgumentMatchers.argThat(rd -> null != rd && (second.getUuid().equals(rd.getUuid())
						|| third.getUuid().equals(rd.getUuid()))), any(), any());
	}

	@Test
	void aChildWhoseInProcessLockIsHeldPastTheBudgetIsRefusedAndTheProductStands() throws Exception {
		service.setInteractiveWait(Duration.ofMillis(300));
		TeaProfileData p = profile(TeaVisibility.PRIVATE);
		p.setProductComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		ReleaseData product = productOver("bundle", resolvedFromOrg(p), List.of(release.getUuid()));
		CountDownLatch done = holdInProcessLock(release.getUuid());
		try {
			TeaPublishResult r = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> publishRelease(product.getUuid(), TeaPublishMode.PUBLISH));
			assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
			assertEquals(TeaChildOutcome.REFUSED, r.children().get(0).outcome());
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, r.children().get(0).refusal());
			verify(teaPublishLock, never()).acquire(eq(release.getUuid()), any());
		} finally {
			done.countDown();
		}
	}
}
