/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import graphql.ExecutionResult;
import jakarta.persistence.EntityManagerFactory;

import io.reliza.service.TeaProfileService;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.ElementCheckPolicy;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.CoordinatorShareService;
import io.reliza.service.VcsRepositoryService;

/**
 * Board writers read the board fresh under their lock, and no board save puts the task counter
 * back (TPF-42). A writer whose request read the board before a concurrent registration used to
 * save that copy whole: the counter went back one draw, so the next registration reused a number
 * and a key, and every other field committed in between (here the board leaving perspective P)
 * came back with it. Every lock site now reads through {@code AgentBoardService.lockBoard}, the
 * three writers that saved with no lock take it, and {@code saveData} keeps the stored counter,
 * read FOR UPDATE, when it is the higher one.
 *
 * <p>The rig is TPF-35's, copied from {@code BoardFormUpdateAtomicityIntegrationTest} (which stays
 * as it is): {@link #inView} binds one EntityManager to the thread as a request does, so the
 * request's board read and the writer's transaction share it; {@link #held} keeps a first write open
 * until the writer is seen waiting on it in {@code pg_locks}. No sleeps decide the order.
 */
public class BoardStaleCopySaveIntegrationTest extends AgentBoardEnforcementTestBase {

	private static final String OPERATOR_PAUSE = "mutation($b: ID!) { agentBoardOperatorPause(boardUuid: $b, pause: true, reason: \"tpf42\") { uuid } }";
	private static final String ONE_REQUEST = "mutation($b: ID!) { r: agentTaskRegister(boardUuid: $b, input: { title: \"tpf42 one request\" }) { uuid number key } p: agentBoardOperatorPause(boardUuid: $b, pause: true, reason: \"tpf42\") { uuid } }";

	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired AuthorizationService authorizationService;
	@Autowired VcsRepositoryService vcsRepositoryService;
	@Autowired CoordinatorShareService coordinatorShareService;

	// ---------- the rig (copied from BoardFormUpdateAtomicityIntegrationTest, which stays as it is) ----------

	/** The call as one request runs it: one EntityManager bound to the thread for the whole call (open-in-view). */
	private <T> T inView(Callable<T> call) throws Exception {
		OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
		interceptor.setEntityManagerFactory(emf);
		ServletWebRequest web = new ServletWebRequest(new MockHttpServletRequest());
		interceptor.preHandle(web);
		try {
			return call.call();
		} finally {
			interceptor.afterCompletion(web, null);
		}
	}

	@FunctionalInterface
	private interface Write {
		void run() throws Exception;
	}

	/**
	 * Runs {@code firstWrite} in a background transaction, flushed, and keeps it open until another
	 * backend waits on one of its locks (at most 10 s); then runs {@code second} on this thread, which
	 * blocks until the holder commits, and asserts it did wait.
	 */
	private <T> T held(Write firstWrite, Callable<T> second) throws Exception {
		CountDownLatch ready = new CountDownLatch(1);
		AtomicBoolean seen = new AtomicBoolean(false);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread holder = new Thread(() -> {
			try {
				new TransactionTemplate(transactionManager).execute(status -> {
					try {
						firstWrite.run();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
					status.flush();
					int me = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
					ready.countDown();
					seen.set(waitForWaiter(me));
					return null;
				});
			} catch (Throwable t) {
				failure.set(t);
			} finally {
				ready.countDown();
			}
		}, "tpf42-holder");
		holder.start();
		assertTrue(ready.await(10, TimeUnit.SECONDS), "the holder never got ready");
		if (null != failure.get()) throw new IllegalStateException("the holder failed", failure.get());
		T result;
		try {
			result = second.call();
		} finally {
			holder.join(TimeUnit.SECONDS.toMillis(30));
		}
		assertFalse(holder.isAlive(), "the holder did not finish");
		if (null != failure.get()) throw new IllegalStateException("the holder failed", failure.get());
		assertTrue(seen.get(), "the writer never waited on the holder: the row did not run the race");
		return result;
	}

	/** True once some backend waits on a lock this backend holds; polls the lock manager live, every 20 ms, 10 s at most. */
	private boolean waitForWaiter(int pid) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM pg_locks WHERE NOT granted AND CAST(? AS integer) = ANY(pg_blocking_pids(pid))",
					Integer.class, pid);
			if (null != waiting && waiting > 0) return true;
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private UUID componentUuid(World w) {
		return agentBoardService.getBoardData(w.a().getUuid()).orElseThrow().getTarget();
	}

	private AgentBoardData board(UUID uuid) {
		return agentBoardService.getBoardData(uuid).orElseThrow();
	}

	// ---------- helpers of this class ----------

	private static String tag() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/** A person whose only board access is a BOARD_READ grant on perspective P. */
	private UserData reader(World w) throws RelizaException {
		return grant(member(w, PermissionType.READ_ONLY), w, PermissionScope.PERSPECTIVE, w.p(), PermissionType.READ_ONLY,
				PermissionFunction.BOARD_READ);
	}

	private boolean covers(UserData reader, World w, UUID board) {
		return authorizationService.boardPermission(reader, w.org().getUuid(), board, PermissionFunction.BOARD_READ,
				CallType.READ);
	}

	private AgentTaskData registerOn(UUID board, String title) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(board), null, title + " " + UUID.randomUUID(), null, null, null,
				null, null, null, WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	/** The held task (re-read after the race) and what the writer returned. */
	private record Race(AgentTaskData held, Object written) {}

	/**
	 * W0 holds the board: it registers a task (drawing a number under the board lock), then takes the
	 * board out of P. The writer runs as a request whose read of the board came before the lock.
	 */
	private Race staleRace(UUID board, Callable<Object> writer) throws Exception {
		AtomicReference<AgentTaskData> drawn = new AtomicReference<>();
		Object written = held(() -> {
			drawn.set(registerOn(board, "held"));
			agentBoardService.setPerspectives(board, List.of(), p -> true, WU);
		}, () -> inView(() -> {
			board(board);
			return writer.call();
		}));
		return new Race(agentTaskService.getTaskData(drawn.get().getUuid()).orElseThrow(), written);
	}

	/** E0: the counter is the drawn one, the board stays out of P, and the next two tasks get the next two keys. */
	private void keepsTheDrawAndTheChange(World w, UUID board, UserData reader, AgentTaskData held) throws RelizaException {
		int drawn = held.getNumber();
		String prefix = board(board).getTaskPrefix();
		assertEquals(drawn, board(board).getNextTaskNumber(), "the save put the task counter back");
		assertTrue(board(board).getPerspectives().isEmpty(), "the save put perspective P back");
		assertFalse(covers(reader, w, board), "the grant on P covers the board again");
		AgentTaskData n1 = registerOn(board, "next");
		AgentTaskData n2 = registerOn(board, "next");
		assertEquals(drawn + 1, n1.getNumber());
		assertEquals(drawn + 2, n2.getNumber());
		assertEquals(prefix + "-" + (drawn + 1), n1.getKey());
		assertEquals(prefix + "-" + (drawn + 2), n2.getKey());
		assertEquals(3, new HashSet<>(List.of(held.getKey(), n1.getKey(), n2.getKey())).size(), "two tasks share a key");
	}

	/** Row 3: one writer from a stale copy on a fresh world; the board is A. */
	private void writerKeepsTheDraw(Function<World, Callable<Object>> writer, BiConsumer<World, AgentBoardData> own)
			throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UserData reader = reader(w);
		assertTrue(covers(reader, w, a));
		Race race = staleRace(a, writer.apply(w));
		own.accept(w, board(a));
		keepsTheDrawAndTheChange(w, a, reader, race.held());
	}

	// ---------- row 1: the settings update ----------

	@Test
	@Timeout(60)
	public void aSettingsUpdateFromACopyReadBeforeARegistrationKeepsTheDrawnCounterAndTheConcurrentChange() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UserData reader = reader(w);
		assertTrue(covers(reader, w, a));
		Race race = staleRace(a, () -> agentBoardService.updateSettingsFromInput(a, vars("cycleCap", 4), WU));
		assertEquals(4, board(a).getCycleCap());
		keepsTheDrawAndTheChange(w, a, reader, race.held());
	}

	// ---------- row 2: the operator's pause through its mutation ----------

	@Test
	@Timeout(60)
	public void theOperatorPauseWhoseOwnReadIsTheStaleCopyKeepsTheDrawnCounter() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UserData reader = reader(w);
		UserData admin = member(w, PermissionType.ADMIN);
		assertTrue(covers(reader, w, a));
		Race race = staleRace(a, () -> asPerson(admin, OPERATOR_PAUSE, vars("b", a.toString())));
		ExecutionResult r = (ExecutionResult) race.written();
		assertTrue(ok(r), r.getErrors().toString());
		assertEquals(BoardPauseLevel.OPERATOR, board(a).getPause().level());
		assertEquals("tpf42", board(a).getPause().reason());
		keepsTheDrawAndTheChange(w, a, reader, race.held());
	}

	// ---------- row 3: every other writer this task changes ----------

	@Test
	@Timeout(60)
	public void documentsConfigFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		String uri = "https://github.com/acme/tpf42-" + tag();
		writerKeepsTheDraw(w -> () -> agentBoardService.setDocumentsConfig(w.a().getUuid(), uri, null, WU),
				(w, bd) -> assertEquals(vcsRepositoryService.getVcsRepositoryDataByUri(w.org().getUuid(), uri)
						.orElseThrow().getUuid(), bd.getDocumentsRepo()));
	}

	@Test
	@Timeout(60)
	public void updateSettingsFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		writerKeepsTheDraw(w -> () -> agentBoardService.updateSettings(w.a().getUuid(), null, null, 4, null, null,
				null, WU), (w2, bd) -> assertEquals(4, bd.getCycleCap()));
	}

	@Test
	@Timeout(60)
	public void coordinatorCapabilitiesFromAStaleCopyKeepTheDrawnCounter() throws Exception {
		writerKeepsTheDraw(w -> () -> agentBoardService.setCoordinatorCapabilities(w.a().getUuid(),
				List.of(AgentCapability.PR_MERGE), WU),
				(w2, bd) -> assertEquals(List.of(AgentCapability.PR_MERGE), bd.getCoordinatorCapabilities()));
	}

	@Test
	@Timeout(60)
	public void updateBoardFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		String description = "tpf42 " + tag();
		writerKeepsTheDraw(w -> () -> agentBoardService.updateBoard(w.a().getUuid(), description, null, null, null,
				null, null, null, null, WU), (w2, bd) -> assertEquals(description, bd.getDescription()));
	}

	@Test
	@Timeout(60)
	public void theSoftAlertMarkFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		writerKeepsTheDraw(w -> () -> agentBoardService.markSoftAlertPosted(w.a().getUuid(), WU),
				(w2, bd) -> assertTrue(bd.isSoftAlertPosted()));
	}

	@Test
	@Timeout(60)
	public void coordinatorSharesFromAStaleCopyKeepTheDrawnCounter() throws Exception {
		// TPF-105 (TPF-71 part 1): the coordinator shares are stamped in place on the board and two tasks
		UUID seat = UUID.randomUUID();                   // never claimed: apportionOnce never reads the seat holder
		ZonedDateTime reportedAt = ZonedDateTime.now();  // held by the test: the exact cursor pin below
		AtomicReference<Map<UUID, Long>> shares = new AtomicReference<>();
		writerKeepsTheDraw(w -> () -> {
			shares.set(coordinatorShareService.apportionOnce(w.a().getUuid(), seat, 1_000L, reportedAt, WU));
			return shares.get();
		}, (w, bd) -> {
			UUID a = w.a().getUuid();
			assertEquals(1_000L, bd.getCoordinatorSharesMicros());
			assertEquals(0L, bd.getCoordinatorTransitions(), "a fallback counts no transition");
			assertNotNull(bd.getCoordinatorShareCursor());
			BigDecimal stored = jdbcTemplate.queryForObject(
					"select cast(record_data->>'coordinatorShareCursor' as numeric) from rearm.agent_boards where uuid = ?",
					BigDecimal.class, a);
			assertEquals(0, stored.compareTo(AgentSessionService.epochSecondsOf(reportedAt)),
					"the cursor is the stamp's numeric epoch form, exactly: " + stored);
			Map<UUID, Long> written = shares.get();
			assertEquals(2, written.size(), "the fallback splits over onA and the held task: " + written);
			assertEquals(500L, (long) written.get(w.onA().getUuid()));
			UUID held = written.keySet().stream().filter(u -> !u.equals(w.onA().getUuid())).findFirst().orElseThrow();
			assertEquals(500L, (long) written.get(held));
			for (UUID t : List.of(held, w.onA().getUuid())) {
				assertEquals(500L, (long) jdbcTemplate.queryForObject(
						"select cast(record_data->>'coordinatorEstimateMicros' as bigint) from rearm.agent_tasks where uuid = ?",
						Long.class, t));
			}
		});
	}

	@Test
	@Timeout(60)
	public void aSeatClaimFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		UUID s = UUID.randomUUID();
		UUID g = UUID.randomUUID();
		writerKeepsTheDraw(w -> () -> agentBoardService.claimCoordinatorSeat(w.a().getUuid(), s, g, WU), (w2, bd) -> {
			assertEquals(s, bd.getCoordinatorSeat().session());
			assertEquals(g, bd.getCoordinatorSeat().agent());
		});
	}

	@Test
	@Timeout(60)
	public void anElementCheckPolicyFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		writerKeepsTheDraw(w -> () -> agentBoardService.setElementCheckPolicy(w.a().getUuid(),
				new ElementCheckPolicy(List.of("trace.parent_exists"), Map.of(), List.of("test")), WU), (w2, bd) -> {
			assertEquals(List.of("trace.parent_exists"), bd.getElementCheckPolicy().blocking());
			assertEquals(List.of("test"), bd.getElementCheckPolicy().orphans());
		});
	}

	@Test
	@Timeout(60)
	public void aDeliveryPolicyFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		writerKeepsTheDraw(w -> () -> agentBoardService.setDeliveryPolicy(w.a().getUuid(),
				new AgentBoardData.DeliveryPolicy(AgentBoardData.DeliveryMode.DECLARED, true, null), WU), (w2, bd) -> {
			assertEquals(AgentBoardData.DeliveryMode.DECLARED, bd.getDeliveryPolicy().mode());
			assertTrue(bd.getDeliveryPolicy().awaitDeclaration());
		});
	}

	@Test
	@Timeout(60)
	public void elementFamiliesFromAStaleCopyKeepTheDrawnCounter() throws Exception {
		writerKeepsTheDraw(w -> () -> agentBoardService.setElementFamilies(w.a().getUuid(), Map.of("SAF", "safety"),
				WU), (w2, bd) -> assertEquals(Map.of("SAF", "safety"), bd.getElementFamilies()));
	}

	@Test
	@Timeout(60)
	public void aSeatReleaseOnSessionCloseFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		UUID s = UUID.randomUUID();
		UUID g = UUID.randomUUID();
		World w = world();
		UUID a = w.a().getUuid();
		UserData reader = reader(w);
		agentBoardService.claimCoordinatorSeat(a, s, g, WU);
		assertTrue(covers(reader, w, a));
		Race race = staleRace(a, () -> {
			agentBoardService.releaseSeatsForSession(s, "closed", WU);
			return null;
		});
		assertNull(board(a).getCoordinatorSeat());
		keepsTheDrawAndTheChange(w, a, reader, race.held());
	}

	@Test
	@Timeout(60)
	public void seedingFromPresetsFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		String tag = tag();
		World w = world();
		UserData reader = reader(w);
		agentBoardService.upsertPreset(w.org().getUuid(), AgentBoardService.RoleConfigSpec.ofBasics(
				"coordinator-board-truth", "seeded " + tag, 10, null, false, true, null), WU);
		UUID c = agentBoardService.createBoard(w.org().getUuid(), "tpf42-c-" + tag, "C", List.of(), null, 4, null,
				componentUuid(w), null, List.of(w.p()), x -> true, WU).getUuid();
		assertTrue(covers(reader, w, c));
		Race race = staleRace(c, () -> agentBoardService.seedFromPresets(board(c), WU));
		assertEquals("seeded " + tag, board(c).getCoordinatorPrompt());
		keepsTheDrawAndTheChange(w, c, reader, race.held());
	}

	@Test
	@Timeout(60)
	public void reseedingTheCoordinatorPromptFromAStaleCopyKeepsTheDrawnCounter() throws Exception {
		String tag = tag();
		World w = world();
		UUID a = w.a().getUuid();
		UserData reader = reader(w);
		agentBoardService.upsertPreset(w.org().getUuid(), AgentBoardService.RoleConfigSpec.ofBasics("tpf42-" + tag,
				"reseeded " + tag, 10, null, false, true, null), WU);
		assertTrue(covers(reader, w, a));
		Race race = staleRace(a, () -> agentBoardService.reseedCoordinatorPrompt(a, "tpf42-" + tag,
				AgentActor.system("test"), WU));
		assertEquals("reseeded " + tag, board(a).getCoordinatorPrompt());
		keepsTheDrawAndTheChange(w, a, reader, race.held());
	}

	// ---------- row 4: the perspective drop ----------

	@Test
	@Timeout(60)
	public void aPerspectiveDropFromACopyReadBeforeARegistrationKeepsTheDrawnCounterAndTheConcurrentChange() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		componentService.setPerspectives(componentUuid(w), List.of(w.q()), WU);
		UserData reader = reader(w);
		assertTrue(covers(reader, w, a));
		AtomicReference<AgentTaskData> drawn = new AtomicReference<>();
		held(() -> {
			drawn.set(registerOn(a, "held"));
			agentBoardService.setDefaultWorkLevel(a, 3, WU);
		}, () -> inView(() -> {
			board(a);
			perspectiveService.deletePerspective(w.p(), TeaProfileService.TeaCaller.ORG_ADMIN, WU);
			return null;
		}));
		AgentTaskData heldTask = agentTaskService.getTaskData(drawn.get().getUuid()).orElseThrow();
		assertEquals(3, board(a).getDefaultWorkLevel());
		keepsTheDrawAndTheChange(w, a, reader, heldTask);
	}

	// ---------- row 5: one request that registers and then pauses ----------

	@Test
	@Timeout(60)
	public void oneRequestThatRegistersAndThenPausesKeepsTheCounter() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UserData admin = member(w, PermissionType.ADMIN);
		ExecutionResult r = inView(() -> asPerson(admin, ONE_REQUEST, vars("b", a.toString())));
		assertTrue(ok(r), r.getErrors().toString());
		Map<String, Object> reg = data(r, "r");
		int drawn = (Integer) reg.get("number");
		assertEquals(drawn, board(a).getNextTaskNumber(), "the pause saved the request's copy, one draw behind");
		assertEquals(BoardPauseLevel.OPERATOR, board(a).getPause().level());
		AgentTaskData n1 = registerOn(a, "next");
		AgentTaskData n2 = registerOn(a, "next");
		assertEquals(drawn + 1, n1.getNumber());
		assertEquals(drawn + 2, n2.getNumber());
		assertEquals(3, new HashSet<>(List.of((String) reg.get("key"), n1.getKey(), n2.getKey())).size(),
				"two tasks share a key");
	}

	// ---------- row 6: the carry alone ----------

	@Test
	@Timeout(60)
	public void oneTransactionThatDrawsAndThenSavesAnOlderCopyKeepsTheCounter() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		AtomicReference<AgentTaskData> inside = new AtomicReference<>();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			try {
				AgentBoardData copy = agentBoardService.lockBoard(a);
				inside.set(agentTaskService.register(board(a), null, "drawn inside " + UUID.randomUUID(), null, null, null,
						null, null, null, WU));
				copy.setDescription("saved after the draw");
				agentBoardService.saveData(copy, WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		});
		int drawn = inside.get().getNumber();
		assertEquals(drawn, board(a).getNextTaskNumber(), "the save put the task counter back");
		assertEquals("saved after the draw", board(a).getDescription());
		AgentTaskData n1 = registerOn(a, "next");
		assertEquals(drawn + 1, n1.getNumber());
		assertNotEquals(inside.get().getKey(), n1.getKey());
	}

	// ---------- rows 7 and 8: the checks judge the locked row ----------

	@Test
	@Timeout(60)
	public void aCoordinatorResumeFromACopyReadBeforeAnOperatorPauseIsRefused() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		Object e = held(() -> agentBoardService.setPause(a, BoardPauseLevel.OPERATOR, true, "freeze",
				AgentActor.system("operator"), WU), () -> inView(() -> {
					board(a);
					try {
						agentBoardService.setPause(a, BoardPauseLevel.COORDINATOR, false, "resume",
								AgentActor.ofSession(UUID.randomUUID()), WU);
						return null;
					} catch (RelizaException x) {
						return x;
					}
				}));
		assertInstanceOf(RelizaException.class, e, "the resume passed the OPERATOR check on a stale copy");
		assertEquals("Board " + a + " carries an OPERATOR pause; the coordinator cannot lift it",
				((RelizaException) e).getMessage());
		assertEquals(BoardPauseLevel.OPERATOR, board(a).getPause().level());
		assertEquals("freeze", board(a).getPause().reason());
	}

	@Test
	@Timeout(60)
	public void aSecondSeatClaimFromACopyReadBeforeTheFirstCommittedIsRefused() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UUID s1 = UUID.randomUUID();
		UUID g1 = UUID.randomUUID();
		UUID s2 = UUID.randomUUID();
		UUID g2 = UUID.randomUUID();
		Object e = held(() -> agentBoardService.claimCoordinatorSeat(a, s1, g1, WU), () -> inView(() -> {
			board(a);
			try {
				agentBoardService.claimCoordinatorSeat(a, s2, g2, WU);
				return null;
			} catch (RelizaException x) {
				return x;
			}
		}));
		assertInstanceOf(RelizaException.class, e, "the second claim passed the seat check on a stale copy");
		assertEquals("Board " + a + " coordinator seat is held by session " + s1
				+ " (operator may force-close a dead session to free it)", ((RelizaException) e).getMessage());
		assertEquals(s1, board(a).getCoordinatorSeat().session());
	}
}
