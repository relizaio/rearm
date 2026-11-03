/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.Release;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.ReleaseRepository.MetricsComputeCandidate;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-138 (architecture round 2, rows 6.1.1-6.1.5): the recent-lane finder
 * {@link ReleaseRepository#findReleasesForMetricsComputeRecent}, eligible releases created inside the
 * window, oldest-created first, capped.
 *
 * <p>The shared test database holds eligible leftovers of other classes, so the rows assert membership
 * with {@code limit = count + 10} (every eligible row fits) and assert the cap as a prefix of the own
 * rows, never as "the result is exactly my rows". Each test owns a fresh organization and deletes its
 * rows after itself.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseMetricsRecentFinderQueryTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private JdbcTemplate jdbc;

	/** An epoch in the past: a release stamped with it is eligible (its last_updated_date postdates it). */
	private static final double PAST_EPOCH = 1_000.0;
	private static final double NEW_EPOCH = 5_000_000_000.0;

	private UUID org;

	@BeforeEach
	void ownOrg() {
		org = testInitializer.obtainOrganization().getUuid();
	}

	@AfterEach
	void deleteOwnRows() {
		jdbc.update("DELETE FROM rearm.releases WHERE record_data->>'org' = ?", org.toString());
		jdbc.update("DELETE FROM rearm.artifacts WHERE record_data->>'org' = ?", org.toString());
	}

	/** Row 6.1.1: FIFO within the window, never newest-first. */
	@Test
	void recentFinderListsOldestCreatedFirst() {
		// created at now() - 50 minutes + {5, 1, 4, 2, 3} seconds, inserted in that order
		int[] offsets = {5, 1, 4, 2, 3};
		Map<Integer, UUID> byOffset = new HashMap<>();
		for (int o : offsets) {
			byOffset.put(o, seedEligibleReleases(org, 1, "50 minutes -" + o + " seconds", "1 minute", false).get(0));
		}
		List<UUID> result = uuids(releaseRepository.findReleasesForMetricsComputeRecent(60, membershipLimit()));
		int previous = -1;
		for (int o = 1; o <= 5; o++) {
			int position = result.indexOf(byOffset.get(o));
			assertTrue(position >= 0, "own row created at +" + o + " s must be listed: " + result);
			assertTrue(position > previous, "own rows must be listed in created order, +" + o + " s at " + position
					+ " is not after " + previous);
			previous = position;
		}
		List<Timestamp> created = createdDatesInOrder(result);
		for (int i = 1; i < created.size(); i++) {
			assertFalse(created.get(i).before(created.get(i - 1)),
					"the whole result must be in non-decreasing created_date, position " + i + ": " + created);
		}
	}

	/** Row 6.1.2: 52 recent rows, 51 in the lane; the youngest waits for the normal lane or the next tick. */
	@Test
	void recentFinderIsCappedAndTheYoungestOwnRowWaits() {
		List<UUID> own = seedEligibleReleases(org, 52, "50 minutes", "1 minute", false);
		List<UUID> result = uuids(releaseRepository.findReleasesForMetricsComputeRecent(60, 51));
		assertEquals(51, result.size(), "the lane returns exactly its cap");
		int listed = 0;
		for (UUID u : own) {
			if (result.contains(u)) listed++;
		}
		for (int k = 0; k < own.size(); k++) {
			if (result.contains(own.get(k))) {
				for (int j = 0; j < k; j++) {
					assertTrue(result.contains(own.get(j)), "own rows listed must be a prefix in created order: row " + k
							+ " is listed but the older row " + j + " is not");
				}
			}
		}
		assertTrue(listed <= 51, "listed own rows " + listed);
		UUID youngest = own.get(51);
		assertFalse(result.contains(youngest), "the 52nd own row cannot be among 51 results with 51 older own rows ahead of it");
		assertTrue(releaseRepository.isEligibleForMetricsComputeByUpdate(youngest),
				"the 52nd row stays eligible: the normal lane or the next tick takes it");
	}

	/** Row 6.1.3: the window edge, aging out into the normal lane, and the window as a bound parameter. */
	@Test
	void rowsOutsideTheWindowAgeOutIntoTheNormalLane() {
		UUID inside = seedEligibleReleases(org, 1, "60 minutes -5 seconds", "1 minute", false).get(0);
		UUID justOutside = seedEligibleReleases(org, 1, "60 minutes 5 seconds", "1 minute", false).get(0);
		UUID outside = seedEligibleReleases(org, 1, "61 minutes", "1 minute", false).get(0);
		int limit = membershipLimit();
		List<UUID> recent = uuids(releaseRepository.findReleasesForMetricsComputeRecent(60, limit));
		assertTrue(recent.contains(inside), "a row created 60 minutes - 5 s ago is inside the window");
		assertFalse(recent.contains(justOutside), "a row created 60 minutes + 5 s ago is outside the window");
		assertFalse(recent.contains(outside), "a row created 61 minutes ago is outside the window");
		List<UUID> byUpdate = uuids(releaseRepository.findReleasesForMetricsComputeByUpdate(limit));
		for (UUID u : List.of(inside, justOutside, outside)) {
			assertTrue(releaseRepository.isEligibleForMetricsComputeByUpdate(u), "every row stays eligible: " + u);
			assertTrue(byUpdate.contains(u), "the normal lane serves every eligible row: " + u);
		}
		List<UUID> wide = uuids(releaseRepository.findReleasesForMetricsComputeRecent(120, limit));
		assertTrue(wide.containsAll(List.of(inside, justOutside, outside)),
				"with a 120-minute window all three are recent: the window is the bound parameter, not a literal");
	}

	/** Row 6.1.4: a fenced recent release is skipped until its fence goes, then the recent lane takes it. */
	@Test
	void fencedRecentReleaseIsSkippedUntilTheFenceGoes() {
		UUID r = recentRelease(org, "30 minutes", false);
		releaseRepository.recordMetricsComputeIncomplete(r, 3600);
		assertFalse(recentListed(r), "(a) a release fenced for an hour is not listed");
		assertFalse(releaseRepository.isEligibleForMetricsComputeByUpdate(r), "(a) and not eligible");

		releaseRepository.clearMetricsComputeBackoff(r);
		assertTrue(recentListed(r), "(b) the cleared fence lists it again");
		assertTrue(releaseRepository.isEligibleForMetricsComputeByUpdate(r), "(b) and it is eligible");

		releaseRepository.recordMetricsComputeIncomplete(r, 0);
		assertTrue(recentListed(r), "(c) a 0 s grace fence has expired by the next call");

		Artifact art = saveArtifactWithLastScanned(org, NEW_EPOCH);
		UUID carrying = saveReleaseWithDirectArtifact(org, PAST_EPOCH, art.getUuid()).getUuid();
		jdbc.update("UPDATE rearm.releases SET created_date = now() - interval '30 minutes' WHERE uuid = ?", carrying);
		Timestamp createdBefore = createdDateOf(carrying);
		releaseRepository.recordMetricsComputeIncomplete(carrying, 3600);
		assertFalse(recentListed(carrying), "(d) fenced for an hour, not listed");
		releaseRepository.touchReleasesByScannedArtifactDirect(art.getUuid().toString());
		assertTrue(recentListed(carrying), "(d) the artifact touch dropped the fence: the recent lane lists it");
		assertEquals(createdBefore, createdDateOf(carrying),
				"(d) the touch bumps last_updated_date, never created_date: the lane, not the back of the queue, takes it");
	}

	/** Row 6.1.5: the drain-mode product flag is the candidate column, as for the normal finder. */
	@Test
	void recentCandidatesCarryTheProductFlag() {
		UUID product = recentRelease(org, "30 minutes", true);
		UUID component = recentRelease(org, "30 minutes", false);
		UUID emptyParents = recentRelease(org, "30 minutes", false);
		mergeReleaseRecord(emptyParents, "{\"parentReleases\": []}");
		Map<UUID, Boolean> flags = new HashMap<>();
		for (MetricsComputeCandidate c : releaseRepository.findReleasesForMetricsComputeRecent(60, membershipLimit())) {
			flags.put(c.getUuid(), c.getProduct());
		}
		assertEquals(Boolean.TRUE, flags.get(product), "a product is listed with product = true");
		assertEquals(Boolean.FALSE, flags.get(component), "a component is listed with product = false");
		assertEquals(Boolean.FALSE, flags.get(emptyParents), "an empty parentReleases array is not a product");
	}

	// ---- helpers ----

	private int membershipLimit() {
		return (int) releaseRepository.countReleasesEligibleForMetricsCompute() + 10;
	}

	private boolean recentListed(UUID uuid) {
		return uuids(releaseRepository.findReleasesForMetricsComputeRecent(60, membershipLimit())).contains(uuid);
	}

	private static List<UUID> uuids(List<MetricsComputeCandidate> candidates) {
		List<UUID> out = new ArrayList<>(candidates.size());
		for (MetricsComputeCandidate c : candidates) out.add(c.getUuid());
		return out;
	}

	private List<Timestamp> createdDatesInOrder(List<UUID> ordered) {
		Map<UUID, Timestamp> byUuid = new HashMap<>();
		jdbc.query("SELECT uuid, created_date FROM rearm.releases WHERE uuid = ANY(?)",
				rs -> { byUuid.put(rs.getObject(1, UUID.class), rs.getTimestamp(2)); },
				(Object) ordered.toArray(new UUID[0]));
		List<Timestamp> out = new ArrayList<>();
		for (UUID u : ordered) {
			if (byUuid.containsKey(u)) out.add(byUuid.get(u));
		}
		return out;
	}

	private Timestamp createdDateOf(UUID uuid) {
		return jdbc.queryForObject("SELECT created_date FROM rearm.releases WHERE uuid = ?", Timestamp.class, uuid);
	}

	/**
	 * n eligible releases of the org in one statement, oldest-created first: created_date and
	 * last_updated_date are now() minus the given intervals plus g seconds (g = 1..n), so both orders
	 * over the own rows are total; no lastScanned, so every row is eligible.
	 */
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

	/** An eligible entity-saved release with a controlled created_date (last_updated_date one minute later). */
	private UUID recentRelease(UUID orgUuid, String createdAgo, boolean product) {
		UUID uuid = saveReleaseWithLastScanned(orgUuid, PAST_EPOCH).getUuid();
		jdbc.update("UPDATE rearm.releases SET created_date = now() - ?::interval,"
				+ " last_updated_date = now() - ?::interval + interval '1 minute' WHERE uuid = ?",
				createdAgo, createdAgo, uuid);
		if (product) {
			mergeReleaseRecord(uuid, "{\"parentReleases\": [{\"release\": \"" + UUID.randomUUID() + "\"}]}");
		}
		return uuid;
	}

	private void mergeReleaseRecord(UUID release, String json) {
		jdbc.update("UPDATE rearm.releases SET record_data = record_data || CAST(? AS jsonb) WHERE uuid = ?", json, release);
	}

	private Artifact saveArtifactWithLastScanned(UUID orgUuid, double lastScanned) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		a.setMetrics(metrics);
		return artifactRepository.save(a);
	}

	private Release saveReleaseWithLastScanned(UUID orgUuid, double lastScanned) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("artifacts", new ArrayList<String>());
		r.setRecordData(recordData);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("lastScanned", lastScanned);
		r.setMetrics(metrics);
		return releaseRepository.save(r);
	}

	private Release saveReleaseWithDirectArtifact(UUID orgUuid, double lastScanned, UUID artifactUuid) {
		Release r = saveReleaseWithLastScanned(orgUuid, lastScanned);
		Map<String, Object> recordData = r.getRecordData();
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}
}
