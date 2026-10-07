/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Where a new document goes, as the server resolves it for the CLI (gaps §1.18): the default by
 * the scope the board's roles produce a type at, an override winning, every placeholder filled.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBoardDocumentPathIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/paths";
	private static final String DOCS = "https://github.com/acme/paths-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	private record Rig(Organization org, AgentBoardData board, Component target, String targetName,
			AgentTaskData task, AgentData agent, AgentSessionData session) {}

	/** A coder producing DETAILED_DESIGN per task and a designer producing ARCHITECTURE per component. */
	private Rig rig(Map<RearmSpecificationType, String> overrides) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String targetName = "Platform API v2 " + UUID.randomUUID();
		Component target = componentService.createComponent(targetName, org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "paths-" + UUID.randomUUID(), "paths board",
				List.of(SRC, "github:acme/paths-docs"), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("designer", "design it", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.COMPONENT, false))), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build it", 20,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, false))), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, overrides, WU);
		AgentTaskData task = agentTaskService.register(board, SRC + "#" + Math.abs(UUID.randomUUID().hashCode() % 100000),
				"paths", null, null, null, null, null, null, WU);
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"coder-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "paths session", null, null, WU);
		return new Rig(org, board, target, targetName, task, agent, session);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private String path(Rig r, RearmSpecificationType spec, UUID task, UUID component) throws RelizaException {
		return agentDocumentService.resolveDocumentPath(board(r), spec, task, component);
	}

	private static String key(Rig r) {
		return r.task().getKey();
	}

	@Test
	public void aTaskScopedTypeGetsAFilePerTaskAndRound() throws RelizaException {
		Rig r = rig(null);
		assertEquals("impl/" + key(r) + "/notes-1.md",
				path(r, RearmSpecificationType.DETAILED_DESIGN, r.task().getUuid(), null));

		// One published round later, the next one: the same counter publish uses.
		agentTaskService.authorize(r.task().getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(r.task().getUuid(), board(r), r.agent().getUuid(), r.session().getUuid(), WU);
		String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
		agentDocumentService.publish(r.session(), new PublishRequest(r.task().getUuid(),
				RearmSpecificationType.DETAILED_DESIGN, null, "impl/" + key(r) + "/notes-1.md", digest,
				"text/markdown", null, null, null, "c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "note",
				ZonedDateTime.now(), null), WU);
		assertEquals("impl/" + key(r) + "/notes-2.md",
				path(r, RearmSpecificationType.DETAILED_DESIGN, r.task().getUuid(), null));
	}

	@Test
	public void aComponentScopedTypeNeedsTheComponentAndSlugsItsName() throws RelizaException {
		Rig r = rig(null);
		String slug = io.reliza.common.Utils.slug(r.targetName());
		assertTrue(slug.startsWith("platform-api-v2-"), slug);
		assertEquals("docs/architecture/" + slug + ".md",
				path(r, RearmSpecificationType.ARCHITECTURE, null, r.target().getUuid()));
		RelizaException e = assertThrows(RelizaException.class,
				() -> path(r, RearmSpecificationType.ARCHITECTURE, r.task().getUuid(), null));
		assertTrue(e.getMessage().contains("per component; name the component"), e.getMessage());
	}

	@Test
	public void aTaskScopedTypeNeedsTheTask() throws RelizaException {
		Rig r = rig(null);
		RelizaException e = assertThrows(RelizaException.class,
				() -> path(r, RearmSpecificationType.DETAILED_DESIGN, null, r.target().getUuid()));
		assertTrue(e.getMessage().contains("per task; name the task"), e.getMessage());
	}

	@Test
	public void theIndexTypesAndQuestionsDefaultPerTask() throws RelizaException {
		Rig r = rig(null);
		assertEquals("questions/" + key(r) + "/round-1.md", path(r, RearmSpecificationType.BOARD_QUESTIONS, r.task().getUuid(), null));
		assertEquals("review-items/" + key(r) + "/round-1.md",
				path(r, RearmSpecificationType.BOARD_REVIEW_ITEMS, r.task().getUuid(), null));
		assertEquals("tests/" + key(r) + "/run-1.md", path(r, RearmSpecificationType.BOARD_TEST_REPORT, r.task().getUuid(), null));
	}

	@Test
	public void anOverrideWins() throws RelizaException {
		Rig r = rig(Map.of(RearmSpecificationType.DETAILED_DESIGN, "work/{key}/dd-{round}.md",
				RearmSpecificationType.ARCHITECTURE, "arch/{key}/{round}.md"));
		assertEquals("work/" + key(r) + "/dd-1.md", path(r, RearmSpecificationType.DETAILED_DESIGN, r.task().getUuid(), null));
		// The override is per task, so the architecture needs the task, not the component.
		assertEquals("arch/" + key(r) + "/1.md",
				path(r, RearmSpecificationType.ARCHITECTURE, r.task().getUuid(), null));
		Map<RearmSpecificationType, String> effective = agentBoardService.effectiveDocumentPaths(board(r));
		assertEquals("work/{key}/dd-{round}.md", effective.get(RearmSpecificationType.DETAILED_DESIGN));
		assertEquals("docs/{type}/{component}.md", effective.get(RearmSpecificationType.GLOSSARY),
				"a type no role produces per task keeps the component default");
	}

	@Test
	public void aTaskOrComponentFromElsewhereIsRefused() throws RelizaException {
		Rig r = rig(null);
		Rig other = rig(null);
		assertThrows(RelizaException.class,
				() -> path(r, RearmSpecificationType.DETAILED_DESIGN, other.task().getUuid(), null),
				"a task on another board");
		assertThrows(RelizaException.class,
				() -> path(r, RearmSpecificationType.ARCHITECTURE, null, UUID.randomUUID()), "an unknown component");
	}
}
