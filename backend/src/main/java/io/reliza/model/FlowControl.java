/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Typed view of the {@code releases.flow_control} jsonb column. Holds
 * scheduling state for async flows that operate on a release. Today the
 * only consumer is the SBOM-component reconcile queue, drained by the
 * every-minute Dependency-Track scheduler.
 *
 * <p>Keys are kept flat (one level deep). Postgres {@code jsonb_set} with
 * {@code create_missing=true} only creates the leaf, not intermediate path
 * segments — nested objects can't be initialized in a single UPSERT, which
 * makes the partial-update SQL in {@code ReleaseRepository} significantly
 * uglier. Future flows pick their own prefixed key names (e.g.
 * {@code dtrackSync*}) at the same level.
 *
 * <p>Empty fields serialize as {@code null} and are dropped by
 * {@link JsonInclude.Include#NON_NULL} so the persisted jsonb stays
 * minimal — an unused flow contributes no keys to the column.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowControl(
        // Timestamps are stored as ISO-8601 strings rather than ZonedDateTime
        // because hypersistence-utils JsonBinaryType uses its own ObjectMapper
        // that doesn't have JavaTimeModule registered — round-tripping a
        // ZonedDateTime through Java-side serialization writes epoch fractions
        // (e.g. "1777147749.139109000") that Postgres can't cast back to
        // timestamptz in the scheduler pickup query. The SQL upserts already
        // write ISO strings via to_jsonb(now()), so keeping the Java type as
        // String matches the persisted shape exactly.
        String sbomReconcileRequestedAt,
        String sbomReconcileSkipUntil,
        Integer sbomReconcileFailureCount) implements Serializable {
}
