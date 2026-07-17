-- Perf + constraint repair for rearm.vuln_analysis, plus V8 cleanup.
--
-- 1. V8 repair: V8__vuln_analysis_constraints.sql created its unique index on
--    rearm.user_groups by mistake (copy-paste), keyed on jsonb fields that do
--    not exist on user_groups ('findingId' etc). Nothing has ever used or
--    enforced it — drop it. The intended uniqueness lands on vuln_analysis
--    below.
DROP INDEX IF EXISTS rearm.vuln_analysis_unique_finding_id;

-- 2. Dedupe before the unique index: the code path (VulnAnalysisService
--    check-then-insert) has always been the only guard, so racing writers may
--    have produced duplicates on the app-level identity key. Keep the most
--    recently updated row of each duplicate group.
DELETE FROM rearm.vuln_analysis va
USING rearm.vuln_analysis newer
WHERE va.record_data->>'org' = newer.record_data->>'org'
  AND va.record_data->>'location' = newer.record_data->>'location'
  AND va.record_data->>'findingId' = newer.record_data->>'findingId'
  AND va.record_data->>'scope' = newer.record_data->>'scope'
  AND coalesce(va.record_data->>'scopeUuid', '') = coalesce(newer.record_data->>'scopeUuid', '')
  AND coalesce(va.record_data->>'findingType', '') = coalesce(newer.record_data->>'findingType', '')
  AND (newer.last_updated_date > va.last_updated_date
       OR (newer.last_updated_date = va.last_updated_date AND newer.uuid > va.uuid));

-- 3. The uniqueness V8 intended, on the key the service actually upserts by
--    (findByOrgAndLocationAndFindingIdAndScopeAndType). COALESCE keeps rows
--    with an absent scopeUuid/findingType from escaping the constraint via
--    NULLS-DISTINCT semantics.
CREATE UNIQUE INDEX IF NOT EXISTS vuln_analysis_unique_finding_idx
    ON rearm.vuln_analysis (
        (record_data->>'org'),
        (record_data->>'location'),
        (record_data->>'findingId'),
        (record_data->>'scope'),
        (coalesce(record_data->>'scopeUuid', '')),
        (coalesce(record_data->>'findingType', ''))
    );

-- 4. Lookup indexes for the hot analysis queries (every-minute drain calls
--    these per finding/canonical; without them every lookup was a full seq
--    scan — observed at ~283 seq scans/second on a loaded instance). Names
--    match the indexes already created manually on prod, so this is a no-op
--    there and a catch-up everywhere else.
CREATE INDEX IF NOT EXISTS vuln_analysis_org_location_idx
    ON rearm.vuln_analysis ((record_data->>'org'), (record_data->>'location'));

CREATE INDEX IF NOT EXISTS vuln_analysis_org_scope_scopeuuid_idx
    ON rearm.vuln_analysis ((record_data->>'org'), (record_data->>'scope'), (record_data->>'scopeUuid'));

-- 5. Drop the redundant duplicate of idx_variants_release that was created
--    manually on prod while diagnosing (variants_release_idx indexes the same
--    (record_data->>'release') expression). No-op on instances that never had
--    it.
DROP INDEX IF EXISTS rearm.variants_release_idx;
