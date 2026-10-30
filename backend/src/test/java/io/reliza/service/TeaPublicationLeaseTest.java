/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.TeaPublicationService.TeaActLane;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;

/**
 * TEA-5 round 3, cases 81 and 82 (T-6; 82 amended in round 4 for the two lanes, T-9): every act, publish or hide, takes the release's lease
 * before its first read and closes it on every exit; a cascade child's lease is taken under the
 * product's and released before it; a wait that runs out, or is interrupted, is
 * PUBLISH_IN_PROGRESS and nothing is read, generated or written.
 */
class TeaPublicationLeaseTest extends TeaPublicationServiceTestBase {

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	/** A maxWait inside the interactive lane's budget (round 4, T-9). */
	private static Duration withinInteractiveWait() {
		return Mockito.argThat(d -> null != d && !d.isNegative()
				&& d.compareTo(TeaPublicationService.INTERACTIVE_WAIT) <= 0);
	}

	private void assertOneLeaseClosedOnce() {
		assertEquals(1, leases.size(), lockOrder.toString());
		verify(leases.get(0), times(1)).close();
	}

	// ---------------------------------------------------------------- 81

	@Test
	void theLeaseIsTakenBeforeTheFirstReadOfTheHeadAndTheRelease() throws Exception {
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(TeaPublishMode.PUBLISH).outcome());

		InOrder heads = Mockito.inOrder(teaPublishLock, publications);
		heads.verify(teaPublishLock).acquire(eq(release.getUuid()), withinInteractiveWait());
		heads.verify(publications, Mockito.calls(1)).findByRelease(release.getUuid());
		InOrder releases = Mockito.inOrder(teaPublishLock, shared);
		releases.verify(teaPublishLock).acquire(eq(release.getUuid()), withinInteractiveWait());
		releases.verify(shared, Mockito.calls(1)).getRelease(release.getUuid());
		assertOneLeaseClosedOnce();
	}

	@Test
	void theLeaseIsClosedOnARefusal() throws Exception {
		setLifecycle(release, ReleaseLifecycle.DRAFT);
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, refused(TeaPublishMode.PUBLISH).reason());
		assertOneLeaseClosedOnce();
	}

	@Test
	void theLeaseIsClosedWhenTheMergeThrows() throws Exception {
		when(releaseService.generateMergedBomForPublication(any(), any(), any()))
				.thenThrow(new IllegalStateException("rebom down"));
		assertThrows(IllegalStateException.class, () -> publish(TeaPublishMode.PUBLISH));
		assertOneLeaseClosedOnce();
	}

	@Test
	void theLeaseIsClosedAfterAHideAndAfterANotPublishedRefusal() throws Exception {
		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> service.hide(release.getUuid(), null, WU));
		assertEquals(TeaPublishRefusal.NOT_PUBLISHED, e.reason());
		assertOneLeaseClosedOnce();

		publish(TeaPublishMode.PUBLISH);
		leases.clear();
		service.hide(release.getUuid(), null, WU);
		assertOneLeaseClosedOnce();
		InOrder order = Mockito.inOrder(teaPublishLock, publications);
		order.verify(teaPublishLock).acquire(eq(release.getUuid()), withinInteractiveWait());
		order.verify(publications, Mockito.calls(1)).findByRelease(release.getUuid());
	}

	@Test
	void aCascadeChildsLeaseIsTakenUnderTheProductsBeforeTheChildsHeadIsReadAndReleasedFirst() throws Exception {
		ReleaseData product = productOver("bundle", resolvedFromOrg(cascadingProfile()), List.of(release.getUuid()));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, r.children().get(0).outcome());
		assertEquals(List.of("acquire:" + product.getUuid(), "acquire:" + release.getUuid(), "close:" + release.getUuid(),
				"close:" + product.getUuid()), lockOrder);
		InOrder child = Mockito.inOrder(teaPublishLock, publications);
		child.verify(teaPublishLock).acquire(eq(release.getUuid()), withinInteractiveWait());
		child.verify(publications, Mockito.calls(1)).findByRelease(release.getUuid());
		verify(publications, times(2)).findByRelease(release.getUuid()); // readFresh at the start and in the commit
	}

	private io.reliza.model.TeaProfileData cascadingProfile() {
		io.reliza.model.TeaProfileData p = profile(TeaVisibility.PRIVATE);
		p.setProductComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		return p;
	}

	// ---------------------------------------------------------------- 82

	private void assertNothingReadOrWritten() throws Exception {
		verify(publications, never()).findByRelease(any());
		verify(shared, never()).getRelease(any(UUID.class));
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
		verify(synthetic, never()).upload(any(), any(), any(), any(), any());
		verify(publications, never()).save(any());
		verify(collections, never()).save(any());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
	}

	@Test
	void aWaitThatRunsOutIsPublishInProgressAndTouchesNothing() throws Exception {
		when(teaPublishLock.acquire(any(UUID.class), any(Duration.class))).thenReturn(null);

		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, e.reason());
		assertTrue(e.getMessage().contains(release.getUuid().toString()) && e.getMessage().contains("pump 1.2.3")
				&& e.getMessage().contains("it did not finish within 30 seconds; repeat the act once it has finished")
				&& e.getMessage().contains("another publish or hide of this release is in progress on this or another "
						+ "ReARM instance"), e.getMessage());
		verify(teaPublishLock).acquire(eq(release.getUuid()), withinInteractiveWait());
		TeaPublishRefusedException h = assertThrows(TeaPublishRefusedException.class,
				() -> service.hide(release.getUuid(), null, WU));
		assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, h.reason());
		assertTrue(h.getMessage().contains("within 30 seconds"), h.getMessage());
		assertNothingReadOrWritten();
	}

	/** Case 82 (amended): the background lane waits five minutes and says so; never longer. */
	@Test
	void aBackgroundWaitThatRunsOutNamesFiveMinutes() throws Exception {
		when(teaPublishLock.acquire(any(UUID.class), any(Duration.class))).thenReturn(null);

		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class, () -> service.publish(
				new TeaPublishRequest(release.getUuid(), TeaPublishMode.PUBLISH, null, WU, TeaActLane.BACKGROUND)));
		TeaPublishRefusedException h = assertThrows(TeaPublishRefusedException.class,
				() -> service.hide(release.getUuid(), null, WU, TeaActLane.BACKGROUND));

		assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, e.reason());
		assertTrue(e.getMessage().contains("it did not finish within 5 minutes"), e.getMessage());
		assertTrue(h.getMessage().contains("it did not finish within 5 minutes"), h.getMessage());
		ArgumentCaptor<Duration> waits = ArgumentCaptor.forClass(Duration.class);
		verify(teaPublishLock, times(2)).acquire(eq(release.getUuid()), waits.capture());
		for (Duration d : waits.getAllValues()) {
			assertTrue(d.compareTo(TeaPublicationService.BACKGROUND_WAIT) <= 0
					&& d.compareTo(TeaPublicationService.INTERACTIVE_WAIT) > 0, d.toString());
		}
		assertNothingReadOrWritten();
	}

	@Test
	void anInterruptedWaitIsPublishInProgressAndKeepsTheInterruptFlag() throws Exception {
		when(teaPublishLock.acquire(any(UUID.class), any(Duration.class))).thenThrow(new InterruptedException());

		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, e.reason());
		assertTrue(Thread.currentThread().isInterrupted());
		Thread.interrupted();
		TeaPublishRefusedException h = assertThrows(TeaPublishRefusedException.class,
				() -> service.hide(release.getUuid(), null, WU));
		assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, h.reason());
		assertTrue(Thread.currentThread().isInterrupted());
		assertNothingReadOrWritten();
	}

	@Test
	void aChildWhoseWaitRunsOutIsRefusedAndTheProductStands() throws Exception {
		ReleaseData product = productOver("bundle", resolvedFromOrg(cascadingProfile()), List.of(release.getUuid()));
		when(teaPublishLock.acquire(eq(release.getUuid()), any(Duration.class))).thenReturn(null);

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaChildPublishResult child = r.children().get(0);
		assertEquals(TeaChildOutcome.REFUSED, child.outcome());
		assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, child.refusal());
		verify(publications, never()).findByRelease(release.getUuid());
		verify(releaseService, never()).generateMergedBomForPublication(
				Mockito.argThat(rd -> null != rd && release.getUuid().equals(rd.getUuid())), any(), any());
	}

	@Test
	void theWaitTextNamesWholeMinutesAndSeconds() {
		assertEquals("5 minutes", TeaPublicationService.waitText(Duration.ofMinutes(5)));
		assertEquals("1 minute", TeaPublicationService.waitText(Duration.ofMinutes(1)));
		assertEquals("1 second", TeaPublicationService.waitText(Duration.ofSeconds(1)));
		assertEquals("90 seconds", TeaPublicationService.waitText(Duration.ofSeconds(90)));
		assertEquals("1500 ms", TeaPublicationService.waitText(Duration.ofMillis(1500)));
	}
}
