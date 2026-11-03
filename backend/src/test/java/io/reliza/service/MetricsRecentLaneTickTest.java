/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.Release;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.ReleaseRepository.MetricsComputeCandidate;
import io.reliza.ws.App;
import io.reliza.ws.SchedulingConfig;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-138 (architecture round 2, rows 6.2.1-6.2.6): the two-lane metrics tick. The tick is called
 * directly with {@code (102, 51, 60)}; the compute is a spy that records the attempted uuid and returns
 * false (no stamp, no fence, no cascade write), so what the finders list before the tick is what the
 * tick's lanes read.
 *
 * <p>The shared test database holds eligible leftovers of other classes. Own recent rows are created
 * 59 minutes ago plus g seconds (older-created than any leftover younger than 59 minutes), own
 * normal-lane rows are touched more than two hours ago ({@link #touchedLongAgo}), and every row
 * asserts, before the tick, the finder memberships it relies on with a message naming the leftovers
 * as the cause (F-1), so a displaced fixture fails on its precondition rather than on the property.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@TestPropertySource(properties = SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false")
public class MetricsRecentLaneTickTest {

	@Autowired private ReleaseService releaseService;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ConfigurableEnvironment environment;

	@MockitoSpyBean private ReleaseMetricsComputeService releaseMetricsComputeService;

	private static final double PAST_EPOCH = 1_000.0;
	private static final int LIMIT = 102;
	private static final int RECENT_LIMIT = SchedulingService.METRICS_RECENT_BATCH_LIMIT;
	private static final int WINDOW = SchedulingService.METRICS_RECENT_WINDOW_MINUTES;

	private UUID org;
	private final List<UUID> attempted = new LinkedList<>();
	private Object releaseServiceTarget;
	private Object realRepository;

	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@BeforeEach
	void setUp() {
		org = testInitializer.obtainOrganization().getUuid();
		synchronized (attempted) { attempted.clear(); }
		doAnswer(inv -> {
			UUID r = inv.getArgument(0);
			synchronized (attempted) { attempted.add(r); }
			return false;
		}).when(releaseMetricsComputeService).computeReleaseMetricsOnRescan(any(UUID.class));
		releaseServiceTarget = AopTestUtils.getUltimateTargetObject(releaseService);
		realRepository = ReflectionTestUtils.getField(releaseServiceTarget, "repository");
	}

	@AfterEach
	void tearDown() {
		ReflectionTestUtils.setField(releaseServiceTarget, "repository", realRepository);
		Mockito.reset(releaseMetricsComputeService);
		jdbc.update("DELETE FROM rearm.releases WHERE record_data->>'org' = ?", org.toString());
	}

	/** Row 6.2.1: a release created now computes in the tick while 102+ older touched rows are pending. */
	@Test
	void aRecentReleaseIsAttemptedBeforeTheNormalLane() {
		List<UUID> blockers = seedEligibleReleases(org, 102, "3 hours", "2 hours", false);
		touchedLongAgo(blockers);
		UUID r = recentRelease(org, "59 minutes", "59 minutes", false);

		List<UUID> byUpdate = byUpdate();
		assertTrue(containsAny(byUpdate, blockers), normalLeftovers("no blocker is among the " + LIMIT
				+ " oldest-touched eligible rows"));
		assertFalse(byUpdate.contains(r), normalLeftovers("R is among the normal lane's " + LIMIT
				+ " rows, so the row cannot show that the lane is what serves it"));
		assertTrue(recent().contains(r), recentLeftovers("R is not in the recent lane's " + RECENT_LIMIT));

		tick();

		List<UUID> seen = attemptedSnapshot();
		assertEquals(1, Collections.frequency(seen, r), "R is attempted exactly once");
		int rAt = seen.indexOf(r);
		boolean anyBlocker = false;
		for (UUID b : blockers) {
			int at = seen.indexOf(b);
			if (at < 0) continue;
			anyBlocker = true;
			assertTrue(rAt < at, "the recent lane completes before the normal lane starts: R at " + rAt + ", blocker at " + at);
		}
		assertTrue(anyBlocker, "the normal lane ran and attempted a blocker");
	}

	/** Row 6.2.2: 52 recent rows, 51 in the lane, the youngest waits. */
	@Test
	void theRecentLaneTakesItsCapAndTheRestWaits() {
		List<UUID> blockers = seedEligibleReleases(org, 102, "3 hours", "2 hours", false);
		touchedLongAgo(blockers);
		List<UUID> own = seedEligibleReleases(org, 52, "59 minutes", "0 seconds", false);

		List<UUID> recent = recent();
		assertEquals(own.subList(0, RECENT_LIMIT), recent, recentLeftovers("the recent lane does not list exactly the "
				+ RECENT_LIMIT + " oldest own rows in created order"));
		List<UUID> byUpdate = byUpdate();
		assertFalse(containsAny(byUpdate, own), "precondition: an own recent row is among the normal lane's "
				+ LIMIT + " rows although every blocker is older-touched");
		assertTrue(containsAny(byUpdate, blockers), normalLeftovers("no blocker is in the normal lane's " + LIMIT));
		Set<UUID> lanes = new HashSet<>(recent);
		lanes.addAll(byUpdate);

		tick();

		List<UUID> seen = attemptedSnapshot();
		List<UUID> missing = new ArrayList<>();
		for (UUID u : own) {
			if (!seen.contains(u)) missing.add(u);
		}
		assertEquals(List.of(own.get(51)), missing, "exactly 51 own recent rows attempted, the youngest-created missing");
		long laneAttempts = seen.stream().filter(lanes::contains).count();
		assertTrue(laneAttempts <= RECENT_LIMIT + LIMIT, "the two lanes attempt at most 153 rows: " + laneAttempts);
		// The eager product cascade after the lanes may add up to LIMIT products of attempted
		// leftovers (unchanged behaviour, capped at limit), so the bound on everything attempted is 255.
		assertTrue(seen.size() <= RECENT_LIMIT + 2 * LIMIT, "attempted " + seen.size());
	}

	/** Row 6.2.3: a row both finders list computes once. */
	@Test
	void aRowListedByBothLanesIsAttemptedOnce() {
		UUID x = recentRelease(org, "59 minutes", "59 minutes", false);
		jdbc.update("UPDATE rearm.releases SET last_updated_date = now() - interval '10 days' WHERE uuid = ?", x);
		assertTrue(recent().contains(x), recentLeftovers("X is not in the recent lane"));
		assertTrue(byUpdate().contains(x), normalLeftovers("X is not in the normal lane's " + LIMIT));

		tick();

		assertEquals(1, Collections.frequency(attemptedSnapshot(), x),
				"X stayed eligible (the compute returned false) and the normal finder listed it again; D3 and the claim drop it");
	}

	/** Row 6.2.4: a recent product computes, not deferred, under drain mode; an old product is deferred. */
	@Test
	void underDrainModeARecentProductComputesAndAnOldProductIsDeferred() {
		// F-3: the storm is touched two hours ago plus g seconds (younger-touched than P and Q, older than
		// any leftover a suite run under two hours leaves), and a precondition asserts an own storm row in
		// the normal lane, so "a component was attempted" is about this row's own components.
		List<UUID> storm = seedEligibleReleases(org, 1001, "3 hours", "2 hours", false);
		UUID p = recentRelease(org, "59 minutes", "59 minutes", true);
		UUID q = recentRelease(org, "5 hours", "5 hours", true);
		touchedLongAgo(List.of(q, p));

		long eligible = releaseRepository.countReleasesEligibleForMetricsCompute();
		assertTrue(eligible > ReleaseService.METRICS_BACKLOG_PRESSURE_THRESHOLD, "precondition: drain mode, eligible = " + eligible);
		List<UUID> byUpdate = byUpdate();
		assertTrue(byUpdate.contains(p), normalLeftovers("P is not among the " + LIMIT
				+ " oldest-touched eligible rows; the row cannot exercise D3 on this database"));
		assertTrue(byUpdate.contains(q), normalLeftovers("Q is not among the " + LIMIT
				+ " oldest-touched eligible rows; the row cannot exercise the deferral on this database"));
		assertTrue(containsAny(byUpdate, storm), normalLeftovers("no own storm component is among the " + LIMIT
				+ " oldest-touched eligible rows"));
		assertTrue(recent().contains(p), recentLeftovers("P is not in the recent lane"));
		Timestamp pBefore = lastUpdatedOf(p);
		Timestamp qBefore = lastUpdatedOf(q);

		tick();

		List<UUID> seen = attemptedSnapshot();
		assertEquals(1, Collections.frequency(seen, p), "P is attempted exactly once");
		int pAt = seen.indexOf(p);
		Set<UUID> stormSet = new HashSet<>(storm);
		boolean anyComponent = false;
		for (int i = 0; i < seen.size(); i++) {
			if (!stormSet.contains(seen.get(i))) continue;
			anyComponent = true;
			assertTrue(pAt < i, "P (recent lane) is attempted before every own storm component: P at " + pAt + ", component at " + i);
		}
		assertTrue(anyComponent, "at least one own storm component was attempted");
		assertEquals(pBefore, lastUpdatedOf(p), "P was not deferred (D2, D3)");
		assertFalse(seen.contains(q), "Q, an old product, is not attempted in drain mode");
		assertTrue(lastUpdatedOf(q).after(qBefore), "Q was deferred by the normal lane's drain deferral");
	}

	/** Row 6.2.5: a fenced recent release is not attempted; after the fence it is. */
	@Test
	void aFencedRecentReleaseWaitsForItsFence() {
		UUID f = recentRelease(org, "30 minutes", "30 minutes", false);
		releaseRepository.recordMetricsComputeIncomplete(f, 3600);
		tick();
		assertFalse(attemptedSnapshot().contains(f), "a fenced release is in neither lane");

		releaseRepository.clearMetricsComputeBackoff(f);
		assertTrue(recent().contains(f), recentLeftovers("F is not in the recent lane after the fence went"));
		synchronized (attempted) { attempted.clear(); }
		tick();
		assertTrue(attemptedSnapshot().contains(f), "after the fence F is attempted");
	}

	/** Row 6.2.6: a failing recent lane does not stop the normal lane. */
	@Test
	void aFailingRecentLaneDoesNotStopTheNormalLane() {
		List<UUID> blocker = seedEligibleReleases(org, 1, "3 hours", "2 hours", false);
		touchedLongAgo(blocker);
		assertTrue(byUpdate().contains(blocker.get(0)), normalLeftovers("the blocker is not in the normal lane's " + LIMIT));

		ReleaseRepository real = (ReleaseRepository) realRepository;
		ReleaseRepository failing = Mockito.mock(ReleaseRepository.class, AdditionalAnswers.delegatesTo(real));
		doThrow(new IllegalStateException("simulated recent finder failure"))
				.when(failing).findReleasesForMetricsComputeRecent(anyInt(), anyInt());
		ReflectionTestUtils.setField(releaseServiceTarget, "repository", failing);

		CapturingAppender log = new CapturingAppender();
		log.start();
		Logger logger = (Logger) LogManager.getLogger(ReleaseService.class);
		logger.addAppender(log);
		try {
			tick();
		} finally {
			logger.removeAppender(log);
			log.stop();
		}

		assertTrue(attemptedSnapshot().contains(blocker.get(0)), "the normal lane still ran and attempted the blocker");
		List<LogEvent> events;
		synchronized (log.events) { events = new ArrayList<>(log.events); }
		assertTrue(events.stream().anyMatch(e -> e.getMessage().getFormattedMessage().contains("recent lane failed")
				&& e.getLevel() == Level.ERROR && e.getThrown() != null),
				"the recent lane's failure is logged at error with its throwable");
	}

	/**
	 * Row 6.4.3 (here, not in the plan test, because the constants are package-private in
	 * io.reliza.service): the window and the cap are constants, not properties.
	 */
	@Test
	void theRecentLaneWindowAndCapAreConstants() {
		assertEquals(60, SchedulingService.METRICS_RECENT_WINDOW_MINUTES);
		assertEquals(51, SchedulingService.METRICS_RECENT_BATCH_LIMIT);
		assertEquals(0, SchedulingService.METRICS_RECENT_BATCH_LIMIT % 3, "even shards over the default 3 workers");
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
			for (String key : enumerable.getPropertyNames()) {
				assertFalse(key.startsWith("relizaprops.metricsRecent"),
						key + " in " + source.getName() + ": the recent lane takes constants, not properties");
			}
		}
	}

	// ---- helpers ----

	private void tick() {
		releaseService.computeMetricsForAllUnprocessedReleases(LIMIT, RECENT_LIMIT, WINDOW);
	}

	private List<UUID> attemptedSnapshot() {
		synchronized (attempted) { return new ArrayList<>(attempted); }
	}

	private List<UUID> recent() {
		return uuids(releaseRepository.findReleasesForMetricsComputeRecent(WINDOW, RECENT_LIMIT));
	}

	private List<UUID> byUpdate() {
		return uuids(releaseRepository.findReleasesForMetricsComputeByUpdate(LIMIT));
	}

	private String normalLeftovers(String what) {
		Long old = jdbc.queryForObject("SELECT count(*) FROM rearm.releases WHERE record_data->>'org' <> ?"
				+ " AND last_updated_date < now() - interval '2 hours'"
				+ " AND last_updated_date > to_timestamp(coalesce(cast (metrics->>'lastScanned' as float), 0))",
				Long.class, org.toString());
		return "precondition: " + what + "; the shared test database holds leftover eligible rows touched more than"
				+ " two hours ago (count = " + old + ")";
	}

	private String recentLeftovers(String what) {
		Long early = jdbc.queryForObject("SELECT count(*) FROM rearm.releases WHERE record_data->>'org' <> ?"
				+ " AND created_date > now() - interval '60 minutes' AND created_date < now() - interval '58 minutes'"
				+ " AND last_updated_date > to_timestamp(coalesce(cast (metrics->>'lastScanned' as float), 0))",
				Long.class, org.toString());
		return "precondition: " + what + "; the shared test database holds leftover eligible rows created between"
				+ " 58 and 60 minutes ago (count = " + early + ")";
	}

	private static boolean containsAny(List<UUID> in, List<UUID> any) {
		for (UUID u : any) {
			if (in.contains(u)) return true;
		}
		return false;
	}

	private static List<UUID> uuids(List<MetricsComputeCandidate> candidates) {
		List<UUID> out = new ArrayList<>(candidates.size());
		for (MetricsComputeCandidate c : candidates) out.add(c.getUuid());
		return out;
	}

	private Timestamp lastUpdatedOf(UUID uuid) {
		return jdbc.queryForObject("SELECT last_updated_date FROM rearm.releases WHERE uuid = ?", Timestamp.class, uuid);
	}

	/**
	 * Sets last_updated_date of the given rows to two hours ago minus their 1-based position in seconds:
	 * older-touched than any leftover a suite run of under two hours leaves, distinct and in a known
	 * order (the first row the youngest of them).
	 */
	private void touchedLongAgo(List<UUID> uuids) {
		jdbc.update("UPDATE rearm.releases r SET last_updated_date = now() - interval '2 hours' - (t.ord || ' seconds')::interval"
				+ " FROM unnest(?::uuid[]) WITH ORDINALITY AS t(id, ord) WHERE r.uuid = t.id",
				(Object) uuids.toArray(new UUID[0]));
	}

	/** n eligible releases of the org in one statement, oldest-created first (see the finder test's twin). */
	private List<UUID> seedEligibleReleases(UUID orgUuid, int n, String createdAgo, String lastUpdatedAgo, boolean product) {
		String parents = product ? "jsonb_build_array(jsonb_build_object('release', gen_random_uuid()::text))" : "'[]'::jsonb";
		return jdbc.queryForList("WITH ins AS (INSERT INTO rearm.releases (uuid, revision, schema_version, metrics_revision,"
				+ " created_date, last_updated_date, record_data, metrics)"
				+ " SELECT gen_random_uuid(), 0, 0, 0, now() - ?::interval + (g || ' seconds')::interval,"
				+ " now() - ?::interval + (g || ' seconds')::interval,"
				+ " jsonb_build_object('org', ?::text, 'lifecycle', 'ASSEMBLED', 'artifacts', '[]'::jsonb, 'parentReleases', "
				+ parents + "), '{}'::jsonb FROM generate_series(1, ?) g RETURNING uuid, created_date)"
				+ " SELECT uuid FROM ins ORDER BY created_date",
				UUID.class, createdAgo, lastUpdatedAgo, orgUuid.toString(), n);
	}

	/** An eligible entity-saved release with a controlled created_date and last_updated_date, plus one second. */
	private UUID recentRelease(UUID orgUuid, String createdAgo, String lastUpdatedAgo, boolean product) {
		Release rel = new Release();
		rel.setUuid(UUID.randomUUID());
		rel.setCreatedDate(ZonedDateTime.now());
		rel.setLastUpdatedDate(ZonedDateTime.now());
		rel.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("artifacts", new ArrayList<String>());
		rel.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", PAST_EPOCH);
		rel.setMetrics(metrics);
		UUID uuid = releaseRepository.save(rel).getUuid();
		jdbc.update("UPDATE rearm.releases SET created_date = now() - ?::interval + interval '1 second',"
				+ " last_updated_date = now() - ?::interval + interval '1 second' WHERE uuid = ?",
				createdAgo, lastUpdatedAgo, uuid);
		if (product) {
			jdbc.update("UPDATE rearm.releases SET record_data = record_data || CAST(? AS jsonb) WHERE uuid = ?",
					"{\"parentReleases\": [{\"release\": \"" + UUID.randomUUID() + "\"}]}", uuid);
		}
		return uuid;
	}
}
