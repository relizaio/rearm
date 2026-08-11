-- finding_change_events v3 -- "events-lite": BRANCH-GRAIN fact rows (board task #38 follow-on,
-- fact-row dedup). ADDITIVE + METADATA-ONLY (empty CREATE TABLE + indexes) -- safe at boot, never
-- touches the ~900k-row live v1/v2 tables. Population, dual-write, read-flip and the eventual drop of
-- v1/v2 are all app-owned, post-boot, per-org-gated steps (later stages), NOT Flyway.
--
-- WHY. finding-dim v2 (V57) dedups finding METADATA (identity stored once in finding_dim). It does
-- NOT reduce the ROW COUNT: v2 keeps the SAME per-release grain as v1, so the same shared-dependency
-- CVE still emits an APPEARED row on every release across every branch that carries it. Demo: 897k
-- rows encode only 6,032 logical findings -- avg 148x fan-out (max 2,400x). The fan-out is an EMIT
-- artifact: each release diffs its metrics against EMPTY (first revision), so every release
-- re-declares APPEARED for findings it inherited unchanged from its branch predecessor.
--
-- v3 diffs along the BRANCH TIMELINE instead (release N against its branch predecessor's terminal
-- metrics, not against empty), so an inherited finding emits NO event. Only genuine transitions land.
-- Demo-measured effect: severity 93.5% / KEV 100% / VEX 0-divergent across the fan-out -> the only
-- real variation is TEMPORAL transitions (SEVERITY_*/KEV_*), which stay as events. Estimated ~98%
-- fact-row reduction. Values (severity/known_exploited/analysis_state) stay BAKED on the coarse event
-- (demo-proven constant across the fan-out), so reconstruction stays byte-identical and the enrichment
-- /overlay read path is untouched (Option A; see ai-agents/finding-events-dedup-v3-design.md).
--
-- SHAPE. Structurally this is finding_change_events_v2 + a branch_uuid grain column, with release_uuid
-- reinterpreted as first_release_uuid (the release/scan that PRODUCED the branch transition -- kept for
-- attribution + reverse-replay ordering, NOT identity). It shares the V57 finding_dim dimension
-- unchanged (metadata-once AND fewer rows stack). The dedup index keeps the proven v2 shape
-- (first_release_uuid, to_metrics_revision, change_kind, finding_dim): one scan produces at most one
-- transition per (change_kind, finding) so that tuple is still a true unique id -- the branch-chained
-- diff simply FEEDS it far fewer rows.
--
-- NO FOREIGN KEYs (per coding_principles.md). finding_dim / branch / release refs are bare uuids;
-- reads tolerate a dangling ref exactly as v1/v2 do.
--
-- NAMING. finding_change_events_v3 is transitional. At the operator-gated cutover (a later stage) v1
-- and v2 are dropped and this is renamed to finding_change_events (a cheap metadata rename).

CREATE TABLE rearm.finding_change_events_v3 (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    entity_type text NOT NULL default 'RELEASE',
    component_uuid uuid NOT NULL,       -- grain anchor
    branch_uuid uuid NOT NULL,          -- grain anchor (NEW; the per-branch finding timeline)
    finding_dim uuid NOT NULL,          -- reference to finding_dim.uuid (bare, no FK) -- shared with v2
    finding_kind text NOT NULL,         -- kept on the fact for kind-filtered reads without a dim lookup
    change_kind text NOT NULL,          -- APPEARED / RESOLVED / SEVERITY_* / KEV_*
    change_date timestamptz NOT NULL,
    -- PROVENANCE (not identity): which release/scan produced this branch transition. Drives attribution
    -- (the changelog shows the release a finding first appeared in) and reverse-replay tie-break ordering.
    first_release_uuid uuid NOT NULL,
    version text NOT NULL,              -- of first_release_uuid (survives a dangling release ref)
    component_name text NOT NULL,       -- survives a dangling component ref
    to_metrics_revision integer NOT NULL,
    severity text,
    previous_severity text,             -- populated only for SEVERITY_INCREASED / SEVERITY_DECREASED
    known_exploited boolean,
    analysis_state text,
    created_date timestamptz NOT NULL default now()
);

-- Org-level changelog read: all changes for an org over a date window.
CREATE INDEX finding_change_events_v3_org_date_idx
    ON rearm.finding_change_events_v3 (org, change_date);

-- Branch-level reconstruction read: THE new primary read grain (reverse-replay onto the branch-latest
-- anchor). Replaces v1/v2's per-release read.
CREATE INDEX finding_change_events_v3_branch_date_idx
    ON rearm.finding_change_events_v3 (org, branch_uuid, change_date);

-- Component/product scope changelog read.
CREATE INDEX finding_change_events_v3_component_date_idx
    ON rearm.finding_change_events_v3 (org, component_uuid, change_date);

-- RECONSTRUCTION read: a release's own (deduped) transitions, keyed by the release that produced them
-- (first_release_uuid), date-bounded. The reverse-replay groups by release, so this is the hot read path
-- (the v3 analogue of finding_change_events_v2_release_date_idx).
CREATE INDEX finding_change_events_v3_first_release_date_idx
    ON rearm.finding_change_events_v3 (org, first_release_uuid, change_date);

-- Idempotency key -- same shape as the v2 dedup index (release renamed to first_release_uuid). One scan
-- (first_release_uuid, to_metrics_revision) produces at most one transition per (change_kind, finding_dim),
-- so this uniquely identifies a produced event and re-diffs / live-emit overlap dedup to no-ops. branch_uuid
-- is functionally determined by first_release_uuid, so it is intentionally NOT in the unique key (indexed
-- separately above for the branch read).
CREATE UNIQUE INDEX finding_change_events_v3_dedup_idx
    ON rearm.finding_change_events_v3 (first_release_uuid, to_metrics_revision, change_kind, finding_dim);
