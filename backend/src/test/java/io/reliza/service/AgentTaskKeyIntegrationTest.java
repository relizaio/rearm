/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import graphql.language.OperationDefinition.Operation;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticSchemaRegistry;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task keys (board-documents.md §4.1-4.4, tests 5-6; task 3d1f9dd7): a number and a key stamped at
 * registration, a prefix claimed in the organization and never reused, a rename that keeps old keys
 * and continues the count, resolution by key, and the numbering of a board from before keys.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTaskKeyIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(7300);

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private AgentBoardRepository boardRepository;

	private AgentBoardData board(Organization org, String name, String prefix) throws RelizaException {
		Component target = componentService.createComponent("keys_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), name, "keys", List.of(), "coordinate", 4, null,
				target.getUuid(), null, prefix, WU);
	}

	private AgentBoardData reload(AgentBoardData bd) {
		return agentBoardService.getBoardData(bd.getUuid()).orElseThrow();
	}

	private AgentTaskData task(AgentBoardData bd, String title) throws RelizaException {
		return agentTaskService.register(reload(bd), null, title, null, null, null, null, null, null, WU);
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	// ---------- 1. registration ----------

	@Test
	public void registrationStampsNumbersAndKeysAndASplitChildGetsItsOwn() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Keys " + UUID.randomUUID(), "RD");
		agentBoardService.upsertRoleConfig(bd,
				AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10, null, false, true, null), true, WU);
		AgentTaskData one = task(bd, "first");
		AgentTaskData two = task(bd, "second");
		assertEquals("RD-1", one.getKey());
		assertEquals(1, one.getNumber());
		assertEquals("RD-2", two.getKey());

		List<AgentTaskData> children = agentTaskService.split(two.getUuid(), reload(bd),
				List.of(new io.reliza.model.AgentTaskInput.SplitChild("child", null, null, null, null, null)), COORD, WU);
		assertEquals("RD-3", children.get(0).getKey(), "a split child registers through the same counter");

		agentTaskService.authorize(one.getUuid(), reload(bd), "coder", 10, null, null, null, null, COORD, WU);
		assertEquals("RD-1", reload(one).getKey(), "a later save leaves the key alone");
		assertEquals(1, reload(one).getNumber());
		assertEquals("RD-1 first", reload(one).label(), "events name the task by key, then title");
	}

	// ---------- 2. prefix ----------

	@Test
	public void aPrefixIsNormalisedAndOneOutOfShapeIsRefused() throws RelizaException {
		assertEquals("AB12", AgentBoardService.normaliseTaskPrefix(" ab12 "));
		for (String bad : List.of("R", "TOOLONGPREFIX", "R-D", "")) {
			assertThrows(RelizaException.class, () -> AgentBoardService.normaliseTaskPrefix(bad), bad);
		}
		assertEquals("RD", AgentBoardService.derivedTaskPrefix("ReARM Dogfood"));
		assertEquals("PL", AgentBoardService.derivedTaskPrefix("platform"));
		assertEquals("PSC", AgentBoardService.derivedTaskPrefix("payments service core"));
	}

	@Test
	public void theDerivedDefaultSkipsATakenPrefix() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData first = board(org, "Alpha Beta", null);
		AgentBoardData second = board(org, "Apple Banana", null);
		assertEquals("AB", first.getTaskPrefix());
		assertEquals("AB2", second.getTaskPrefix());
	}

	// ---------- 3. registry ----------

	@Test
	public void aPrefixIsNeverReusedEvenAfterItsBoardIsGone() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData first = board(org, "First " + UUID.randomUUID(), "QX");
		RelizaException taken = assertThrows(RelizaException.class,
				() -> board(org, "Second " + UUID.randomUUID(), "QX"));
		assertTrue(taken.getMessage().contains(first.getName()), taken.getMessage());

		// Another write of the org record keeps the claim: it is part of the organization's data.
		organizationService.setGlobalTeamAssignmentRules(org.getUuid(), List.of(), WU);
		assertEquals(first.getUuid(), getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow()
				.getAgentTaskPrefixes().get("QX").board());

		agentBoardService.archiveBoardByName(org.getUuid(), first.getName(), COORD, WU);
		assertThrows(RelizaException.class, () -> board(org, "Third " + UUID.randomUUID(), "QX"),
				"archived, its prefix still is not free");
		boardRepository.deleteById(first.getUuid());
		assertThrows(RelizaException.class, () -> board(org, "Fourth " + UUID.randomUUID(), "QX"),
				"deleted, its prefix still is not free");
	}

	// ---------- 4. rename ----------

	@Test
	public void aRenameKeepsOldKeysAndContinuesTheCount() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Rename " + UUID.randomUUID(), "RD");
		AgentTaskData last = null;
		for (int i = 0; i < 3; i++) last = task(bd, "task " + i);
		assertEquals("RD-3", last.getKey());

		agentBoardService.setTaskPrefix(bd.getUuid(), "R2", WU);
		assertEquals("R2-4", task(bd, "after the rename").getKey());
		assertEquals("RD-3", reload(last).getKey(), "an existing key never changes");
		assertEquals(last.getUuid(), agentTaskService.resolveKey(org.getUuid(), "RD-3").orElseThrow().getUuid(),
				"and it still resolves");
		assertEquals(List.of("RD", "R2"), reload(bd).getTaskPrefixHistory());

		agentBoardService.setTaskPrefix(bd.getUuid(), "RD", WU);
		assertEquals("RD", reload(bd).getTaskPrefix(), "the board's own old prefix is a no-op claim");
		assertEquals(List.of("RD", "R2"), reload(bd).getTaskPrefixHistory());
		assertEquals("RD-5", task(bd, "back again").getKey());
	}

	// ---------- 5. resolution ----------

	@Test
	public void aKeyResolvesAndAnUnknownOneDoesNot() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Resolve " + UUID.randomUUID(), "RS");
		AgentTaskData td = task(bd, "find me");
		assertEquals(td.getUuid(), agentTaskService.resolveKey(org.getUuid(), "RS-1").orElseThrow().getUuid());
		assertEquals(td.getUuid(), agentTaskService.resolveKey(org.getUuid(), " rs-1 ").orElseThrow().getUuid(),
				"typed in lower case");
		for (String unknown : List.of("ZZ-1", "RS-2", "RS-", "RS-x", "nothing")) {
			assertTrue(agentTaskService.resolveKey(org.getUuid(), unknown).isEmpty(), unknown);
		}
		assertTrue(agentTaskService.resolveKey(testInitializer.obtainOrganization().getUuid(), "RS-1").isEmpty(),
				"a key names a task in its own organization only");

		ProgrammaticSchemaRegistry registry = new ProgrammaticSchemaRegistry();
		assertTrue(registry.allows(Operation.QUERY, "agentTaskByKeyProgrammatic"));
		assertTrue(registry.allows(Operation.QUERY, "agentTaskByKey"));
	}

	// ---------- 6. a board from before keys ----------

	@Test
	public void aBoardFromBeforeKeysIsNumberedOnItsFirstReadInRegistrationOrder() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Legacy Board", null);
		List<AgentTaskData> tasks = List.of(task(bd, "oldest"), task(bd, "middle"), task(bd, "newest"));
		// Back to how a board and its tasks looked before keys existed.
		for (AgentTaskData t : tasks) {
			AgentTaskData raw = reload(t);
			raw.setNumber(null);
			raw.setKey(null);
			agentTaskService.saveData(raw, WU);
		}
		AgentBoardData raw = reload(bd);
		raw.setTasksNumbered(false);
		raw.setNextTaskNumber(0);
		raw.setTaskPrefix(null);
		raw.setTaskPrefixHistory(new java.util.ArrayList<>());
		agentBoardService.saveData(raw, WU);
		assertNull(reload(tasks.get(0)).getKey());
		assertEquals("oldest", reload(tasks.get(0)).label(), "an unnumbered task is named as before");

		agentTaskService.ensureNumbered(bd.getUuid(), WU);
		assertEquals(List.of("LB-1", "LB-2", "LB-3"), tasks.stream().map(t -> reload(t).getKey()).toList());
		agentTaskService.ensureNumbered(bd.getUuid(), WU);
		assertEquals(List.of("LB-1", "LB-2", "LB-3"), tasks.stream().map(t -> reload(t).getKey()).toList(),
				"idempotent");
		assertEquals("LB-4", task(bd, "after").getKey());
	}


	// ---------- RD3-2: a task number without a board revision ----------

	private int revision(AgentBoardData bd) {
		return boardRepository.findById(bd.getUuid()).orElseThrow().getRevision();
	}

	@Test
	public void registeringOnANumberedBoardWithAPrefixWritesNoBoardRevision() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Counted " + UUID.randomUUID(), null);
		AgentTaskData first = task(bd, "first");
		int revision = revision(bd);
		List<AgentTaskData> more = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) more.add(task(bd, "t" + i));
		assertEquals(revision, revision(bd), "the counter moves in place: no revision per registration");
		for (int i = 0; i < 5; i++) {
			assertEquals(first.getNumber() + 1 + i, more.get(i).getNumber());
			assertEquals(reload(bd).getTaskPrefix() + "-" + (first.getNumber() + 1 + i), more.get(i).getKey());
		}
		assertEquals(first.getNumber() + 5, reload(bd).getNextTaskNumber(), "the row's counter is the last number");
	}

	@Test
	public void twentyConcurrentRegistrationsGetTwentyDistinctSequentialNumbers() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Busy " + UUID.randomUUID(), null);
		int before = task(bd, "before").getNumber();
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(20);
		java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
		List<java.util.concurrent.Future<Integer>> numbers = new java.util.ArrayList<>();
		try {
			for (int i = 0; i < 20; i++) {
				String title = "concurrent " + i;
				numbers.add(pool.submit(() -> {
					go.await();
					return task(bd, title).getNumber();
				}));
			}
			go.countDown();
			List<Integer> got = new java.util.ArrayList<>();
			for (java.util.concurrent.Future<Integer> f : numbers) got.add(f.get(120, java.util.concurrent.TimeUnit.SECONDS));
			java.util.Collections.sort(got);
			List<Integer> expected = new java.util.ArrayList<>();
			for (int i = 1; i <= 20; i++) expected.add(before + i);
			assertEquals(expected, got, "distinct and with no gap");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void theFirstRegistrationOnABoardFromBeforeKeysIsOneBoardRevision() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org, "Old Ledger", null);
		List<AgentTaskData> tasks = List.of(task(bd, "one"), task(bd, "two"));
		for (AgentTaskData t : tasks) {
			AgentTaskData raw = reload(t);
			raw.setNumber(null);
			raw.setKey(null);
			agentTaskService.saveData(raw, WU);
		}
		AgentBoardData raw = reload(bd);
		raw.setTasksNumbered(false);
		raw.setNextTaskNumber(0);
		raw.setTaskPrefix(null);
		raw.setTaskPrefixHistory(new java.util.ArrayList<>());
		agentBoardService.saveData(raw, WU);
		int revision = revision(bd);

		AgentTaskData after = task(bd, "three");
		assertEquals(revision + 1, revision(bd), "one revision: the flag and the prefix");
		String prefix = reload(bd).getTaskPrefix();
		assertNotNull(prefix);
		assertEquals(List.of(prefix + "-1", prefix + "-2"), tasks.stream().map(t -> reload(t).getKey()).toList(),
				"the old tasks numbered first");
		assertEquals(prefix + "-3", after.getKey());
		assertEquals(3, reload(bd).getNextTaskNumber());
		assertTrue(reload(bd).isTasksNumbered());
	}
}
