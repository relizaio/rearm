/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.testsupport.StampAssertions.assertContentSave;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ModelAssertionState;
import io.reliza.model.Organization;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The session's usage rollup stamps in place (TPF-71 / TPF-105, architecture round 3, 8.1 A3, 8.2):
 * a usage report rebuilds usageTotals, modelMismatch and, one way only, modelAssertion with no revision,
 * no audit row and no last_updated_date; a content save still audits and carries the rollup into the
 * trail.
 *
 * <p>The report runs through its public entry, which also calls {@code touch} (AgentSessionUsageService
 * 166): the RD3-1 keep-alive stamp, itself audit-free, moves {@code lastActivityAt} in the same report,
 * so that key is in the stamped set of every report here.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentSessionBookkeepingStampTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	/** The rollup's three keys, plus the keep-alive stamp the report's touch writes beside them. */
	static final Set<String> ROLLUP_KEYS = Set.of("usageTotals", "modelMismatch", "modelAssertion", "lastActivityAt");

	private AgentSessionData session(Organization org, UUID model) throws RelizaException {
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"tpf105-agent-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"tpf105-" + UUID.randomUUID(), "tpf105 session", null, model, WU);
	}

	private UsageReport line(UUID sessionUuid, String model, SessionUsageSource source, int requests) {
		return new UsageReport(sessionUuid, null, System.nanoTime(), source,
				ZonedDateTime.now().minusMinutes(5), ZonedDateTime.now(), 10, 3, 60, null, null, Map.of(),
				List.of(new UsageLine(model, null, 0L, requests, 1_000, 100, 0, 0L, null, 1_000L, 1_000L, null)));
	}

	private void report(AgentSessionData s, UsageReport r) throws RelizaException {
		usageService.report(agentSessionService.getSessionData(s.getUuid()).orElseThrow(), r, WU);
	}

	private RowSnapshot row(UUID uuid) {
		return snapshot(jdbcTemplate, TableName.AGENT_SESSIONS, uuid);
	}

	private AgentSessionData read(AgentSessionData s) {
		return agentSessionService.getSessionData(s.getUuid()).orElseThrow();
	}

	@Test
	public void rebuildSessionTotalsStampsTheRollupWithoutARevisionOrAnAuditRow() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization(), null);
		assertEquals(ModelAssertionState.DECLARED, read(s).getModelAssertion());
		RowSnapshot before = row(s.getUuid());

		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 2));
		RowSnapshot once = row(s.getUuid());
		assertStampedOnly(before, once, ROLLUP_KEYS);
		AgentSessionData after = read(s);
		assertNotNull(after.getUsageTotals());
		assertEquals(2, after.getUsageTotals().requests());
		assertEquals(1, after.getUsageTotals().reports());
		assertFalse(after.isModelMismatch());
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, after.getModelAssertion(),
				"transcript rows with no mismatch observe the model");

		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 3));
		assertStampedOnly(before, row(s.getUuid()), ROLLUP_KEYS);
		assertEquals(5, read(s).getUsageTotals().requests(), "the rollup is rebuilt from both rows");
		assertEquals(2, read(s).getUsageTotals().reports());
	}

	@Test
	public void theAssertionMovesOneWayOnly() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String declared = "tpf105-declared-" + UUID.randomUUID();
		UUID model = modelOntologyService.resolve(org.getUuid(), declared, null, WU).model().getUuid();

		// A mismatching row first: the flag flips and the assertion stays DECLARED.
		AgentSessionData m = session(org, model);
		RowSnapshot before = row(m.getUuid());
		report(m, line(m.getUuid(), "tpf105-other-" + UUID.randomUUID(), SessionUsageSource.TRANSCRIPT, 1));
		assertStampedOnly(before, row(m.getUuid()), ROLLUP_KEYS);
		assertTrue(read(m).isModelMismatch());
		assertEquals(ModelAssertionState.DECLARED, read(m).getModelAssertion(), "a mismatch observes nothing");

		// A matching row observes the model; a mismatching one after it does not take it back.
		AgentSessionData o = session(org, model);
		report(o, line(o.getUuid(), declared, SessionUsageSource.TRANSCRIPT, 1));
		assertFalse(read(o).isModelMismatch());
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, read(o).getModelAssertion());
		RowSnapshot observed = row(o.getUuid());
		report(o, line(o.getUuid(), "tpf105-other-" + UUID.randomUUID(), SessionUsageSource.TRANSCRIPT, 1));
		assertStampedOnly(observed, row(o.getUuid()), ROLLUP_KEYS);
		assertTrue(read(o).isModelMismatch());
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, read(o).getModelAssertion(), "DECLARED is never written back");
		assertEquals("RUNTIME_OBSERVED", jdbcTemplate.queryForObject(
				"select record_data->>'modelAssertion' from rearm.agent_sessions where uuid = ?", String.class, o.getUuid()));
	}

	@Test
	public void oneObservedRowAmongSelfReportedOnesObservesTheModel() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization(), null);
		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.SELF_REPORTED, 1));
		assertEquals(ModelAssertionState.DECLARED, read(s).getModelAssertion(), "an agent's own claim observes nothing");
		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.SELF_REPORTED, 1));
		assertEquals(ModelAssertionState.DECLARED, read(s).getModelAssertion());

		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 1));
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, read(s).getModelAssertion(),
				"one transcript row among the self-reported ones is enough");
		assertEquals(3, read(s).getUsageTotals().reports());
	}

	@Test
	public void theRebuildReturnsTheSessionAsItStampedIt() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization(), null);
		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 2));
		jdbcTemplate.update("update rearm.agent_sessions set record_data = jsonb_set(jsonb_set(record_data, '{usageTotals}', 'null'::jsonb), "
				+ "'{modelAssertion}', '\"DECLARED\"'::jsonb) where uuid = ?", s.getUuid());
		assertNull(read(s).getUsageTotals(), "the rollup is gone from the row");

		AgentSessionData rebuilt = usageService.rebuildSessionTotals(read(s), WU);
		assertNotNull(rebuilt.getUsageTotals(), "the returned session carries the rebuilt totals");
		assertEquals(2, rebuilt.getUsageTotals().requests());
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, rebuilt.getModelAssertion(), "and the observed assertion");
		assertEquals(2, read(s).getUsageTotals().requests(), "as the row does");
	}

	@Test
	public void aSessionWithoutAnObservedRowOfAnotherModelIsNeverFlaggedAsAMismatch() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String declared = "tpf105-declared-" + UUID.randomUUID();
		UUID model = modelOntologyService.resolve(org.getUuid(), declared, null, WU).model().getUuid();

		// a declared model and self-reported usage of another model: nothing observed, so nothing to contradict
		AgentSessionData withModel = session(org, model);
		report(withModel, line(withModel.getUuid(), "tpf105-other-" + UUID.randomUUID(), SessionUsageSource.SELF_REPORTED, 1));
		assertFalse(read(withModel).isModelMismatch(), "a self-report of another model is not an observed mismatch");
		assertEquals(ModelAssertionState.DECLARED, read(withModel).getModelAssertion());
		assertEquals("false", jdbcTemplate.queryForObject("select record_data->>'modelMismatch' from rearm.agent_sessions where uuid = ?",
				String.class, withModel.getUuid()));

		// and again after a rebuild, with a second self-reported row of the declared model
		report(withModel, line(withModel.getUuid(), declared, SessionUsageSource.SELF_REPORTED, 1));
		assertFalse(read(withModel).isModelMismatch());
		assertFalse(usageService.rebuildSessionTotals(read(withModel), WU).isModelMismatch());

		// no declared model at all
		AgentSessionData none = session(org, null);
		report(none, line(none.getUuid(), "claude-opus-5", SessionUsageSource.SELF_REPORTED, 1));
		assertFalse(read(none).isModelMismatch(), "no declared model, nothing to mismatch");
		assertEquals(ModelAssertionState.DECLARED, read(none).getModelAssertion());
		report(none, line(none.getUuid(), "claude-opus-5", SessionUsageSource.SELF_REPORTED, 1));
		assertFalse(read(none).isModelMismatch());

		// an observed row of the declared model, among self-reported rows of another: observed, and not flagged
		AgentSessionData mixed = session(org, model);
		report(mixed, line(mixed.getUuid(), "tpf105-other-" + UUID.randomUUID(), SessionUsageSource.SELF_REPORTED, 1));
		report(mixed, line(mixed.getUuid(), declared, SessionUsageSource.TRANSCRIPT, 1));
		assertFalse(read(mixed).isModelMismatch());
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, read(mixed).getModelAssertion());
	}

	@Test
	public void everyObservedSourceObservesTheModelAndARebuildStartsFromTheRows() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		for (SessionUsageSource source : List.of(SessionUsageSource.TRANSCRIPT, SessionUsageSource.OTEL, SessionUsageSource.PROVIDER)) {
			AgentSessionData s = session(org, null);
			report(s, line(s.getUuid(), "claude-opus-5", source, 1));
			assertEquals(ModelAssertionState.RUNTIME_OBSERVED, read(s).getModelAssertion(), source + " alone observes the model");
		}
		AgentSessionData own = session(org, null);
		report(own, line(own.getUuid(), "claude-opus-5", SessionUsageSource.SELF_REPORTED, 1));
		assertEquals(ModelAssertionState.DECLARED, read(own).getModelAssertion(), "a self-report alone does not");

		// the assertion is one-way, so only a rebuild from the rows shows which rows count
		for (List<SessionUsageSource> sequence : List.of(
				List.of(SessionUsageSource.TRANSCRIPT, SessionUsageSource.SELF_REPORTED),
				List.of(SessionUsageSource.SELF_REPORTED, SessionUsageSource.TRANSCRIPT, SessionUsageSource.SELF_REPORTED),
				List.of(SessionUsageSource.SELF_REPORTED, SessionUsageSource.OTEL),
				List.of(SessionUsageSource.PROVIDER, SessionUsageSource.SELF_REPORTED, SessionUsageSource.SELF_REPORTED))) {
			AgentSessionData s = session(org, null);
			for (SessionUsageSource source : sequence) report(s, line(s.getUuid(), "claude-opus-5", source, 1));
			jdbcTemplate.update("update rearm.agent_sessions set record_data = jsonb_set(record_data, '{modelAssertion}', cast('\"DECLARED\"' as jsonb)) where uuid = ?", s.getUuid());
			assertEquals(ModelAssertionState.DECLARED, read(s).getModelAssertion());

			AgentSessionData rebuilt = usageService.rebuildSessionTotals(read(s), WU);
			assertEquals(ModelAssertionState.RUNTIME_OBSERVED, rebuilt.getModelAssertion(), "rebuilt from " + sequence);
			assertEquals(ModelAssertionState.RUNTIME_OBSERVED, read(s).getModelAssertion(), "and stored, from " + sequence);
		}
	}

	@Test
	public void aRebuildWaitsForTheRowLockAndSeesTheRowsCommittedMeanwhile() throws Exception {
		AgentSessionData s = session(testInitializer.obtainOrganization(), null);
		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 1));
		assertEquals(1, read(s).getUsageTotals().reports());
		AgentSessionData seen = read(s);

		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			java.util.concurrent.Future<AgentSessionData> rebuild = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
				jdbcTemplate.queryForList("select uuid from rearm.agent_sessions where uuid = ? for update", s.getUuid());
				java.util.concurrent.Future<AgentSessionData> f = executor.submit(() -> usageService.rebuildSessionTotals(seen, WU));
				try {
					Thread.sleep(2_500);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertTrue(!f.isDone(), "the rebuild waits for the row lock the other transaction holds");
				try {
					report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 1));   // a second row, committed with the lock
				} catch (RelizaException e) {
					throw new IllegalStateException(e);
				}
				return f;
			});
			assertEquals(2, rebuild.get(60, java.util.concurrent.TimeUnit.SECONDS).getUsageTotals().reports(), "the rebuild scans both rows");
		} finally {
			executor.shutdownNow();
		}
		assertEquals(2, read(s).getUsageTotals().reports(), "and the stored rollup is the one over both rows");
		assertEquals(2, read(s).getUsageTotals().requests());
	}

	// ---------- 8.2: content saves still audit, and the rollup rides into the trail with the next one ----------

	@Test
	@SuppressWarnings("unchecked")
	public void aContentSaveBetweenTwoStampsIsTheOneRevisionAndCarriesTheFirstStamp() throws RelizaException {
		AgentSessionData s = session(testInitializer.obtainOrganization(), null);
		RowSnapshot start = row(s.getUuid());

		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 2));     // stamp 1: 2 requests
		RowSnapshot stamped = row(s.getUuid());
		assertStampedOnly(start, stamped, ROLLUP_KEYS);

		String title = "renamed-" + UUID.randomUUID();
		agentSessionService.updateMeta(s.getUuid(), title, null, WU);                         // the content save (864)
		RowSnapshot saved = row(s.getUuid());
		assertContentSave(stamped, saved);
		Map<String, Object> copy = Utils.OM.readValue(jdbcTemplate.queryForObject(
				"select cast(revision_record_data as text) from rearm.audit where entity_name = ? and entity_uuid = ? and revision = ?",
				String.class, TableName.AGENT_SESSIONS.toString(), s.getUuid(), stamped.revision()), Map.class);
		assertEquals(stamped.recordData(), copy, "the audit row is the session as it was before the content save");
		assertEquals(2, ((Number) ((Map<String, Object>) copy.get("usageTotals")).get("requests")).intValue(),
				"the first stamp rode into the trail");

		report(s, line(s.getUuid(), "claude-opus-5", SessionUsageSource.TRANSCRIPT, 3));     // stamp 2: 5 requests
		RowSnapshot end = row(s.getUuid());
		assertStampedOnly(saved, end, ROLLUP_KEYS);
		assertEquals(start.revision() + 1, end.revision(), "one revision over stamp, content save, stamp");
		assertEquals(start.auditRows() + 1, end.auditRows(), "one audit row over the three");
		assertEquals(5, read(s).getUsageTotals().requests(), "the live row carries the second stamp");
		assertEquals(title, read(s).getTitle(), "and the content save's title");
	}
}
