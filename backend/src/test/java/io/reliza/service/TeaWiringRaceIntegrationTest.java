/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.UserData;
import io.reliza.model.saas.ApprovalPolicyData.RetiredGlobal;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.saas.ApprovalPolicyRepository;
import io.reliza.service.DeclarativeConfigService.CatalogComponentDto;
import io.reliza.service.DeclarativeConfigService.CatalogSpecDto;
import io.reliza.service.saas.ApprovalPolicyService;
import io.reliza.service.saas.TeaOutputEventService.TeaWiring;

/**
 * TEA-7, round 5, cases 56 and 57 (T-13, the run 4 replay Y8 with two transactions): a wiring
 * save checks and writes one row. A third transaction holds the row locked while two saves are
 * sent; each queues on the lock the save takes before its check, so the second is checked against
 * the first's row. Whole-record writers that do not own the wiring keys (setPerspectives, the
 * declarative catalog) write the stored keys back, and two admin saves of one policy are serialized
 * so a stale page meets the tombstone instead of resurrecting what the other admin removed.
 */
public class TeaWiringRaceIntegrationTest extends TeaWiringIntegrationTestBase {

	@Autowired ComponentRepository componentRepository;
	@Autowired ApprovalPolicyRepository approvalPolicyRepository;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired DeclarativeConfigService declarativeConfigService;

	private static final String ASSEMBLED = "release.lifecycle == \"ASSEMBLED\"";
	private final Map<String, UserData> bySubject = new java.util.concurrent.ConcurrentHashMap<>();
	private ExecutorService pool;

	@BeforeEach
	void threads() {
		pool = Executors.newFixedThreadPool(4);
	}

	@AfterEach
	void stopThreads() {
		pool.shutdownNow();
	}

	/** Every thread resolves its own user from its token, so two users can call at once. */
	private void usersByToken() {
		doAnswer(inv -> {
			Object auth = inv.getArgument(0);
			return auth instanceof JwtAuthenticationToken t ? Optional.ofNullable(bySubject.get(t.getToken().getSubject()))
					: Optional.empty();
		}).when(userService).getUserDataByAuth(any());
	}

	private ExecutionResult runAs(UserData user, Map<String, Object> input) {
		bySubject.put(user.getUuid().toString(), user);
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.getUuid().toString()).build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		try {
			return dgsQueryExecutor.execute(UPDATE, vars("c", input));
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private int lockWaiters() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid "
				+ "WHERE NOT l.granted AND a.datname = current_database()", Integer.class);
	}

	private void awaitWaiters(int n) throws InterruptedException {
		long until = System.currentTimeMillis() + 30_000;
		while (lockWaiters() < n) {
			if (System.currentTimeMillis() > until) throw new IllegalStateException("fewer than " + n + " waiters");
			Thread.sleep(50);
		}
	}

	/** A transaction on its own thread holding the row lock until {@code release} opens. */
	private Future<?> hold(Runnable lock, CountDownLatch release) throws InterruptedException {
		CountDownLatch held = new CountDownLatch(1);
		Future<?> f = pool.submit(() -> new TransactionTemplate(transactionManager).execute(s -> {
			lock.run();
			held.countDown();
			try {
				release.await(60, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}));
		assertTrue(held.await(30, TimeUnit.SECONDS), "the lock holder started");
		return f;
	}

	/** Sends {@code first} then {@code second} while the row is held, each queued on the lock, then frees the row. */
	private <T> List<Future<T>> race(Runnable lock, Callable<T> first, Callable<T> second) throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Future<?> holder = hold(lock, release);
		int base = lockWaiters();
		Future<T> a = pool.submit(first);
		awaitWaiters(base + 1);
		Future<T> b = pool.submit(second);
		awaitWaiters(base + 2);
		release.countDown();
		holder.get(30, TimeUnit.SECONDS);
		for (Future<T> f : List.of(a, b)) {
			try {
				f.get(60, TimeUnit.SECONDS);
			} catch (ExecutionException e) {
				// a refused save: the caller reads it from the future
			}
		}
		return List.of(a, b);
	}

	private List<String> ruleNames(UUID component) {
		return storedComponent(component).getReleaseInputTriggers().stream().map(ReleaseInputEvent::getName).toList();
	}

	private static ReleaseInputEvent renamed(ReleaseInputEvent r, String name) {
		ReleaseInputEvent c = copyOf(r);
		c.setName(name);
		return c;
	}

	// ---------------------------------------------------------------- 56

	@Test
	void theWiringCheckAndTheWriteSeeOneRow() throws Exception {
		UserData admin = admin();
		ReleaseOutputEvent m = mail("mail");
		ReleaseOutputEvent p8 = teaAction(EventType.TEA_PUBLISH, "p8");
		ReleaseOutputEvent h8 = teaAction(EventType.TEA_HIDE, "h8");
		ReleaseInputEvent ruleMail = rule("rule-mail", ASSEMBLED, m);
		ReleaseInputEvent ruleHide = rule("rule-hide", "release.lifecycle == \"READY_TO_SHIP\"", h8);
		ReleaseInputEvent rulePub = rule("rule-pub", ASSEMBLED, p8);
		UUID c = component();
		UserData writer = writer(c);
		assertSaved(update(admin, input(c, "outputTriggers", List.of(action(m), action(p8), action(h8)),
				"releaseInputTriggers", List.of(rule(ruleMail)))), "the admin's wiring");
		usersByToken();
		Runnable lockC = () -> componentRepository.lockRowForUpdate(c);

		// (a) the admin adds rule-hide; the writer's page (from before) edits rule-mail
		Map<String, Object> adminAdds = input(c, "releaseInputTriggers", List.of(rule(ruleMail), rule(ruleHide)));
		Map<String, Object> writerStale = input(c, "releaseInputTriggers", List.of(rule(renamed(ruleMail, "rule-mail-ed"))));
		List<Future<ExecutionResult>> a = race(lockC, () -> runAs(admin, adminAdds), () -> runAs(writer, writerStale));
		assertSaved(a.get(0).get(), "(a) the admin's save");
		assertRefused(a.get(1).get(), "(a) the writer's stale save, checked against the admin's row");
		assertEquals(List.of("rule-mail", "rule-hide"), ruleNames(c));

		// (c) the writer's stale save touching nothing TEA races the admin's next save: both applied
		ReleaseInputEvent ruleHide2 = rule("rule-hide-2", "release.lifecycle == \"READY_TO_SHIP\"", h8);
		Map<String, Object> adminAdds2 = input(c, "releaseInputTriggers",
				List.of(rule(ruleMail), rule(ruleHide), rule(ruleHide2)));
		Map<String, Object> writerRepo = input(c, "repoPath", "tea-7-56c");
		List<Future<ExecutionResult>> cc = race(lockC, () -> runAs(admin, adminAdds2), () -> runAs(writer, writerRepo));
		assertSaved(cc.get(0).get(), "(c) admin");
		assertSaved(cc.get(1).get(), "(c) the writer's save that touches nothing TEA");
		assertEquals(List.of("rule-mail", "rule-hide", "rule-hide-2"), ruleNames(c), "(c) the admin's rules intact");
		assertEquals("tea-7-56c", storedComponent(c).getRepoPath());

		// (b) on a second component the admin removes rule-pub; the writer's page still carries it
		World w = componentRelease("pkg:generic/c56b@1.0.0");
		UUID c2 = w.component();
		UserData writer2 = writer(c2);
		assertSaved(update(admin, input(c2, "outputTriggers", List.of(action(m), action(p8)),
				"releaseInputTriggers", List.of(rule(ruleMail), rule(rulePub)))), "(b) the admin's wiring");
		usersByToken();
		Map<String, Object> adminRemoves = input(c2, "releaseInputTriggers", List.of(rule(ruleMail)));
		Map<String, Object> writerKeeps = input(c2, "releaseInputTriggers",
				List.of(rule(renamed(ruleMail, "rule-mail-ed")), rule(rulePub)));
		List<Future<ExecutionResult>> b = race(() -> componentRepository.lockRowForUpdate(c2),
				() -> runAs(admin, adminRemoves), () -> runAs(writer2, writerKeeps));
		assertSaved(b.get(0).get(), "(b) admin");
		assertRefused(b.get(1).get(), "(b) the writer's stale save restoring rule-pub");
		assertEquals(List.of("rule-mail"), ruleNames(c2));
		UUID r = anotherRelease(w, "1.8." + Math.abs(UUID.randomUUID().hashCode() % 100000));
		sbomPass(r);
		assertFalse(queued(r), "(b) nothing publishes");
		assertTrue(triggerRows(r, p8.getUuid()).isEmpty());

		// (d) a stale setPerspectives and (e) a stale declarative catalog apply, each from a read
		// taken before the admin's save, write the admin's name, policy and wiring back
		String renamedName = nameOf(c) + "-renamed";
		ComponentData beforeAdmin = storedComponent(c);
		CountDownLatch read = new CountDownLatch(2);
		CountDownLatch adminSaved = new CountDownLatch(1);
		Future<?> perspectives = pool.submit(() -> new TransactionTemplate(transactionManager).execute(s -> {
			getComponentService.getComponent(c).orElseThrow(); // the stale copy, kept by the persistence context
			read.countDown();
			await(adminSaved);
			try {
				componentService.setPerspectives(c, List.of(), WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
			return null;
		}));
		Future<?> catalog = pool.submit(() -> new TransactionTemplate(transactionManager).execute(s -> {
			getComponentService.getComponent(c).orElseThrow();
			read.countDown();
			await(adminSaved);
			CatalogComponentDto cc2 = new CatalogComponentDto();
			cc2.setName(beforeAdmin.getName()); // the name the stale catalog read sees
			cc2.setRepoPath("tea-7-56e");
			CatalogSpecDto spec = new CatalogSpecDto();
			spec.setComponents(new ArrayList<>(List.of(cc2)));
			try {
				return declarativeConfigService.applyCatalog(org.getUuid(), spec, false, null, WU);
			} catch (RelizaException e) {
				throw new IllegalStateException(e);
			}
		}));
		assertTrue(read.await(30, TimeUnit.SECONDS));
		ReleaseInputEvent ruleHide3 = rule("rule-hide-3", "release.lifecycle == \"READY_TO_SHIP\"", h8);
		Map<String, Object> adminRenames = renameTo(c, renamedName);
		adminRenames.put("releaseInputTriggers", List.of(rule(ruleMail), rule(ruleHide), rule(ruleHide2), rule(ruleHide3)));
		assertSaved(update(admin, adminRenames), "(d) the admin's rename and rule");
		TeaWiring adminsWiring = wiring(c);
		adminSaved.countDown();
		perspectives.get(60, TimeUnit.SECONDS);
		catalog.get(60, TimeUnit.SECONDS);
		ComponentData after = storedComponent(c);
		assertEquals(renamedName, after.getName(), "(d)(e) the admin's name");
		assertEquals(List.of("rule-mail", "rule-hide", "rule-hide-2", "rule-hide-3"), ruleNames(c));
		assertEquals(adminsWiring, wiring(c), "(d)(e) the admin's wiring, policy and refs");
		assertEquals("tea-7-56e", after.getRepoPath(), "(e) the catalog's own field is written");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	// ---------------------------------------------------------------- 57

	@Test
	void twoAdminSavesOfOnePolicyAreSerialized() throws Exception {
		UUID e = approvalEntry();
		ReleaseOutputEvent m = mail("mail");
		ReleaseOutputEvent gp = teaAction(EventType.TEA_PUBLISH, "gp");
		ReleaseInputEvent r1 = rule("r1", ASSEMBLED, m);
		ReleaseInputEvent r2 = rule("r2", approved(e), m);
		UUID policy = policyWith("tea-7-57", List.of(m, gp), List.of(r1, r2), e);
		Runnable lockP = () -> approvalPolicyRepository.lockRowForUpdate(policy);

		// admin A removes gp; admin B's page from before re-sends gp with mail edited
		ReleaseOutputEvent mEdited = ReleaseOutputEvent.builder().uuid(m.getUuid()).name("mail-edited")
				.type(EventType.EMAIL_NOTIFICATION).users(java.util.Set.of()).build();
		var outputs = race(lockP,
				() -> approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(m)), WU),
				() -> approvalPolicyService.setGlobalOutputEvents(policy, new ArrayList<>(List.of(gp, mEdited)), WU));
		outputs.get(0).get();
		ExecutionException refused = assertThrows(ExecutionException.class, () -> outputs.get(1).get());
		assertTrue(refused.getCause() instanceof RelizaException);
		RetiredGlobal tomb = policyData(policy).getRetiredGlobals().get(0);
		assertEquals(String.format(ApprovalPolicyService.UUID_RETIRED, "Global output event", "gp", gp.getUuid(),
				tomb.retiredAt(), "action"), refused.getCause().getMessage());
		assertEquals(List.of(m.getUuid()), policyData(policy).getGlobalOutputEvents().stream()
				.map(ReleaseOutputEvent::getUuid).toList(), "A's list");
		assertEquals("mail", policyData(policy).getGlobalOutputEvents().get(0).getName());
		assertEquals(gp.getUuid(), tomb.uuid());

		// a plain concurrent edit of two different rules: both applied, the second over the first's row
		ReleaseInputEvent r1Edited = renamed(r1, "r1-edited");
		ReleaseInputEvent r2Edited = renamed(r2, "r2-edited");
		var inputs = race(lockP,
				() -> approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(List.of(r1Edited, copyOf(r2))), WU),
				() -> approvalPolicyService.setGlobalInputEvents(policy, new ArrayList<>(List.of(copyOf(r1), r2Edited)), WU));
		inputs.get(0).get();
		inputs.get(1).get();
		assertEquals(List.of("r1", "r2-edited"), policyData(policy).getGlobalInputEvents().stream()
				.map(ReleaseInputEvent::getName).toList());
	}
}
