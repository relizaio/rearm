/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SbomComponent;

public interface SbomComponentRepository extends CrudRepository<SbomComponent, UUID> {

	Optional<SbomComponent> findByOrgAndCanonicalPurl(UUID org, String canonicalPurl);

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
				AND sc.synthetic_bucket_index IS NULL)
		""",
		nativeQuery = true)
	boolean existsUnbucketedMatchableByOrg(@Param("orgUuidAsString") String orgUuidAsString);
}
