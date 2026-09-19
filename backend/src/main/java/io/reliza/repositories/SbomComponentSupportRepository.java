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

import io.reliza.model.SbomComponentSupport;

public interface SbomComponentSupportRepository extends CrudRepository<SbomComponentSupport, UUID> {

	Optional<SbomComponentSupport> findBySbomComponentUuid(UUID sbomComponentUuid);

	/** One row's raw payload, for the isolated bulk read below. */
	interface SupportPayloadRow {
		UUID getComponentUuid();
		String getPayload();
	}

	/**
	 * Bulk read as RAW JSON, so each payload can be parsed individually.
	 *
	 * <p>This exists because an entity-materializing bulk read deserializes the JSONB inside result-set construction: a single payload this
	 * build cannot read -- an unrecognised enum value after a rollback, or a hand-edited
	 * row -- throws and fails the ENTIRE batch. That batch is a whole release's component
	 * list, and on the export path it also contains encoding-variant fallback candidates
	 * that need not appear in the served BOM at all. So one poison attestation could 500 a
	 * BOM download for a release that does not even contain that component.
	 *
	 * <p>Reading text and parsing per row with {@link SupportData#parse} confines the
	 * damage to the offending component, which is excluded from the export and from
	 * coverage rather than taking the release down with it.
	 *
	 * <p>The ids arrive as ONE comma-joined string cast to a {@code uuid[]}. This was an
	 * {@code IN} list until 2026-09-04, which binds a parameter per element against the
	 * Postgres 65,535-parameter protocol cap -- and this is the EXPORT path, called with every
	 * component in a BOM plus the encoding-variant fallback candidates, so a large release
	 * would have failed the download rather than merely a gauge. Safe as text: UUIDs, never
	 * user input.
	 *
	 * <p>Scoped by {@code org} even though every current caller already passes an
	 * org-filtered id set. This method is now the SINGLE funnel for every bulk support
	 * read, so one future caller that forgets to scope its ids would leak silently; the
	 * table denormalizes org precisely so this costs nothing.
	 */
	@Query(value = """
			SELECT s.sbom_component_uuid AS componentUuid, s.support_data::text AS payload
			FROM rearm.sbom_component_support s
			WHERE s.org = CAST(:orgUuidAsString AS uuid)
			  AND s.sbom_component_uuid = ANY(CAST(string_to_array(:ids, ',') AS uuid[]))
			""", nativeQuery = true)
	List<SupportPayloadRow> findRawByComponentUuids(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("ids") String ids);

	/**
	 * Cheap existence probe for the export-injection hot path: does the org have ANY
	 * component carrying a support attestation? Lets a zero-support org skip the
	 * per-component resolution on every BOM download (the injector still runs, with no
	 * facts, to strip uploader-forged properties and stamp the disclosure marker).
	 * {@code org} is denormalized onto this table, so this needs no join and is served
	 * by {@code sbom_component_support_org_idx}.
	 */
	@Query(value = """
			SELECT EXISTS(
				SELECT 1 FROM rearm.sbom_component_support s
				WHERE s.org = CAST(:orgUuidAsString AS uuid))
			""", nativeQuery = true)
	boolean existsSupportByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Coverage numerator: non-root components carrying a MANUFACTURER (MANUAL)
	 * attestation. Deliberately NOT "any attestation": later slices auto-stamp
	 * SUPPLIER (at reconcile) and ENRICHED (the BEAR puller), which are
	 * machine/vendor-sourced rather than a manufacturer disclosure, and counting them
	 * would silently inflate a pre-submission readiness signal toward 100%.
	 *
	 * <p>Unlike its V901 predecessor this DOES count "assessed, nothing published" --
	 * a row with an empty {@code milestones} object. That is the whole point of the
	 * V83 shape: the manufacturer looked, and the diligence is the record. Recognising
	 * it needs the row-level {@code assessmentSource}, because such a row has no
	 * milestone provenance to inspect.
	 *
	 * <p>WITHDRAWN rows are EXCLUDED. A retracted attestation is not injected into any
	 * export ({@code SupportBomInjector}), so counting it here would have the readiness
	 * gauge report coverage the served BOM does not contain -- the one number whose job
	 * is to be trusted before a submission, disagreeing with the artifact itself.
	 *
	 * <p><b>Every enum domain in the payload is validated here, not just the level.</b> A
	 * row this build cannot parse is dropped from the export by the per-row read, so
	 * counting it would leave the gauge reporting coverage the served BOM omits -- the
	 * divergence the WITHDRAWN exclusion exists to prevent. An earlier revision guarded
	 * only {@code levelOfSupport}, which left the gap wide open for the case most likely to
	 * cause it: a rollback across a new {@code SupportMilestoneType}, which the payload's
	 * own javadoc calls forward-only.
	 *
	 * <p>The value sets are PASSED IN rather than hardcoded so they cannot drift from the
	 * Java enums. There are SIX domains, not four: the record-level level/party/state/source,
	 * the milestone map KEYS, and each milestone's OWN source. The last two are the ones a
	 * value-only check misses, and the per-milestone source was missed once already -- it
	 * lost its degrade-to-null with the others, so an unrecognised value there is just as
	 * fatal to the parse as a record-level one. When a new enum reaches this payload, it
	 * needs a predicate here or the gauge silently starts counting rows the BOM omits.
	 *
	 * <p>The {@code jsonb_typeof} guard is not decoration: {@code jsonb_each} raises
	 * "cannot call jsonb_each on a non-object" for a null or array {@code milestones},
	 * and because this is one aggregate over the whole org, a single malformed row
	 * would fail the query for EVERY component rather than just its own.
	 */
	/**
	 * The attested-non-root predicate, ONE body shared by both scopes.
	 *
	 * <p>It used to be copied into the org-wide and release-scoped queries. That is exactly
	 * the must-stay-in-step pair the sixth-enum-domain miss came from: a domain added to one
	 * copy and not the other splits the two gauges silently, and the copies were 40 lines
	 * apart. Two entry points are genuinely needed -- Postgres rejects an empty IN () so the
	 * release scope cannot be a nullable parameter on one method -- but that argues for two
	 * METHODS, not two BODIES. {@code @Query} takes a constant expression, so both are
	 * assembled from this.
	 */
	String NON_ROOT_CLAUSE = "AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'\n";

	/**
	 * The payload half of the predicate: everything that reads the support row, with no
	 * reference to {@code sc} beyond the join key. Split out from {@link #NON_ROOT_CLAUSE}
	 * so the release-page filter can reuse it inside an {@code EXISTS} / {@code NOT EXISTS}
	 * subquery, where a correlated isRoot test would invert (a root component satisfies
	 * NOT EXISTS trivially) and quietly readmit exactly the rows the gauge excludes.
	 *
	 * <p>Reassembled below into the original body, so the two count queries are unchanged.
	 */
	String ATTESTED_PAYLOAD_PREDICATE = """
			AND (s.support_data->>'state') IS DISTINCT FROM 'WITHDRAWN'
			AND ((s.support_data->>'levelOfSupport') IS NULL
			OR (s.support_data->>'levelOfSupport') IN (:validLevels))
			AND ((s.support_data->>'party') IS NULL
			OR (s.support_data->>'party') IN (:validParties))
			AND ((s.support_data->>'state') IS NULL
			OR (s.support_data->>'state') IN (:validStates))
			AND ((s.support_data->>'assessmentSource') IS NULL
			OR (s.support_data->>'assessmentSource') IN (:validSources))
			AND NOT EXISTS (
			SELECT 1 FROM jsonb_object_keys(
			CASE WHEN jsonb_typeof(s.support_data->'milestones') = 'object'
			THEN s.support_data->'milestones' ELSE '{}'::jsonb END) AS k
			WHERE k NOT IN (:validMilestoneTypes))
			AND NOT EXISTS (
			SELECT 1 FROM jsonb_each(
			CASE WHEN jsonb_typeof(s.support_data->'milestones') = 'object'
			THEN s.support_data->'milestones' ELSE '{}'::jsonb END) AS m(k, v)
			WHERE (v->>'source') IS NOT NULL
			AND (v->>'source') NOT IN (:validSources))
			AND (
			s.support_data->>'assessmentSource' = 'MANUAL'
			OR EXISTS (
			SELECT 1 FROM jsonb_each(
			CASE WHEN jsonb_typeof(s.support_data->'milestones') = 'object'
			THEN s.support_data->'milestones' ELSE '{}'::jsonb END) AS m(k, v)
			WHERE v->>'source' = 'MANUAL')
			)
			""";

	String ATTESTED_NON_ROOT_PREDICATE = NON_ROOT_CLAUSE + ATTESTED_PAYLOAD_PREDICATE;

	String COUNT_ATTESTED_HEAD = """
			SELECT count(*) FROM rearm.sbom_components sc
			JOIN rearm.sbom_component_support s ON s.sbom_component_uuid = sc.uuid
			WHERE sc.org = CAST(:orgUuidAsString AS uuid)
			""";

	/**
	 * Coverage numerator, org-wide. Every enum domain in the payload is validated, not just
	 * the level: a row this build cannot parse is dropped from the export by the per-row read,
	 * so counting it would leave the gauge reporting coverage the served BOM omits.
	 */
	@Query(value = COUNT_ATTESTED_HEAD + ATTESTED_NON_ROOT_PREDICATE, nativeQuery = true)
	long countAttestedNonRootByOrg(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("validLevels") Collection<String> validLevels,
			@Param("validParties") Collection<String> validParties,
			@Param("validStates") Collection<String> validStates,
			@Param("validSources") Collection<String> validSources,
			@Param("validMilestoneTypes") Collection<String> validMilestoneTypes);

	/**
	 * Same numerator, restricted to a release's own components.
	 *
	 * <p>The ids arrive as ONE comma-joined string cast to a uuid[], not as an IN list. A
	 * PRODUCT unwind over a large release can reach thousands of components, and the Postgres
	 * JDBC protocol caps a statement at 65,535 bound parameters -- an IN list binds one
	 * parameter per element and would fail in production on exactly the releases this feature
	 * exists to serve. Safe to build as text because the values are UUIDs, never user input.
	 */
	@Query(value = COUNT_ATTESTED_HEAD
			+ "  AND s.sbom_component_uuid = ANY(CAST(string_to_array(:componentUuids, ',') AS uuid[]))\n"
			+ ATTESTED_NON_ROOT_PREDICATE, nativeQuery = true)
	long countAttestedNonRootByOrgAndComponentUuidIn(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("componentUuids") String componentUuids,
			@Param("validLevels") Collection<String> validLevels,
			@Param("validParties") Collection<String> validParties,
			@Param("validStates") Collection<String> validStates,
			@Param("validSources") Collection<String> validSources,
			@Param("validMilestoneTypes") Collection<String> validMilestoneTypes);
}
