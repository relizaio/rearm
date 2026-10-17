/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentSessionUsage;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.Organization;
import io.reliza.model.SessionUsageAttribution;
import io.reliza.model.SessionUsageHosting;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.AgentSessionUsageRepository;
import io.reliza.service.AgentSessionUsageService.UsageAck;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The report path: what a client may file, what it may claim about whose work it was, and what
 * the rollup then says.
 *
 * <p>The numbers here are taken from a real Claude Code session rather than invented, because the
 * two guards most likely to be wrong -- the oversize ceiling and the context threshold -- are
 * wrong in opposite directions for plausible-looking made-up figures.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionUsageIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private AgentSessionUsageRepository usageRepository;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private AgentSessionData session(Organization org) throws RelizaException {
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"usage-agent-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"usage-" + UUID.randomUUID(), "usage test session", null, null, WU);
	}

	private UsageLine line(String model, long band, int requests, long in, long out, long cacheRead) {
		return new UsageLine(model, null, band, requests, in, out, cacheRead, 0L, null,
				in + cacheRead, in + cacheRead, null);
	}

	private UsageReport report(UUID sessionUuid, long seq, UsageLine... lines) {
		return new UsageReport(sessionUuid, null, seq, SessionUsageSource.TRANSCRIPT,
				ZonedDateTime.now().minusMinutes(5), ZonedDateTime.now(), 10, 3, 60, null, null,
				Map.of(), List.of(lines));
	}

	@Test
	public void aDeltaSpanningModelsAndBandsLandsAsOneRowEach() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		// One Stop hook, one sequence number, four facts: two models, each with requests either
		// side of the long-context threshold. Under a key of (session, seq) alone, three of these
		// four would have been silently dropped as duplicates.
		UsageAck ack = usageService.report(s, report(s.getUuid(), 68_505_228L,
				line("claude-opus-5", 200000, 31, 10_000, 5_000, 900_000),
				line("claude-opus-5", 0, 4, 2_000, 800, 40_000),
				line("claude-fable-5-1", 200000, 3, 9_000, 1_000, 800_000),
				line("claude-fable-5-1", 0, 5, 1_500, 600, 30_000)), WU);
		assertEquals(4, ack.accepted());
		assertEquals(0, ack.duplicates());
		assertEquals(4, usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid()).size());
	}

	@Test
	public void aRetriedDeltaInsertsNothingAndSaysSo() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		UsageReport r = report(s.getUuid(), 4_200L, line("claude-opus-5", 0, 5, 1_000, 500, 20_000));
		assertEquals(1, usageService.report(s, r, WU).accepted());

		UsageAck retry = usageService.report(s, r, WU);
		assertEquals(0, retry.accepted(), "a retry must not double-count the same window");
		assertEquals(1, retry.duplicates());
		assertEquals(1, usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid()).size());
	}

	@Test
	public void aSequenceBelowTheHighWaterMarkIsRefused() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		usageService.report(s, report(s.getUuid(), 5_000L,
				line("claude-opus-5", 0, 2, 500, 200, 1_000)), WU);
		// A replayed backfill would re-file windows already counted, so it is refused outright
		// rather than deduped: the rows would be new keys, and the totals would double.
		assertThrows(RelizaException.class, () -> usageService.report(s,
				report(s.getUuid(), 4_999L, line("claude-opus-5", 0, 2, 500, 200, 1_000)), WU));
	}

	@Test
	public void aSequenceAboveTwoToThe31IsFine() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		// The CLI sends a transcript byte offset; this session's own transcript passed 68 MB, and
		// a 32-bit sequence would wrap on a long-lived one.
		long beyondInt = 3_000_000_000L;
		assertEquals(1, usageService.report(s, report(s.getUuid(), beyondInt,
				line("claude-opus-5", 0, 1, 100, 50, 900)), WU).accepted());
	}

	@Test
	public void theOversizeGuardScalesWithRequests() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		// A first-run backfill over a whole session: 4.92 billion cache-read tokens across ~5,000
		// requests is exactly what this session's transcript holds, and it must be filed.
		UsageAck ok = usageService.report(s, report(s.getUuid(), 1_000L,
				line("claude-opus-5", 200000, 5_000, 335_455, 12_182_981, 4_920_384_786L)), WU);
		assertEquals(1, ok.accepted(), "a legitimate whole-session backfill must not be refused");

		// The same counters attributed to one request is a parse gone wrong, not a turn.
		UsageAck refused = usageService.report(s, report(s.getUuid(), 2_000L,
				line("claude-opus-5", 200000, 1, 335_455, 12_182_981, 4_920_384_786L)), WU);
		assertEquals(0, refused.accepted());
		assertEquals(1, refused.refused().size());
		assertTrue(refused.refused().get(0).contains("plausible ceiling"));
	}

	@Test
	public void aLineCoveringNoRequestsIsRefused() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		UsageAck ack = usageService.report(s, report(s.getUuid(), 10L,
				line("claude-opus-5", 0, 0, 100, 50, 900)), WU);
		assertEquals(0, ack.accepted());
		assertEquals(1, ack.refused().size());
	}

	@Test
	public void aClosedSessionAcceptsItsFinalFlushAndThenStops() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentSessionData s = session(org);
		agentSessionService.close(s.getUuid(), WU);
		AgentSessionData closed = agentSessionService.getSessionData(s.getUuid()).orElseThrow();

		// SessionEnd fires after the agent already closed the session; refusing this would drop
		// the last turn of every well-behaved agent.
		assertEquals(1, usageService.report(closed, report(s.getUuid(), 1L,
				line("claude-opus-5", 0, 1, 100, 50, 900)), WU).accepted());
	}

	@Test
	public void sessionTotalsEqualTheRowsTheyRollUp() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		usageService.report(s, report(s.getUuid(), 1L,
				line("claude-opus-5", 0, 3, 1_000, 500, 20_000)), WU);
		AgentSessionData afterFirst = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		usageService.report(afterFirst, report(s.getUuid(), 2L,
				line("claude-opus-5", 0, 2, 400, 200, 8_000)), WU);

		AgentSessionData reloaded = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		List<AgentSessionUsage> rows = usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid());
		long inputFromRows = rows.stream().mapToLong(AgentSessionUsage::getInputTokens).sum();
		long cacheFromRows = rows.stream().mapToLong(AgentSessionUsage::getCacheReadTokens).sum();

		assertNotNull(reloaded.getUsageTotals());
		assertEquals(inputFromRows, reloaded.getUsageTotals().inputTokens());
		assertEquals(cacheFromRows, reloaded.getUsageTotals().cacheReadTokens());
		assertEquals(2, reloaded.getUsageTotals().reports());
		assertEquals(5, reloaded.getUsageTotals().requests());
	}

	@Test
	public void withNoPriceThereIsNoCostRatherThanAZero() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		usageService.report(s, report(s.getUuid(), 1L,
				line("claude-opus-5", 0, 1, 1_000, 500, 20_000)), WU);
		AgentSessionData reloaded = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		// Nobody has entered a rate for this org, so the honest answer is "we cannot say", and
		// the rollup marks itself incomplete rather than reporting free work.
		assertNull(reloaded.getUsageTotals().derivedCostMicros());
		assertFalse(reloaded.getUsageTotals().costComplete());
	}

	@Test
	public void usageWithNoAssignmentIsUnattributedRatherThanGuessed() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		UsageAck ack = usageService.report(s, report(s.getUuid(), 1L,
				line("claude-opus-5", 0, 1, 100, 50, 900)), WU);
		assertEquals(SessionUsageAttribution.UNATTRIBUTED, ack.attribution());
		assertNull(ack.task());
	}

	@Test
	public void aTaskTheSessionNeverHeldIsRefused() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		UsageReport claimingSomeoneElsesTask = new UsageReport(s.getUuid(), null, 1L,
				SessionUsageSource.TRANSCRIPT, null, null, 1, 0, 0, null, UUID.randomUUID(),
				Map.of(), List.of(line("claude-opus-5", 0, 1, 100, 50, 900)));
		// A client naming a task it never worked is a bug in the client, and a silent accept
		// would put one agent's spend on another's work.
		assertThrows(RelizaException.class, () -> usageService.report(s, claimingSomeoneElsesTask, WU));
	}

	@Test
	public void theHostingOnTheLineIsWhatTheRowCarries() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		UsageLine onBedrock = new UsageLine("claude-opus-5", SessionUsageHosting.BEDROCK, 0L, 1,
				100, 50, 900, 0L, null, 1_000L, 1_000L, null);
		usageService.report(s, report(s.getUuid(), 1L, onBedrock), WU);
		assertEquals(SessionUsageHosting.BEDROCK,
				usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid()).get(0).getHosting());
	}

	@Test
	public void aProviderQualifiedModelStringResolvesToTheModelAndRecordsItsHosting()
			throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		// The client did not state hosting, but the string it reported says where it ran.
		usageService.report(s, report(s.getUuid(), 1L,
				line("us.anthropic.claude-fable-5-1-v1:0", 0, 1, 100, 50, 900)), WU);
		AgentSessionUsage row = usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid()).get(0);
		assertEquals(SessionUsageHosting.BEDROCK, row.getHosting());
		assertEquals("us.anthropic.claude-fable-5-1-v1:0", row.getRecordData().get("modelDeclared"));

		// and the plain form lands on the same catalogue row
		AgentSessionData reloaded = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		usageService.report(reloaded, report(s.getUuid(), 2L,
				line("claude-fable-5-1", 0, 1, 100, 50, 900)), WU);
		List<AgentSessionUsage> rows = usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid());
		assertEquals(rows.get(0).getModel(), rows.get(1).getModel(),
				"the Bedrock form and the direct form are one model");
	}

	/**
	 * A usage row already attributed to a task, written straight to the repository.
	 *
	 * <p>Attribution itself is covered above and end to end by the board suite; what is under test
	 * here is the hop WINDOW, so the rows are planted rather than routed through report() to keep
	 * the reported_at values under the test's control.
	 */
	private void plantHopRow(AgentSessionData s, UUID taskUuid, ZonedDateTime reportedAt,
			long seq, long inputTokens) {
		AgentSessionUsage row = new AgentSessionUsage();
		row.setOrg(s.getOrg());
		row.setSession(s.getUuid());
		row.setAgent(s.getAgent());
		row.setTask(taskUuid);
		row.setModel(UUID.randomUUID());
		row.setSource(SessionUsageSource.TRANSCRIPT);
		row.setClientSeq(seq);
		row.setReportedAt(reportedAt);
		row.setRequests(1);
		row.setInputTokens(inputTokens);
		row.setTurns(1);
		row.setRecordData(Map.of());
		usageRepository.save(row);
	}

	@Test
	public void theOpenEndedHopWindowCountsEverythingSinceTheAssignment() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentSessionData s = session(org);
		UUID task = UUID.randomUUID();
		ZonedDateTime assignedAt = ZonedDateTime.now().minusMinutes(10);

		plantHopRow(s, task, assignedAt.plusMinutes(1), 1L, 100);
		plantHopRow(s, task, assignedAt.plusMinutes(2), 2L, 250);

		// The live hop has no end yet, so hopSnapshot binds a null upper bound. Postgres cannot
		// infer a bare parameter's type from `IS NULL`, so without an explicit cast in the query
		// this throws for every sign-off and every return -- which is how it reached main's board
		// suite rather than this one.
		HopUsage hop = usageService.hopSnapshot(task, s.getUuid(), assignedAt);
		assertEquals(350, hop.inputTokens());
		assertEquals(2, hop.reports());
	}

	@Test
	public void theHopExcludesWhatFellOutsideItsAssignmentWindow() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentSessionData s = session(org);
		UUID task = UUID.randomUUID();
		ZonedDateTime assignedAt = ZonedDateTime.now().minusMinutes(10);

		// Before the assignment: the same session working on something else earlier.
		plantHopRow(s, task, assignedAt.minusMinutes(5), 1L, 999);
		plantHopRow(s, task, assignedAt.plusMinutes(1), 2L, 100);

		HopUsage hop = usageService.hopSnapshot(task, s.getUuid(), assignedAt);
		assertEquals(100, hop.inputTokens(), "usage from before the assignment is not this hop's");
		assertEquals(1, hop.reports());
	}

	@Test
	public void aHopThatConsumedNothingIsEmptyRatherThanNull() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentSessionData s = session(org);
		HopUsage hop = usageService.hopSnapshot(UUID.randomUUID(), s.getUuid(), ZonedDateTime.now());
		assertEquals(0, hop.reports());
		assertNull(hop.derivedCostMicros(), "nothing priced means no cost, not a cost of zero");
	}

	@Test
	public void aHopWithRowsButNoPriceReportsNoCostRatherThanZero() throws RelizaException {
		// The blocker: cost started at zero and was returned unconditionally, so an unpriced hop
		// claimed to have cost nothing. taskUsage treats a non-null cost as evidence that
		// something priced, so one such hop made the whole task read as free -- the exact
		// confusion between "$0.00" and "no price" the rest of the stack is built to avoid.
		// The pre-existing test only covered a hop with NO ROWS, which took the empty() path and
		// never reached this arithmetic.
		Organization org = testInitializer.obtainOrganization();
		AgentSessionData s = session(org);
		UUID task = UUID.randomUUID();
		ZonedDateTime assignedAt = ZonedDateTime.now().minusMinutes(10);
		plantHopRow(s, task, assignedAt.plusMinutes(1), 1L, 5_000);

		HopUsage hop = usageService.hopSnapshot(task, s.getUuid(), assignedAt);
		assertEquals(1, hop.reports(), "the row must be counted");
		assertEquals(5_000, hop.inputTokens());
		assertNull(hop.derivedCostMicros(),
				"no pricing entry covers this row, so there is no cost - not a cost of zero");
	}

	@Test
	public void theRollupDoesNotRevertASessionEditMadeSinceItWasRead() throws RelizaException {
		// The rollup used to write the whole session record from the AgentSessionData its caller
		// had loaded BEFORE taking the row lock. The totals came out right, so the bug was
		// invisible; everything else on the session quietly went back to what it had been when the
		// caller read it. Here the stale copy is explicit: a title change lands between the read
		// and the rebuild, exactly as an artifact attach or a hold would in production.
		Organization org = testInitializer.obtainOrganization();
		AgentSessionData s = session(org);
		usageService.report(s, report(s.getUuid(), 1L,
				line("claude-opus-5", 0, 2, 1_000, 500, 10_000)), WU);

		// A copy read before the concurrent edit -- what a fetcher would be holding.
		AgentSessionData stale = agentSessionService.getSessionData(s.getUuid()).orElseThrow();

		String newTitle = "renamed-" + UUID.randomUUID();
		agentSessionService.updateMeta(s.getUuid(), newTitle, null, WU);

		usageService.rebuildSessionTotals(stale, WU);

		AgentSessionData after = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		assertEquals(newTitle, after.getTitle(),
				"the rollup must not revert a field changed after its caller read the session");
		assertNotNull(after.getUsageTotals(), "and it must still have written the totals");
		assertEquals(2, after.getUsageTotals().requests());
	}

	// ---------- refusals and the rollback rule (gaps §1.11) ----------

	@Test
	public void aUsageReportKeepsAcceptedLinesWhenOneIsRefused() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization());
		// The middle line covers no request, which validation refuses. It is refused inside the
		// report, per line, so it does not leave the method: the rollback rule on report does not
		// touch the lines that were filed.
		UsageAck ack = usageService.report(s, report(s.getUuid(), 7_001L,
				line("claude-opus-5", 0, 2, 1_000, 100, 0),
				line("claude-fable-5-1", 0, 0, 500, 50, 0),
				line("claude-sonnet-5", 0, 3, 2_000, 200, 0)), WU);
		assertEquals(2, ack.accepted());
		assertEquals(1, ack.refused().size());
		assertTrue(ack.refused().get(0).contains("at least one request"), ack.refused().toString());
		assertEquals(2, usageRepository.findBySessionOrderByReportedAtAsc(s.getUuid()).size());
		AgentSessionData reloaded = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		assertEquals(3_000L, reloaded.getUsageTotals().inputTokens(), "totals rebuilt from the two filed lines");
		assertEquals(5, reloaded.getUsageTotals().requests());
	}

	@Test
	public void rebuildSessionTotalsRefusesOnlyBeforeItWrites() {
		// Its one refusal -- an unknown session -- comes before the lock and any write, so the
		// rollback rule has nothing to undo there; it is declared so the guard holds.
		AgentSessionData ghost = new AgentSessionData();
		org.springframework.test.util.ReflectionTestUtils.setField(ghost, "uuid", UUID.randomUUID());
		assertThrows(RelizaException.class, () -> usageService.rebuildSessionTotals(ghost, WU));
	}
}
