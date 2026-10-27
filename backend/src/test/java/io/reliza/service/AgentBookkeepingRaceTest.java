/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.Organization;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The session's usage rollup against a concurrent content save (TPF-71 / TPF-105, architecture round 3,
 * 8.3), shaped as WebhookDeliveryRaceTest 77-142: each round a usage report and a title change start
 * together behind a barrier, each in its own transaction bounded by a 10 s lock timeout. Both writers
 * lock the session, so they serialise; after every round the title and the rollup are both present,
 * and over the run only the title changes are revisions.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBookkeepingRaceTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final int ROUNDS = 15;

	@FunctionalInterface
	private interface Write {
		void run() throws Exception;
	}

	/** A write in a transaction of its own whose lock waits fail after 10 s instead of hanging. */
	private void bounded(Write write) {
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
			try {
				write.run();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private UsageReport line(UUID sessionUuid) {
		return new UsageReport(sessionUuid, null, System.nanoTime(), SessionUsageSource.TRANSCRIPT,
				ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, null, Map.of(),
				List.of(new UsageLine("claude-opus-5", null, 0L, 1, 100, 10, 0, 0L, null, 100L, 100L, null)));
	}

	private int revision(UUID s) {
		return jdbcTemplate.queryForObject("select revision from rearm.agent_sessions where uuid = ?", Integer.class, s);
	}

	private int auditRows(UUID s) {
		return jdbcTemplate.queryForObject("select count(*) from rearm.audit where entity_name = 'agent_sessions' "
				+ "and entity_uuid = ?", Integer.class, s);
	}

	@Test
	public void aUsageReportAndATitleChangeRacingKeepBothAndOnlyTheTitleIsARevision() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"race-" + UUID.randomUUID(), null, null, null, WU);
		UUID session = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"race-" + UUID.randomUUID(), "race", null, null, WU).getUuid();
		int revision0 = revision(session);
		int audit0 = auditRows(session);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<String> rounds = new ArrayList<>();
		try {
			for (int round = 0; round < ROUNDS; round++) {
				CyclicBarrier gate = new CyclicBarrier(2);
				String title = "title-" + round;
				List<Future<?>> futures = new ArrayList<>();
				futures.add(pool.submit(() -> {
					try {
						gate.await(10, TimeUnit.SECONDS);
						bounded(() -> {
							AgentSessionData fresh = agentSessionService.getSessionData(session).orElseThrow();
							usageService.report(fresh, line(session), WU);
						});
					} catch (Throwable th) {
						failures.add(th);
					}
					return null;
				}));
				futures.add(pool.submit(() -> {
					try {
						gate.await(10, TimeUnit.SECONDS);
						bounded(() -> agentSessionService.updateMeta(session, title, null, WU));
					} catch (Throwable th) {
						failures.add(th);
					}
					return null;
				}));
				for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
				AgentSessionData now = agentSessionService.getSessionData(session).orElseThrow();
				rounds.add(round + ": title=" + now.getTitle() + " reports="
						+ (null == now.getUsageTotals() ? null : now.getUsageTotals().reports())
						+ " revision=" + revision(session) + " audit=" + auditRows(session));
				if (failures.isEmpty()) {
					assertEquals(title, now.getTitle(), "the content is present after round " + round);
					assertEquals(round + 1, now.getUsageTotals().reports(), "the rollup is present after round " + round);
				}
			}
		} finally {
			pool.shutdownNow();
		}

		if (!failures.isEmpty()) {
			StringBuilder sb = new StringBuilder("A racing usage report and title change raised " + failures.size()
					+ " failure(s) over " + ROUNDS + " rounds:\n");
			for (Throwable t : failures) {
				sb.append("  - ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
			}
			fail(sb.toString());
		}
		System.out.println("AgentBookkeepingRaceTest rounds:\n  " + String.join("\n  ", rounds));
		assertEquals(revision0 + ROUNDS, revision(session), "one revision per title change and none per report: " + rounds);
		assertEquals(audit0 + ROUNDS, auditRows(session), "one audit row per title change and none per report: " + rounds);
	}
}
