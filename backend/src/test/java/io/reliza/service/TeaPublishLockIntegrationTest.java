/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * TEA-5 round 3, case 89 (T-6), against the test database: the release's lease is released on
 * every exit of an act, and a lease held elsewhere refuses the act with PUBLISH_IN_PROGRESS once
 * the wait runs out.
 */
public class TeaPublishLockIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired private TeaPublishLock teaPublishLock;
	@PersistenceContext private EntityManager entityManager;

	/** The engine bean itself, so its package-private seams run on the real instance. */
	private TeaPublicationService engine() {
		return AopTestUtils.getUltimateTargetObject(teaPublicationService);
	}

	@AfterEach
	void restoreWait() {
		engine().setInteractiveWait(TeaPublicationService.INTERACTIVE_WAIT);
	}

	/** TEA publish leases held in this database by any session. */
	private long heldLeases() {
		return ((Number) entityManager.createNativeQuery("select count(*) from pg_locks where locktype = 'advisory' "
				+ "and classid = 48 and objsubid = 2 and database = (select oid from pg_database "
				+ "where datname = current_database())").getSingleResult()).longValue();
	}

	private TeaPublishRequest request(World w, TeaPublishMode mode) {
		return new TeaPublishRequest(w.release(), mode, null, WU);
	}

	/** A publish from another thread takes the lease within one poll. */
	private void assertFreeForAnotherThread(World w, TeaPublishMode mode) throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> f = pool.submit(() -> {
				try {
					return engine().publishLocked(request(w, mode));
				} catch (TeaPublishRefusedException e) {
					return e.reason();
				}
			});
			assertNotNull(f.get(1500, TimeUnit.MILLISECONDS));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void theLeaseIsReleasedAfterARefusalAFailureAndACompletedAct() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		// a refusal: a DRAFT release
		ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.DRAFT, WU);
		TeaPublishRefusedException draft = assertThrows(TeaPublishRefusedException.class,
				() -> engine().publishLocked(request(w, TeaPublishMode.PUBLISH)));
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, draft.reason());
		assertEquals(0, heldLeases());
		ossReleaseService.updateReleaseLifecycle(w.release(), ReleaseLifecycle.ASSEMBLED, WU);
		assertFreeForAnotherThread(w, TeaPublishMode.REPUBLISH);

		// a failure: the merge throws
		doThrow(new IllegalStateException("rebom down")).when(rebomService).mergeAndStoreBoms(anyList(), any(), any());
		assertThrows(Exception.class, () -> engine().publishLocked(request(w, TeaPublishMode.PUBLISH)));
		assertEquals(0, heldLeases());
		reset(rebomService);
		wireRebom();

		// a completed act
		assertEquals(TeaPublishOutcome.PUBLISHED, engine().publishLocked(request(w, TeaPublishMode.PUBLISH)).outcome());
		assertEquals(0, heldLeases());
		assertFreeForAnotherThread(w, TeaPublishMode.REPUBLISH);
	}

	@Test
	void aLeaseHeldElsewhereRefusesTheActWithPublishInProgressOnceTheWaitRunsOut() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		TeaPublishLock.Lease held = teaPublishLock.tryAcquire(w.release());
		assertNotNull(held);
		try {
			assertNull(teaPublishLock.tryAcquire(w.release()), "a second connection must not take a held lease");
			assertEquals(1, heldLeases());
			engine().setInteractiveWait(Duration.ofSeconds(1));
			TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
					() -> asRequest(w.release(), () -> engine().publishLocked(request(w, TeaPublishMode.PUBLISH))));
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, e.reason());
			assertTrue(e.getMessage().contains("another publish or hide of this release is in progress")
					&& e.getMessage().contains("it did not finish within 1 second"), e.getMessage());
			assertTrue(teaPublicationService.getPublicationOfRelease(w.release()).isEmpty());
			assertTrue(uploaded.isEmpty());
			TeaPublishRefusedException hide = assertThrows(TeaPublishRefusedException.class,
					() -> asRequest(w.release(), () -> engine().hideLocked(w.release(), null, WU)));
			assertEquals(TeaPublishRefusal.PUBLISH_IN_PROGRESS, hide.reason());
		} finally {
			held.close();
		}
		TeaPublishLock.Lease next = teaPublishLock.tryAcquire(w.release());
		assertNotNull(next, "closing the lease frees it");
		next.close();
		assertEquals(0, heldLeases());
	}
}
