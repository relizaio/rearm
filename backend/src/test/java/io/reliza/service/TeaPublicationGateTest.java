/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.TeaPublicationService.TeaActLane;
import io.reliza.service.TeaPublicationService.TeaPublishGate;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;

/**
 * TEA-7, round 3, unit (v), T-7: a publish request's gate is asked once, under the in-process
 * lock and the lease, on the rows the act's fresh read refreshed, before the mode gate, the TEA
 * id mint and any generation; its exception leaves the engine as it was thrown, with the lease
 * closed, the in-process lock released and nothing written. The four- and five-argument requests
 * carry no gate.
 */
class TeaPublicationGateTest extends TeaPublicationServiceTestBase {

	/** What the gate saw, per call. */
	private final List<String> seen = new ArrayList<>();

	private TeaPublishGate recording() {
		return (rd, cd) -> seen.add(rd.getUuid() + " " + rd.getLifecycle() + " " + cd.getUuid() + " " + cd.getName());
	}

	private TeaPublishRequest gated(TeaPublishMode mode, TeaPublishGate gate) {
		return new TeaPublishRequest(release.getUuid(), mode, null, WU, TeaActLane.BACKGROUND, gate);
	}

	private boolean releaseLockHeld() {
		@SuppressWarnings("unchecked")
		Map<UUID, ReentrantLock> locks = (Map<UUID, ReentrantLock>) ReflectionTestUtils.getField(service, "releaseLocks");
		ReentrantLock lock = locks.get(release.getUuid());
		return null != lock && lock.isLocked();
	}

	@Test
	void theGateIsAskedOnceOnTheFreshRowsBeforeTheModeGateTheMintAndTheContent() throws Exception {
		// the refresh is what makes the rows fresh: the gate must see what it wrote
		doAnswer(inv -> {
			Object entity = inv.getArgument(0);
			if (entity instanceof Release r && release.getUuid().equals(r.getUuid())) {
				ReleaseData rd = ReleaseData.dataFromRecord(r);
				rd.setLifecycle(ReleaseLifecycle.GENERAL_AVAILABILITY);
				r.setRecordData(Utils.dataToRecord(rd));
			} else if (entity instanceof Component c && component.getUuid().equals(c.getUuid())) {
				ComponentData cd = ComponentData.dataFromRecord(c);
				cd.setName("pump-refreshed");
				c.setRecordData(Utils.dataToRecord(cd));
			}
			return null;
		}).when(entityManager).refresh(any());
		TeaPublishGate gate = mock(TeaPublishGate.class);
		doAnswer(inv -> {
			recording().check(inv.getArgument(0), inv.getArgument(1));
			return null;
		}).when(gate).check(any(), any());

		assertEquals(TeaPublishOutcome.PUBLISHED, service.publish(gated(TeaPublishMode.PUBLISH_OR_REPUBLISH, gate)).outcome());

		assertEquals(List.of(release.getUuid() + " GENERAL_AVAILABILITY " + component.getUuid() + " pump-refreshed"), seen);
		InOrder order = Mockito.inOrder(teaPublishLock, entityManager, gate, organizationService, releaseService);
		order.verify(teaPublishLock, Mockito.calls(1)).acquire(any(UUID.class), any());
		order.verify(entityManager, Mockito.calls(2)).refresh(any());
		order.verify(gate, Mockito.calls(1)).check(any(), any());
		order.verify(organizationService, Mockito.calls(1)).ensureTeaUuid(any(), any());
		order.verify(releaseService, Mockito.calls(1)).generateMergedBomForPublication(any(), any(), any());
		verify(gate, times(1)).check(any(), any());
	}

	@Test
	void theGateComesBeforeTheModeGate() {
		// REPUBLISH of a release with no head is the mode gate's NOT_PUBLISHED; the gate answers first
		IllegalStateException withheld = new IllegalStateException("withheld");
		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> service.publish(gated(TeaPublishMode.REPUBLISH, (rd, cd) -> { throw withheld; })));
		assertSame(withheld, thrown);
	}

	@Test
	void aThrowingGateLeavesUnwrappedWithNothingWrittenAndEveryLockReleased() throws Exception {
		IllegalStateException withheld = new IllegalStateException("withheld under the lease");

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> service.publish(gated(TeaPublishMode.PUBLISH_OR_REPUBLISH, (rd, cd) -> { throw withheld; })));

		assertSame(withheld, thrown, "neither caught nor wrapped");
		verify(organizationService, never()).ensureTeaUuid(any(), any());
		verify(componentService, never()).ensureTeaUuid(any(), any());
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
		assertNothingWritten();
		assertTrue(teaEvents(release.getUuid()).isEmpty(), "no TEA_PUBLICATION event");
		assertEquals(1, leases.size());
		verify(leases.get(0), times(1)).close();
		assertFalse(releaseLockHeld(), "the in-process lock is released");

		// the release can be published at once afterwards
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(TeaPublishMode.PUBLISH).outcome());
	}

	@Test
	void theFourAndFiveArgumentRequestsCarryNoGate() throws Exception {
		TeaPublishRequest interactive = new TeaPublishRequest(release.getUuid(), TeaPublishMode.PUBLISH, null, WU);
		TeaPublishRequest background = new TeaPublishRequest(release.getUuid(), TeaPublishMode.PUBLISH, null, WU,
				TeaActLane.BACKGROUND);
		assertNull(interactive.gate());
		assertNull(background.gate());
		assertEquals(TeaActLane.INTERACTIVE, interactive.lane());
		assertEquals(TeaPublishOutcome.PUBLISHED, service.publish(background).outcome());
	}
}
