/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AuditRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;

/**
 * A board's task-key prefix claim racing another claim of the same prefix, or an unrelated save of
 * the organization (TPF-35, probe 13 of the TEA out-of-scope list). Every claim takes the org's
 * AGENT_TASK_PREFIXES lock; the registry read after it must come from the database, not from an
 * organization the request's persistence context loaded before the lock, and the claim writes one
 * registry entry rather than the whole organization record.
 *
 * <p>The rig: {@link #inView} binds one EntityManager to the thread, as Spring Boot's open-in-view
 * interceptor does for a request, so every read and transaction of the second write joins it;
 * {@link #held} runs the first write in a background transaction that stays open, holding its locks,
 * until the second write is seen waiting on it in {@code pg_locks}. No sleeps decide the order.
 */
public class TaskPrefixClaimRaceIntegrationTest extends AgentBoardEnforcementTestBase {

	private static final String APPLY = "mutation($s: BoardSpecInput!, $d: Boolean) { applyBoardProgrammatic(spec: $s, dryRun: $d) {"
			+ " errors changes { action message } } }";
	private static final String UI_CREATE = "mutation($o: ID!, $i: AgentBoardInput!) { agentBoardCreate(orgUuid: $o, input: $i) { uuid } }";
	private static final String UI_UPDATE = "mutation($b: ID!, $i: AgentBoardInput!) { agentBoardUpdate(boardUuid: $b, input: $i) { uuid } }";

	@Autowired AgentBoardRepository boardRepository;
	@Autowired OrganizationRepository organizationRepository;
	@Autowired AuditRepository auditRepository;
	@Autowired OrganizationService organizationService;
	@Autowired GetOrganizationService getOrganizationService;
	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired JdbcTemplate jdbcTemplate;

	// ---------- the rig ----------

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
		}, "tpf35-holder");
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
		assertTrue(seen.get(), "the second claim never waited on the holder: the row did not run the race");
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

	// ---------- fixtures (copied from BoardDeclarativeScopedKeyIntegrationTest, which stays as it is) ----------

	private ApiKey bareKey(World w, PermissionDto... grants) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.FREEFORM, w.org().getUuid(),
				UUID.randomUUID().toString(), "prefix race", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(grants), WU);
		return key;
	}

	private ApiKey perspectiveKey(World w, UUID perspective) throws RelizaException {
		return bareKey(w, on(w, PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_WRITE,
				PermissionFunction.CONFIGURATION_WRITE, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_AGENT,
				PermissionFunction.BOARD_WRITE));
	}

	private ApiKey orgWideKey(World w) throws RelizaException {
		return bareKey(w, on(w, PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.READ_WRITE,
				PermissionFunction.BOARD_WRITE, PermissionFunction.CONFIGURATION_WRITE));
	}

	private static String pName(World w) {
		return "P-" + w.targetName().substring("enf-node-".length());
	}

	private Map<String, Object> boardFile(String name, Object... more) {
		Map<String, Object> s = vars("kind", "BOARD", "version", 1, "name", name);
		s.putAll(vars(more));
		return s;
	}

	private ExecutionResult apply(ApiKey key, Map<String, Object> spec, boolean dryRun) {
		return asKey(key, APPLY, vars("d", dryRun, "s", spec));
	}

	@SuppressWarnings("unchecked")
	private static List<String> errorMessages(ExecutionResult r) {
		Map<String, Object> res = data(r, "applyBoardProgrammatic");
		return ((List<Map<String, Object>>) res.get("changes")).stream()
				.filter(c -> "ERROR".equals(c.get("action"))).map(c -> (String) c.get("message")).toList();
	}

	@SuppressWarnings("unchecked")
	private static int errorCount(ExecutionResult r) {
		Map<String, Object> res = data(r, "applyBoardProgrammatic");
		return ((Number) res.get("errors")).intValue();
	}

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

	// ---------- helpers of this class ----------

	/** A prefix no other test holds: R and five hex characters, upper-cased (2 to 8 letters and digits). */
	private static String freshPrefix() {
		return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
	}

	private Map<String, OrganizationData.TaskPrefixClaim> registry(UUID org) {
		Map<String, OrganizationData.TaskPrefixClaim> m = getOrganizationService.getOrganizationData(org).orElseThrow()
				.getAgentTaskPrefixes();
		return null == m ? Map.of() : m;
	}

	/** The refusal a caller who sees the holder reads (refuseClaimedElsewhere), with the claim's date as stored. */
	private String seen(UUID org, String prefix, AgentBoardData holder) {
		OrganizationData.TaskPrefixClaim claim = registry(org).get(prefix);
		assertNotNull(claim, "no claim for " + prefix);
		return prefix + " is used by board " + holder.getName() + " since " + claim.claimedAt().toLocalDate()
				+ "; a task-key prefix is never reused in an organization";
	}

	private AgentBoardData board(UUID uuid) {
		return agentBoardService.getBoardData(uuid).orElseThrow();
	}

	private Optional<AgentBoardData> byName(World w, String name) {
		return boardRepository.findByOrgAndName(w.org().getUuid().toString(), name).map(AgentBoardData::dataFromRecord);
	}

	/** The registry names the holder for the prefix, the holder holds it, and no other board of the org holds it now. */
	private void oneHolder(World w, String prefix, AgentBoardData holder) {
		assertEquals(holder.getUuid(), registry(w.org().getUuid()).get(prefix).board(), "the registry's holder");
		assertEquals(prefix, board(holder.getUuid()).getTaskPrefix(), "the holder's prefix");
		long holding = agentBoardService.listByOrg(w.org().getUuid()).stream()
				.filter(b -> prefix.equals(b.getTaskPrefix())).count();
		assertEquals(1, holding, "boards holding " + prefix);
	}

	// ---------- rows 1 to 8: a held claim, or a held organization save, then a second write ----------

	@Test
	@Timeout(60)
	public void aDeclarativeCreateRacingAClaimIsRefused() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		AgentBoardData h = boardIn(w, "race-", List.of(w.q()));
		String p = freshPrefix();
		ApiKey orgWide = orgWideKey(w);
		String n = "race-new-" + UUID.randomUUID();

		ExecutionResult r = held(() -> agentBoardService.setTaskPrefix(h.getUuid(), p, WU),
				() -> inView(() -> apply(orgWide, boardFile(n, "target", w.targetName(), "taskPrefix", p), false)));

		assertEquals(List.of(seen(org, p, h)), errorMessages(r));
		assertEquals(1, errorCount(r));
		assertTrue(byName(w, n).isEmpty(), "nothing is created");
		oneHolder(w, p, h);
	}

	@Test
	@Timeout(60)
	public void aDeclarativeUpdateRacingAClaimIsRefused() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		AgentBoardData h = boardIn(w, "race-", List.of(w.q()));
		String p = freshPrefix();
		ApiKey orgWide = orgWideKey(w);
		AgentBoardData before = board(w.a().getUuid());

		ExecutionResult r = held(() -> agentBoardService.setTaskPrefix(h.getUuid(), p, WU),
				() -> inView(() -> apply(orgWide, boardFile(w.a().getName(), "taskPrefix", p), false)));

		assertEquals(List.of(seen(org, p, h)), errorMessages(r));
		AgentBoardData after = board(w.a().getUuid());
		assertEquals(before.getTaskPrefix(), after.getTaskPrefix());
		assertEquals(before.getTaskPrefixHistory(), after.getTaskPrefixHistory());
		oneHolder(w, p, h);
	}

	@Test
	@Timeout(60)
	public void aFormCreateRacingAClaimIsRefused() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		AgentBoardData h = boardIn(w, "race-", List.of(w.q()));
		String p = freshPrefix();
		UserData admin = member(w, PermissionType.ADMIN);
		String n = "race-ui-" + UUID.randomUUID();
		String target = componentUuid(w).toString();

		ExecutionResult r = held(() -> agentBoardService.setTaskPrefix(h.getUuid(), p, WU),
				() -> inView(() -> asPerson(admin, UI_CREATE, vars("o", org.toString(), "i",
						vars("name", n, "target", target, "taskPrefix", p)))));

		assertEquals(seen(org, p, h), firstMessage(r));
		assertTrue(byName(w, n).isEmpty(), "nothing is created");
		oneHolder(w, p, h);
	}

	@Test
	@Timeout(60)
	public void aFormUpdateOfThePrefixRacingAClaimIsRefused() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		AgentBoardData h = boardIn(w, "race-", List.of(w.q()));
		String p = freshPrefix();
		UserData admin = member(w, PermissionType.ADMIN);
		AgentBoardData before = board(w.a().getUuid());

		ExecutionResult r = held(() -> agentBoardService.setTaskPrefix(h.getUuid(), p, WU),
				() -> inView(() -> asPerson(admin, UI_UPDATE, vars("b", w.a().getUuid().toString(), "i",
						vars("taskPrefix", p)))));

		assertEquals(seen(org, p, h), firstMessage(r));
		AgentBoardData after = board(w.a().getUuid());
		assertEquals(before.getTaskPrefix(), after.getTaskPrefix());
		assertEquals(before.getTaskPrefixHistory(), after.getTaskPrefixHistory());
		oneHolder(w, p, h);
	}

	@Test
	@Timeout(60)
	public void aFormUpdateWithSettingsRacingAClaimIsRefused() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		AgentBoardData h = boardIn(w, "race-", List.of(w.q()));
		String p = freshPrefix();
		UserData admin = member(w, PermissionType.ADMIN);
		AgentBoardData before = board(w.a().getUuid());

		ExecutionResult r = held(() -> agentBoardService.setTaskPrefix(h.getUuid(), p, WU),
				() -> inView(() -> asPerson(admin, UI_UPDATE, vars("b", w.a().getUuid().toString(), "i",
						vars("taskPrefix", p, "settings", vars("cycleCap", 5))))));

		// What a refused update leaves of its settings is TPF-37's subject: not asserted here.
		assertEquals(seen(org, p, h), firstMessage(r));
		AgentBoardData after = board(w.a().getUuid());
		assertEquals(before.getTaskPrefix(), after.getTaskPrefix());
		assertEquals(before.getTaskPrefixHistory(), after.getTaskPrefixHistory());
		oneHolder(w, p, h);
	}

	@Test
	@Timeout(60)
	public void lazyNumberingRacingAClaimDerivesPastIt() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		String explicit = "LZ" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT);
		AgentBoardData l = agentBoardService.createBoard(org, "Zeta Quux", "lazy", List.of(), "coordinate", 4, null,
				componentUuid(w), null, explicit, WU);
		// Back to a board without a prefix, as AgentTaskKeyIntegrationTest does for a board from before keys.
		AgentBoardData raw = board(l.getUuid());
		raw.setTaskPrefix(null);
		raw.setTaskPrefixHistory(new java.util.ArrayList<>());
		agentBoardService.saveData(raw, WU);
		AgentBoardData boardL = board(l.getUuid());

		AgentTaskData t = held(() -> agentBoardService.setTaskPrefix(w.b().getUuid(), "ZQ", WU),
				() -> inView(() -> {
					// The read authorizeProgrammaticOrg makes before register.
					getOrganizationService.getOrganizationData(org);
					return agentTaskService.register(boardL, null, "first", null, null, null, null, null, null, WU);
				}));

		assertEquals("ZQ2", board(l.getUuid()).getTaskPrefix());
		assertEquals("ZQ2-1", agentTaskService.getTaskData(t.getUuid()).orElseThrow().getKey());
		assertEquals(w.b().getUuid(), registry(org).get("ZQ").board());
		assertEquals(l.getUuid(), registry(org).get("ZQ2").board());
	}

	@Test
	@Timeout(60)
	public void aDeclarativeCreateRacingAClaimDoesNotNameABoardTheKeyDoesNotSee() throws Exception {
		World w = world();
		AgentBoardData h = boardIn(w, "race-", List.of(w.q()));
		String p = freshPrefix();
		ApiKey scoped = perspectiveKey(w, w.p());
		String n = "race-scoped-" + UUID.randomUUID();

		ExecutionResult r = held(() -> agentBoardService.setTaskPrefix(h.getUuid(), p, WU),
				() -> inView(() -> apply(scoped, boardFile(n, "target", w.targetName(),
						"perspectives", List.of(pName(w)), "taskPrefix", p), false)));

		assertEquals(List.of(hiddenPrefix(p)), errorMessages(r));
		assertTrue(byName(w, n).isEmpty(), "nothing is created");
		oneHolder(w, p, h);
	}

	@Test
	@Timeout(60)
	public void aClaimWhileAnOrganizationSaveIsInFlightKeepsBoth() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		String p = freshPrefix();
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setJustificationMandatory(Boolean.TRUE);

		held(() -> organizationService.updateSettings(org, settings, WU),
				() -> agentBoardService.setTaskPrefix(w.a().getUuid(), p, WU));

		assertEquals(Boolean.TRUE, getOrganizationService.getOrganizationData(org).orElseThrow().getSettings()
				.getJustificationMandatory(), "the settings save survives the claim");
		assertEquals(w.a().getUuid(), registry(org).get(p).board());
		assertEquals(p, board(w.a().getUuid()).getTaskPrefix());
	}

	// ---------- rows 9 to 11: the statement's own properties ----------

	@Test
	@Timeout(60)
	public void aClaimStartsTheRegistryWhenItIsAbsentOrNull() throws Exception {
		World absent = world();
		World nulled = world();
		jdbcTemplate.update("UPDATE rearm.organizations SET record_data = record_data - 'agentTaskPrefixes' WHERE uuid = ?",
				absent.org().getUuid());
		jdbcTemplate.update("UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{agentTaskPrefixes}', "
				+ "'null'::jsonb) WHERE uuid = ?", nulled.org().getUuid());
		for (World w : List.of(absent, nulled)) {
			UUID org = w.org().getUuid();
			String name = getOrganizationService.getOrganizationData(org).orElseThrow().getName();
			String p = freshPrefix();
			agentBoardService.setTaskPrefix(w.a().getUuid(), p, WU);
			OrganizationData od = getOrganizationService.getOrganizationData(org).orElseThrow();
			assertEquals(name, od.getName());
			assertEquals(List.of(p), List.copyOf(od.getAgentTaskPrefixes().keySet()));
			assertEquals(w.a().getUuid(), od.getAgentTaskPrefixes().get(p).board());
			assertEquals("object", jdbcTemplate.queryForObject(
					"SELECT jsonb_typeof(record_data->'agentTaskPrefixes') FROM rearm.organizations WHERE uuid = ?",
					String.class, org));
		}
	}

	@Test
	@Timeout(60)
	public void aClaimNeverReplacesAnEntry() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		String prefixOfB = board(w.b().getUuid()).getTaskPrefix();
		OrganizationData.TaskPrefixClaim before = registry(org).get(prefixOfB);
		assertEquals(w.b().getUuid(), before.board());

		organizationService.recordTaskPrefixClaim(org, prefixOfB, w.a().getUuid(), WU);

		OrganizationData.TaskPrefixClaim after = registry(org).get(prefixOfB);
		assertEquals(w.b().getUuid(), after.board());
		assertEquals(before.claimedAt().toInstant(), after.claimedAt().toInstant());
	}

	@Test
	@Timeout(60)
	public void aClaimWritesNoOrganizationRevisionAndNoAuditRow() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		long revision = organizationRepository.findById(org).orElseThrow().getRevision();
		int audits = organizationAudits(org);

		agentBoardService.setTaskPrefix(w.a().getUuid(), freshPrefix(), WU);

		assertEquals(revision, (long) organizationRepository.findById(org).orElseThrow().getRevision());
		assertEquals(audits, organizationAudits(org));
	}

	private int organizationAudits(UUID org) {
		return auditRepository.retrieveAuditRecordsForEntity(TableName.ORGANIZATIONS.toString(), org.toString(), "1000",
				"0").size();
	}
}
