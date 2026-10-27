package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.ComponentLock;
import io.reliza.model.ComponentLock.AttestationRequirement;
import io.reliza.model.ComponentLock.UnlockLevel;
import io.reliza.model.DeclarativeProvenance;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.repositories.AuditRepository;
import io.reliza.repositories.VersionAssignmentRepository;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The rig and fixtures for the TPF-38 component writer tests: every writer that read-modify-writes a
 * component row reads it under the row lock with a refresh.
 *
 * <p>Two rigs, because the idiom has two halves. {@link #held} runs a first writer in a background
 * transaction that stays open, flushed, until a second writer of the same row is seen waiting on it
 * in {@code pg_locks}: it fails a writer that reads unlocked (refreshed or not), whose later
 * {@code UPDATE} then waits and overwrites. {@link #preRead} loads the component into a transaction,
 * commits a marker from another one, then runs the writer in the first: it fails a writer that locks
 * without a refresh, because the lock statement hands back the managed, pre-marker copy. No sleeps
 * decide an order; every wait is bounded by a transaction-local {@code lock_timeout}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
abstract class ComponentConcurrentWriteTestBase {

	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ComponentService componentService;
	@Autowired protected GetComponentService getComponentService;
	@Autowired protected ComponentLockService componentLockService;
	@Autowired protected BoardPerspectiveService boardPerspectiveService;
	@Autowired protected PerspectiveService perspectiveService;
	@Autowired protected UserService userService;
	@Autowired protected VcsRepositoryService vcsRepositoryService;
	@Autowired protected AuditRepository auditRepository;
	@Autowired protected VersionAssignmentRepository versionAssignmentRepository;
	@Autowired protected PlatformTransactionManager transactionManager;
	@Autowired protected JdbcTemplate jdbcTemplate;

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	protected UUID org;
	protected UUID c;
	/** Two real, live perspectives of the org. */
	protected UUID p;
	protected UUID q;

	@BeforeEach
	void world() throws RelizaException {
		org = testInitializer.obtainOrganization().getUuid();
		c = newComponent();
		p = perspectiveService.createPerspective(org, "race-p-" + UUID.randomUUID(), WU).getUuid();
		q = perspectiveService.createPerspective(org, "race-q-" + UUID.randomUUID(), WU).getUuid();
	}

	// ---------- fixtures ----------

	protected UUID newComponent() throws RelizaException {
		return componentService.createComponent("race_" + UUID.randomUUID(), org, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WU).getUuid();
	}

	/** X(value): a settings save of one field, as the component form sends it. */
	protected void x(UUID component, String repoPath) throws RelizaException {
		componentService.updateComponent(ComponentDto.builder().uuid(component).repoPath(repoPath).build(), WU);
	}

	protected void x(String repoPath) throws RelizaException {
		x(c, repoPath);
	}

	protected ComponentLock lock(UUID component, String reason) throws RelizaException {
		return componentLockService.lockComponent(component, reason, UnlockLevel.AGENT, AttestationRequirement.NONE, WU);
	}

	protected static DeclarativeProvenance provenance() {
		return new DeclarativeProvenance("race-" + UUID.randomUUID(), ZonedDateTime.now(), null);
	}

	protected UUID newVcs() {
		return vcsRepositoryService.provisionVcsRepository(org, "github.com/example/race-" + UUID.randomUUID(), VcsType.GIT, WU);
	}

	/** A real, active member of the org. */
	protected UUID newMember() throws RelizaException {
		String tag = UUID.randomUUID().toString();
		return UserData.dataFromRecord(userService.createUser("RaceUser_" + tag, tag + "@race.tpf38.io", false,
				List.of(org), "oauth_" + tag, OauthType.GITHUB, WU)).getUuid();
	}

	/** Stores one mail action naming the given users on the component. */
	protected UUID seedAction(UUID component, UUID... users) throws RelizaException {
		ReleaseOutputEvent action = ReleaseOutputEvent.builder()
				.uuid(UUID.randomUUID()).name("race-mail").type(EventType.EMAIL_NOTIFICATION)
				.users(new LinkedHashSet<>(List.of(users))).notificationMessage("released").build();
		componentService.updateComponent(ComponentDto.builder().uuid(component).outputTriggers(List.of(action)).build(), WU);
		return action.getUuid();
	}

	/** An in-memory board of the org hanging off the given perspectives. */
	protected AgentBoardData board(UUID... perspectives) {
		AgentBoardData b = new AgentBoardData();
		b.setOrg(org);
		b.setPerspectives(List.of(perspectives));
		return b;
	}

	// ---------- reads (on the test thread, outside any transaction, after every commit) ----------

	protected ComponentData stored(UUID component) {
		return getComponentService.getComponentData(component).get();
	}

	protected ComponentData stored() {
		return stored(c);
	}

	protected int revision(UUID component) {
		return getComponentService.getComponent(component).get().getRevision();
	}

	protected int audits(UUID component) {
		return auditRepository.retrieveAuditRecordsForEntity(TableName.COMPONENTS.toString(), component.toString(), "1000", "0").size();
	}

	protected static boolean activeLock(ComponentData cd, UUID lockUuid) {
		return null != cd.getLocks() && cd.getLocks().stream().anyMatch(l -> lockUuid.equals(l.uuid()) && l.active());
	}

	protected static Set<UUID> actionUsers(ComponentData cd, UUID action) {
		return cd.getOutputTriggers().stream().filter(t -> action.equals(t.getUuid())).findFirst().orElseThrow().getUsers();
	}

	// ---------- the rigs ----------

	@FunctionalInterface
	protected interface Write {
		void run() throws Exception;
	}

	@FunctionalInterface
	protected interface Writer {
		void run(ComponentData seen) throws Exception;
	}

	protected static RuntimeException unchecked(Exception e) {
		return e instanceof RuntimeException re ? re : new IllegalStateException(e);
	}

	/** A write in a transaction of its own whose lock waits fail after 10 s instead of hanging. */
	protected void bounded(Write write) {
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			versionAssignmentRepository.setLocalLockTimeout("10s");
			try {
				write.run();
			} catch (Exception e) {
				throw unchecked(e);
			}
		});
	}

	/**
	 * Runs {@code first} in a background transaction, flushed so its row lock is held, and keeps it
	 * open until another backend waits on it (at most 10 s); then runs {@code second} through
	 * {@link #bounded} on this thread and asserts it did wait.
	 */
	protected void held(Write first, Write second) throws Exception {
		CountDownLatch heldLatch = new CountDownLatch(1);
		AtomicBoolean seen = new AtomicBoolean(false);
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = ex.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
				try {
					first.run();
				} catch (Exception e) {
					throw unchecked(e);
				}
				s.flush();
				int me = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				heldLatch.countDown();
				seen.set(waitForWaiter(me));
			}));
			if (!heldLatch.await(10, TimeUnit.SECONDS)) {
				holder.get(30, TimeUnit.SECONDS);
				throw new IllegalStateException("the holder never got ready");
			}
			bounded(second);
			holder.get(30, TimeUnit.SECONDS);
			assertTrue(seen.get(), "the second writer never waited on the first: the row did not run the race");
		} finally {
			ex.shutdownNow();
		}
	}

	/**
	 * Runs {@code first} in a background transaction, flushed, and holds it until {@code release}
	 * counts down (20 s at most); returns once it holds the row.
	 */
	protected Future<?> holdUntil(ExecutorService ex, Write first, CountDownLatch release) throws Exception {
		CountDownLatch heldLatch = new CountDownLatch(1);
		Future<?> holder = ex.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			try {
				first.run();
				s.flush();
				heldLatch.countDown();
				release.await(20, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw unchecked(e);
			}
		}));
		if (!heldLatch.await(10, TimeUnit.SECONDS)) {
			holder.get(30, TimeUnit.SECONDS);
			throw new IllegalStateException("the holder never got ready");
		}
		return holder;
	}

	/** True once some backend waits on a lock this backend holds; polls the lock manager live, every 20 ms, 10 s at most. */
	protected boolean waitForWaiter(int pid) {
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

	/**
	 * The request shape: one transaction reads the component (the fetcher's authorization read that
	 * open-in-view keeps managed), a marker commits from another transaction, then the writer runs in
	 * the first. The marker's transaction fails in 5 s if the outer one took a lock by accident.
	 */
	protected void preRead(UUID component, Write marker, Writer writer) {
		TransactionTemplate inner = new TransactionTemplate(transactionManager);
		inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			ComponentData seen = getComponentService.getComponentData(component).get();
			inner.executeWithoutResult(s2 -> {
				versionAssignmentRepository.setLocalLockTimeout("5s");
				try {
					marker.run();
				} catch (Exception e) {
					throw unchecked(e);
				}
			});
			try {
				writer.run(seen);
			} catch (Exception e) {
				throw unchecked(e);
			}
		});
	}

	protected void preRead(Write marker, Writer writer) {
		preRead(c, marker, writer);
	}
}
