/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.BoardPerspectiveService.PerspectiveConsent;

/**
 * Task TEA-14, decision 3 (architecture rows 26 and 27): the TEA gate of TEA-9 runs again under the
 * component row lock on every board membership path. A board set change that makes a document
 * component resolve PUBLIC is refused by the board's own pre-check and, with the pre-check bypassed,
 * by the lock gate; a perspective deletion whose document-component removal comes to resolve PUBLIC
 * between the pre-check and the lock is refused under the lock, with TEA-9's text (not a silent
 * rollback), and the whole deletion rolls back, the boards saved before the refusal included.
 */
public class AgentBoardPerspectivesLockGateIntegrationTest extends AgentBoardPerspectivesTestBase {

	private static final String LOCK_GATE = "Only an organization admin can change the perspectives of component ";

	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	private UserData scopeAdminOn(World w, UUID perspective) throws RelizaException {
		UserData u = member(w, PermissionType.READ_ONLY, List.of());
		userService.setUserPermission(u.getUuid(), w.org().getUuid(), PermissionScope.PERSPECTIVE, perspective,
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	// row 26
	@Test
	public void aBoardSetChangeThatResolvesPublicIsRefusedBeforeAndUnderTheLock() throws RelizaException {
		World w = world();
		AgentBoardData bd = board(w, List.of(w.b()), ANY);
		UUID d = publicAPrivateB(w, bd);
		assertEquals("PRIVATE", visibility(d));
		UserData writer = grantOn(grantOn(member(w, PermissionType.READ_ONLY, List.of()), w, w.a(),
				PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE), w, w.b(),
				PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE);
		PerspectiveConsent consent = authorizationService.perspectiveConsent(writer, w.org().getUuid());
		assertEquals(TeaProfileService.TeaCaller.SCOPE_ADMIN, consent.teaCaller());

		RelizaException board = assertThrows(RelizaException.class,
				() -> agentBoardService.setPerspectives(bd.getUuid(), List.of(w.a()), consent, WU));
		assertTrue(board.getMessage().startsWith("changing the board's perspectives"), board.getMessage());
		assertTrue(board.getMessage().contains("document component " + nameOf(d)), board.getMessage());
		assertEquals(List.of(w.b()), reload(bd).getPerspectives());
		assertEquals(Set.of(w.b()), memberships(d));

		// the board pre-check bypassed: the same move, as the board sync makes it, meets the gate under the lock
		RelizaException locked = assertThrows(RelizaException.class,
				() -> boardPerspectiveService.changeMembership(d, List.of(w.a()), List.of(w.b()), consent.teaCaller(), WU));
		assertTrue(locked.getMessage().startsWith(LOCK_GATE + nameOf(d)), locked.getMessage());
		assertEquals(Set.of(w.b()), memberships(d), "nothing written either way");
		assertEquals("PRIVATE", visibility(d));
	}

	// row 27
	@Test
	@Timeout(90)
	public void deletingAPerspectiveWhoseRemovalResolvesPublicIsRefusedUnderTheLock() throws Exception {
		World w = world();
		// two boards in A, the first without a document component, the second with one covered by A's PRIVATE row
		AgentBoardData first = board(w, List.of(w.a()), ANY);
		AgentBoardData second = board(w, List.of(w.a()), ANY);
		teaProfileService.saveProfile(w.org().getUuid(), TeaProfileScope.PERSPECTIVE, w.a(),
				TeaProfileServiceTestBase.full(), TeaProfileService.TeaCaller.ORG_ADMIN, WU);
		UUID d = publish(second, w).component();
		assertEquals(Set.of(w.a()), memberships(d));
		componentService.setPerspectives(w.target().getUuid(), List.of(w.b()), WU);
		UserData onA = scopeAdminOn(w, w.a());
		TeaProfileService.TeaCaller scopeAdmin = authorizationService.teaCaller(onA, w.org().getUuid());
		assertEquals(TeaProfileService.TeaCaller.SCOPE_ADMIN, scopeAdmin);

		// The deleter's pre-check (TEA-9) passes: without A, D resolves to the organization, which has no row.
		// While the deletion waits for the second board's row, an organization admin makes the organization
		// PUBLIC and commits; under the lock, D's removal from A now resolves PUBLIC.
		CountDownLatch holding = new CountDownLatch(1);
		AtomicBoolean waitedOn = new AtomicBoolean(false);
		ExecutorService ex = Executors.newSingleThreadExecutor();
		RelizaException refused;
		try {
			Future<?> holder = ex.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
				try {
					agentBoardService.lockBoard(second.getUuid());
					int me = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
					holding.countDown();
					waitedOn.set(waitForWaiter(me));
					teaProfileService.saveProfile(w.org().getUuid(), TeaProfileScope.ORGANIZATION, null,
							TeaProfileServiceTestBase.full().toBuilder().visibility(TeaVisibility.PUBLIC).build(),
							TeaProfileService.TeaCaller.ORG_ADMIN, WU);
				} catch (RelizaException e) {
					throw new IllegalStateException(e);
				}
			}));
			assertTrue(holding.await(20, TimeUnit.SECONDS), "the holder never locked the second board");
			refused = assertThrows(RelizaException.class, () -> perspectiveService.deletePerspective(w.a(), scopeAdmin, WU));
			holder.get(30, TimeUnit.SECONDS);
		} finally {
			ex.shutdownNow();
		}
		assertTrue(waitedOn.get(), "the deletion never waited on the second board: the row did not run the race");
		assertTrue(refused.getMessage().startsWith(LOCK_GATE + nameOf(d)), refused.getMessage());
		assertFalse(refused.getMessage().contains("rolled back"), refused.getMessage());
		assertEquals(StatusEnum.ACTIVE, perspectiveService.listPerspectivesOfOrg(w.org().getUuid()).stream()
				.filter(p -> w.a().equals(p.getUuid())).findFirst().orElseThrow().getStatus());
		assertEquals(List.of(w.a()), reload(first).getPerspectives(),
				"the first board's drop, saved before the refusal, rolled back with the deletion");
		assertEquals(List.of(w.a()), reload(second).getPerspectives());
		assertEquals(Set.of(w.a()), memberships(d));
	}

	/** Whether another backend comes to wait on {@code pid}'s locks within 10 seconds. */
	private boolean waitForWaiter(int pid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM pg_locks WHERE NOT granted AND CAST(? AS integer) = ANY(pg_blocking_pids(pid))",
					Integer.class, pid);
			if (null != waiting && waiting > 0) return true;
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}
}
