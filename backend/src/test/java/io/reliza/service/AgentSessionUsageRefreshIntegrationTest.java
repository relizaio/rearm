/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageAck;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Usage reported after a hop closed reaches that hop (gaps §1.22, task 76563925, architecture-1
 * §4 tests 1-8).
 *
 * <p>One role, "coder", with a 1000-micro allowance; usage priced at one micro per input token on
 * a model of the test's own. The refresher is a spy so one test can make it fail; every other
 * test runs it for real.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionUsageRefreshIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private io.reliza.repositories.AgentSessionUsageRepository usageRepository;
	@MockitoSpyBean private HopUsageRefresher refresher;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/late-usage";
	private static final String DOCS_SOURCE = "github:acme/late-usage-docs";
	private static final String DOCS = "https://github.com/acme/late-usage-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(9100);
	private static final long ALLOWANCE = 1_000L;

	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session, ModelOntologyData model,
			String modelName) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "late-" + UUID.randomUUID(),
				"late usage", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder",
				"build it", 10, null, false, true, null, null, null, null, List.of(), List.of(),
				null, ALLOWANCE), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData coord = register(org, "coord");
		agentBoardService.claimCoordinatorSeat(board.getUuid(), open(org, coord).getUuid(), coord.getUuid(), WU);
		String modelName = "late-model-" + UUID.randomUUID();
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), modelName, null, WU).model();
		m = modelOntologyService.addModelPricing(m.getUuid(), price(ZonedDateTime.now().minusDays(1), 1_000_000L), WU);
		AgentData worker = register(org, "w");
		return new Rig(board, worker, open(org, worker), m, modelName);
	}

	private static PricingEntry price(ZonedDateTime from, long microsPerMillionInput) {
		return new PricingEntry(null, from, null, "USD", PricingUnit.PER_MILLION_TOKENS,
				microsPerMillionInput, 0L, 0L, 0L, null, null, null, null, null, null);
	}

	private AgentData register(Organization org, String prefix) throws RelizaException {
		return agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				prefix + "-" + UUID.randomUUID(), null, null, null, WU);
	}

	private AgentSessionData open(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "test session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	/** A task the rig's session holds as coder. */
	private AgentTaskData assigned(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		t = agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		return reassigned(r, t);
	}

	private AgentTaskData reassigned(Rig r, AgentTaskData t) throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return reload(t);
	}

	private AgentTaskData signedOff(Rig r, AgentTaskData t) throws RelizaException {
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
	}

	/** One line of {@code tokens} input tokens, which prices at that many micros. */
	private UsageReport line(Rig r, AgentTaskData t, long seq, long tokens) {
		return new UsageReport(r.session().getUuid(), null, seq, SessionUsageSource.TRANSCRIPT,
				ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, t.getUuid(), Map.of(),
				List.of(new UsageLine(r.modelName(), null, 0L, 1, tokens, 0, 0, 0, null, tokens, tokens, null)));
	}

	private UsageAck send(Rig r, UsageReport report) throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(r.session().getUuid()).orElseThrow();
		return usageService.report(fresh, report, WU);
	}

	private UsageAck report(Rig r, AgentTaskData t, long tokens) throws RelizaException {
		return send(r, line(r, t, System.nanoTime(), tokens));
	}

	private HopUsage signOffUsage(AgentTaskData t, int i) {
		return reload(t).getSignOffs().get(i).usage();
	}

	private List<AgentBoardData.BoardEvent> alerts(Rig r, String containing) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream()
				.filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.ALERT)
				.filter(ev -> ev.message().contains(containing))
				.toList();
	}

	@Test
	public void aReportAfterSignOffFillsTheHop() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		assertEquals(0, signOffUsage(t, 0).reports(), "nothing was reported before the sign-off");
		assertNull(signOffUsage(t, 0).refreshedAt());

		report(r, t, 300);
		report(r, t, 200);

		HopUsage hop = signOffUsage(t, 0);
		assertEquals(2, hop.reports());
		assertEquals(500L, hop.derivedCostMicros());
		assertEquals(500L, hop.inputTokens());
		assertNotNull(hop.refreshedAt(), "says it was filled in after the hop ended");
		assertEquals(ALLOWANCE, hop.allowanceMicros(), "keeps the allowance stamped when the hop ended");
		assertEquals(2, usageService.taskUsage(reload(t)).reports());
		assertEquals(500L, usageService.taskUsage(reload(t)).derivedCostMicros());
	}

	@Test
	public void aDuplicateReportChangesNothing() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		UsageReport once = line(r, t, System.nanoTime(), 300);
		send(r, once);
		HopUsage first = signOffUsage(t, 0);
		assertEquals(1, first.reports());

		UsageAck again = send(r, once);
		assertEquals(0, again.accepted());
		assertEquals(1, again.duplicates());
		HopUsage after = signOffUsage(t, 0);
		assertEquals(first, after, "no refresh: refreshedAt and the figures are unchanged");
	}

	@Test
	public void rowsDuringTheNextHopBelongToIt() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r);
		agentTaskService.returnTask(t.getUuid(), r.session().getUuid(), TaskReturnReason.TASK_UNCLEAR,
				"which repo?", WU);
		report(r, t, 100);
		assertEquals(1, reload(t).getReturns().get(0).usage().reports(), "late for the returned hop");

		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		t = reassigned(r, t);
		report(r, t, 700);
		HopUsage returned = reload(t).getReturns().get(0).usage();
		assertEquals(1, returned.reports(), "the same session holds the task again: the row is the live hop's");
		assertEquals(100L, returned.derivedCostMicros());

		t = signedOff(r, t);
		HopUsage second = signOffUsage(t, 0);
		assertEquals(1, second.reports());
		assertEquals(700L, second.derivedCostMicros());
		assertNull(second.refreshedAt(), "taken at the hop's end, not filled in");

		report(r, t, 50);
		assertEquals(1, reload(t).getReturns().get(0).usage().reports(),
				"a row after the second hop is the second hop's, not the first's");
		assertEquals(2, signOffUsage(t, 0).reports());
	}

	@Test
	public void aReturnIsRefreshedLikeASignOff() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r);
		agentTaskService.returnTask(t.getUuid(), r.session().getUuid(), TaskReturnReason.TASK_UNCLEAR,
				"unclear", WU);
		report(r, t, 400);
		HopUsage hop = reload(t).getReturns().get(0).usage();
		assertEquals(1, hop.reports());
		assertEquals(400L, hop.derivedCostMicros());
		assertNotNull(hop.refreshedAt());
		assertEquals("unclear", reload(t).getReturns().get(0).description(), "nothing else about the hop changes");
	}

	@Test
	public void oldRowsKeepTheirCost() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		report(r, t, 100);
		assertEquals(100L, signOffUsage(t, 0).derivedCostMicros());

		// The rate doubles from now on: the first entry closes and a second one opens.
		ZonedDateTime change = ZonedDateTime.now();
		UUID firstEntry = r.model().getPricing().get(0).uuid();
		modelOntologyService.expireModelPricing(r.model().getUuid(), firstEntry, change, WU);
		modelOntologyService.addModelPricing(r.model().getUuid(), price(change, 2_000_000L), WU);

		report(r, t, 100);
		HopUsage hop = signOffUsage(t, 0);
		assertEquals(2, hop.reports());
		assertEquals(300L, hop.derivedCostMicros(), "the first row keeps its 100; the second costs 200");
		assertEquals(2, hop.priceVersions().size(), "names both entries it used");
	}

	@Test
	public void theSoftAlertFiresOnceOnRefresh() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), 10_000L, 50, null, null, null, null, WU);
		AgentTaskData t = signedOff(r, assigned(r));
		assertTrue(alerts(r, "line crossed").isEmpty());

		report(r, t, 6_000);
		assertEquals(1, alerts(r, "line crossed").size(), "the refresh that crosses the line posts it");
		assertTrue(board(r).isSoftAlertPosted());

		report(r, t, 100);
		assertEquals(2, signOffUsage(t, 0).reports(), "a further refresh ran");
		assertEquals(1, alerts(r, "line crossed").size(), "and posted no second alert");
	}

	@Test
	public void theOverAllowanceAlertFollowsTheRefresh() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		String over = "hop of coder on task";
		assertTrue(alerts(r, over).isEmpty(), "nothing was reported, so nothing was over at the sign-off");

		report(r, t, 800);
		assertTrue(alerts(r, over).isEmpty(), "800 is within the 1000 allowance");
		report(r, t, 400);
		assertEquals(1, alerts(r, over).size(), "the refresh that takes the hop over posts it");
		assertTrue(alerts(r, over).get(0).message().contains("spent 1200"), alerts(r, over).get(0).message());
		report(r, t, 100);
		assertEquals(1, alerts(r, over).size(), "once per hop");
	}

	@Test
	public void aSessionThatReportsThenClosesLeavesItsHopCosted() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r);
		report(r, t, 250);
		agentSessionService.close(r.session().getUuid(), WU);
		AgentTaskData.TaskReturn released = reload(t).getReturns().get(0);
		assertEquals(TaskReturnReason.SESSION_CLOSED, released.reason());
		assertNotNull(released.usage(), "the release takes the hop's snapshot like any return");
		assertEquals(250L, released.usage().derivedCostMicros());
		assertNull(released.usage().refreshedAt());
	}

	@Test
	public void theRefreshRunsAfterTheReportCommits() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = signedOff(r, assigned(r));
		doThrow(new IllegalStateException("forced")).when(refresher).refreshTask(any(), any(), any());
		// The console appender holds the original stdout, so the error is read off the logger itself.
		List<String> errors = new CopyOnWriteArrayList<>();
		AbstractAppender capture = new AbstractAppender("refresh-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent e) {
				if (e.getLevel() == Level.ERROR) errors.add(e.getMessage().getFormattedMessage());
			}
		};
		capture.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(HopUsageRefresher.class);
		logger.addAppender(capture);
		try {
			UsageAck ack = report(r, t, 300);
			assertEquals(1, ack.accepted(), "the report is acknowledged");
			assertEquals(1, usageRepository.findByTask(t.getUuid()).size(), "and its row committed");
			assertEquals(0, signOffUsage(t, 0).reports(), "the failed refresh left the hop as it was");
			assertTrue(errors.stream().anyMatch(m -> m.contains("Filling the closed hops of task " + t.getUuid())),
					"and said so at error level: " + errors);
		} finally {
			logger.removeAppender(capture);
			capture.stop();
		}
	}
}
