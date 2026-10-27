/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
// Tolerate unknown keys so a release whose flow_control carries keys this
// version doesn't model yet (e.g. a newer flow's marker, or pgjdbc's
// PGobject {"type":"jsonb",...} envelope that hypersistence-utils can leak
// on round-trip) still deserializes into the entity instead of throwing
// UnrecognizedPropertyException at load and failing the whole findById.
@JsonIgnoreProperties(ignoreUnknown = true)
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
        Integer sbomReconcileFailureCount,
        // One-shot marker for the once-per-release BOM-diff (SBOM components
        // changelog) notification. Stamped the first time a reconcile drains
        // a release at lifecycle >= ASSEMBLED, so the alert fires exactly once
        // regardless of how many reconciles run over the release's lifetime.
        // firstScanned-style: presence means "already evaluated", independent
        // of whether the diff was non-empty. Survives clearSbomReconcileRequested
        // (which strips only the sbomReconcile* keys).
        String bomDiffNotifiedAt,
        // Product auto-integration queue (mirrors the sbomReconcile* keys).
        // Stamped by ReleaseRepository.markAutoIntegrateRequested when a
        // release needs its dependent product feature sets auto-integrated,
        // drained after-commit on the bounded autoIntegrateExecutor and by
        // the per-minute scheduler. autoIntegrateSkipUntil holds the
        // failure-backoff fence ONLY -- "this failed, do not retry before X" --
        // and autoIntegrateFailureCount the attempt count that sizes it. The
        // in-flight LEASE is autoIntegrateClaimedAt below, deliberately a
        // separate key: while the two shared skipUntil, any write clearing the
        // fence also cancelled a running worker's lease and let a second worker
        // claim the same release.
        String autoIntegrateRequestedAt,
        String autoIntegrateSkipUntil,
        Integer autoIntegrateFailureCount,
        // Stamped by claimAutoIntegrate when a worker takes the lease; the lease
        // EXPIRES at claimedAt + AUTO_INTEGRATE_LEASE_SECONDS, which is what both
        // the claim and the scheduler drain test, so a crashed run frees itself.
        // The post-run conditional clear compares it against autoIntegrateRequestedAt:
        // a request newer than the claim arrived mid-run and must survive the
        // clear (lost-wakeup guard), so only the lease/bookkeeping is dropped.
        String autoIntegrateClaimedAt,
        // Metrics-compute backoff fence (mirrors the sbomReconcile*/autoIntegrate*
        // skip pattern). Set when a scannable release's metrics compute ends
        // incomplete (own BOM or a child release still unscanned) or throws, so
        // the release stops occupying the per-tick finder slots every minute
        // while it waits — without a fence, permanently-incomplete rows are the
        // oldest rows in every finder's ORDER BY and starve everything behind
        // them. The row stays finder-eligible (no lastScanned is stamped);
        // the fence only spaces out retries. Cleared on a complete compute, and
        // dropped with a re-queue (touchForMetricsRecompute) when a child's
        // metrics change pushes its containing products
        // (markContainingProductsStale), so parents heal on the next tick instead
        // of waiting out the backoff; a product at the hourly cap re-queues its
        // settled unscanned children the same way (TPF-68).
        String metricsComputeSkipUntil,
        Integer metricsComputeFailureCount,
        // TEA act queue (task TEA-7; mirrors the autoIntegrate* keys). Stamped by
        // ReleaseRepository.requestTeaAct when a TEA_PUBLISH or TEA_HIDE output event
        // fires: one pending act per release, a newer request overwriting the kind, the
        // trigger and the comment of an unclaimed one. Run after the pass commits on the
        // bounded teaActExecutor and by the per-minute drain (TeaOutputEventService).
        // teaActClaimedAt is the worker's lease, which expires at claimedAt +
        // TEA_ACT_LEASE_SECONDS; teaActSkipUntil is the retry fence ONLY (a publish in
        // progress elsewhere, an unexpected exception) and teaActFailureCount the attempts
        // that ended in it. The conditional clear keeps a request newer than the claim
        // (lost-wakeup guard), exactly as for auto-integrate.
        String teaActRequestedAt,
        TeaActKind teaActKind,
        UUID teaActTrigger,
        String teaActTriggerName,
        String teaActComment,
        String teaActClaimedAt,
        String teaActSkipUntil,
        Integer teaActFailureCount) implements Serializable {

    /** What a queued TEA act does; Jackson writes the name into the jsonb and the SQL compares the same name. */
    public enum TeaActKind { PUBLISH, HIDE }
}
