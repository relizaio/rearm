/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.repositories.SystemInfoRepository;
import io.reliza.ws.App;

/**
 * TPF-114 mutation round: the edges of the persisted fan-out watermark that
 * SystemInfoFanOutWatermarkTest leaves open. The stamp creates the system_info row when it is
 * absent and writes the one row only; the read answers nothing for no org; the two scheduler
 * setters read the row under its lock (so an in-place stamp in flight is never
 * overwritten by their whole-record save); the repository stamp leaves no stale entity behind.
 * The row is shared by every test of the fork's database, so the test restores its data and the
 * two scheduler fields it touches.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SystemInfoFanOutWatermarkEdgesTest {

	@Autowired private SystemInfoService systemInfoService;
	@Autowired private SystemInfoRepository systemInfoRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PlatformTransactionManager transactionManager;

	private String originalData;
	private ZonedDateTime originalLastDtrackSync;
	private ZonedDateTime originalSweepWatermark;

	@BeforeEach
	void remember() {
		systemInfoService.getDefaultOrg(); // creates the row when absent
		originalData = jdbcTemplate.queryForObject("select data::text from rearm.system_info where id = 1", String.class);
		originalLastDtrackSync = systemInfoService.getLastDtrackSync();
		originalSweepWatermark = systemInfoService.getUnmappedBomSweepWatermark();
	}

	@AfterEach
	void restore() {
		jdbcTemplate.update("delete from rearm.system_info where id = 2");
		systemInfoService.getDefaultOrg(); // the row again, if a test removed it
		jdbcTemplate.update("update rearm.system_info set data = cast(? as jsonb) where id = 1", originalData);
		systemInfoService.setLastDtrackSync(originalLastDtrackSync);
		systemInfoService.setUnmappedBomSweepWatermark(originalSweepWatermark);
	}

	private static ZonedDateTime micros(ZonedDateTime t) {
		return t.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	@Test
	public void noOrgHasNoWatermark() {
		systemInfoService.stampFanOutDrainedAt(UUID.randomUUID(), micros(ZonedDateTime.now().minusMinutes(1)));

		assertNull(systemInfoService.getFanOutDrainedAt(null));
	}

	@Test
	public void theStampCreatesTheRowWhenItIsAbsentAndStillStamps() {
		UUID org = UUID.randomUUID();
		ZonedDateTime w = micros(ZonedDateTime.now().minusMinutes(1));
		jdbcTemplate.update("delete from rearm.system_info where id = 1");

		systemInfoService.stampFanOutDrainedAt(org, w);

		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from rearm.system_info where id = 1", Integer.class));
		assertEquals(w.toInstant(), systemInfoService.getFanOutDrainedAt(org).toInstant());
	}

	@Test
	public void theStampWritesTheOneRowOnly() {
		jdbcTemplate.update("insert into rearm.system_info (id, data) values (2, cast('{\"keep\":\"me\"}' as jsonb))");
		UUID org = UUID.randomUUID();

		systemInfoService.stampFanOutDrainedAt(org, micros(ZonedDateTime.now().minusMinutes(1)));

		assertEquals("{\"keep\": \"me\"}", jdbcTemplate.queryForObject(
				"select data::text from rearm.system_info where id = 2", String.class));
		assertNotNull(systemInfoService.getFanOutDrainedAt(org));
	}

	@Test
	public void theRepositoryStampLeavesNoStaleEntityInTheTransactionThatMadeIt() {
		UUID org = UUID.randomUUID();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		Object seen = tx.execute(status -> {
			assertNull(systemInfoRepository.findSystemInfo().getData().get("fanOutDrainedAt") instanceof Map<?, ?> m
					? m.get(org.toString()) : null);
			assertEquals(1, systemInfoRepository.stampFanOutDrainedAt(org.toString(), "2026-10-29T00:00:00Z"));
			Object map = systemInfoRepository.findSystemInfo().getData().get("fanOutDrainedAt");
			return map instanceof Map<?, ?> m ? m.get(org.toString()) : null;
		});

		assertEquals("2026-10-29T00:00:00Z", seen, "the read after the stamp, in the same transaction, sees it");
	}

	/**
	 * A stamp is in flight (written, not yet committed) when a whole-record setter starts. The
	 * setter must read the row under its lock, so it waits for the stamp and then saves a record
	 * that carries it; a plain read would take the committed row without the stamp and its save
	 * would write that record over the stamp.
	 */
	private void assertAStampInFlightSurvivesTheSetter(Runnable setter) throws Exception {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		String key = UUID.randomUUID().toString();
		String value = "2026-10-29T00:00:00Z";
		CountDownLatch stamped = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> stamper = pool.submit(() -> tx.executeWithoutResult(status -> {
				jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, '{fanOutDrainedAt}',"
						+ " (case when jsonb_typeof(data->'fanOutDrainedAt') = 'object' then data->'fanOutDrainedAt'"
						+ " else '{}'::jsonb end) || jsonb_build_object(cast(? as text), cast(? as text))) where id = 1",
						key, value);
				stamped.countDown();
				try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}));
			assertTrue(stamped.await(30, TimeUnit.SECONDS), "the stamp is written and held");
			Future<?> waiting = pool.submit(setter);
			assertThrows(TimeoutException.class, () -> waiting.get(1500, TimeUnit.MILLISECONDS),
					"the setter waits for the stamp in flight");
			release.countDown();
			stamper.get(30, TimeUnit.SECONDS);
			waiting.get(30, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			pool.shutdownNow();
		}
		assertEquals(value, jdbcTemplate.queryForObject(
				"select data->'fanOutDrainedAt'->>? from rearm.system_info where id = 1", String.class, key),
				"the setter's whole-record save carries the stamp that was in flight");
	}

	@Test
	public void setLastDtrackSyncKeepsAStampInFlight() throws Exception {
		assertAStampInFlightSurvivesTheSetter(() -> systemInfoService.setLastDtrackSync(ZonedDateTime.now()));
	}

	@Test
	public void setUnmappedBomSweepWatermarkKeepsAStampInFlight() throws Exception {
		assertAStampInFlightSurvivesTheSetter(() -> systemInfoService.setUnmappedBomSweepWatermark(ZonedDateTime.now()));
	}
}
