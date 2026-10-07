/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ElementIndex;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A prose document's element index is published, checked, stored and served (gaps §2.A, task
 * e200cb32, design test 9). The architect publishes the requirements; the coder, whose input is that
 * design, publishes functions that trace to them.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentElementIndexIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/elements";
	private static final String DOCS_SOURCE = "github:acme/elements-docs";
	private static final String DOCS = "https://github.com/acme/elements-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(9990);

	private static final String REQUIREMENTS = "{\"grammarVersion\":\"1\",\"elements\":["
			+ "{\"id\":\"REQ-1\",\"family\":\"requirement\",\"title\":\"Refuse a cycle\",\"contentDigest\":\"a1\",\"line\":3},"
			+ "{\"id\":\"REQ-2\",\"title\":\"Name the tasks\",\"parent\":\"REQ-1\",\"contentDigest\":\"a2\",\"line\":8}],"
			+ "\"warnings\":[]}";

	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "elements-" + UUID.randomUUID(),
				"elements", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE, InputScope.TASK, null, null, null)),
				List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, false)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		return new Rig(board, w, agentSessionService.initialize(org.getUuid(), w.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU));
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private static String sha(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private ReleaseData publish(Rig r, AgentTaskData t, RearmSpecificationType type, String path, String commit,
			String elements, String elementsDigest) throws RelizaException {
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(), type, null, path,
				"digest-of-" + path, "text/markdown", null, null, null, commit, DOCS, "doc", ZonedDateTime.now(), null,
				elements, elementsDigest), WU);
	}

	/** A task held by the architect. */
	private AgentTaskData withArchitect(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return reload(t);
	}

	@Test
	public void aPublishStoresTheCheckedIndexAndTheTaskServesIt() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData rd = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/a.md", "c1", REQUIREMENTS, sha(REQUIREMENTS));

		ElementIndex stored = sharedReleaseService.getReleaseData(rd.getUuid()).orElseThrow().getDocument().elements();
		assertNotNull(stored);
		assertEquals(sha(REQUIREMENTS), stored.digest());
		assertEquals(List.of("REQ-1", "REQ-2"), stored.elements().stream().map(ElementIndex.Element::id).toList());
		assertEquals("requirement", stored.elements().get(1).family(), "the server sets the family from the board");
		assertTrue(stored.warnings().isEmpty(), stored.warnings().toString());

		List<AgentDocumentService.TaskElement> onTask = agentDocumentService.taskElements(reload(t));
		assertEquals(2, onTask.size());
		assertEquals(rd.getUuid(), onTask.get(0).release());
		assertEquals("ARCHITECTURE", onTask.get(0).specification());
	}

	@Test
	public void theElementsOfAPinnedInputResolve() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData design = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/b.md", "c1", REQUIREMENTS,
				sha(REQUIREMENTS));
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "designed",
				List.of(design.getUuid()), WU);
		assertEquals("coder", reload(t).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);

		String functions = "{\"grammarVersion\":\"1\",\"elements\":["
				+ "{\"id\":\"FN-1\",\"title\":\"Detect\",\"traces\":[{\"verb\":\"satisfies\",\"target\":\"REQ-1\"}],\"contentDigest\":\"f1\",\"line\":2},"
				+ "{\"id\":\"FN-2\",\"title\":\"Report\",\"parent\":\"REQ-2\",\"traces\":[{\"verb\":\"satisfies\",\"target\":\"REQ-9\"}],\"contentDigest\":\"f2\",\"line\":9}],"
				+ "\"warnings\":[]}";
		ReleaseData note = publish(r, reload(t), RearmSpecificationType.DETAILED_DESIGN, "impl/b.md", "c2", functions,
				sha(functions));

		ElementIndex stored = note.getDocument().elements();
		assertEquals(1, stored.warnings().size(), stored.warnings().toString());
		assertEquals(ElementIndex.UNRESOLVED_TARGET, stored.warnings().get(0).code());
		assertTrue(stored.warnings().get(0).message().contains("REQ-9"),
				"REQ-1 and REQ-2 resolve through the pinned design; REQ-9 does not");
		assertEquals(4, agentDocumentService.taskElements(reload(t)).size(), "the task serves both documents' elements");
	}

	@Test
	public void aRepublishIsTheSameReleaseOnlyWithTheSameIndex() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData first = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/c.md", "c1", REQUIREMENTS,
				sha(REQUIREMENTS));
		assertEquals(first.getUuid(), publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/c.md", "c1",
				REQUIREMENTS, sha(REQUIREMENTS)).getUuid(), "a retry returns the release");

		String other = REQUIREMENTS.replace("Refuse a cycle", "Refuse every cycle");
		RelizaException differs = assertThrows(RelizaException.class, () -> publish(r, t,
				RearmSpecificationType.ARCHITECTURE, "design/c.md", "c1", other, sha(other)));
		assertTrue(differs.getMessage().contains("differs from the earlier publish"), differs.getMessage());

		RelizaException tampered = assertThrows(RelizaException.class, () -> publish(r, t,
				RearmSpecificationType.ARCHITECTURE, "design/d.md", "c3", other, sha(REQUIREMENTS)));
		assertTrue(tampered.getMessage().contains("does not match its elementsDigest"), tampered.getMessage());
	}

	@Test
	public void aDocumentWithoutAnIndexPublishesAsBeforeAndIndexTypesTakeNone() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData plain = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/e.md", "c1", null, null);
		assertNull(plain.getDocument().elements());

		RelizaException refused = assertThrows(RelizaException.class, () -> agentDocumentService.publish(r.session(),
				new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null, null, null,
						new java.util.LinkedHashMap<>(java.util.Map.of("kind", "BOARD_QUESTIONS", "verdict", "REJECTED",
								"reviewItems", List.of())), null, null, null, null, null, REQUIREMENTS, sha(REQUIREMENTS)), WU));
		assertTrue(refused.getMessage().contains("carries a review item index, not elements"), refused.getMessage());
	}
}
