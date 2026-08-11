-- Per-canonical-artifact flow control, mirroring rearm.releases.flow_control.
--
-- Drives the stale-canonical-qualifier sweep (SbomComponentService
-- .sweepStaleCanonicalQualifiers). Rows written before the qualifier-preserving
-- canonicalization (rearm#181 / rearm-saas#281) carry an sbom_components
-- canonical_purl with the identity-bearing qualifiers stripped, which merges
-- distinct distro branches into one component identity and lets
-- Dependency-Track apply advisories fixed in another branch (the OSV fixed:0
-- false-positive class). V66 repaired that with a DELETE + org-wide release
-- re-enqueue; that pass expands the whole estate at once and timed out on
-- large instances.
--
-- Why the marker lives here rather than on artifact_sbom_components: the
-- repair unit is the canonical artifact (one BOM parse), and this table holds
-- one row per artifact rather than one per component per artifact -- on a
-- reference instance 1.4k rows against 56k, so both the column and its index
-- land on a small table. artifact_sbom_components is never altered.
--
-- The marker is a jsonb object rather than a scalar column so later per-artifact
-- queue state can share it the way releases.flow_control already carries the
-- sbomReconcile*/autoIntegrate*/metricsCompute* keys.
--
-- canonicalFormVersion is the canonical-purl form a row has been verified
-- against (absent = never verified, treated as 0). Bumping
-- CANONICAL_FORM_VERSION in SbomComponentService re-arms the whole estate
-- lazily, which is what makes this converge again the next time
-- CANONICAL_PRESERVED_QUALIFIERS gains a type or a qualifier -- a migration
-- can only fix the epoch it ships in, and in particular cannot catch rows
-- written stripped afterwards by a backend running against an older rebom.

ALTER TABLE rearm.artifact_canonical_map
    ADD COLUMN flow_control jsonb;

-- Sweep pickup index. Expression (not partial) on the coalesced version so a
-- version bump needs no new index: when the estate is fully swept every row
-- sits at the current version and the "< version" range scan terminates
-- immediately. All three of ->>, ::int and coalesce are immutable, so the
-- expression is indexable.
--
-- Built non-concurrently: the table is one row per artifact and is only ever
-- inserted into (nothing in the application deletes from it), so the write
-- lock is short even on large instances.
CREATE INDEX artifact_canonical_map_canonical_form_version_idx
    ON rearm.artifact_canonical_map
    ((coalesce((flow_control->>'canonicalFormVersion')::int, 0)));
