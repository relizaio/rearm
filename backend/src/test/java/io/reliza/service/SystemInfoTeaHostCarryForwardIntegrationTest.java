/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.model.Organization;
import io.reliza.model.SystemInfoData;
import io.reliza.model.SystemInfoData.EmailSendType;
import io.reliza.model.SystemInfoData.SetEmailPropertiesDto;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SystemInfoRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-142, architecture section 4 row 27 (F-9): every whole-record save of system_info carries the committed
 * teaHostAssignments forward under the row lock (SystemInfoService.saveSystemInfo), so a setter that read the row
 * before a committed assign or unassign cannot write a stale map back. The row is shared by the whole test database,
 * so each case snapshots it first and writes the snapshot back after.
 */
@SpringBootTest(classes = {App.class}, properties = "relizaprops.teaCustomDomains=tea-a.test,tea-b.test,Tea-C.test.")
public class SystemInfoTeaHostCarryForwardIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private SystemInfoService systemInfoService;
	@Autowired private SystemInfoRepository systemInfoRepository;
	@Autowired private TeaHostAssignmentService teaHostAssignmentService;
	@Autowired private UserService userService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Value("${relizaprops.installationSecret}") private String installationSecret;

	private final ExecutorService pool = Executors.newFixedThreadPool(2);
	private String snapshot;

	@BeforeEach
	void snapshotTheRow() {
		systemInfoService.getSystemInfoData();
		snapshot = jdbcTemplate.queryForObject("SELECT data::text FROM rearm.system_info WHERE id = 1", String.class);
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	@AfterEach
	void restoreTheRow() {
		pool.shutdownNow();
		int n = jdbcTemplate.update("UPDATE rearm.system_info SET data = CAST(? AS jsonb) WHERE id = 1", snapshot);
		if (0 == n) jdbcTemplate.update("INSERT INTO rearm.system_info (id, data) VALUES (1, CAST(? AS jsonb))", snapshot);
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	private Map<String, String> storedMap() {
		String text = jdbcTemplate.queryForObject("SELECT data->>'teaHostAssignments' FROM rearm.system_info WHERE id = 1", String.class);
		if (null == text) return Map.of();
		@SuppressWarnings("unchecked")
		Map<String, String> m = Utils.OM.readValue(text, LinkedHashMap.class);
		return m;
	}

	private String storedKey(String key) {
		return jdbcTemplate.queryForObject("SELECT data->>'" + key + "' FROM rearm.system_info WHERE id = 1", String.class);
	}

	/** Runs {@code work} in a committed transaction of another thread and waits for it. */
	private void elsewhere(ThrowingRunnable work) {
		try {
			pool.submit(() -> {
				new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
					try {
						work.run();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				});
				return null;
			}).get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** The outer transaction loads the entity first, so the setter is handed the persistence context's stale copy. */
	private void staleThen(ThrowingRunnable between, ThrowingRunnable setter) {
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			systemInfoRepository.findSystemInfo();
			elsewhere(between);
			try {
				setter.run();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private int lockWaiters() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid "
				+ "WHERE NOT l.granted AND a.datname = current_database()", Integer.class);
	}

	private void awaitWaiters(int n) throws InterruptedException {
		long until = System.currentTimeMillis() + 30_000;
		while (lockWaiters() < n) {
			if (System.currentTimeMillis() > until) throw new IllegalStateException("fewer than " + n + " lock waiters");
			Thread.sleep(50);
		}
	}

	private static void finished(Future<?> f) throws InterruptedException, TimeoutException {
		try {
			f.get(60, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError(e.getCause());
		}
	}

	@Test
	void withStoredTeaHostAssignmentsReplacesOrRemovesTheKey() {
		Map<String, Object> copy = new LinkedHashMap<>();
		copy.put("defaultOrg", "x");
		copy.put(SystemInfoData.TEA_HOST_ASSIGNMENTS_FIELD, Map.of("tea-a.test", "stale"));
		Map<String, Object> kept = SystemInfoData.withStoredTeaHostAssignments(copy, Optional.of("{\"tea-b.test\":\"fresh\"}"));
		assertEquals(Map.of("tea-b.test", "fresh"), kept.get(SystemInfoData.TEA_HOST_ASSIGNMENTS_FIELD));
		Map<String, Object> absent = SystemInfoData.withStoredTeaHostAssignments(copy, Optional.empty());
		assertFalse(absent.containsKey(SystemInfoData.TEA_HOST_ASSIGNMENTS_FIELD));
		assertEquals("x", absent.get("defaultOrg"));
	}

	// (a) a stale setDefaultOrg after a committed unassign
	@Test
	void aStaleSaveCannotRevertACommittedUnassign() throws Exception {
		UUID a = testInitializer.obtainOrganization().getUuid();
		UUID x = testInitializer.obtainOrganization().getUuid();
		teaHostAssignmentService.assign("tea-a.test", a, null);
		staleThen(() -> teaHostAssignmentService.unassign("tea-a.test", null), () -> systemInfoService.setDefaultOrg(x));
		assertFalse(storedMap().containsKey("tea-a.test"), storedMap().toString());
		assertEquals(x.toString(), storedKey("defaultOrg"));
	}

	// (b) a stale setEmailProperties after a committed assign
	@Test
	void aStaleSaveCannotLoseACommittedAssign() throws Exception {
		UUID a = testInitializer.obtainOrganization().getUuid();
		staleThen(() -> teaHostAssignmentService.assign("tea-a.test", a, null), () -> systemInfoService.setEmailProperties(
				SetEmailPropertiesDto.builder().emailSendType(EmailSendType.SENDGRID).fromEmail("tpf142@example.com").build()));
		assertEquals(Map.of("tea-a.test", a.toString()), storedMap());
		assertEquals("tpf142@example.com", storedKey("fromEmail"));
		assertEquals("SENDGRID", storedKey("emailSendType"));
	}

	// (c) the save waits for an unassign that holds the row lock, then writes the map that unassign committed
	@Test
	void aSaveWaitsForAnUnassignHoldingTheLock() throws Exception {
		UUID a = testInitializer.obtainOrganization().getUuid();
		UUID x = testInitializer.obtainOrganization().getUuid();
		teaHostAssignmentService.assign("tea-a.test", a, null);
		CountDownLatch loaded = new CountDownLatch(1);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		Future<?> setter = pool.submit(() -> tx.executeWithoutResult(s -> {
			try {
				systemInfoRepository.findSystemInfo();
				loaded.countDown();
				assertTrue(locked.await(60, TimeUnit.SECONDS));
				systemInfoService.setDefaultOrg(x);
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		}));
		assertTrue(loaded.await(60, TimeUnit.SECONDS));
		Future<?> unassign = pool.submit(() -> tx.executeWithoutResult(s -> {
			try {
				teaHostAssignmentService.unassign("tea-a.test", null);
				locked.countDown();
				assertTrue(release.await(60, TimeUnit.SECONDS));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}));
		assertTrue(locked.await(60, TimeUnit.SECONDS));
		awaitWaiters(1);
		release.countDown();
		finished(unassign);
		finished(setter);
		assertFalse(storedMap().containsKey("tea-a.test"), storedMap().toString());
		assertEquals(x.toString(), storedKey("defaultOrg"));
	}

	// (d) the three other unlocked setters, plain
	@Test
	void theOtherSettersKeepTheMapAndWriteTheirOwnField() throws Exception {
		UUID a = testInitializer.obtainOrganization().getUuid();
		teaHostAssignmentService.assign("tea-a.test", a, null);
		systemInfoService.setSendGridKey("tpf142-key");
		assertEquals(Map.of("tea-a.test", a.toString()), storedMap());
		assertTrue(null != storedKey("sendGridKey") && !"tpf142-key".equals(storedKey("sendGridKey")), "stored encrypted");
		ZonedDateTime now = ZonedDateTime.now();
		systemInfoService.setLicenseData("tpf142-license", now, now.plusDays(1), 3, 4);
		assertEquals(Map.of("tea-a.test", a.toString()), storedMap());
		assertEquals("tpf142-license", storedKey("license"));
		jdbcTemplate.update("UPDATE rearm.system_info SET data = jsonb_set(data, '{systemSealed}', 'true'::jsonb) WHERE id = 1");
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Unseal " + tag, "tpf142-u-" + tag + "@tea.io", true, List.of(), "tpf142-" + UUID.randomUUID(),
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		UserData ud = userService.getUserData(u.getUuid()).orElseThrow();
		systemInfoService.unSealSystem(installationSecret, ud);
		assertEquals(Map.of("tea-a.test", a.toString()), storedMap());
		assertEquals("false", storedKey("systemSealed"));
	}

	// (e) assign on an empty table creates the row and locks after it
	@Test
	void assignOnAnEmptyTableCreatesTheRow() throws Exception {
		Organization a = testInitializer.obtainOrganization();
		jdbcTemplate.update("DELETE FROM rearm.system_info WHERE id = 1");
		teaHostAssignmentService.assign("tea-a.test", a.getUuid(), null);
		assertEquals(Map.of("tea-a.test", a.getUuid().toString()), storedMap());
		assertNull(storedKey("defaultOrg"));
	}
}
