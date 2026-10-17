/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.ElementCheckPolicy;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.ElementCheckReport.Result;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A round the hop replaced by publishing the next one is not an output of the hop (task RD4-18): among
 * the offered outputs each series -- a task's rounds of one specification type -- resolves to the newest
 * round this hop published, so a failing round 1 the hop fixed in round 2 neither blocks the hand-over
 * nor is handed over, and a failing newest round still refuses.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentHopNewestRoundHandoverIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/newest-round";
	private static final String DOCS_SOURCE = "github:acme/newest-round-docs";
	private static final String DOCS = "https://github.com/acme/newest-round-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(18180);

	/** REQ-12's parent REQ-4 exists nowhere: trace.parent_exists fails. */
	private static final String DANGLING = "{\"grammarVersion\":\"1\",\"elements\":["
			+ "{\"id\":\"REQ-12\",\"title\":\"Report the cycle\",\"parent\":\"REQ-4\",\"contentDigest\":\"b1\",\"line\":3},"
			+ "{\"id\":\"T-2\",\"title\":\"Report test\",\"traces\":[{\"verb\":\"verifies\",\"target\":\"REQ-12\"}],"
			+ "\"contentDigest\":\"t2\",\"line\":9}],"
			+ "\"warnings\":[]}";

	/** The same document with the dangling parent removed: every check passes. */
	private static final String FIXED = DANGLING.replace("\"parent\":\"REQ-4\",", "");

	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "newest-round-" + UUID.randomUUID(),
				"newest round", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE, InputScope.TASK, null, null, null)),
				List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN, InputScope.TASK, false)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		agentBoardService.setElementCheckPolicy(board.getUuid(),
				new ElementCheckPolicy(List.of(ElementCheckCatalogueService.TRACE_PARENT_EXISTS), Map.of(), null), WU);
		AgentData w = worker(org.getUuid());
		return new Rig(board, w, session(org.getUuid(), w));
	}

	private AgentData worker(UUID org) throws RelizaException {
		return agentService.findOrRegisterRootAgent(org, UUID.randomUUID(), "w-" + UUID.randomUUID(), null, null, null, WU);
	}

	private AgentSessionData session(UUID org, AgentData w) throws RelizaException {
		return agentSessionService.initialize(org, w.getUuid(), null, "s-" + UUID.randomUUID(), "worker", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private ReleaseData reread(ReleaseData rd) {
		return sharedReleaseService.getReleaseData(rd.getUuid()).orElseThrow();
	}

	private static String sha(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	/** A round at its own path, as the board's {round} template gives each round. */
	private ReleaseData publish(AgentSessionData s, AgentTaskData t, RearmSpecificationType spec, String path,
			String commit, String elements) throws Exception {
		return agentDocumentService.publish(s, new PublishRequest(t.getUuid(), spec, null, path,
				"digest-of-" + path + commit, "text/markdown", null, null, null, commit, DOCS, "doc",
				ZonedDateTime.now(), null, elements, null == elements ? null : sha(elements)), WU);
	}

	private ReleaseData architecture(Rig r, AgentTaskData t, int round, String commit, String elements) throws Exception {
		return publish(r.session(), t, RearmSpecificationType.ARCHITECTURE, "design/x/architecture-" + round + ".md",
				commit, elements);
	}

	private AgentTaskData withArchitect(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return reload(t);
	}

	private static List<UUID> lastOutputs(AgentTaskData td) {
		return td.getSignOffs().get(td.getSignOffs().size() - 1).outputs();
	}

	private static ElementCheckReport.ElementCheckResult result(ElementCheckReport report, String check) {
		return report.results().stream().filter(c -> c.check().equals(check)).findFirst().orElseThrow();
	}

	@Test
	public void aFailingRoundTheHopFixedInTheNextRoundDoesNotBlockAndIsNotHandedOver() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData one = architecture(r, t, 1, "c1", DANGLING);
		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "designed", List.of(one.getUuid()), WU));
		assertTrue(refused.getMessage().startsWith("hand-over refused: blocking check(s) failed on ARCHITECTURE round 1"),
				refused.getMessage());

		ReleaseData two = architecture(r, reload(t), 2, "c2", FIXED);
		assertEquals(Integer.valueOf(2), two.getDocument().round(), "another path is the next round, not a new version");
		assertNull(reread(one).getDocument().supersededBy(), "round 1 is not marked replaced");
		ElementCheckReport twoReport = agentDocumentService.latestElementCheckReport(two.getUuid()).orElseThrow().getDocument().elementChecks();
		assertEquals(Result.PASS, result(twoReport, ElementCheckCatalogueService.TRACE_PARENT_EXISTS).result());

		// The CLI offers everything the hop published, round 1 included.
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED,
				"designed", List.of(one.getUuid(), two.getUuid()), WU);
		assertEquals("coder", after.getRole());
		assertEquals(List.of(two.getUuid()), lastOutputs(after), "only the newest round is the hop's output");
		assertEquals(ReleaseLifecycle.ASSEMBLED, reread(two).getLifecycle());
		assertEquals(ReleaseLifecycle.DRAFT, reread(one).getLifecycle(), "the replaced round is not handed over");

		// Round 1 stays readable, with its failing report, in the task's documents and checks.
		AgentTaskData now = reload(t);
		assertTrue(agentDocumentService.documentsOfTask(now).stream().anyMatch(rd -> rd.getUuid().equals(one.getUuid())));
		ElementCheckReport oneReport = agentDocumentService.latestElementCheckReport(one.getUuid()).orElseThrow().getDocument().elementChecks();
		ElementCheckReport.ElementCheckResult failed = result(oneReport, ElementCheckCatalogueService.TRACE_PARENT_EXISTS);
		assertEquals(Result.FAIL, failed.result());
		assertTrue(failed.blocking());
		assertTrue(agentDocumentService.elementChecksOfTask(now).stream()
				.anyMatch(c -> one.getUuid().equals(c.scope().checked())), "round 1's report is in the task's checks");
	}

	@Test
	public void aFailingNewestRoundStillRefusesNamingItWithAPassingEarlierRoundOffered() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData one = architecture(r, t, 1, "c1", FIXED);
		ReleaseData two = architecture(r, reload(t), 2, "c2", DANGLING.replace("REQ-12", "REQ-13"));
		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "designed", List.of(one.getUuid(), two.getUuid()), WU));
		assertTrue(refused.getMessage().startsWith("hand-over refused: blocking check(s) failed on ARCHITECTURE round 2"
				+ " (check report round 2): trace.parent_exists (REQ-13 → REQ-4 not found)"), refused.getMessage());
		assertFalse(refused.getMessage().contains("round 1"), refused.getMessage());
		// Offering only the passing round 1 does not get round 2 past the gate either.
		assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
				SignOffOutcome.PASSED, "designed", List.of(one.getUuid()), WU));
		AgentTaskData held = reload(t);
		assertEquals(TaskStatus.ASSIGNED, held.getStatus());
		assertEquals(ReleaseLifecycle.DRAFT, reread(one).getLifecycle());
		assertEquals(ReleaseLifecycle.DRAFT, reread(two).getLifecycle());
	}

	@Test
	public void offeringOnlyTheReplacedRoundHandsOverTheNewestRound() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData one = architecture(r, t, 1, "c1", DANGLING);
		ReleaseData two = architecture(r, reload(t), 2, "c2", FIXED);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED,
				"designed", List.of(one.getUuid()), WU);
		assertEquals(List.of(two.getUuid()), lastOutputs(after));
		assertEquals(ReleaseLifecycle.ASSEMBLED, reread(two).getLifecycle());
		assertEquals(ReleaseLifecycle.DRAFT, reread(one).getLifecycle());
	}

	/** Round 1 fails, round 2 fails, round 2 is corrected at the same path as a new version that passes. */
	private record Corrected(ReleaseData one, ReleaseData twoV1, ReleaseData twoV2) {}

	private Corrected failFailThenCorrectRoundTwo(Rig r, AgentTaskData t) throws Exception {
		ReleaseData one = architecture(r, t, 1, "c1", DANGLING);
		RelizaException first = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "designed", List.of(one.getUuid()), WU));
		assertTrue(first.getMessage().startsWith("hand-over refused: blocking check(s) failed on ARCHITECTURE round 1"),
				first.getMessage());

		ReleaseData twoV1 = architecture(r, reload(t), 2, "c2", DANGLING.replace("REQ-12", "REQ-15"));
		RelizaException second = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "designed", List.of(one.getUuid(), twoV1.getUuid()), WU));
		assertTrue(second.getMessage().startsWith("hand-over refused: blocking check(s) failed on ARCHITECTURE round 2"
				+ " (check report round 2): trace.parent_exists (REQ-15 → REQ-4 not found)"), second.getMessage());
		assertFalse(second.getMessage().contains("round 1"), second.getMessage());

		ReleaseData twoV2 = architecture(r, reload(t), 2, "c3", FIXED);
		assertEquals(Integer.valueOf(2), twoV2.getDocument().round(), "the same path is the same round");
		assertTrue(reread(twoV1).getDocument().superseded(), "round 2's first version is replaced by its correction");
		assertEquals(twoV2.getUuid(), reread(twoV1).getDocument().supersededBy());
		return new Corrected(one, twoV1, twoV2);
	}

	private void assertOnlyTheCorrectionIsHandedOver(Corrected c, AgentTaskData after) {
		assertEquals("coder", after.getRole());
		assertEquals(List.of(c.twoV2().getUuid()), lastOutputs(after),
				"the newest version of the newest round is the hop's output, not round 2's replaced version");
		assertEquals(ReleaseLifecycle.ASSEMBLED, reread(c.twoV2()).getLifecycle());
		assertEquals(ReleaseLifecycle.DRAFT, reread(c.twoV1()).getLifecycle());
		assertEquals(ReleaseLifecycle.DRAFT, reread(c.one()).getLifecycle());
	}

	@Test
	public void aNewestRoundTheHopCorrectedAsANewVersionIsHandedOverAsItsCorrection() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		Corrected c = failFailThenCorrectRoundTwo(r, t);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED,
				"designed", List.of(c.one().getUuid(), c.twoV1().getUuid(), c.twoV2().getUuid()), WU);
		assertOnlyTheCorrectionIsHandedOver(c, after);
	}

	@Test
	public void offeringOnlyRoundOneHandsOverTheCorrectedVersionOfRoundTwo() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		Corrected c = failFailThenCorrectRoundTwo(r, t);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED,
				"designed", List.of(c.one().getUuid()), WU);
		assertOnlyTheCorrectionIsHandedOver(c, after);
	}

	@Test
	public void twoSeriesInOneHopResolveIndependently() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData arch = architecture(r, t, 1, "c1", FIXED);
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "designed",
				List.of(arch.getUuid()), WU);
		assertEquals("coder", reload(t).getRole());

		// The coder hop: two rounds of its note and one question round.
		UUID org = r.board().getOrg();
		AgentData coder = worker(org);
		AgentSessionData cs = session(org, coder);
		agentTaskService.assign(t.getUuid(), board(r), coder.getUuid(), cs.getUuid(), WU);
		ReleaseData note1 = publish(cs, reload(t), RearmSpecificationType.DETAILED_DESIGN, "impl/x/notes-1.md", "c2", null);
		ReleaseData asked = agentDocumentService.publish(cs, new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null, null, null, questions("q1"), null, null,
				null, null, null), WU);
		ReleaseData note2 = publish(cs, reload(t), RearmSpecificationType.DETAILED_DESIGN, "impl/x/notes-2.md", "c3", null);
		assertEquals(Integer.valueOf(2), note2.getDocument().round());
		assertEquals(Integer.valueOf(1), asked.getDocument().round());

		AgentTaskData after = agentTaskService.signOff(t.getUuid(), cs.getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(note1.getUuid(), asked.getUuid(), note2.getUuid()), WU);
		assertEquals(List.of(note2.getUuid(), asked.getUuid()), lastOutputs(after),
				"each series hands over its newest round; a lower round number of another type is not replaced");
		assertEquals(ReleaseLifecycle.DRAFT, reread(note1).getLifecycle());
		assertEquals(ReleaseLifecycle.ASSEMBLED, reread(note2).getLifecycle());
		assertEquals(ReleaseLifecycle.ASSEMBLED, reread(asked).getLifecycle());
	}

	@Test
	public void aReturnResolvesTheSameWay() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData one = architecture(r, t, 1, "c1", DANGLING);
		ReleaseData two = architecture(r, reload(t), 2, "c2", FIXED);
		AgentTaskData after = agentTaskService.returnTask(t.getUuid(), r.session().getUuid(),
				TaskReturnReason.TASK_UNCLEAR, "unclear", List.of(one.getUuid(), two.getUuid()), WU);
		assertEquals(List.of(two.getUuid()), after.getReturns().get(after.getReturns().size() - 1).outputs());
		assertEquals(ReleaseLifecycle.DRAFT, reread(one).getLifecycle());
		assertEquals(ReleaseLifecycle.DRAFT, reread(two).getLifecycle(), "a return promotes nothing");
	}

	@Test
	public void aRoundOfAnEarlierHopIsNotReplacedByThisHopsRound() throws Exception {
		Rig r = rig();
		AgentTaskData t = withArchitect(r);
		ReleaseData one = architecture(r, t, 1, "c1", FIXED);
		agentTaskService.returnTask(t.getUuid(), r.session().getUuid(), TaskReturnReason.TASK_UNCLEAR, "more",
				List.of(one.getUuid()), WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		architecture(r, reload(t), 2, "c2", FIXED.replace("REQ-12", "REQ-14"));
		// Round 1 is the earlier hop's: offering it is refused as before, not taken as this hop's round 2.
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "designed", List.of(one.getUuid()), WU));
		assertTrue(e.getMessage().contains("was published before this hop began"), e.getMessage());
	}

	private static Map<String, Object> questions(String id) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", id);
		item.put("priority", 1);
		item.put("status", "OPEN");
		item.put("title", "question " + id);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(item)));
		Map<String, Object> about = new LinkedHashMap<>();
		about.put("specification", RearmSpecificationType.ARCHITECTURE.name());
		about.put("release", null);
		idx.put("about", about);
		return idx;
	}
}
