/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.model.Release;
import io.reliza.service.ReleaseRebomService;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The SCE and deliverable gather lookups and the rebom invalidation walk, rewritten from jsonb
 * expansions and jsonb_exists over jsonb_path_query_array to GIN-served @> probes, return the same
 * results as the forms they replace, run side by side on the same rows: several artifacts per SCE and
 * per deliverable, a commit-level SCE artifact (no componentUuid), a sibling component on the same
 * SCE, a holder in a second organization, a batch with uuids nobody holds, and a product tree three
 * levels deep with a cycle and a product of another organization naming a release of the first.
 *
 * <p>The gather statements carry no organization predicate, before and after: a holder in another
 * organization is returned by both forms (the caller drops it on hydration). The walk keeps its
 * organization predicate: a product of another organization is reached by neither form.
 *
 * <p>The batched gather and the walk run with JIT off for their transaction (SET LOCAL), the
 * caller's when there is one: the new plans' estimated cost crosses jit_above_cost, and LLVM compile
 * would cost more than the index probes it optimises. The last tests pin that, and that the setting
 * does not outlive the transaction.
 *
 * <p>The OLD constants are the statement texts at rearm-saas 59cb33a5, copied verbatim; the rebom
 * statements are the old tail behind the two unchanged seeds.
 */
@SpringBootTest(classes = {App.class})
class GatherLookupsGinEquivalenceTest {

	private static final String OLD_FIND_RELEASE_UUIDS_SHARING_SCE_ARTIFACT = """
		WITH
		unprocessedSces (uuid, componentUuid) AS (
			SELECT sce.uuid,
           		(artifact_element->>'componentUuid')::uuid as componentUuid
    			FROM rearm.source_code_entries sce,
         		jsonb_array_elements(sce.record_data->'artifacts') as artifact_element
    		WHERE artifact_element->>'artifactUuid' = :artUuidAsString
		)
		SELECT rlzs.uuid FROM unprocessedSces, rearm.releases rlzs
		WHERE record_data->>'sourceCodeEntry' = cast (unprocessedSces.uuid as text)
		  AND (rlzs.record_data->>'component')::uuid = unprocessedSces.componentUuid;
		""";

	private static final String OLD_FIND_RELEASES_SHARING_SCE_ARTIFACTS = """
		WITH
		unprocessedSces (uuid, componentUuid) AS (
			SELECT sce.uuid,
				(artifact_element->>'componentUuid')::uuid as componentUuid
			FROM rearm.source_code_entries sce,
				jsonb_array_elements(sce.record_data->'artifacts') as artifact_element
			WHERE artifact_element->>'artifactUuid' IN (:artifactUuidsAsStrings)
		)
		SELECT DISTINCT rlzs.uuid FROM unprocessedSces, rearm.releases rlzs
		WHERE record_data->>'sourceCodeEntry' = cast (unprocessedSces.uuid as text)
			AND (rlzs.record_data->>'component')::uuid = unprocessedSces.componentUuid;
		""";

	private static final String OLD_FIND_RELEASES_SHARING_DELIVERABLE_ARTIFACTS = """
		WITH
			unprocessedDeliverables (uuid) AS (
				SELECT DISTINCT del.uuid FROM rearm.deliverables del,
					jsonb_array_elements_text(del.record_data->'artifacts') AS art
				WHERE art IN (:artifactUuidsAsStrings)),
			unprocessedRlzIds (uuid) AS (
				SELECT DISTINCT var.record_data->>'release' FROM unprocessedDeliverables, rearm.variants var,
					jsonb_array_elements_text(var.record_data->'outboundDeliverables') AS del
				WHERE del = unprocessedDeliverables.uuid::text)
			SELECT DISTINCT rlzs.uuid FROM unprocessedRlzIds, rearm.releases rlzs
				WHERE rlzs.uuid = cast (unprocessedRlzIds.uuid as uuid);
		""";

	private static final String OLD_RELEASES_SHARING_DELIVERABLE_ARTIFACT_IDS = """
		WITH
			unprocessedDeliverables (uuid) AS (
				SELECT del.uuid from rearm.deliverables del
				WHERE record_data @> jsonb_build_object('artifacts', jsonb_build_array(:artUuidAsString))),
			unprocessedRlzIds (uuid) AS (
				SELECT distinct var.record_data->>'release' from unprocessedDeliverables, rearm.variants var
				WHERE record_data @> jsonb_build_object('outboundDeliverables', jsonb_build_array(unprocessedDeliverables.uuid)))
		""";

	private static final String OLD_FIND_RELEASES_SHARING_DELIVRABLE_ARTIFACT = OLD_RELEASES_SHARING_DELIVERABLE_ARTIFACT_IDS
			+ """
			SELECT rlzs.* FROM unprocessedRlzIds, rearm.releases rlzs
				WHERE rlzs.uuid = cast (unprocessedRlzIds.uuid as uuid);
			""";

	private static final String OLD_FIND_RELEASE_UUIDS_SHARING_DELIVERABLE_ARTIFACT = OLD_RELEASES_SHARING_DELIVERABLE_ARTIFACT_IDS
			+ """
			SELECT rlzs.uuid FROM unprocessedRlzIds, rearm.releases rlzs
				WHERE rlzs.uuid = cast (unprocessedRlzIds.uuid as uuid);
			""";

	private static final String OLD_INVALIDATE_RELEASE_REBOMS_ABOVE_TAIL = """
		  UNION
			SELECT p.uuid::text FROM rearm.releases p JOIN above a
				ON p.record_data->>'org' = :orgUuidAsString
			   AND jsonb_exists(jsonb_path_query_array(p.record_data, '$.parentReleases[*].release'), a.u)
		)
		UPDATE rearm.release_reboms rr
		   SET record_data = jsonb_set(rr.record_data, '{reboms}', '[]'::jsonb),
			   revision = rr.revision + 1,
			   last_updated_date = now()
		 WHERE rr.record_data->>'org' = :orgUuidAsString
		   AND rr.record_data->>'release' IN (SELECT u FROM above)
		""";

	private static final String OLD_INVALIDATE_RELEASE_REBOMS_OF_RELEASES_AND_ABOVE = """
		WITH RECURSIVE above(u) AS (
			SELECT r.uuid::text FROM rearm.releases r
			 WHERE r.record_data->>'org' = :orgUuidAsString
			   AND r.uuid::text = ANY(CAST(:seedReleaseUuidsAsStrings AS text[]))
		""" + OLD_INVALIDATE_RELEASE_REBOMS_ABOVE_TAIL;

	private static final String OLD_INVALIDATE_RELEASE_REBOMS_OF_COMPONENT_RELEASES_AND_ABOVE = """
		WITH RECURSIVE above(u) AS (
			SELECT r.uuid::text FROM rearm.releases r
			 WHERE r.record_data->>'org' = :orgUuidAsString
			   AND r.record_data->>'component' = :componentUuidAsString
		""" + OLD_INVALIDATE_RELEASE_REBOMS_ABOVE_TAIL;

	@Autowired private JdbcTemplate jdbc;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ReleaseRebomRepository releaseRebomRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseRebomService releaseRebomService;

	private NamedParameterJdbcTemplate named;

	@BeforeEach
	void namedTemplate() {
		named = new NamedParameterJdbcTemplate(jdbc);
	}

	private static String id() {
		return UUID.randomUUID().toString();
	}

	private UUID sce(UUID org, String artifactsJson) {
		UUID uuid = UUID.randomUUID();
		jdbc.update("INSERT INTO rearm.source_code_entries (uuid, record_data) VALUES (?, jsonb_build_object("
				+ "'org', ?, 'artifacts', CAST(? AS jsonb)))", uuid, org.toString(), artifactsJson);
		return uuid;
	}

	private static String sceArtifact(String artifact, UUID component) {
		return "{\"artifactUuid\":\"" + artifact + "\",\"componentUuid\":"
				+ (component == null ? "null" : "\"" + component + "\"") + "}";
	}

	private UUID release(UUID org, UUID component, UUID sce, List<UUID> parents) {
		UUID uuid = UUID.randomUUID();
		StringBuilder pr = new StringBuilder("[");
		for (UUID p : parents) {
			if (pr.length() > 1) pr.append(',');
			pr.append("{\"release\":\"").append(p).append("\",\"deliverables\":[]}");
		}
		pr.append(']');
		jdbc.update("INSERT INTO rearm.releases (uuid, record_data) VALUES (?, jsonb_build_object("
				+ "'org', ?, 'component', ?, 'version', ?, 'sourceCodeEntry', CAST(? AS text),"
				+ " 'parentReleases', CAST(? AS jsonb)))",
				uuid, org.toString(), component.toString(), "1.0.0-" + uuid, sce == null ? null : sce.toString(),
				pr.toString());
		return uuid;
	}

	private UUID deliverable(UUID org, String... artifacts) {
		UUID uuid = UUID.randomUUID();
		jdbc.update("INSERT INTO rearm.deliverables (uuid, record_data) VALUES (?, jsonb_build_object("
				+ "'org', ?, 'artifacts', to_jsonb(CAST(? AS text[]))))", uuid, org.toString(), artifacts);
		return uuid;
	}

	private void variant(UUID org, String release, UUID... outbound) {
		String[] dels = Arrays.stream(outbound).map(UUID::toString).toArray(String[]::new);
		jdbc.update("INSERT INTO rearm.variants (record_data) VALUES (jsonb_build_object("
				+ "'org', ?, 'release', ?, 'outboundDeliverables', to_jsonb(CAST(? AS text[]))))",
				org.toString(), release, dels);
	}

	private Set<UUID> oldSingle(String artifact) {
		return new HashSet<>(named.queryForList(OLD_FIND_RELEASE_UUIDS_SHARING_SCE_ARTIFACT,
				new MapSqlParameterSource("artUuidAsString", artifact), UUID.class));
	}

	private List<UUID> oldSingleList(String artifact) {
		return named.queryForList(OLD_FIND_RELEASE_UUIDS_SHARING_SCE_ARTIFACT,
				new MapSqlParameterSource("artUuidAsString", artifact), UUID.class);
	}

	private Set<UUID> old(String sql, Collection<String> artifacts) {
		return new HashSet<>(named.queryForList(sql,
				new MapSqlParameterSource("artifactUuidsAsStrings", artifacts), UUID.class));
	}

	private static String[] arr(Collection<String> c) {
		return c.toArray(String[]::new);
	}

	private static <T> List<T> sorted(List<T> l) {
		List<T> s = new ArrayList<>(l);
		s.sort(null);
		return s;
	}

	private record SceSeed(String a1, String a2, String a3, String commitLevel, String otherOrgArtifact,
			UUID r1, UUID r2, UUID sibling, UUID rOther) {}

	/**
	 * SCE s1 (org A) carries a1 and a2 for component c1, a3 for the sibling component c2 and a
	 * commit-level element with no component; s2 carries a1 again for c3. Releases r1 (c1, s1),
	 * sibling (c2, s1), r2 (c3, s2) and an unrelated release of c1 on another SCE. SCE s3 of org B
	 * carries otherOrgArtifact and a1 for component cb, with release rOther.
	 */
	private SceSeed sceSeed() throws Exception {
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		UUID c1 = UUID.randomUUID();
		UUID c2 = UUID.randomUUID();
		UUID c3 = UUID.randomUUID();
		UUID cb = UUID.randomUUID();
		String a1 = id();
		String a2 = id();
		String a3 = id();
		String commitLevel = id();
		String otherOrgArtifact = id();
		UUID s1 = sce(orgA, "[" + sceArtifact(a1, c1) + "," + sceArtifact(a2, c1) + "," + sceArtifact(a3, c2) + ","
				+ sceArtifact(commitLevel, null) + "]");
		UUID s2 = sce(orgA, "[" + sceArtifact(a1, c3) + "]");
		UUID s3 = sce(orgB, "[" + sceArtifact(otherOrgArtifact, cb) + "," + sceArtifact(a1, cb) + "]");
		sce(orgA, "[]");
		UUID r1 = release(orgA, c1, s1, List.of());
		UUID sibling = release(orgA, c2, s1, List.of());
		UUID r2 = release(orgA, c3, s2, List.of());
		release(orgA, c1, sce(orgA, "[" + sceArtifact(id(), c1) + "]"), List.of());
		UUID rOther = release(orgB, cb, s3, List.of());
		return new SceSeed(a1, a2, a3, commitLevel, otherOrgArtifact, r1, r2, sibling, rOther);
	}

	@Test
	void theSingleSceLookupMatchesTheOldForm() throws Exception {
		SceSeed s = sceSeed();
		for (String a : List.of(s.a1(), s.a2(), s.a3(), s.commitLevel(), s.otherOrgArtifact(), id())) {
			// no DISTINCT in either form: the same rows, duplicates included
			assertEquals(sorted(oldSingleList(a)), sorted(releaseRepository.findReleaseUuidsSharingSceArtifact(a)),
					"single SCE lookup for " + a);
		}
		// what the fixture means: a1 reaches r1, r2 and, cross-org as before, rOther; never the sibling
		assertEquals(Set.of(s.r1(), s.r2(), s.rOther()), oldSingle(s.a1()));
		assertEquals(Set.of(s.sibling()), oldSingle(s.a3()));
		assertEquals(Set.of(), oldSingle(s.commitLevel()));
		assertEquals(Set.of(s.rOther()), oldSingle(s.otherOrgArtifact()));
	}

	@Test
	void theBatchedSceLookupMatchesTheOldFormWithUuidsMissing() throws Exception {
		SceSeed s = sceSeed();
		List<List<String>> batches = List.of(
				List.of(s.a1()),
				List.of(s.a1(), s.a2(), s.a3()),
				List.of(s.a2(), id(), s.commitLevel(), id()),
				List.of(s.otherOrgArtifact(), s.a3(), id()),
				List.of(s.a1(), s.a1(), s.a2()),
				List.of(id(), id()));
		for (List<String> b : batches) {
			Set<UUID> expected = old(OLD_FIND_RELEASES_SHARING_SCE_ARTIFACTS, b);
			List<UUID> actual = releaseRepository.findReleasesSharingSceArtifacts(arr(b));
			assertEquals(expected, new HashSet<>(actual), "batched SCE lookup for " + b);
			assertEquals(actual.size(), new HashSet<>(actual).size(), "distinct uuids for " + b);
		}
		assertEquals(Set.of(s.r1(), s.r2(), s.sibling(), s.rOther()),
				old(OLD_FIND_RELEASES_SHARING_SCE_ARTIFACTS, List.of(s.a1(), s.a3())));
	}

	private record DeliverableSeed(String d1a, String d1b, String d2a, String shared, String noVariant,
			String otherOrgArtifact, UUID r1, UUID r2, UUID r3, UUID rOther) {}

	/**
	 * Deliverable d1 carries d1a, d1b and shared; d2 carries d2a and shared; d3 carries noVariant and
	 * no variant lists it. Release r1's variant lists d1; r2's lists d1 and d2; r3's lists d2; a
	 * variant names a release that does not exist and lists d2. In org B a deliverable carries
	 * otherOrgArtifact and shared, listed by rOther's variant.
	 */
	private DeliverableSeed deliverableSeed() throws Exception {
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		String d1a = id();
		String d1b = id();
		String d2a = id();
		String shared = id();
		String noVariant = id();
		String otherOrgArtifact = id();
		UUID d1 = deliverable(orgA, d1a, d1b, shared);
		UUID d2 = deliverable(orgA, d2a, shared);
		deliverable(orgA, noVariant);
		deliverable(orgA);
		UUID db = deliverable(orgB, otherOrgArtifact, shared);
		UUID c = UUID.randomUUID();
		UUID r1 = release(orgA, c, null, List.of());
		UUID r2 = release(orgA, c, null, List.of());
		UUID r3 = release(orgA, c, null, List.of());
		UUID rOther = release(orgB, UUID.randomUUID(), null, List.of());
		variant(orgA, r1.toString(), d1);
		variant(orgA, r2.toString(), d1, d2);
		variant(orgA, r3.toString(), d2);
		variant(orgA, id(), d2);
		variant(orgB, rOther.toString(), db);
		return new DeliverableSeed(d1a, d1b, d2a, shared, noVariant, otherOrgArtifact, r1, r2, r3, rOther);
	}

	@Test
	void theBatchedDeliverableLookupMatchesTheOldFormWithUuidsMissing() throws Exception {
		DeliverableSeed s = deliverableSeed();
		List<List<String>> batches = List.of(
				List.of(s.d1a()),
				List.of(s.d1a(), s.d1b()),
				List.of(s.d2a(), id(), s.noVariant()),
				List.of(s.shared()),
				List.of(s.otherOrgArtifact(), id(), id()),
				List.of(s.d1b(), s.d2a(), s.d2a()),
				List.of(id()));
		for (List<String> b : batches) {
			Set<UUID> expected = old(OLD_FIND_RELEASES_SHARING_DELIVERABLE_ARTIFACTS, b);
			List<UUID> actual = releaseRepository.findReleasesSharingDeliverableArtifacts(arr(b));
			assertEquals(expected, new HashSet<>(actual), "batched deliverable lookup for " + b);
			assertEquals(actual.size(), new HashSet<>(actual).size(), "distinct uuids for " + b);
		}
		// the shared artifact reaches both orgs, as before; a missing release is dropped by the join
		assertEquals(Set.of(s.r1(), s.r2(), s.r3(), s.rOther()),
				old(OLD_FIND_RELEASES_SHARING_DELIVERABLE_ARTIFACTS, List.of(s.shared())));
		assertEquals(Set.of(s.r2(), s.r3()), old(OLD_FIND_RELEASES_SHARING_DELIVERABLE_ARTIFACTS, List.of(s.d2a())));
	}

	/**
	 * The single-artifact deliverable statements, uuid and entity form: the shared artifact sits in
	 * two deliverables of org A and one of org B; r2 and r3 are reached through both deliverables or
	 * through two variants, the missing release is dropped, and rOther (org B) is returned by both
	 * forms, as before. Neither form repeats a release.
	 */
	@Test
	void theSingleDeliverableLookupMatchesTheOldForm() throws Exception {
		DeliverableSeed s = deliverableSeed();
		UUID orgA = testInitializer.obtainOrganization().getUuid();
		UUID d4 = deliverable(orgA, s.d2a(), id());
		UUID r4 = release(orgA, UUID.randomUUID(), null, List.of());
		variant(orgA, r4.toString(), d4);
		variant(orgA, r4.toString(), d4);
		for (String a : List.of(s.shared(), s.d1a(), s.d2a(), s.noVariant(), s.otherOrgArtifact(), id())) {
			MapSqlParameterSource p = new MapSqlParameterSource("artUuidAsString", a);
			List<UUID> oldUuids = named.queryForList(OLD_FIND_RELEASE_UUIDS_SHARING_DELIVERABLE_ARTIFACT, p, UUID.class);
			assertEquals(sorted(oldUuids), sorted(releaseRepository.findReleaseUuidsSharingDeliverableArtifact(a)),
					"uuid form for " + a);
			List<UUID> oldEntities = named.query(OLD_FIND_RELEASES_SHARING_DELIVRABLE_ARTIFACT, p,
					(rs, i) -> rs.getObject("uuid", UUID.class));
			List<UUID> newEntities = releaseRepository.findReleasesSharingDeliverableArtifact(a).stream()
					.map(Release::getUuid).toList();
			assertEquals(sorted(oldEntities), sorted(newEntities), "entity form for " + a);
		}
		assertEquals(sorted(List.of(s.r1(), s.r2(), s.r3(), s.rOther())), sorted(named.queryForList(
				OLD_FIND_RELEASE_UUIDS_SHARING_DELIVERABLE_ARTIFACT,
				new MapSqlParameterSource("artUuidAsString", s.shared()), UUID.class)));
		assertEquals(sorted(List.of(s.r2(), s.r3(), r4)), sorted(releaseRepository
				.findReleaseUuidsSharingDeliverableArtifact(s.d2a())));
	}

	private record Tree(UUID org, UUID component, UUID a, UUID b, UUID p1, UUID p2, UUID p3, UUID q, UUID x,
			UUID y, UUID other, List<UUID> all) {}

	private void rebom(UUID org, UUID release) {
		jdbc.update("INSERT INTO rearm.release_reboms (record_data) VALUES (jsonb_build_object('org', ?, "
				+ "'release', ?, 'reboms', CAST(? AS jsonb)))", org.toString(), release.toString(),
				"[{\"rebomId\":\"" + id() + "\"}]");
	}

	/**
	 * Org A: component releases a and b of one component; p1 holds a and b, p2 holds p1, p3 holds p2
	 * and q, q holds b. x and y hold each other (a cycle) and y holds p3. Org B: other holds a. Every
	 * release has a rebom record.
	 */
	private Tree tree() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID orgB = testInitializer.obtainOrganization().getUuid();
		UUID component = UUID.randomUUID();
		UUID a = release(org, component, null, List.of());
		UUID b = release(org, component, null, List.of());
		UUID p1 = release(org, UUID.randomUUID(), null, List.of(a, b));
		UUID p2 = release(org, UUID.randomUUID(), null, List.of(p1));
		UUID q = release(org, UUID.randomUUID(), null, List.of(b));
		UUID p3 = release(org, UUID.randomUUID(), null, List.of(p2, q));
		UUID x = UUID.randomUUID();
		UUID y = release(org, UUID.randomUUID(), null, List.of(p3, x));
		jdbc.update("INSERT INTO rearm.releases (uuid, record_data) VALUES (?, jsonb_build_object('org', ?, "
				+ "'component', ?, 'version', '1.0.0-x', 'parentReleases', jsonb_build_array(jsonb_build_object("
				+ "'release', ?, 'deliverables', '[]'::jsonb))))",
				x, org.toString(), UUID.randomUUID().toString(), y.toString());
		UUID other = release(orgB, UUID.randomUUID(), null, List.of(a));
		List<UUID> all = List.of(a, b, p1, p2, p3, q, x, y);
		for (UUID r : all) rebom(org, r);
		rebom(orgB, other);
		return new Tree(org, component, a, b, p1, p2, p3, q, x, y, other, all);
	}

	private record Outcome(int count, Map<UUID, String> rows) {}

	/** Runs {@code update} in a transaction that is rolled back, returning its count and the rows after it. */
	private Outcome rolledBack(Tree t, Supplier<Integer> update) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		return tx.execute(status -> {
			int count = update.get();
			Map<UUID, String> rows = new HashMap<>();
			for (UUID r : List.of(t.a(), t.b(), t.p1(), t.p2(), t.p3(), t.q(), t.x(), t.y(), t.other())) {
				rows.put(r, jdbc.queryForObject("SELECT (record_data->'reboms')::text || '/' || revision"
						+ " FROM rearm.release_reboms WHERE record_data->>'release' = ?", String.class, r.toString()));
			}
			status.setRollbackOnly();
			return new Outcome(count, rows);
		});
	}

	private void assertSameWalk(Tree t, String[] seeds) {
		MapSqlParameterSource p = new MapSqlParameterSource("orgUuidAsString", t.org().toString())
				.addValue("seedReleaseUuidsAsStrings", seeds);
		Outcome expected = rolledBack(t, () -> named.update(OLD_INVALIDATE_RELEASE_REBOMS_OF_RELEASES_AND_ABOVE, p));
		Outcome actual = rolledBack(t,
				() -> releaseRebomRepository.invalidateRowsOfReleasesAndProductsAbove(t.org().toString(), seeds));
		assertEquals(expected, actual, "walk from " + Arrays.toString(seeds));
	}

	@Test
	void theRebomWalkMatchesTheOldFormThroughLevelsAndACycle() throws Exception {
		Tree t = tree();
		assertSameWalk(t, new String[] {t.a().toString()});
		assertSameWalk(t, new String[] {t.b().toString()});
		assertSameWalk(t, new String[] {t.q().toString(), t.p1().toString()});
		assertSameWalk(t, new String[] {t.x().toString()});
		assertSameWalk(t, new String[] {t.other().toString()});
		assertSameWalk(t, new String[] {id()});
		// what the fixture means: from a, every level above, through the cycle, and not other (org B)
		MapSqlParameterSource p = new MapSqlParameterSource("orgUuidAsString", t.org().toString())
				.addValue("seedReleaseUuidsAsStrings", new String[] {t.a().toString()});
		Outcome old = rolledBack(t, () -> named.update(OLD_INVALIDATE_RELEASE_REBOMS_OF_RELEASES_AND_ABOVE, p));
		assertEquals(6, old.count());
		assertEquals("[]/1", old.rows().get(t.x()));
		assertFalse(old.rows().get(t.q()).startsWith("[]"));
		assertFalse(old.rows().get(t.other()).startsWith("[]"));
	}

	@Test
	void theComponentSeededRebomWalkMatchesTheOldForm() throws Exception {
		Tree t = tree();
		MapSqlParameterSource p = new MapSqlParameterSource("orgUuidAsString", t.org().toString())
				.addValue("componentUuidAsString", t.component().toString());
		Outcome expected = rolledBack(t,
				() -> named.update(OLD_INVALIDATE_RELEASE_REBOMS_OF_COMPONENT_RELEASES_AND_ABOVE, p));
		Outcome actual = rolledBack(t, () -> releaseRebomRepository.invalidateRowsOfComponentReleasesAndProductsAbove(
				t.org().toString(), t.component().toString()));
		assertEquals(expected, actual);
		assertEquals(t.all().size(), expected.count());
	}

	private String jit() {
		return jdbc.queryForObject("SELECT current_setting('jit')", String.class);
	}

	/**
	 * Runs {@code work} in a read-only REQUIRES_NEW transaction, as the fan-out's own read transaction
	 * does, with jit set on first, and returns the setting read in that transaction after it.
	 */
	private String jitAfterIn(Runnable work, boolean rollBack) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.setReadOnly(!rollBack);
		return tx.execute(status -> {
			jdbc.execute("SET LOCAL jit = on");
			assertEquals("on", jit());
			work.run();
			String after = jit();
			if (rollBack) status.setRollbackOnly();
			return after;
		});
	}

	@Test
	void theBatchedGatherRunsWithJitOffForItsTransactionOnly() throws Exception {
		SceSeed s = sceSeed();
		String serverSetting = new TransactionTemplate(transactionManager).execute(status -> jit());
		UUID org = testInitializer.obtainOrganization().getUuid();
		List<UUID> artifacts = List.of(UUID.fromString(s.a1()), UUID.randomUUID());
		assertEquals("off", jitAfterIn(() -> sharedReleaseService.gatherReleaseIdsForArtifacts(artifacts, org), false));
		// in its own transaction when called outside one; nothing outlives it on the pooled connections
		assertEquals(Set.of(s.r1(), s.r2(), s.rOther()), sharedReleaseService.gatherReleaseIdsForArtifacts(artifacts, org));
		for (int i = 0; i < 10; i++) {
			assertEquals(serverSetting, new TransactionTemplate(transactionManager).execute(status -> jit()));
		}
	}

	@Test
	void theRebomWalkRunsWithJitOffForTheCallersTransaction() throws Exception {
		Tree t = tree();
		String serverSetting = new TransactionTemplate(transactionManager).execute(status -> jit());
		assertEquals("off", jitAfterIn(() -> releaseRebomService.invalidateRowsOfAndAbove(t.org(), List.of(t.a())), true));
		assertEquals("off", jitAfterIn(
				() -> releaseRebomService.invalidateRowsOfComponentReleasesAndAbove(t.org(), t.component()), true));
		assertEquals(serverSetting, new TransactionTemplate(transactionManager).execute(status -> jit()));
	}
}
