/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.repositories.AgentSessionRepository;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board the staleness tests share (task RD3-4; split out for TPF-69): helpers only, no tests. A reviewer that
 * publishes review items and a required coder, two agents with a session each, the staleness block set from a map,
 * and a clock ahead of now, which is how minutes pass here.
 */
abstract class AgentBoardStalenessTestBase {

	@Autowired TestInitializer testInitializer;
	@Autowired AgentBoardService agentBoardService;
	@Autowired AgentTaskService agentTaskService;
	@Autowired AgentDocumentService agentDocumentService;
	@Autowired AgentService agentService;
	@Autowired AgentSessionService agentSessionService;
	@Autowired AgentSessionUsageService usageService;
	@Autowired ComponentService componentService;
	@Autowired BoardStalenessService staleness;
	@Autowired AgentBoardRepository boardRepository;
	@Autowired AgentSessionRepository sessionRepository;

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	static final String DOCS = "https://github.com/acme/stale-docs";
	static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
	static final AtomicInteger SEQ = new AtomicInteger(1);

	record Rig(Organization org, String target, AgentBoardData board, AgentData a, AgentSessionData sa,
			AgentData b, AgentSessionData sb) {}

	/** A reviewer that publishes review items, and a required coder; two agents with a session each. */
	Rig rig() throws RelizaException {
		return rig(true);
	}

	Rig rig(boolean withReviewer) throws RelizaException {
		return rig(withReviewer, 4);
	}

	/** With the board's per-agent WIP limit given. */
	Rig rig(boolean withReviewer, Integer perAgentWipLimit) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String targetName = "node_" + UUID.randomUUID();
		Component target = componentService.createComponent(targetName, org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "stale-" + UUID.randomUUID(),
				"staleness", List.of(), "coordinate", perAgentWipLimit, null, target.getUuid(), null, WU);
		if (withReviewer) {
			agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("reviewer", "review it", 10,
					null, false, true, null, null, RoleNecessity.OPTIONAL, null, List.of(),
					List.of(new ProducedOutput(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK, false))), true, WU);
		}
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build it", 20,
				null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "sa-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentData b = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "sb-" + UUID.randomUUID(),
				null, null, null, WU);
		return new Rig(org, targetName, board, a, session(org, a), b, session(org, b));
	}

	AgentSessionData session(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null, "s-" + UUID.randomUUID(),
				"session", null, null, WU);
	}

	AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	AgentTaskData reload(AgentTaskData t) {
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	AgentTaskData queued(Rig r, String role) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, "stale " + UUID.randomUUID(), null, null, null,
				null, null, null, PERSON, true, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), role, 10, null, null, null, null, COORD, WU);
	}

	void setStaleness(Rig r, Map<String, Object> block) throws RelizaException {
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("staleness", block);
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), settings, WU);
	}

	static Map<String, Object> block(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	List<BoardStalenessService.Breach> breaches(Rig r, ZonedDateTime at) {
		AgentBoardData bd = board(r);
		return staleness.breaches(bd, bd.getStaleness(), at);
	}

	List<String> alerts(Rig r, String containing) {
		return agentBoardService.recentEvents(r.board().getUuid()).stream()
				.filter(e -> e.kind() == AgentBoardData.BoardEventKind.ALERT)
				.map(AgentBoardData.BoardEvent::message).filter(m -> m.contains(containing)).toList();
	}

	int boardRevision(Rig r) {
		return boardRepository.findById(r.board().getUuid()).orElseThrow().getRevision();
	}

	int sessionRevision(AgentSessionData s) {
		return sessionRepository.findById(s.getUuid()).orElseThrow().getRevision();
	}

	static ZonedDateTime in(int minutes) {
		return ZonedDateTime.now().plusMinutes(minutes);
	}
}
