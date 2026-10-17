/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironmentImpl;
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
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentDocumentService;
import io.reliza.service.AgentDocumentService.ElementDependent;
import io.reliza.service.AgentDocumentService.ElementDependents;
import io.reliza.service.AgentDocumentService.ElementVersion;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentDocumentService.TaskElement;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The rework point (gaps §2.A, task 7e55b4a5; elements.md §8): a review item names an element, the
 * element's dependents are walked across documents, and its history across rounds is read. The
 * architect writes the requirements; the coder writes functions and tests that trace to them.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentElementDependentsIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private ApplicationContext applicationContext;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/rework";
	private static final String DOCS_SOURCE = "github:acme/rework-docs";
	private static final String DOCS = "https://github.com/acme/rework-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(7710);
	private static final AtomicInteger COMMIT = new AtomicInteger();

	private record Rig(Organization org, AgentBoardData board, AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rework-" + UUID.randomUUID(),
				"rework", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE, InputScope.TASK, null, null, null)),
				List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, false)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData w = worker(org);
		return new Rig(org, board, w, session(org, w));
	}

	private AgentData worker(Organization org) throws RelizaException {
		return agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "w-" + UUID.randomUUID(), null,
				null, null, WU);
	}

	private AgentSessionData session(Organization org, AgentData w) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), w.getUuid(), null, "s-" + UUID.randomUUID(), "worker",
				null, null, WU);
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

	/** An element as the index JSON has it. */
	private static String el(String id, String parent, String verb, String target, String digest, int line) {
		StringBuilder b = new StringBuilder("{\"id\":\"").append(id).append("\",\"title\":\"").append(id).append(" title\"");
		if (null != parent) b.append(",\"parent\":\"").append(parent).append("\"");
		if (null != verb) {
			b.append(",\"").append("assumes".equals(verb) ? "assumes\":[\"" + target + "\"]"
					: "traces\":[{\"verb\":\"" + verb + "\",\"target\":\"" + target + "\"}]");
		}
		return b.append(",\"contentDigest\":\"").append(digest).append("\",\"line\":").append(line).append("}").toString();
	}

	private static String index(String... elements) {
		return "{\"grammarVersion\":\"1\",\"elements\":[" + String.join(",", elements) + "],\"warnings\":[]}";
	}

	private ReleaseData publish(AgentSessionData s, AgentTaskData t, RearmSpecificationType type, String path,
			String elements) throws Exception {
		String commit = "c" + COMMIT.incrementAndGet();
		return agentDocumentService.publish(s, new PublishRequest(t.getUuid(), type, null, path,
				"digest-of-" + path + commit, "text/markdown", null, null, null, commit, DOCS, "doc", ZonedDateTime.now(),
				null, elements, sha(elements)), WU);
	}

	private AgentTaskData withArchitect(Rig r, AgentData w, AgentSessionData s) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), w.getUuid(), s.getUuid(), WU);
		return reload(t);
	}

	private static final String DESIGN = index(el("REQ-1", null, null, null, "a1", 3), el("REQ-2", "REQ-1", null, null, "a2", 8));

	/** The architect's design, handed over; the coder holds the task. Returns the design release. */
	private ReleaseData designedAndHandedOver(Rig r, AgentTaskData t) throws Exception {
		ReleaseData design = publish(r.session(), t, RearmSpecificationType.ARCHITECTURE, "design/a.md", DESIGN);
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "designed",
				List.of(design.getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return design;
	}

	private static List<String> ids(ElementDependents d) {
		return d.dependents().stream().map(x -> x.element().id()).toList();
	}

	private static ElementDependent dependent(ElementDependents d, String id) {
		return d.dependents().stream().filter(x -> id.equals(x.element().id())).findFirst().orElseThrow();
	}

	// ---------- dependents ----------

	@Test
	public void theDependentsOfARequirementAcrossThreeDocumentsNearestFirst() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r, r.worker(), r.session());
		designedAndHandedOver(r, t);
		ReleaseData note = publish(r.session(), reload(t), RearmSpecificationType.DETAILED_DESIGN, "impl/n.md",
				index(el("FN-1", null, "satisfies", "REQ-1", "f1", 2), el("FN-2", "REQ-2", null, null, "f2", 9)));
		publish(r.session(), reload(t), RearmSpecificationType.TEST_PLAN, "tests/p.md",
				index(el("T-1", null, "verifies", "FN-1", "t1", 4)));

		ElementDependents d = agentDocumentService.dependentsOf(reload(t), "REQ-1", null);
		assertTrue(d.found());
		assertFalse(d.truncated());
		assertEquals(4, d.count());
		assertEquals(List.of("REQ-2", "FN-1", "FN-2", "T-1"), ids(d), "nearest first");
		assertEquals(1, dependent(d, "REQ-2").distance());
		assertEquals("parent", dependent(d, "REQ-2").via().get(0).kind());
		assertEquals("satisfies", dependent(d, "FN-1").via().get(0).kind());
		assertEquals(note.getUuid(), dependent(d, "FN-1").element().release());
		assertEquals(2, dependent(d, "T-1").distance());
		assertEquals(new AgentDocumentService.ElementEdge("T-1", "FN-1", "verifies"), dependent(d, "T-1").via().get(0));

		ElementDependents shallow = agentDocumentService.dependentsOf(reload(t), "REQ-1", 1);
		assertEquals(List.of("REQ-2", "FN-1"), ids(shallow));
		assertTrue(shallow.truncated(), "depth 1 stops before the second ring and says so");

		ElementDependents unknown = agentDocumentService.dependentsOf(reload(t), "REQ-77", null);
		assertFalse(unknown.found());
		assertEquals(0, unknown.count());
	}

	@Test
	public void aCycleTerminates() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r, r.worker(), r.session());
		publish(r.session(), t, RearmSpecificationType.ARCHITECTURE, "design/c.md",
				index(el("REQ-5", "REQ-6", null, null, "x5", 1), el("REQ-6", "REQ-5", null, null, "x6", 2)));
		ElementDependents d = agentDocumentService.dependentsOf(reload(t), "REQ-5", null);
		assertEquals(List.of("REQ-6"), ids(d));
		assertFalse(d.truncated());
	}

	@Test
	public void theBoardsLatestDocumentsAreInScopeAndTheTasksOwnWinsATie() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r, r.worker(), r.session());
		designedAndHandedOver(r, t);
		ReleaseData note = publish(r.session(), reload(t), RearmSpecificationType.DETAILED_DESIGN, "impl/m.md",
				index(el("FN-1", null, "satisfies", "REQ-1", "f1", 2)));

		// Another task on the board writes a design that leans on REQ-1 and reuses FN-1's id.
		AgentData other = worker(r.org());
		AgentSessionData otherSession = session(r.org(), other);
		AgentTaskData u = withArchitect(r, other, otherSession);
		publish(otherSession, u, RearmSpecificationType.ARCHITECTURE, "design/u.md",
				index(el("ADR-9", null, "assumes", "REQ-1", "d9", 5), el("FN-1", null, "satisfies", "REQ-2", "zz", 6)));

		ElementDependents d = agentDocumentService.dependentsOf(reload(t), "REQ-1", null);
		assertTrue(ids(d).contains("ADR-9"), "the board's latest design is in scope: " + ids(d));
		assertEquals("assumes", dependent(d, "ADR-9").via().get(0).kind());
		assertEquals(note.getUuid(), dependent(d, "FN-1").element().release(), "the task's own FN-1 wins");
		assertEquals("f1", dependent(d, "FN-1").element().contentDigest());
	}

	// ---------- history ----------

	@Test
	public void anElementsHistoryMarksTheRoundThatChangedIt() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r, r.worker(), r.session());
		// Each round at its own path, as a {round} template writes them: the same path again in one hop is a
		// new version of the round, not the next round (task RD4-7).
		ReleaseData one = publish(r.session(), t, RearmSpecificationType.ARCHITECTURE, "design/h-1.md",
				index(el("REQ-1", null, null, null, "v1", 3)));
		ReleaseData two = publish(r.session(), reload(t), RearmSpecificationType.ARCHITECTURE, "design/h-2.md",
				index(el("REQ-1", null, null, null, "v2", 5)));
		ReleaseData three = publish(r.session(), reload(t), RearmSpecificationType.ARCHITECTURE, "design/h-3.md",
				index(el("REQ-1", null, null, null, "v2", 5), el("REQ-3", null, null, null, "n3", 9)));

		List<ElementVersion> h = agentDocumentService.elementHistory(reload(t), "REQ-1");
		assertEquals(List.of(one.getUuid(), two.getUuid(), three.getUuid()), h.stream().map(ElementVersion::release).toList());
		assertEquals(List.of(false, true, false), h.stream().map(ElementVersion::changed).toList());
		assertEquals(List.of(3, 5, 5), h.stream().map(ElementVersion::line).toList());
		assertEquals(List.of(1, 2, 3), h.stream().map(ElementVersion::round).toList());
		assertEquals(1, agentDocumentService.elementHistory(reload(t), "REQ-3").size());
		assertTrue(agentDocumentService.elementHistory(reload(t), "REQ-77").isEmpty());
	}

	// ---------- a review item names an element ----------

	private Map<String, Object> review(UUID about, Map<String, Object> location) {
		Map<String, Object> reviewItem = new LinkedHashMap<>();
		reviewItem.put("id", "F-1");
		reviewItem.put("priority", 2);
		reviewItem.put("status", "OPEN");
		reviewItem.put("title", "the requirement is vague");
		reviewItem.put("location", location);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_REVIEW_ITEMS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", List.of(reviewItem));
		idx.put("about", Map.of("specification", "ARCHITECTURE", "release", about.toString()));
		return idx;
	}

	private ReleaseData publishReview(Rig r, AgentTaskData t, Map<String, Object> index) throws RelizaException {
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null, index, null, null, null, null,
				null), WU);
	}

	private DgsDataFetchingEnvironment env(Object source, Object localContext) {
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.source(source).localContext(localContext).build(), applicationContext);
	}

	@Test
	public void aReviewItemNamesAnElementAndReadsItAsReviewed() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r, r.worker(), r.session());
		ReleaseData design = designedAndHandedOver(r, t);

		RelizaException typo = assertThrows(RelizaException.class, () -> publishReview(r, reload(t),
				review(design.getUuid(), Map.of("element", "REQ-3"))));
		assertTrue(typo.getMessage().contains("names element REQ-3") && typo.getMessage().contains("nearest: REQ-1, REQ-2"),
				typo.getMessage());

		ReleaseData round = publishReview(r, reload(t), review(design.getUuid(), Map.of("element", "REQ-2")));
		BoardReviewItem f = round.getDocument().reviewItems().reviewItems().get(0);
		assertEquals(new BoardReviewItemIndex.BoardReviewItemLocation("design/a.md", 8, null, "REQ-2"), f.location(),
				"path and line are filled in from the design's index");

		// the resolver hands the round's about release down to its items
		DataFetcherResult<BoardReviewItemIndex> items = fetcher.documentReviewItems(env(round.getDocument(), null));
		Object ctx = items.getLocalContext();
		assertNotNull(ctx);
		TaskElement seen = fetcher.reviewItemElement(env(f, ctx));
		assertEquals("REQ-2", seen.id());
		assertEquals(design.getUuid(), seen.release());
		assertEquals("a2", seen.contentDigest(), "the element as it was in the reviewed release");

		// a task's open items resolve through its documents as they are now
		DataFetcherResult<List<BoardReviewItem>> open = fetcher.taskOpenReviewItems(env(reload(t), null));
		assertEquals("REQ-2", fetcher.reviewItemElement(env(open.getData().get(0), open.getLocalContext())).id());

		// and an about release without an index has nothing to show
		assertNull(agentDocumentService.elementOf(round.getUuid(), "REQ-2").orElse(null));
		assertNull(fetcher.reviewItemElement(env(f, null)), "no context, no element");
	}
}
