-- finding_change_events (board task #38, phase 1) -- diffs-only changelog source.
--
-- A write-time-emitted, one-row-per-change record of the deltas between two
-- consecutive metrics snapshots of the SAME release (re-scan driven). The
-- changelog will later (phase 3) read these lightweight deltas instead of
-- diffing the heavy metrics_audit JSONB snapshots at read time. This table is
-- the foundation for that re-source and supersedes the metrics_audit-read path
-- from #252.
--
-- Column-mapped (NOT the standard single-record_data JSONB shape): rows are
-- append-only derived facts queried by (org, change_date) for the org-level
-- changelog and by (release_uuid, change_date) per release. The denormalized
-- component_name / version are stamped at write time so the changelog read can
-- avoid re-resolving them.
--
-- NO FOREIGN KEYs (per coding_principles.md -- referential integrity is the
-- service layer's job; matches metrics_audit, which also carries none). Reads
-- tolerate dangling release/component references.

CREATE TABLE rearm.finding_change_events (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    entity_type text NOT NULL default 'RELEASE',
    release_uuid uuid NOT NULL,
    version text NOT NULL,
    component_uuid uuid NOT NULL,
    component_name text NOT NULL,
    change_date timestamptz NOT NULL,
    to_metrics_revision integer NOT NULL,
    change_kind text NOT NULL,    -- APPEARED / RESOLVED / SEVERITY_INCREASED / KEV_ADDED
    finding_kind text NOT NULL,   -- VULNERABILITY / VIOLATION / WEAKNESS
    finding_key text NOT NULL,
    purl text,
    vuln_id text,
    cwe_id text,
    rule_id text,
    location text,
    violation_type text,
    severity text,
    previous_severity text,       -- populated only for SEVERITY_INCREASED
    known_exploited boolean,
    analysis_state text,
    aliases jsonb,
    created_date timestamptz NOT NULL default now()
);

-- Org-level changelog read: all changes for an org over a date window.
CREATE INDEX finding_change_events_org_date_idx
    ON rearm.finding_change_events (org, change_date);

-- Per-release changelog read: a single release's change timeline.
CREATE INDEX finding_change_events_release_date_idx
    ON rearm.finding_change_events (release_uuid, change_date);

-- Idempotency key for the write-time ON CONFLICT DO NOTHING insert: re-saving
-- the same revision (e.g. a retried metrics save) must not duplicate rows.
-- One change row is uniquely identified by the revision it produced plus the
-- (changeKind, findingKind, findingKey) of the finding it concerns.
CREATE UNIQUE INDEX finding_change_events_dedup_idx
    ON rearm.finding_change_events (release_uuid, to_metrics_revision, change_kind, finding_kind, finding_key);
