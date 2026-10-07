/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.CommissionIntake;
import io.reliza.model.AgentTaskRoleConfigData.Commissions;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board the investigation tests share (task RD4-12): helpers only, no tests. An architect that may commission
 * the tester; a tester that produces BOARD_TEST_REPORT on work tasks and BOARD_INVESTIGATION_REPORT when asked, and whose own
 * work-task input (a test plan) no investigation has; a lead that reviews; a coder that commissions nobody; and a
 * researcher that does nothing but investigate. One agent and session per role.
 */
abstract class InvestigationTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	static final String DOCS = "https://github.com/acme/investigation-docs";
	static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
	static final RearmSpecificationType REPORT = RearmSpecificationType.BOARD_INVESTIGATION_REPORT;

	@Autowired TestInitializer testInitializer;
	@Autowired AgentBoardService agentBoardService;
	@Autowired AgentTaskService agentTaskService;
	@Autowired AgentDocumentService agentDocumentService;
	@Autowired AgentTaskInputService agentTaskInputService;
	@Autowired SharedReleaseService sharedReleaseService;
	@Autowired ComponentService componentService;
	@Autowired AgentService agentService;
	@Autowired AgentSessionService agentSessionService;

	record Worker(AgentData agent, AgentSessionData session) {
		UUID sessionUuid() { return session.getUuid(); }
		UUID agentUuid() { return agent.getUuid(); }
	}

	record Rig(Organization org, String target, AgentBoardData board, Worker architect, Worker tester, Worker lead,
			Worker coder, Worker researcher) {}

	static ProducedOutput produces(RearmSpecificationType spec) {
		return new ProducedOutput(spec, InputScope.TASK, false);
	}

	static ProducedOutput report() {
		return new ProducedOutput(REPORT, InputScope.TASK, true);
	}

	static AgentBoardService.RoleConfigSpec role(String name, int order, List<RequiredInput> inputs,
			List<ProducedOutput> outputs, Commissions commissions) {
		return new AgentBoardService.RoleConfigSpec(name, name + " prompt", order, null, false, true, null, null, null,
				null, inputs, outputs, null, null, null, commissions);
	}

	Rig rig() throws RelizaException {
		return rig(CommissionIntake.AUTO);
	}

	Rig rig(CommissionIntake intake) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String targetName = "inv_" + UUID.randomUUID();
		Component target = componentService.createComponent(targetName, org.getUuid(), ComponentType.PRODUCT, "semver",
				"Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "investigation-" + UUID.randomUUID(),
				"investigations", List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, role("researcher", 5, List.of(), List.of(report()), null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("tester", 30,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.TEST_PLAN, InputScope.TASK, null, null, null)),
				List.of(produces(RearmSpecificationType.BOARD_TEST_REPORT), report()), null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("lead", 40, List.of(),
				List.of(produces(RearmSpecificationType.BOARD_REVIEW_ITEMS)), null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("coder", 20, List.of(),
				List.of(produces(RearmSpecificationType.DETAILED_DESIGN)), null), true, WU);
		agentBoardService.upsertRoleConfig(board, role("architect", 10, List.of(),
				List.of(produces(RearmSpecificationType.ARCHITECTURE)),
				new Commissions(List.of("tester", "researcher"), intake, 2_000_000L, null)), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		return new Rig(org, targetName, board, worker(org, "architect"), worker(org, "tester"), worker(org, "lead"),
				worker(org, "coder"), worker(org, "researcher"));
	}

	Worker worker(Organization org, String name) throws RelizaException {
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), name + "-" + UUID.randomUUID(),
				null, null, null, WU);
		return new Worker(a, agentSessionService.initialize(org.getUuid(), a.getUuid(), null, "s-" + UUID.randomUUID(),
				name, null, null, WU));
	}

	AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	AgentTaskRoleConfigData roleNamed(Rig r, String name) {
		return agentBoardService.getRoleConfig(r.board().getUuid(), name).orElseThrow();
	}

	/** A work task authorized for a role and taken by that role's worker. */
	AgentTaskData held(Rig r, String role, Worker w) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, "work " + UUID.randomUUID(), null, null, null, null,
				null, null, PERSON, false, WU);
		agentTaskService.authorize(t.getUuid(), board(r), role, 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), w.agentUuid(), w.sessionUuid(), WU);
		return reload(t);
	}

	static AgentTaskService.CommissionRequest ask(String role, UUID fromTask) {
		return new AgentTaskService.CommissionRequest(role, "look into " + UUID.randomUUID(), "What does the UI do today?",
				fromTask, List.of(), null, null, null, null, null, null, null);
	}

	static AgentTaskService.CommissionRequest ask(String role, UUID fromTask, String review) {
		return new AgentTaskService.CommissionRequest(role, "look into " + UUID.randomUUID(), "What does the UI do today?",
				fromTask, List.of(), null, null, review, null, null, null, null);
	}

	/** The architect, holding a work task, commissions an investigation from it. */
	AgentTaskData commissionedFrom(Rig r, AgentTaskData from, AgentTaskService.CommissionRequest req) throws RelizaException {
		return agentTaskService.commission(board(r), req, r.architect().sessionUuid(),
				AgentActor.ofSession(r.architect().sessionUuid()), WU);
	}

	ReleaseData publish(Worker w, AgentTaskData t, RearmSpecificationType type) throws RelizaException {
		String path = type.name().toLowerCase() + "/" + UUID.randomUUID() + ".md";
		return agentDocumentService.publish(w.session(), new PublishRequest(t.getUuid(), type, null, path,
				"digest-" + UUID.randomUUID(), "text/markdown", null, null, null,
				"c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "doc", ZonedDateTime.now(), null), WU);
	}

	/** A BOARD_REVIEW_ITEMS round, the ids given with their statuses, e.g. "F-1", "OPEN". */
	ReleaseData reviewItems(Worker w, AgentTaskData t, String verdict, String... idStatus) throws RelizaException {
		List<Map<String, Object>> items = new java.util.ArrayList<>();
		for (int i = 0; i < idStatus.length; i += 2) {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("id", idStatus[i]);
			f.put("priority", 1);
			f.put("status", idStatus[i + 1]);
			f.put("title", "issue " + idStatus[i]);
			items.add(f);
		}
		Map<String, Object> index = new LinkedHashMap<>();
		index.put("kind", "BOARD_REVIEW_ITEMS");
		index.put("verdict", verdict);
		index.put("reviewItems", items);
		String path = "review-items/" + UUID.randomUUID() + ".md";
		return agentDocumentService.publish(w.session(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, path, "d-" + UUID.randomUUID(), "text/markdown",
				path + ".json", "i-" + UUID.randomUUID(), index, "c" + UUID.randomUUID().toString().substring(0, 8),
				DOCS, "reviewItems", ZonedDateTime.now(), null), WU);
	}

	/** The investigating worker takes the investigation, publishes its report and passes with it. */
	ReleaseData investigate(Rig r, Worker w, AgentTaskData inv) throws RelizaException {
		agentTaskService.assign(inv.getUuid(), board(r), w.agentUuid(), w.sessionUuid(), WU);
		ReleaseData report = publish(w, reload(inv), REPORT);
		agentTaskService.signOff(inv.getUuid(), w.sessionUuid(), SignOffOutcome.PASSED, "found it",
				List.of(report.getUuid()), WU);
		return report;
	}

	List<String> infos(Rig r, String containing) {
		return agentBoardService.recentEvents(r.board().getUuid()).stream()
				.filter(e -> e.kind() == AgentBoardData.BoardEventKind.INFO)
				.map(AgentBoardData.BoardEvent::message).filter(m -> m.contains(containing)).toList();
	}
}
