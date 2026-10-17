/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * COMPLETED means delivered (task 9af9d722, architecture-1 §4 tests 1-7): a task whose roles have
 * passed waits in DELIVERING until its linked PRs are merged. PR rows are written through the same
 * upsert CI uses, {@code PullRequestService.applyFromInput}.
 *
 * <p>The shared fixtures: a rig with a board, a VCS repository and a worker session, and PR rows.
 *
 * <p>One file per topic: add a test to the class for its topic, or to a new class extending the base, never at the
 * end of a class other tasks are appending to. Task RD4-15 split the old {@code AgentDeliveryIntegrationTest} this
 * way, because tasks appending to its end made each other's PRs conflict at the closing brace; the methods kept
 * their names.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
abstract class AgentDeliveryTestBase {

	@Autowired TestInitializer testInitializer;
	@Autowired AgentBoardService agentBoardService;
	@Autowired AgentTaskService agentTaskService;
	@Autowired AgentDocumentService agentDocumentService;
	@Autowired AgentDeliveryService agentDeliveryService;
	@Autowired PullRequestService pullRequestService;
	@Autowired VcsRepositoryService vcsRepositoryService;
	@Autowired ComponentService componentService;
	@Autowired AgentService agentService;
	@Autowired AgentSessionService agentSessionService;
	@Autowired io.reliza.ws.AgentTaskDataFetcher.TaskPullRequestBatchLoader taskPullRequestLoader;

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	static final String SRC = "github:acme/deliver";
	static final String DOCS_SOURCE = "github:acme/deliver-docs";
	static final String DOCS = "https://github.com/acme/deliver-docs";
	static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
	static final AtomicInteger ISSUE = new AtomicInteger(9900);
	static final AtomicInteger PR_NUMBER = new AtomicInteger(1);

	record Rig(Organization org, AgentBoardData board, UUID vcs, AgentData worker, AgentSessionData session) {}

	/** One REQUIRED coder; with {@code designer}, a designer before it that produces ARCHITECTURE. */
	Rig rig(boolean designer) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "deliver-" + UUID.randomUUID(),
				"delivery", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		if (designer) {
			agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("designer", "design", 10,
					null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(),
					List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.COMPONENT, false)),
					null, null), true, WU);
		}
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/acme/deliver-" + UUID.randomUUID(), VcsType.GIT, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		return new Rig(org, board, vcs, w, agentSessionService.initialize(org.getUuid(), w.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU));
	}

	AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	static String newPrUrl() {
		return "https://github.com/acme/app/pull/" + PR_NUMBER.incrementAndGet();
	}

	/** Register or update a PR the way CI does. */
	void pr(Rig r, String url, PullRequestState state) {
		Map<String, Object> in = new HashMap<>();
		String bare = url.replaceAll("/+$", "");
		in.put("identity", bare.substring(bare.lastIndexOf('/') + 1));
		in.put("state", state.name());
		in.put("endpoint", url);
		in.put("title", "change");
		in.put("targetBranchName", "main");
		assertTrue(pullRequestService.applyFromInput(in, r.org().getUuid(), null, r.vcs(), WU).isPresent());
	}

	AgentTaskData queuedCoder(Rig r, List<UUID> dependsOn) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, dependsOn, null, null, null, COORD, WU);
	}

	/** The coder takes the task, links {@code urls} and passes: the last required role. */
	AgentTaskData passedWith(Rig r, AgentTaskData t, String... urls) throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		for (String u : urls) agentTaskService.linkPr(t.getUuid(), u, WU);
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
	}

	static StatusChange last(AgentTaskData td) {
		return td.getStatusHistory().get(td.getStatusHistory().size() - 1);
	}

	List<String> events(Rig r, AgentBoardData.BoardEventKind kind, String containing) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream().filter(ev -> ev.kind() == kind)
				.map(AgentBoardData.BoardEvent::message).filter(m -> m.contains(containing)).toList();
	}

	// ---------- delivery modes and declarations (task 18c5c293) ----------

	static final String SHA = "0123456789abcdef0123456789abcdef01234567";

	void mode(Rig r, AgentBoardData.DeliveryMode mode, boolean declare) throws RelizaException {
		agentBoardService.setDeliveryPolicy(r.board().getUuid(), new AgentBoardData.DeliveryPolicy(mode, declare, null), WU);
	}

	AgentTaskData declare(AgentTaskData t, String unit, AgentTaskData.DeliveryOutcome outcome) throws RelizaException {
		return agentTaskService.declareDelivery(t.getUuid(), unit,
				AgentTaskData.DeliveryOutcome.DELIVERED == outcome ? SHA : null, outcome, "by hand", COORD, WU);
	}
}
