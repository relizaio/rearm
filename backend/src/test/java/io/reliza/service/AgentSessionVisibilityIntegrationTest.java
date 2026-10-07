/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.oss.TestInitializer;

/**
 * Who may read a session (task 0192a587, gaps §1.13): the key that opened it, the org's ADMIN keys,
 * and the key holding the coordinator seat of a board the session worked on. Not any key in the
 * organization, which is what let a reviewer read the producer's session before reviewing.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
public class AgentSessionVisibilityIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentSessionVisibilityService visibility;

	private record Actor(UUID key, AgentData agent, AgentSessionData session) {}

	private Actor actor(Organization org, String prefix) throws RelizaException {
		UUID key = UUID.randomUUID();
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				prefix + "-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), a.getUuid(), key,
				prefix + "-s-" + UUID.randomUUID(), "session", null, null, WU);
		return new Actor(key, a, s);
	}

	private AgentBoardData board(Organization org, String name) throws RelizaException {
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData b = agentBoardService.createBoard(org.getUuid(), name + "-" + UUID.randomUUID(),
				"visibility", List.of(), "coordinate", 2, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(b, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build it", 10,
				null, false, true, null), true, WU);
		return agentBoardService.getBoardData(b.getUuid()).orElseThrow();
	}

	private AgentSessionData reload(Actor a) {
		return agentSessionService.getSessionData(a.session().getUuid()).orElseThrow();
	}

	@Test
	public void aSessionIsNotReadableByAnyOrgKey() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData worked = board(org, "worked");
		AgentBoardData elsewhere = board(org, "elsewhere");
		Actor producer = actor(org, "producer");
		Actor reviewer = actor(org, "reviewer");
		Actor coordinator = actor(org, "coordinator");
		Actor otherCoordinator = actor(org, "other-coordinator");
		agentBoardService.claimCoordinatorSeat(worked.getUuid(), coordinator.session().getUuid(),
				coordinator.agent().getUuid(), WU);
		agentBoardService.claimCoordinatorSeat(elsewhere.getUuid(), otherCoordinator.session().getUuid(),
				otherCoordinator.agent().getUuid(), WU);

		// the producer works a task on the first board
		AgentTaskData t = agentTaskService.register(agentBoardService.getBoardData(worked.getUuid()).orElseThrow(),
				null, "work", null, null, null, null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), agentBoardService.getBoardData(worked.getUuid()).orElseThrow(),
				"coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), agentBoardService.getBoardData(worked.getUuid()).orElseThrow(),
				producer.agent().getUuid(), producer.session().getUuid(), WU);

		AgentSessionData session = reload(producer);
		java.util.function.Predicate<UUID> none = b -> false;
		assertTrue(visibility.mayRead(session, producer.key(), false, none), "its own key");
		assertFalse(visibility.mayRead(session, reviewer.key(), false, none), "not any key in the organization");
		assertTrue(visibility.mayRead(session, coordinator.key(), false, none),
				"the seat of a board the session worked on");
		assertFalse(visibility.mayRead(session, otherCoordinator.key(), false, none),
				"a seat on a board the session never worked is no reason");
		assertTrue(visibility.mayRead(session, reviewer.key(), true, none), "an org ADMIN key");
		assertFalse(visibility.mayRead(session, null, false, none));
		assertFalse(visibility.mayRead(null, producer.key(), true, none));

		// D15 (task d8e7bd7e): BOARD_READ on a board the session worked is the third reader.
		assertTrue(visibility.mayRead(session, reviewer.key(), false, b -> b.equals(worked.getUuid())),
				"BOARD_READ on the board it worked");
		assertFalse(visibility.mayRead(session, reviewer.key(), false, b -> b.equals(elsewhere.getUuid())),
				"BOARD_READ on another board is no reason");
		assertEquals(java.util.Set.of(worked.getUuid()), session.getBoardsWorked(), "recorded once the assignment committed");

		// A session from before the record: read from its tasks once, and written.
		AgentSessionData old = reload(producer);
		old.setBoardsWorked(new java.util.LinkedHashSet<>());
		agentSessionService.saveData(old, WU);
		assertTrue(reload(producer).getBoardsWorked().isEmpty());
		assertTrue(visibility.mayRead(reload(producer), reviewer.key(), false, b -> b.equals(worked.getUuid())));
		assertEquals(java.util.Set.of(worked.getUuid()), reload(producer).getBoardsWorked(), "the derived set is written");
	}
}
