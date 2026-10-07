/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoard;
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
import io.reliza.repositories.AgentBoardRepository;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Where documents go by key (board-documents.md §3, test 4; task 0cc38817): the defaults the
 * handoff repository already uses, {task} read as {key} wherever a template comes from, a task
 * from before keys keyed on its first publish, and a board root on a repository several boards share.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDocumentPathsIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/key-paths";
	private static final String DOCS = "https://github.com/acme/key-paths-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");
	private static final RearmSpecificationType ARCH = RearmSpecificationType.ARCHITECTURE;
	private static final RearmSpecificationType DD = RearmSpecificationType.DETAILED_DESIGN;

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentBoardRepository boardRepository;
	@Autowired private SharedReleaseService sharedReleaseService;

	private record Rig(Organization org, Component target, String targetName, AgentBoardData board, AgentTaskData task,
			AgentData agent, AgentSessionData session) {}

	/** An architect per task producing ARCHITECTURE and a GLOSSARY with no layout of its own; a coder per task. */
	private Rig rig(String name, String prefix) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String targetName = "keypaths_" + UUID.randomUUID();
		Component target = componentService.createComponent(targetName, org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), name, "key paths",
				List.of(SRC, "github:acme/key-paths-docs"), "coordinate", 4, null, target.getUuid(), null, prefix, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(ARCH, InputScope.TASK, false),
						new ProducedOutput(RearmSpecificationType.GLOSSARY, InputScope.TASK, false))), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(DD, InputScope.TASK, false))), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentTaskData task = agentTaskService.register(board, null, "paths", null, null, null, null, null, null, WU);
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"a-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "paths", null, null, WU);
		return new Rig(org, target, targetName, board, task, agent, session);
	}

	private Rig rig() throws RelizaException {
		return rig("Key Paths " + UUID.randomUUID(), null);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData task(Rig r) {
		return agentTaskService.getTaskData(r.task().getUuid()).orElseThrow();
	}

	private String path(Rig r, RearmSpecificationType spec) throws RelizaException {
		return agentDocumentService.resolveDocumentPath(board(r), spec, r.task().getUuid(), null);
	}

	private void assignArchitect(Rig r) throws RelizaException {
		agentTaskService.authorize(r.task().getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(r.task().getUuid(), board(r), r.agent().getUuid(), r.session().getUuid(), WU);
	}

	private void publishArchitecture(Rig r, String path) throws RelizaException {
		agentDocumentService.publish(r.session(), new PublishRequest(r.task().getUuid(), ARCH, null, path,
				"sha256:" + UUID.randomUUID().toString().replace("-", ""), "text/markdown", null, null, null,
				"c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "doc", ZonedDateTime.now(), null), WU);
	}

	/** The task's architecture rounds, oldest first, by the path each was written at. */
	private List<String> rounds(Rig r) {
		return task(r).getReleases().stream().map(u -> sharedReleaseService.getReleaseData(u).orElseThrow())
				.filter(rd -> null != rd.getDocument() && ARCH == rd.getDocument().specification())
				.map(rd -> rd.getDocument().path()).toList();
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private ApplyResult apply(Rig r, Map<String, Object> more) throws RelizaException {
		Map<String, Object> spec = map("kind", "BOARD", "version", 1, "name", r.board().getName());
		spec.putAll(more);
		return agentBoardService.applyBoard(r.org().getUuid(), AgentBoardService.boardSpecFromInput(spec), false,
				null, PERSON, WU);
	}

	// ---------- 1. defaults ----------

	@Test
	public void theDefaultsAreTheHandoffRepositorysLayoutByKey() throws RelizaException {
		Rig r = rig();
		String key = r.task().getKey();
		assertTrue(key.matches("[A-Z0-9]+-1"), key);
		assertEquals("design/" + key + "/architecture-1.md", path(r, ARCH));
		assertEquals("impl/" + key + "/notes-1.md", path(r, DD));
		assertEquals("design/" + key + "/glossary-1.md", path(r, RearmSpecificationType.GLOSSARY),
				"a prose type with no layout of its own sits beside the architecture");
		assertEquals("review-items/" + key + "/round-1.md", path(r, RearmSpecificationType.BOARD_REVIEW_ITEMS));
		assertEquals("tests/" + key + "/run-1.md", path(r, RearmSpecificationType.BOARD_TEST_REPORT));
		assertEquals("questions/" + key + "/round-1.md", path(r, RearmSpecificationType.BOARD_QUESTIONS));

		// Produced per component, ARCHITECTURE keeps the component default.
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(ARCH, InputScope.COMPONENT, false))), true, WU);
		assertEquals("docs/architecture/" + Utils.slug(r.targetName()) + ".md",
				agentDocumentService.resolveDocumentPath(board(r), ARCH, null, r.target().getUuid()));
	}

	// ---------- 2. {task} is {key} ----------

	@Test
	public void aTaskPlaceholderIsReadAsKeyFromEverySource() throws RelizaException {
		Rig r = rig();
		String key = r.task().getKey();

		// A row stored before the change.
		AgentBoard row = boardRepository.findById(r.board().getUuid()).orElseThrow();
		Map<String, Object> raw = new LinkedHashMap<>(row.getRecordData());
		raw.put("documentPaths", map("BOARD_REVIEW_ITEMS", "reviews/{task}/round-{round}.md"));
		row.setRecordData(raw);
		boardRepository.save(row);
		assertEquals("reviews/{key}/round-{round}.md", board(r).getDocumentPaths().get(RearmSpecificationType.BOARD_REVIEW_ITEMS));
		assertEquals("reviews/" + key + "/round-1.md", path(r, RearmSpecificationType.BOARD_REVIEW_ITEMS));

		// setDocumentsConfig, the form's path.
		agentBoardService.setDocumentsConfig(r.board().getUuid(), null, Map.of(ARCH, "arch/{task}-{round}.md"), WU);
		assertEquals("arch/{key}-{round}.md", board(r).getDocumentPaths().get(ARCH));

		// A board file, and its export.
		ApplyResult applied = apply(r, map("documentPaths", map("DETAILED_DESIGN", "work/{task}/{round}.md")));
		assertEquals(0, applied.getErrors(), applied.getChanges().toString());
		assertEquals("work/{key}/{round}.md", board(r).getDocumentPaths().get(DD));
		assertEquals("work/{key}/{round}.md", agentBoardService.exportBoard(r.board().getUuid()).getDocumentPaths().get(DD));
		assertEquals("work/{key}/{round}.md", agentBoardService.effectiveDocumentPaths(board(r)).get(DD));
	}

	// ---------- 3. a task from before keys ----------

	@Test
	public void aTaskWithoutAKeyGetsItOnPublishAndItsNextRoundGoesUnderIt() throws RelizaException {
		Rig r = rig("Before Keys " + UUID.randomUUID(), null);
		assignArchitect(r);
		// Round 1 written the old way, then the board put back to how it looked before keys.
		String task8 = r.task().getUuid().toString().substring(0, 8);
		String oldPath = "design/" + task8 + "/architecture-1.md";
		publishArchitecture(r, oldPath);
		String oldReviewItems = "review-items/" + task8 + "/round-1.md";
		agentDocumentService.publish(r.session(), new PublishRequest(r.task().getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, oldReviewItems, "sha256:f" + UUID.randomUUID(), "text/markdown",
				"review-items/" + task8 + "/round-1.json", "idx-" + UUID.randomUUID(),
				map("kind", "BOARD_REVIEW_ITEMS", "verdict", "PASSED", "reviewItems", List.of()),
				"c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "reviewItems", ZonedDateTime.now(), null), WU);
		AgentTaskData unkeyed = task(r);
		unkeyed.setNumber(null);
		unkeyed.setKey(null);
		agentTaskService.saveData(unkeyed, WU);
		AgentBoardData unnumbered = board(r);
		unnumbered.setTasksNumbered(false);
		unnumbered.setNextTaskNumber(0);
		unnumbered.setTaskPrefix(null);
		unnumbered.setTaskPrefixHistory(new java.util.ArrayList<>());
		agentBoardService.saveData(unnumbered, WU);
		assertNull(task(r).getKey());

		String next = path(r, ARCH);
		String key = task(r).getKey();
		assertTrue(null != key && key.endsWith("-1"), "the path read numbered the board: " + key);
		assertEquals("design/" + key + "/architecture-2.md", next, "round 2, under the key");
		// Round 1 stays where it was written and still counts: rounds go by task and type, not by path.
		assertEquals(List.of(oldPath), rounds(r));
		// And the carry-forward check still finds round 1 of an index type as the previous round.
		assertEquals("review-items/" + key + "/round-2.md", path(r, RearmSpecificationType.BOARD_REVIEW_ITEMS));
		assertEquals(oldReviewItems, agentDocumentService.latestRoundRelease(task(r), RearmSpecificationType.BOARD_REVIEW_ITEMS)
				.orElseThrow().getDocument().path());

		publishArchitecture(r, next);
		assertEquals("design/" + key + "/architecture-3.md", path(r, ARCH));
		assertEquals(List.of(oldPath, next), rounds(r));
	}

	// ---------- 4. root ----------

	@Test
	public void aSharedRepositoryPutsEachBoardUnderItsOwnRoot() throws RelizaException {
		Rig r = rig();
		String key = r.task().getKey();
		String slug = AgentBoardData.slug(r.board().getName());
		assertEquals("", board(r).documentsRoot());

		agentBoardService.setDocumentsBlock(r.board().getUuid(), map("shared", true), WU);
		assertEquals("boards/" + slug + "/", board(r).documentsRoot());
		assertEquals("boards/" + slug + "/design/" + key + "/architecture-1.md", path(r, ARCH));
		assertEquals("design/{key}/architecture-{round}.md", agentBoardService.effectiveDocumentPaths(board(r)).get(ARCH),
				"templates stay bare: the root goes in front once, at resolution");

		agentBoardService.setDocumentsBlock(r.board().getUuid(), map("root", "teams/{board}"), WU);
		assertEquals("teams/" + slug + "/design/" + key + "/architecture-1.md", path(r, ARCH), "an explicit root wins");
		agentBoardService.setDocumentsBlock(r.board().getUuid(), map("root", ""), WU);
		assertEquals("design/" + key + "/architecture-1.md", path(r, ARCH), "an explicit empty root is the repository root");
		agentBoardService.setDocumentsBlock(r.board().getUuid(), map("shared", false, "root", "/mine/"), WU);
		assertEquals("mine/", board(r).getDocuments().root(), "a leading slash is dropped as stored");
		assertEquals("mine/design/" + key + "/architecture-1.md", path(r, ARCH), "a root without shared is honoured");

		RelizaException climb = assertThrows(RelizaException.class,
				() -> agentBoardService.setDocumentsBlock(r.board().getUuid(), map("root", "a/../../etc"), WU));
		assertTrue(climb.getMessage().contains("documents.root"), climb.getMessage());
		assertEquals("mine/", board(r).getDocuments().root(), "a refused root changes nothing");

		// The slug is the board's name as it stands, not as it was.
		agentBoardService.setDocumentsBlock(r.board().getUuid(), map("shared", true, "root", null), WU);
		AgentBoardData renamed = board(r);
		renamed.setName("Renamed " + UUID.randomUUID());
		agentBoardService.saveData(renamed, WU);
		assertEquals("boards/" + AgentBoardData.slug(renamed.getName()) + "/", board(r).documentsRoot());

		// A null block restores every default.
		agentBoardService.setDocumentsBlock(r.board().getUuid(), null, WU);
		assertNull(board(r).getDocuments());
		assertEquals("", board(r).documentsRoot());
	}

	// ---------- 5. the board file ----------

	@Test
	public void aBoardFileDeclaresSharedAndRootAndItsExportAppliesUnchanged() throws RelizaException {
		Rig r = rig();
		ApplyResult applied = apply(r, map("documents", map("prefix", "Team Docs", "shared", true, "root", "/boards/x")));
		assertEquals(0, applied.getErrors(), applied.getChanges().toString());
		AgentBoardData.DocumentsConfig docs = board(r).getDocuments();
		assertEquals("Team Docs", docs.prefix());
		assertEquals(Boolean.TRUE, docs.shared());
		assertEquals("boards/x", docs.root());

		AgentBoardService.BoardSpecDto exported = agentBoardService.exportBoard(r.board().getUuid());
		assertEquals(Boolean.TRUE, exported.getDocuments().getShared());
		assertEquals("boards/x", exported.getDocuments().getRoot());
		@SuppressWarnings("unchecked")
		Map<String, Object> input = Utils.OM.convertValue(exported, Map.class);
		ApplyResult again = agentBoardService.applyBoard(r.org().getUuid(), AgentBoardService.boardSpecFromInput(input),
				false, null, PERSON, WU);
		assertEquals(0, again.getErrors(), again.getChanges().toString());
		assertTrue(again.getChanges().stream().allMatch(c -> DeclarativeConfigService.Action.UNCHANGED == c.getAction()),
				again.getChanges().toString());

		// D15: a member left out stays, a member declared null clears, the block declared null clears all.
		apply(r, map("documents", map("root", null)));
		assertEquals(Boolean.TRUE, board(r).getDocuments().shared(), "left out, it stays");
		assertNull(board(r).getDocuments().root(), "declared null, it clears");
		assertEquals("Team Docs", board(r).getDocuments().prefix());
		apply(r, map("documents", null));
		assertNull(board(r).getDocuments());

		ApplyResult climb = apply(r, map("documents", map("root", "../elsewhere")));
		assertTrue(climb.getErrors() > 0, climb.getChanges().toString());
		assertTrue(climb.getChanges().toString().contains("documents.root"), climb.getChanges().toString());
		assertNull(board(r).getDocuments(), "a refused file writes nothing");
	}
}
