/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A refusal rolls back what the refusing call wrote (operator-actions brief §8).
 *
 * <p>{@code RelizaException} is checked, and Spring rolls back only on unchecked exceptions unless
 * told otherwise, so without the rule a service that wrote and then refused committed the write.
 * The rule has to sit on each method: a method-level {@code @Transactional} replaces a class-level
 * one entirely, and every transaction in these services is declared per method.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTransactionRollbackTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private PlatformTransactionManager transactionManager;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	private static final List<Class<?>> SERVICES = List.of(AgentTaskService.class, AgentDocumentService.class,
			AgentBoardService.class, AgentSessionService.class, AgentSessionUsageService.class,
			AgentService.class, AgentIdentityService.class, AgentRoleHistoryService.class,
			DeclarativeConfigService.class, AgentRoutingService.class);

	/**
	 * Methods that deliberately do not roll back on a refusal, each with its reason at the method.
	 * postEvent refuses only before writing and is called best-effort inside other transactions.
	 */
	private static final Set<String> OPT_OUTS = Set.of("AgentBoardService.postEvent");

	@Test
	public void everyTransactionInTheAgentServicesRollsBackOnARefusal() {
		List<String> missing = new ArrayList<>();
		for (Class<?> svc : SERVICES) {
			for (Method m : svc.getDeclaredMethods()) {
				Transactional tx = m.getAnnotation(Transactional.class);
				if (null == tx) continue;
				String name = svc.getSimpleName() + "." + m.getName();
				if (OPT_OUTS.contains(name)) {
					assertTrue(Arrays.asList(tx.noRollbackFor()).contains(RelizaException.class),
							name + " is listed as an opt-out but does not declare it");
					continue;
				}
				boolean rollsBack = Arrays.stream(tx.rollbackFor())
						.anyMatch(c -> c.isAssignableFrom(RelizaException.class));
				if (!rollsBack) missing.add(name);
			}
		}
		assertTrue(missing.isEmpty(), "these transactions commit what they wrote before refusing: "
				+ missing + ". Add rollbackFor = RelizaException.class, or list the method as an opt-out"
				+ " with its reason.");
	}

	@Test
	public void theEffectsApplierRollsBackOnAnyException() throws Exception {
		Transactional tx = BoardEffectsApplier.class
				.getMethod("applyOnce", BoardEffects.class, WhoUpdated.class).getAnnotation(Transactional.class);
		assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class),
				"applyOnce is retried; a checked failure halfway through must not commit what it applied");
	}

	@Test
	public void theHopUsageRefresherRollsBackOnAnyException() throws Exception {
		Transactional tx = HopUsageRefresher.class
				.getMethod("refreshTask", UUID.class, UUID.class, WhoUpdated.class).getAnnotation(Transactional.class);
		assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class),
				"a refresh that fails after filling one hop must not commit a half-filled task");
		assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, tx.propagation(),
				"its own transaction: the report it follows has already committed");
	}

	@Test
	public void theCoordinatorShareServiceRollsBackOnAnyException() throws Exception {
		Transactional tx = CoordinatorShareService.class.getMethod("apportionOnce", UUID.class, UUID.class,
				long.class, java.time.ZonedDateTime.class, WhoUpdated.class).getAnnotation(Transactional.class);
		assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class),
				"a failure after some shares were written must not commit a partial split");
		assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, tx.propagation(),
				"its own transaction: the report it follows has already committed");
	}

	@Test
	public void aRefusalMarksTheSurroundingTransactionSoItsEarlierWriteDoesNotPersist() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component node = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rollback-board", "test board",
				List.of(), "coordinator", 5, null, node.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics(
				"retired", "gone", 10, null, false, false, null), true, WU);
		AgentBoardData b = agentBoardService.getBoardData(board.getUuid()).orElseThrow();

		// One transaction: register a task (a real write), then authorize it for an inactive role,
		// which refuses. The caller swallows the refusal and lets the transaction commit, as a
		// caller that catches and carries on would. The refusal must still undo the write.
		AtomicReference<UUID> registered = new AtomicReference<>();
		TransactionTemplate tt = new TransactionTemplate(transactionManager);
		assertThrows(UnexpectedRollbackException.class, () -> tt.executeWithoutResult(status -> {
			try {
				AgentTaskData t = agentTaskService.register(b, null, "written then refused", null,
						null, null, null, null, null, WU);
				registered.set(t.getUuid());
				agentTaskService.authorize(t.getUuid(), b, "retired", 1, null, null, null, null, COORD, WU);
			} catch (RelizaException expected) {
				assertEquals(true, expected.getMessage().contains("inactive"), expected.getMessage());
			}
		}));
		assertFalse(agentTaskService.getTaskData(registered.get()).isPresent(),
				"the task written before the refusal was committed");
	}
}
