/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.ElementCheckPolicy;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.ElementCheckReport.ElementCheckResult;
import io.reliza.model.ElementCheckReport.Result;
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
 * Grammar 1.2 at the board (task RD4-6): a publish keeps the definitions its document type may make and records the
 * rest as references, an index type carries elements when a family is defined in it, and {@code doc publish --check}
 * previews the checks a publish would run in the same scope without creating anything.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDocumentElementCheckIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/grammar";
	private static final String DOCS_SOURCE = "github:acme/grammar-docs";
	private static final String DOCS = "https://github.com/acme/grammar-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(1200);

	/** REQ-1 alone: every check passes. */
	private static final String CLEAN = "{\"grammarVersion\":\"1.2\",\"elements\":["
			+ "{\"id\":\"REQ-1\",\"title\":\"Take the lock\",\"contentDigest\":\"a1\",\"line\":3}],"
			+ "\"references\":[],\"warnings\":[]}";

	/** REQ-12's parent REQ-4 exists nowhere: trace.parent_exists fails. */
	private static final String DANGLING = "{\"grammarVersion\":\"1.2\",\"elements\":["
			+ "{\"id\":\"REQ-1\",\"title\":\"Take the lock\",\"contentDigest\":\"a1\",\"line\":3},"
			+ "{\"id\":\"REQ-12\",\"title\":\"Report it\",\"parent\":\"REQ-4\",\"contentDigest\":\"b1\",\"line\":9}],"
			+ "\"references\":[],\"warnings\":[]}";

	/**
	 * What a CLI that read no lists sends for a design answering a tester and an asker: T-1 and Q-1 as elements.
	 * Grammar 1.2, so the board sorts them itself.
	 */
	private static final String UNSORTED = "{\"grammarVersion\":\"1.2\",\"elements\":["
			+ "{\"id\":\"REQ-1\",\"title\":\"Take the lock\",\"contentDigest\":\"a1\",\"line\":3},"
			+ "{\"id\":\"T-1\",\"title\":\"the flaky test is fixed\",\"contentDigest\":\"t1\",\"line\":8},"
			+ "{\"id\":\"Q-1\",\"title\":\"answered: the task's lock\",\"contentDigest\":\"q1\",\"line\":12}],"
			+ "\"references\":[],\"warnings\":[]}";

	/** A test report's T-1, which verifies nothing. */
	private static final String REPORT = "{\"grammarVersion\":\"1.2\",\"elements\":["
			+ "{\"id\":\"T-1\",\"title\":\"the flaky test is fixed\",\"contentDigest\":\"t1\",\"line\":3}],"
			+ "\"references\":[],\"warnings\":[]}";

	/** A test plan's T-1, which verifies REQ-1. */
	private static final String PLAN = "{\"grammarVersion\":\"1.2\",\"elements\":["
			+ "{\"id\":\"T-1\",\"title\":\"The publish retries under the lock\",\"traces\":[{\"verb\":\"verifies\","
			+ "\"target\":\"REQ-1\"}],\"contentDigest\":\"p1\",\"line\":3}],\"references\":[],\"warnings\":[]}";

	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("grammar_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "grammar-" + UUID.randomUUID(),
				"grammar", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false),
						new ProducedOutput(RearmSpecificationType.TEST_PLAN, InputScope.TASK, false)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), w.getUuid(), null, "s-" + UUID.randomUUID(),
				"worker", null, null, WU);
		return new Rig(board, w, s);
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

	private ReleaseData publish(Rig r, AgentTaskData t, RearmSpecificationType spec, String path, String commit,
			String elements) throws Exception {
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(), spec, null, path,
				"digest-of-" + path + commit, "text/markdown", null, null, null, commit, DOCS, "doc", ZonedDateTime.now(),
				null, elements, sha(elements)), WU);
	}

	private AgentTaskData withArchitect(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return reload(t);
	}

	private List<ReleaseData> reports(AgentTaskData td) {
		return agentDocumentService.documentsOfTask(reload(td)).stream()
				.filter(rd -> RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT == rd.getDocument().specification()).toList();
	}

	private static ElementCheckResult result(ElementCheckReport report, String check) {
		return report.results().stream().filter(c -> c.check().equals(check)).findFirst().orElseThrow();
	}

	private static List<String> outcomes(ElementCheckReport report) {
		return report.results().stream().map(c -> c.check() + "=" + c.result() + (c.blocking() ? "!" : "") + " "
				+ c.offences().stream().map(ElementCheckReport.Offence::message).toList()).toList();
	}

	@Test
	public void aHeadingOfAFamilyDefinedElsewhereIsStoredAsAReference() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData design = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/a.md", "c1", UNSORTED);
		ElementIndex stored = design.getDocument().elements();
		assertEquals(List.of("REQ-1"), stored.elements().stream().map(ElementIndex.Element::id).toList());
		assertEquals(List.of("T-1", "Q-1"), stored.references().stream().map(ElementIndex.Reference::id).toList());
		assertEquals(List.of("test", "question"), stored.references().stream().map(ElementIndex.Reference::family).toList());
		ElementCheckReport report = agentDocumentService.latestElementCheckReport(design.getUuid()).orElseThrow().getDocument().elementChecks();
		assertEquals(Result.PASS, result(report, ElementCheckCatalogueService.IDS_FAMILY).result(), outcomes(report).toString());
		assertEquals(ElementCheckCatalogueService.CATALOGUE_VERSION, report.catalogueVersion());
		assertTrue(agentDocumentService.taskElements(reload(t)).stream().noneMatch(e -> "T-1".equals(e.id())),
				"a reference is not one of the task's elements");
	}

	@Test
	public void aDocumentThatOnlyReferencesCutsNoReport() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		String onlyRefs = "{\"grammarVersion\":\"1.2\",\"elements\":[],\"references\":[{\"id\":\"T-1\",\"family\":\"test\","
				+ "\"title\":\"fixed\",\"line\":3}],\"warnings\":[]}";
		ReleaseData note = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/refs.md", "c1", onlyRefs);
		assertEquals(1, note.getDocument().elements().references().size());
		assertTrue(reports(t).isEmpty(), "nothing is defined, so nothing is checked");
	}

	@Test
	public void anOlderIndexIsReadAsBeforeAndAnUnknownGrammarIsRefused() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData old = publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/old.md", "c1",
				UNSORTED.replace("\"1.2\"", "\"1.1\""));
		assertEquals(3, old.getDocument().elements().elements().size(), "grammar 1.1: every heading defines");
		assertTrue(old.getDocument().elements().references().isEmpty());
		RelizaException refused = assertThrows(RelizaException.class, () -> publish(r, reload(t),
				RearmSpecificationType.ARCHITECTURE, "design/new.md", "c2", CLEAN.replace("\"1.2\"", "\"2\"")));
		assertTrue(refused.getMessage().contains("grammar 2; this server reads 1, 1.1, 1.2"), refused.getMessage());
	}

	@Test
	public void thePreviewRunsWhatAPublishWouldAndCreatesNothing() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/p.md", "c1", CLEAN);
		agentBoardService.setElementCheckPolicy(board(r).getUuid(),
				new ElementCheckPolicy(List.of(ElementCheckCatalogueService.TRACE_PARENT_EXISTS), null, null), WU);
		int documentsBefore = agentDocumentService.documentsOfTask(reload(t)).size();

		ElementCheckReport preview = agentDocumentService.previewElementChecks(reload(t), RearmSpecificationType.ARCHITECTURE,
				DANGLING, sha(DANGLING));
		ElementCheckResult parent = result(preview, ElementCheckCatalogueService.TRACE_PARENT_EXISTS);
		assertEquals(Result.FAIL, parent.result());
		assertTrue(parent.blocking(), "the board blocks on it, so doc publish --check exits 1");
		assertEquals(AgentDocumentService.PREVIEW_RELEASE, preview.scope().checked());
		assertEquals(documentsBefore, agentDocumentService.documentsOfTask(reload(t)).size(), "no release, no report");
		assertEquals(1, reports(t).size());

		// The same index published for real is checked in the same scope, with the same outcome.
		ReleaseData published = publish(r, reload(t), RearmSpecificationType.ARCHITECTURE, "design/p.md", "c2", DANGLING);
		ElementCheckReport real = agentDocumentService.latestElementCheckReport(published.getUuid()).orElseThrow().getDocument().elementChecks();
		assertEquals(outcomes(real), outcomes(preview));
		assertEquals(real.scope().releases().subList(1, real.scope().releases().size()),
				preview.scope().releases().subList(1, preview.scope().releases().size()),
				"the rest of the scope is the publish's: the round it replaces is not in it");

		RelizaException digest = assertThrows(RelizaException.class, () -> agentDocumentService.previewElementChecks(reload(t),
				RearmSpecificationType.ARCHITECTURE, DANGLING, sha(CLEAN)));
		assertTrue(digest.getMessage().contains("does not match its elementsDigest"), digest.getMessage());
	}

	@Test
	public void aTestReportDefinesItsTestsUntilAPlanDoes() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		publish(r, t, RearmSpecificationType.ARCHITECTURE, "design/t.md", "c1", CLEAN);

		// No plan: the report owns T-1, which verifies nothing.
		ElementCheckReport alone = agentDocumentService.previewElementChecks(reload(t), RearmSpecificationType.BOARD_TEST_REPORT, REPORT, null);
		assertEquals(Result.FAIL, result(alone, ElementCheckCatalogueService.TESTS_NO_ORPHANS).result(), outcomes(alone).toString());

		// A plan on the task owns T-1: the report repeats it without a duplicate, and is not judged for it.
		publish(r, reload(t), RearmSpecificationType.TEST_PLAN, "plan/t.md", "c2", PLAN);
		ElementCheckReport withPlan = agentDocumentService.previewElementChecks(reload(t), RearmSpecificationType.BOARD_TEST_REPORT, REPORT, null);
		assertEquals(Result.PASS, result(withPlan, ElementCheckCatalogueService.IDS_UNIQUE).result(), outcomes(withPlan).toString());
		assertEquals(Result.PASS, result(withPlan, ElementCheckCatalogueService.TESTS_NO_ORPHANS).result(),
				outcomes(withPlan).toString());

		// An index type takes elements only under grammar 1.2.
		RelizaException older = assertThrows(RelizaException.class, () -> agentDocumentService.previewElementChecks(reload(t),
				RearmSpecificationType.BOARD_TEST_REPORT, REPORT.replace("\"1.2\"", "\"1.1\""), null));
		assertTrue(older.getMessage().contains("carries a review item index, not elements, below grammar 1.2"), older.getMessage());
	}
}
