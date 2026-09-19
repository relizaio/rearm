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
import io.reliza.model.SupportAttestationFilter;

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
	 * <p><b>A component carrying a support attestation is never collected</b>, even
	 * when it is otherwise orphaned: deleting it would destroy the regulatory record
	 * keyed on its uuid. That is a stay of execution and NOT a repair. The row became
	 * orphaned because the canonical sweeper repointed its mappings elsewhere, so
	 * pinning it converts a detectable orphan into permanent debris -- present in no
	 * UI and no export, and now immortal. {@code sbom_component_support.canonical_purl}
	 * is stored so a re-link pass can reunite the attestation with the live component,
	 * but THAT PASS IS NOT WRITTEN YET. Until it is, this clause trades a silent
	 * data-loss bug for a visible leak, which is the right way round but is not done.
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
			      AND NOT EXISTS (SELECT 1 FROM rearm.sbom_component_support sup
			                      WHERE sup.sbom_component_uuid = s.uuid)
			    LIMIT :lim)
			""", nativeQuery = true)
	int deleteOrphanedUnbucketedComponents(@Param("lim") int lim);

	String COUNT_NON_ROOT_HEAD = """
			SELECT count(*) FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			""";

	/**
	 * One clause behind BOTH gauges and the page filter -- aliased to
	 * {@link SbomComponentSupportRepository#NON_ROOT_CLAUSE} rather than restated.
	 *
	 * <p>It used to be a second copy of the same text, 350 lines from the first, under a
	 * javadoc claiming a change could not reach one gauge and not the other. That claim was
	 * false as written: there were two bodies, so a change to the definition of "root" would
	 * have moved the coverage denominator and left the page's population behind, and the two
	 * numbers sit next to each other in the UI.
	 */
	String NON_ROOT_PREDICATE = SbomComponentSupportRepository.NON_ROOT_CLAUSE;

	/**
	 * Shared WHERE body for the release component page and its count.
	 *
	 * <p>ONE body for both, because a page whose total is computed by a second predicate
	 * reports "showing 1-50 of 312" over a different 312 than it is paging through.
	 *
	 * <p>The attestation test reuses
	 * {@link SbomComponentSupportRepository#ATTESTED_PAYLOAD_PREDICATE} verbatim, once under
	 * EXISTS and once under NOT EXISTS, so UNATTESTED is the exact complement of ATTESTED and
	 * both agree with the coverage gauge by construction rather than by review. The text
	 * appears twice in the statement but comes from one constant, so the halves cannot drift.
	 *
	 * <p>Root components are excluded here as they are from the gauge's denominator: a root
	 * is the release's own artifact coordinate, it is never a third-party dependency to
	 * disclose, and bulk attestation reports SKIPPED_ROOT for it. This is why the page is a
	 * separate query from the unpaged graph list, which keeps the root node.
	 *
	 * <p>Ids arrive comma-joined and cast to uuid[] rather than as an IN list: a PRODUCT
	 * unwind can reach thousands of components and the Postgres JDBC protocol caps a
	 * statement at 65,535 bound parameters. Safe as text -- the values are UUIDs.
	 */
	String RELEASE_PAGE_WHERE = """
			FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND sc.uuid = ANY(CAST(string_to_array(:componentUuids, ',') AS uuid[]))
			"""
			+ SbomComponentSupportRepository.NON_ROOT_CLAUSE
			+ """
			AND (CAST(:searchLike AS text) IS NULL
			OR sc.canonical_purl ILIKE CAST(:searchLike AS text) ESCAPE '!')
			"""
			// Assembled with explicit concatenation rather than inside the text block: a
			// text-block seam would put a newline INSIDE the quoted SQL literal, and
			// 'ALL\n' never equals 'ALL'. That failure is silent -- every branch false,
			// zero rows, no error.
			+ "AND (CAST(:attestation AS text) = '" + SupportAttestationFilter.CODE_ALL + "'\n"
			+ "OR (CAST(:attestation AS text) = '" + SupportAttestationFilter.CODE_ATTESTED
			+ "' AND EXISTS (\n"
			+ "SELECT 1 FROM rearm.sbom_component_support s WHERE s.sbom_component_uuid = sc.uuid\n"
			+ SbomComponentSupportRepository.ATTESTED_PAYLOAD_PREDICATE
			+ "))\n"
			+ "OR (CAST(:attestation AS text) = '" + SupportAttestationFilter.CODE_UNATTESTED
			+ "' AND NOT EXISTS (\n"
			+ "SELECT 1 FROM rearm.sbom_component_support s WHERE s.sbom_component_uuid = sc.uuid\n"
			+ SbomComponentSupportRepository.ATTESTED_PAYLOAD_PREDICATE
			+ ")))\n";

	/**
	 * The keyset cursor clause. Deliberately NOT part of {@link #RELEASE_PAGE_WHERE}: the
	 * count must describe the whole filtered population, not the tail after the cursor, or
	 * the footer's total would shrink as the caller walks.
	 *
	 * <p>Row-value comparison against the SAME composite the ORDER BY uses, which is what
	 * makes the cursor exact rather than approximate. Both columns are NOT NULL in the
	 * schema, so the comparison cannot go three-valued and silently drop a row.
	 */
	String RELEASE_PAGE_AFTER = """
			AND (CAST(:afterPurl AS text) IS NULL
			OR (sc.canonical_purl, sc.uuid) > (CAST(:afterPurl AS text), CAST(:afterUuid AS uuid)))
			""";

	/**
	 * One page of a release's non-root component ids, filtered, ordered and cursored in SQL.
	 *
	 * <p>Returns ids, not rows: the caller hydrates only the page. Filtering in Java is not
	 * an option here -- the merged release row carries uuids and dependency edges and nothing
	 * else, so purl and attestation state only exist once the two hydration loads have run,
	 * which is exactly the cost pagination is meant to avoid paying for the whole BOM.
	 *
	 * <p>KEYSET, not OFFSET, and the reason is specific to this filter. UNATTESTED is a
	 * predicate over MUTABLE state: attesting a page removes those rows from the set, so an
	 * offset would index into a set that has shifted underneath it and skip exactly as many
	 * components as were just written. A cursor anchored to (canonical_purl, uuid) is
	 * unaffected by rows disappearing BEHIND it, so a "select all unattested" walk visits
	 * every row exactly once with no client-side protocol to get wrong.
	 *
	 * <p>Ordered by canonical_purl with uuid as tiebreak -- the same composite the cursor
	 * compares. An earlier revision justified the tiebreak by claiming canonical_purl is not
	 * unique per org; that is false. sbom_components_org_canonical_purl_unique makes
	 * (org, canonical_purl) unique (V28, restated in V37), and this query pins sc.org, so
	 * within one result set the purl is already a total order and the tiebreak never fires.
	 * It stays because the ORDER BY and the cursor comparison must use the SAME composite,
	 * and pinning the primary key into both is what makes that true by construction rather
	 * than by depending on a constraint in another file. Drop the unique constraint and this
	 * query is still exact.
	 *
	 * <p>Fetches {@code lim} rows; the caller asks for one more than the page size to learn
	 * whether another page exists without a second query.
	 */
	@Query(value = "SELECT sc.uuid " + RELEASE_PAGE_WHERE + RELEASE_PAGE_AFTER
			+ " ORDER BY sc.canonical_purl ASC, sc.uuid ASC LIMIT :lim",
			nativeQuery = true)
	List<UUID> findReleaseComponentPage(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("componentUuids") String componentUuids,
			@Param("searchLike") String searchLike,
			@Param("attestation") String attestation,
			@Param("validLevels") Collection<String> validLevels,
			@Param("validParties") Collection<String> validParties,
			@Param("validStates") Collection<String> validStates,
			@Param("validSources") Collection<String> validSources,
			@Param("validMilestoneTypes") Collection<String> validMilestoneTypes,
			@Param("afterPurl") String afterPurl,
			@Param("afterUuid") UUID afterUuid,
			@Param("lim") int lim);

	/** Total matching the same filter, for the page footer. Same body as the page. */
	@Query(value = "SELECT count(*) " + RELEASE_PAGE_WHERE, nativeQuery = true)
	long countReleaseComponentPage(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("componentUuids") String componentUuids,
			@Param("searchLike") String searchLike,
			@Param("attestation") String attestation,
			@Param("validLevels") Collection<String> validLevels,
			@Param("validParties") Collection<String> validParties,
			@Param("validStates") Collection<String> validStates,
			@Param("validSources") Collection<String> validSources,
			@Param("validMilestoneTypes") Collection<String> validMilestoneTypes);

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

	/**
	 * Support-disclosure coverage denominator: the org's non-root components (roots
	 * are the app itself, not third-party dependencies to attest).
	 */
	@Query(value = COUNT_NON_ROOT_HEAD + NON_ROOT_PREDICATE, nativeQuery = true)
	long countNonRootByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Same denominator, restricted to a release's own components. The id set is resolved in
	 * Java by {@code SbomComponentService.findReleaseComponentUuids} and passed in, rather
	 * than joined here: the release-to-component path unwinds PRODUCT dependencies and is not
	 * expressible as one join, so re-deriving it in SQL would let the gauge drift from the
	 * component list it sits above.
	 *
	 * <p>The ids arrive as ONE comma-joined string cast to a {@code uuid[]}, not as an
	 * {@code IN} list. A PRODUCT unwind over a large release can reach thousands of
	 * components, and the Postgres JDBC protocol caps a statement at 65,535 bound parameters
	 * -- an {@code IN} list binds one per element and would fail on exactly the large releases
	 * this feature exists to serve. Safe to build as text because the values are UUIDs,
	 * never user input.
	 */
	@Query(value = COUNT_NON_ROOT_HEAD
			+ "  AND sc.uuid = ANY(CAST(string_to_array(:componentUuids, ',') AS uuid[]))\n"
			+ NON_ROOT_PREDICATE, nativeQuery = true)
	long countNonRootByOrgAndComponentUuidIn(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("componentUuids") String componentUuids);

	/**
	 * Load components by id, scoped to an org, with the id set passed as ONE comma-joined
	 * string cast to a {@code uuid[]}.
	 *
	 * <p>Replaces a {@code findAllById} on the write path. Derived {@code IN} lists bind one
	 * parameter per element and the Postgres JDBC protocol caps a statement at 65,535 of
	 * them, so a large enough call fails outright -- on exactly the bulk attestation this
	 * feature exists to serve. The same substitution was already made one method away in
	 * {@link SbomComponentSupportRepository#findRawByComponentUuids} for the read side; this
	 * is the write side of the same bug.
	 *
	 * <p>The org filter is IN THE QUERY rather than applied to the results afterwards. The
	 * caller previously loaded every requested row and then dropped the foreign ones in Java,
	 * which is a correct answer reached by reading rows it had no business reading. Safe to
	 * build the id list as text because the values are UUIDs, parsed by the GraphQL layer
	 * before they reach here, never free-form user input.
	 */
	@Query(value = """
			SELECT sc.* FROM rearm.sbom_components sc
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			AND sc.uuid = ANY(CAST(string_to_array(:componentUuids, ',') AS uuid[]))
			""", nativeQuery = true)
	List<SbomComponent> findByOrgAndUuidIn(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("componentUuids") String componentUuids);

}
