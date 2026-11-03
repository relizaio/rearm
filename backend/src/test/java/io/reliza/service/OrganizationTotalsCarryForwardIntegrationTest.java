/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManagerFactory;

/**
 * TPF-141 (architecture round 2, 6.4.5 and 6.4.6): the nightly totals stamp racing a whole-record
 * save of the organization. A save that loaded the organization before a stamp, in a request's
 * persistence context, and saves after it writes the stamped cells back byte for byte (the
 * withStoredTotals guard); a stamp that arrives while a save holds the row waits for its commit
 * and lands after it.
 *
 * <p>The rig: {@link #inView} binds one EntityManager to the thread as Spring Boot's open-in-view
 * interceptor does for a request (the {@code TaskPrefixClaimRaceIntegrationTest} rig), so the
 * second read of the organization returns the instance the request loaded first.
 */
@SpringBootTest(classes = {App.class})
class OrganizationTotalsCarryForwardIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private OrgTotalsCacheService orgTotalsCacheService;
	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private EntityManagerFactory emf;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbc;

	private final List<UUID> releases = new ArrayList<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@AfterEach
	void tearDown() {
		for (UUID release : releases) {
			jdbc.update("DELETE FROM rearm.releases WHERE uuid = ?", release);
		}
		releases.clear();
		executor.shutdownNow();
	}

	/** The call as one request runs it: one EntityManager bound to the thread for the whole call (open-in-view). */
	private <T> T inView(Callable<T> call) throws Exception {
		OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
		interceptor.setEntityManagerFactory(emf);
		ServletWebRequest web = new ServletWebRequest(new MockHttpServletRequest());
		interceptor.preHandle(web);
		try {
			return call.call();
		} finally {
			interceptor.afterCompletion(web, null);
		}
	}

	private void activeRelease(UUID org) {
		UUID uuid = UUID.randomUUID();
		jdbc.update("INSERT INTO rearm.releases (uuid, record_data) VALUES (?, cast(? as jsonb))", uuid,
				"{\"org\":\"" + org + "\",\"status\":\"ACTIVE\"}");
		releases.add(uuid);
	}

	private String totalsText(UUID org) {
		return jdbc.queryForObject("SELECT record_data->>'totals' FROM rearm.organizations WHERE uuid = ?",
				String.class, org);
	}

	private List<Object> meta(UUID org) {
		return jdbc.queryForObject("SELECT revision, last_updated_date FROM rearm.organizations WHERE uuid = ?",
				(rs, n) -> List.of(rs.getInt(1), rs.getObject(2, OffsetDateTime.class)), org);
	}

	private long auditRows(UUID org) {
		return jdbc.queryForObject("SELECT count(*) FROM rearm.audit WHERE entity_uuid = ? AND entity_name = ?",
				Long.class, org, TableName.ORGANIZATIONS.toString());
	}

	/** 6.4.5: a save that loaded before the stamp and saves after it keeps the stamped cells. */
	@Test
	void aSaveThatLoadedBeforeTheStampWritesTheStampedTotalsBack() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		activeRelease(org);
		List<Object> metaBefore = meta(org);
		long auditsBefore = auditRows(org);

		String stamped = inView(() -> {
			Organization loaded = getOrganizationService.getOrganization(org).orElseThrow();
			assertFalse(loaded.getRecordData().containsKey("totals"), loaded.getRecordData().toString());

			assertEquals(List.of(), executor.submit(() -> orgTotalsCacheService.countAndStampOrg(org)).get());
			String text = totalsText(org);
			assertNotNull(text);
			Organization again = getOrganizationService.getOrganization(org).orElseThrow();
			assertSame(loaded, again);
			assertFalse(again.getRecordData().containsKey("totals"), again.getRecordData().toString());

			OrganizationData.Settings patch = new OrganizationData.Settings();
			patch.setJustificationMandatory(true);
			organizationService.updateSettings(org, patch, WU);
			return text;
		});

		assertEquals(stamped, totalsText(org), "the save writes the stamped cells back byte for byte");
		assertEquals("true", jdbc.queryForObject(
				"SELECT record_data->'settings'->>'justificationMandatory' FROM rearm.organizations WHERE uuid = ?",
				String.class, org));
		List<Object> metaAfter = meta(org);
		assertEquals((Integer) metaBefore.get(0) + 1, metaAfter.get(0));
		assertNotEquals(metaBefore.get(1), metaAfter.get(1));
		assertEquals(auditsBefore + 1, auditRows(org));

		Map<String, Object> m = organizationService.getNumericAnalytics(
				getOrganizationService.getOrganizationData(org).orElseThrow());
		assertEquals(1L, m.get("releases"));
		assertEquals(0L, m.get("artifacts"));
		assertEquals(0L, m.get("commits"));
		assertEquals(0L, m.get("deliverables"));
		@SuppressWarnings("unchecked")
		Map<String, Map<String, Object>> cells = Utils.OM.readValue(stamped, LinkedHashMap.class);
		Instant oldest = cells.values().stream().map(c -> Instant.parse((String) c.get("countedAt")))
				.min(Instant::compareTo).orElseThrow();
		assertEquals(DateTimeFormatter.ISO_INSTANT.format(oldest), m.get("counted_at"));
	}

	/** 6.4.6: a stamp that arrives while a save holds the row waits for its commit and lands after it. */
	@Test
	void aStampWaitsForTheSavesLockAndLandsAfterIt() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String instant = "2026-11-02T00:00:03.512417Z";
		AtomicReference<Future<Integer>> stamp = new AtomicReference<>();

		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			organizationRepository.currentTotalsForUpdate(org);
			stamp.set(executor.submit(() -> organizationRepository.stampTotal(org, "releases", 5L, instant)));
			assertThrows(TimeoutException.class, () -> stamp.get().get(300, TimeUnit.MILLISECONDS));
		});

		assertEquals(1, stamp.get().get(10, TimeUnit.SECONDS));
		assertEquals("5", jdbc.queryForObject(
				"SELECT record_data->'totals'->'releases'->>'count' FROM rearm.organizations WHERE uuid = ?",
				String.class, org));
	}
}
