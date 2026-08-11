/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.SbomComponent;

public interface SbomComponentRepository extends CrudRepository<SbomComponent, UUID> {

	Optional<SbomComponent> findByOrgAndCanonicalPurl(UUID org, String canonicalPurl);

	/**
	 * Encoding-independent candidate lookup for the canonical-qualifier sweep's
	 * mint path. rebom's persisted canonicals are NOT byte-consistent across eras
	 * (measured live: {@code base-files@12.4%2Bdeb12u13} encoded vs
	 * {@code libstdc%2B%2B6@12.2.0-14+deb12u1} raw {@code +} in the version of the
	 * same estate), so a byte-equality probe can miss an existing row and mint an
	 * encoding-variant duplicate. record_data stores DECODED name/version, which
	 * makes it the encoding-independent key; the caller filters the candidates
	 * with a semantic purl comparison. Unindexed jsonb probe -- acceptable because
	 * this only runs when a repair is about to create a row, which in steady state
	 * is never.
	 */
	@Query(value = """
			SELECT * FROM rearm.sbom_components
			WHERE org = :org
			  AND record_data->>'name' = :name
			  AND ((:version IS NULL AND record_data->>'version' IS NULL)
			       OR record_data->>'version' = :version)
			LIMIT 50
			""", nativeQuery = true)
	List<SbomComponent> findCandidatesByOrgNameVersion(
			@Param("org") UUID org, @Param("name") String name, @Param("version") String version);

	@Query(
		value = "SELECT * FROM rearm.sbom_components WHERE org = CAST(:orgUuidAsString AS uuid) AND canonical_purl IN (:canonicalPurls)",
		nativeQuery = true)
	List<SbomComponent> findByOrgAndCanonicalPurlIn(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("canonicalPurls") Collection<String> canonicalPurls);

	/**
	 * Search canonical sbom_components scoped to an org. With per-org pinning
	 * the org filter is a direct column match — no join through
	 * release_sbom_components. Version filter is optional; pass null to match
	 * any version.
	 */
	@Query(
		value = """
			SELECT *
			FROM rearm.sbom_components
			WHERE org = CAST(:orgUuidAsString AS uuid)
			AND record_data->>'name' = :name
			AND (CAST(:version AS text) IS NULL OR record_data->>'version' = :version)
		""",
		nativeQuery = true)
	List<SbomComponent> searchByOrgAndNameAndOptionalVersion(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("name") String name,
			@Param("version") String version);

	/**
	 * Version-agnostic purl search: every canonical component sharing one
	 * type/namespace/name coordinate, whatever its version. Backs a pasted
	 * versionless purl ({@code pkg:npm/lodash}), which is the shape advisories
	 * quote.
	 *
	 * <p>The {@code '@'} anchor on the LIKE is load-bearing -- without it
	 * {@code pkg:npm/lodash} would also match {@code pkg:npm/lodash-es@1.0.0}.
	 * The equality arm catches a stored component that genuinely has no version.
	 *
	 * <p>Two forms of the same coordinate are passed deliberately:
	 * {@code basePurl} raw for the equality arm, {@code basePurlLike}
	 * LIKE-escaped for the pattern arm. Purls routinely contain {@code _},
	 * which is a single-character LIKE wildcard, so the pattern arm would
	 * otherwise over-match.
	 *
	 * <p>Bounded by LIMIT: a prefix LIKE cannot use the
	 * {@code (org, canonical_purl)} unique btree under a non-C collation, so
	 * this is a scan of the org's components. The cap keeps a pathological
	 * coordinate (thousands of versions) from returning an unbounded payload
	 * to a search box.
	 */
	@Query(
		value = """
			SELECT *
			FROM rearm.sbom_components
			WHERE org = CAST(:orgUuidAsString AS uuid)
			AND (canonical_purl = :basePurl
			     OR canonical_purl LIKE :basePurlLike || '@%' ESCAPE '\\')
			ORDER BY canonical_purl ASC
			LIMIT 500
		""",
		nativeQuery = true)
	List<SbomComponent> searchByOrgAndCanonicalPurlCoordinate(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("basePurl") String basePurl,
			@Param("basePurlLike") String basePurlLike);

	/**
	 * The matchable population for an org's synthetic Dependency-Track buckets:
	 * canonical components keyed on a purl or cpe (the only schemes DTrack can
	 * match advisories against). Ordered by canonical_purl so bucket membership
	 * is deterministic across runs — the basis for the per-bucket content hash.
	 *
	 * <p>Used for orgs WITHOUT BEAR enrichment configured: there is no enrichment
	 * to wait on, so every matchable component ships immediately.
	 *
	 * <p>Root/self components ({@code record_data.isRoot = true} — the release's own
	 * artifact coordinate, synthesised from {@code bom.metadata.component}) are
	 * excluded here and in the enriched / enrichment-candidate variants below. They
	 * are the app itself, not third-party dependencies to scan; BEAR never enriches
	 * them, so in a BEAR-gated org they would otherwise stay un-enriched forever,
	 * never ship, and permanently block their artifact's synthetic-DTrack coverage
	 * (the SBOM would sit on "scan pending").
	 */
	@Query(
		value = """
			SELECT sc.*
			FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
			ORDER BY sc.canonical_purl ASC
		""",
		nativeQuery = true)
	List<SbomComponent> findMatchableByOrgOrdered(
			@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Matchable population for orgs WITH BEAR enrichment configured: only ship a
	 * component once its enriched licenses have been pulled (enriched_at set), so
	 * Dependency-Track always receives enriched licenses. Same deterministic order
	 * as {@link #findMatchableByOrgOrdered}.
	 */
	@Query(
		value = """
			SELECT sc.*
			FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
			AND sc.enriched_at IS NOT NULL
			ORDER BY sc.canonical_purl ASC
		""",
		nativeQuery = true)
	List<SbomComponent> findEnrichedMatchableByOrgOrdered(
			@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Enrichment-puller candidates: un-enriched matchable components for an org,
	 * oldest first, capped at {@code lim}. The puller resolves each to a BOM,
	 * probes rebom, and on COMPLETED pulls enriched licenses for the whole BOM.
	 */
	@Query(
		value = """
			SELECT sc.*
			FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
			AND sc.enriched_at IS NULL
			ORDER BY sc.created_date ASC
			LIMIT :lim
		""",
		nativeQuery = true)
	List<SbomComponent> findUnenrichedMatchableByOrgOrdered(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("lim") int lim);

	/**
	 * Cheap idle-skip probe for the synthetic-DTrack scheduler: true when the org
	 * has a matchable component not yet assigned to a bucket (a new or
	 * just-enriched component that {@code submitOrg} still needs to bucket and
	 * ship). Backed by the partial {@code sbom_components_unbucketed_idx}, so it's
	 * an empty-index hit in steady state. Bear-agnostic by design — see
	 * {@code SyntheticSbomService.hasPendingSyntheticWork}.
	 */
	@Query(
		value = """
			SELECT EXISTS(
				SELECT 1
				FROM rearm.sbom_components sc
				WHERE sc.org = CAST(:orgUuidAsString AS uuid)
				AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
				AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
				AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
				AND sc.synthetic_bucket_index IS NULL)
		""",
		nativeQuery = true)
	boolean existsUnbucketedMatchableByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * GC pass for orphaned canonical components: rows no
	 * {@code artifact_sbom_components} mapping references AND no synthetic
	 * bucket carries. These are pure debris -- chiefly the stripped-era rows the
	 * canonical-qualifier sweeper deliberately left behind when repointing
	 * mappings (rearm-saas#340) -- and they are harmful in aggregate: they
	 * occupy the enrichment candidate window and the stall counts forever, and
	 * because they have no representative BOM ("no resolvable BOM" in the stall
	 * diagnostics) no pull can ever enrich them directly. Deleting is safe by
	 * construction: unreferenced means no artifact attributes findings through
	 * them, and unbucketed means no bucket membership, content hash, or ref_map
	 * knows them. Bounded per tick; self-cleans after any future sweep too.
	 *
	 * <p>Known cosmetic residue: parents jsonb edges on OTHER rows may hold this
	 * row's uuid (display-only; the #340 sweep rewrites stale edges as it goes).
	 */
	@Modifying
	@Transactional
	@Query(value = """
			DELETE FROM rearm.sbom_components sc
			WHERE sc.uuid IN (
			    SELECT s.uuid FROM rearm.sbom_components s
			    WHERE s.synthetic_bucket_index IS NULL
			      AND NOT EXISTS (SELECT 1 FROM rearm.artifact_sbom_components a
			                      WHERE a.sbom_component_uuid = s.uuid)
			    LIMIT :lim)
			""", nativeQuery = true)
	int deleteOrphanedUnbucketedComponents(@Param("lim") int lim);

	/**
	 * Mark a component enrichment-terminal (see V75 / SbomComponentFlowControl).
	 * Merge-write so future flow_control keys survive; only ever fires on rows
	 * that are still un-enriched (the caller checks), and enriched_at stays NULL.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.sbom_components
			SET flow_control = coalesce(flow_control, '{}'::jsonb)
			                   || jsonb_build_object(
			                        'enrichmentTerminalAt', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SSOF'),
			                        'enrichmentTerminalReason', cast(:reason as text))
			WHERE uuid = :uuid AND enriched_at IS NULL
			""", nativeQuery = true)
	int markEnrichmentTerminal(@Param("uuid") UUID uuid, @Param("reason") String reason);

	/** Diagnostics: how many of the org's components are enrichment-terminal. */
	@Query(value = """
			SELECT count(*) FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			  AND sc.flow_control->>'enrichmentTerminalAt' IS NOT NULL
			""", nativeQuery = true)
	long countEnrichmentTerminal(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Stall diagnostic: backlog-wide count of un-enriched matchable components
	 * with no resolvable BOM (no mapping row). Before the GC drains them, these
	 * are permanent window residents; the count trending to zero is the GC
	 * working. Rate-limited report cadence only.
	 */
	@Query(value = """
			SELECT count(*) FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			  AND sc.enriched_at IS NULL
			  AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			  AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			  AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
			  AND NOT EXISTS (SELECT 1 FROM rearm.artifact_sbom_components a
			                  WHERE a.sbom_component_uuid = sc.uuid)
			""", nativeQuery = true)
	long countUnresolvableUnenriched(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Batched coordinate-candidate fetch for the enrichment stamping pass: all
	 * of the org's components whose decoded name matches any of {@code names},
	 * in ONE statement per pulled BOM. The per-canonical variant
	 * ({@code findCandidatesByOrgNameVersion}, kept for the sweeper's rare mint
	 * path) filters on unindexed jsonb and was being called once per canonical
	 * of every pulled BOM after #349 -- ~300 org-wide scans per BOM on the
	 * shared scheduler tick during a backlog drain. This form is one scan per
	 * BOM; the caller matches version + coordinates in Java. Chosen over an
	 * expression index after review: the cost is drain-time only (steady state
	 * pulls nothing), so permanent DDL was not warranted.
	 */
	@Query(value = """
			SELECT * FROM rearm.sbom_components
			WHERE org = :org
			  AND record_data->>'name' IN (:names)
			LIMIT 5000
			""", nativeQuery = true)
	List<SbomComponent> findCandidatesByOrgAndNames(
			@Param("org") UUID org, @Param("names") Collection<String> names);

	/**
	 * Stall diagnostic: matchable components that are still unbucketed well
	 * after creation.
	 *
	 * <p>Bucket assignment is local and immediate (assignStickyBuckets runs
	 * every tick and does no I/O), so a matchable component that is STILL
	 * unbucketed hours later is not normal transience -- it means
	 * {@code submitOrg} keeps excluding it, overwhelmingly because the BEAR
	 * gate only ships components whose {@code enriched_at} is set. Those
	 * components are never covered by an INGESTED bucket, so every artifact
	 * containing one is rejected by fan-out's coverage gate indefinitely.
	 *
	 * <p>Returns {@code [count, oldest_created_date, unenriched_count]} so the
	 * caller can say how many, since when, and whether enrichment is the
	 * cause. Backed by the same partial index as the dirty-check probe.
	 */
	@Query(
		value = """
			SELECT count(*) AS stale_count,
			       min(sc.created_date) AS oldest_created,
			       count(*) FILTER (WHERE sc.enriched_at IS NULL) AS unenriched_count
			FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
			AND sc.synthetic_bucket_index IS NULL
			AND sc.created_date < :cutoff
		""",
		nativeQuery = true)
	Object[] summarizeStaleUnbucketedMatchable(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("cutoff") java.time.ZonedDateTime cutoff);

	/** A sample stuck component, for a log line an operator can act on. */
	@Query(
		value = """
			SELECT sc.canonical_purl
			FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			AND sc.flow_control->>'enrichmentTerminalAt' IS NULL
			AND sc.synthetic_bucket_index IS NULL
			AND sc.created_date < :cutoff
			ORDER BY sc.created_date ASC
			LIMIT 1
		""",
		nativeQuery = true)
	String findOldestStaleUnbucketedPurl(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("cutoff") java.time.ZonedDateTime cutoff);
}
