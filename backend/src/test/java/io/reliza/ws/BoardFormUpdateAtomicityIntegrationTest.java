/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AuditRepository;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.VcsRepositoryService;

/**
 * The board form's update is all or nothing (TPF-37, probe 10 of the TEA out-of-scope list):
 * agentBoardUpdate runs its steps in one transaction under the board's row lock
 * ({@code AgentBoardService.updateAtomically}), so a refusal at any step leaves the board, its
 * revision, its audit rows and the organization's prefix registry exactly as they were, and a valid
 * update of every field still applies every field.
 *
 * <p>The rig is TPF-35's, copied from {@code TaskPrefixClaimRaceIntegrationTest}: {@link #inView}
 * binds one EntityManager to the thread as a request does, so the fetcher's board read and the
 * update's transaction share it; {@link #held} keeps a first write open until the second is seen
 * waiting on it in {@code pg_locks}. No sleeps decide the order.
 */
public class BoardFormUpdateAtomicityIntegrationTest extends AgentBoardEnforcementTestBase {

	private static final String UI_UPDATE = "mutation($b: ID!, $i: AgentBoardInput!) { agentBoardUpdate(boardUuid: $b, input: $i) { uuid description taskPrefix } }";

	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired AgentBoardRepository boardRepository;
	@Autowired AuditRepository auditRepository;
	@Autowired GetOrganizationService getOrganizationService;
	@Autowired VcsRepositoryService vcsRepositoryService;

	// ---------- the rig (copied from TaskPrefixClaimRaceIntegrationTest, which stays as it is) ----------

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
		}, "tpf37-holder");
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
		assertTrue(seen.get(), "the update never waited on the holder: the row did not run the race");
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

	// ---------- fixtures (copied from TaskPrefixClaimRaceIntegrationTest and BoardDeclarativeScopedKeyIntegrationTest) ----------

	private static String firstMessage(ExecutionResult r) {
		assertFalse(r.getErrors().isEmpty(), "refused");
		return r.getErrors().get(0).getMessage();
	}

	private AgentBoardData boardIn(World w, String prefix, List<UUID> perspectives) throws RelizaException {
		return agentBoardService.createBoard(w.org().getUuid(), prefix + UUID.randomUUID().toString().substring(0, 8),
				"fixture", List.of(), "coordinate", 4, null, componentUuid(w), null, perspectives, x -> true, WU);
	}

	private UUID componentUuid(World w) {
		return agentBoardService.getBoardData(w.a().getUuid()).orElseThrow().getTarget();
	}

	private static String hiddenPrefix(String prefix) {
		return "task prefix " + prefix + " is not available: a task-key prefix is never reused in an organization";
	}

	/** A prefix no other test holds: R and five hex characters, upper-cased (2 to 8 letters and digits). */
	private static String freshPrefix() {
		return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
	}

	private Map<String, OrganizationData.TaskPrefixClaim> registry(UUID org) {
		Map<String, OrganizationData.TaskPrefixClaim> m = getOrganizationService.getOrganizationData(org).orElseThrow()
				.getAgentTaskPrefixes();
		return null == m ? Map.of() : m;
	}

	private AgentBoardData board(UUID uuid) {
		return agentBoardService.getBoardData(uuid).orElseThrow();
	}

	private static String qName(World w) {
		return "Q-" + w.targetName().substring("enf-node-".length());
	}

	// ---------- helpers of this class ----------

	/** The stored board as one value: its record (jsonb text is canonical), its revision and its audit rows. */
	private record Snapshot(String recordData, int revision, int audits) {}

	private Snapshot snapshot(UUID board) {
		String recordData = jdbcTemplate.queryForObject("SELECT record_data::text FROM rearm.agent_boards WHERE uuid = ?",
				String.class, board);
		int revision = boardRepository.findById(board).orElseThrow().getRevision();
		int audits = auditRepository.retrieveAuditRecordsForEntity(TableName.AGENT_BOARDS.toString(), board.toString(),
				"1000", "0").size();
		return new Snapshot(recordData, revision, audits);
	}

	private void unchanged(UUID board, Snapshot before) {
		assertEquals(before, snapshot(board), "a refused update left the board changed");
	}

	/** The probe's person: configures board A through perspective P, holds nothing on Q. */
	private UserData onP(World w) throws RelizaException {
		return grant(member(w, PermissionType.READ_ONLY), w, PermissionScope.PERSPECTIVE, w.p(), PermissionType.READ_WRITE,
				PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE);
	}

	/** One field per step of the update, in step order; every value passes its check. */
	private static Map<String, Object> every(World w, String prefix, String tag) {
		return vars(
				"description", "every field " + tag,
				"sources", List.of("github:acme/tpf37-" + tag),
				"coordinatorPrompt", "coordinate every field",
				"perAgentWipLimit", 3,
				"priorityType", "STRICT",
				"defaultWorkLevel", 2,
				"elementFamilies", vars("SAF", "safety"),
				"elementCheckPolicy", vars("blocking", List.of("trace.parent_exists"), "orphans", List.of("test")),
				"deliveryPolicy", vars("merge", vars("by", "COORDINATOR")),
				"perspectives", List.of(w.p().toString(), w.q().toString()),
				"taskPrefix", prefix,
				"documents", vars("prefix", "tpf37"),
				"documentsRepo", "https://github.com/acme/tpf37-" + tag,
				"coordinatorCapabilities", List.of("PR_MERGE"),
				"settings", vars("cycleCap", 4),
				"groups", List.of(vars("key", "tpf37-core", "name", "Core")));
	}

	private static String tag() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	// ---------- the rows ----------

	/** Row 1, probe 10: a description and a prefix another board holds; refused, and nothing is saved. */
	@Test
	@Timeout(60)
	void aTakenPrefixAfterADescriptionLeavesTheBoardUnchanged() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UUID org = w.org().getUuid();
		AgentBoardData inQ = boardIn(w, "tpf37-", List.of(w.q()));
		String taken = board(inQ.getUuid()).getTaskPrefix();
		UserData person = onP(w);
		Snapshot before = snapshot(a);

		ExecutionResult r = inView(() -> asPerson(person, UI_UPDATE,
				vars("b", a, "i", vars("description", "changed-before-refusal", "taskPrefix", taken))));

		assertEquals(hiddenPrefix(taken), firstMessage(r), "the person does not see the holder");
		unchanged(a, before);
		assertEquals("A", board(a).getDescription());
		assertEquals(inQ.getUuid(), registry(org).get(taken).board(), "the registry still names the holder");
	}

	/** Row 2: a refused perspective after a description change; refused, and nothing is saved. */
	@Test
	@Timeout(60)
	void aRefusedPerspectiveAfterADescriptionLeavesTheBoardUnchanged() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UserData person = onP(w);
		Snapshot before = snapshot(a);

		ExecutionResult r = inView(() -> asPerson(person, UI_UPDATE, vars("b", a, "i",
				vars("description", "changed-before-refusal", "perspectives", List.of(w.p().toString(), w.q().toString())))));

		assertEquals("adding perspective " + qName(w) + " needs BOARD_WRITE and CONFIGURATION_WRITE covering it; the caller lacks"
				+ " BOARD_WRITE and CONFIGURATION_WRITE on " + qName(w), firstMessage(r));
		unchanged(a, before);
		assertEquals(List.of(w.p()), board(a).getPerspectives());
	}

	/** Row 3: a valid update of every field applies every field, the refreshing steps keeping the earlier ones. */
	@Test
	@Timeout(60)
	void aValidUpdateOfEveryFieldAppliesEveryField() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UUID org = w.org().getUuid();
		UserData admin = member(w, PermissionType.ADMIN);
		String prefix = freshPrefix();
		String tag = tag();

		ExecutionResult r = inView(() -> asPerson(admin, UI_UPDATE, vars("b", a, "i", every(w, prefix, tag))));

		Map<String, Object> answer = data(r, "agentBoardUpdate");
		assertEquals("every field " + tag, answer.get("description"));
		assertEquals(prefix, answer.get("taskPrefix"));
		AgentBoardData bd = board(a);
		assertEquals("every field " + tag, bd.getDescription());
		assertEquals(List.of("github:acme/tpf37-" + tag), bd.getSources());
		assertEquals("coordinate every field", bd.getCoordinatorPrompt());
		assertEquals(3, bd.getPerAgentWipLimit());
		assertEquals(AgentBoardData.PriorityType.STRICT, bd.getPriorityType());
		assertEquals(Integer.valueOf(2), bd.getDefaultWorkLevel());
		assertEquals(Map.of("SAF", "safety"), bd.getElementFamilies());
		assertEquals(List.of("trace.parent_exists"), bd.getElementCheckPolicy().blocking());
		assertEquals(List.of("test"), bd.getElementCheckPolicy().orphans());
		assertEquals("COORDINATOR", bd.getDeliveryPolicy().merge().by());
		assertEquals(List.of(w.p(), w.q()), bd.getPerspectives());
		assertEquals(prefix, bd.getTaskPrefix());
		assertEquals(prefix, bd.getTaskPrefixHistory().get(bd.getTaskPrefixHistory().size() - 1));
		assertEquals("tpf37", bd.getDocuments().prefix());
		assertEquals(vcsRepositoryService.getVcsRepositoryDataByUri(org, "https://github.com/acme/tpf37-" + tag)
				.orElseThrow().getUuid(), bd.getDocumentsRepo());
		assertEquals(List.of(AgentCapability.PR_MERGE), bd.getCoordinatorCapabilities());
		assertEquals(Integer.valueOf(4), bd.getCycleCap());
		assertTrue(bd.groupByKey("tpf37-core").isPresent(), "the group");
		assertEquals("Core", bd.groupByKey("tpf37-core").orElseThrow().name());
		assertEquals(a, registry(org).get(prefix).board(), "the registry names A");
	}

	/** Row 4: the same input with the last step refused leaves nothing applied, the prefix claim included. */
	@Test
	@Timeout(60)
	void theSameInputWithTheLastStepRefusedLeavesNothingApplied() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UUID org = w.org().getUuid();
		UserData admin = member(w, PermissionType.ADMIN);
		String prefix = freshPrefix();
		String tag = tag();
		Map<String, Object> input = every(w, prefix, tag);
		input.put("groups", List.of(vars("key", "tpf37-core", "name", "Core", "uuid", "not-a-uuid")));
		Snapshot before = snapshot(a);

		ExecutionResult r = inView(() -> asPerson(admin, UI_UPDATE, vars("b", a, "i", input)));

		assertEquals("A group uuid is a uuid (got 'not-a-uuid')", firstMessage(r));
		unchanged(a, before);
		assertFalse(registry(org).containsKey(prefix), "the prefix claim rolled back");
		assertTrue(vcsRepositoryService.getVcsRepositoryDataByUri(org, "https://github.com/acme/tpf37-" + tag).isPresent(),
				"the VCS repository step 9 provisioned commits on its own (architecture 3.6)");
	}

	/** Row 5: a request copy older than a task draw does not put the board's counter back. */
	@Test
	@Timeout(60)
	void aRequestCopyOlderThanATaskDrawDoesNotPutTheCounterBack() throws Exception {
		World w = world();
		UUID a = w.a().getUuid();
		UserData admin = member(w, PermissionType.ADMIN);
		AtomicReference<AgentTaskData> heldTask = new AtomicReference<>();

		ExecutionResult r = held(
				() -> heldTask.set(agentTaskService.register(board(a), null, "held " + UUID.randomUUID(), null, null, null,
						null, null, null, WU)),
				() -> inView(() -> asPerson(admin, UI_UPDATE, vars("b", a, "i", vars("description", "after the draw")))));

		assertTrue(ok(r), r.getErrors().toString());
		int drawn = agentTaskService.getTaskData(heldTask.get().getUuid()).orElseThrow().getNumber();
		assertEquals(drawn, board(a).getNextTaskNumber(), "the counter as the draw left it");
		assertEquals("after the draw", board(a).getDescription());
		AgentTaskData next = agentTaskService.register(board(a), null, "next " + UUID.randomUUID(), null, null, null, null,
				null, null, WU);
		assertEquals(drawn + 1, agentTaskService.getTaskData(next.getUuid()).orElseThrow().getNumber());
	}
}
