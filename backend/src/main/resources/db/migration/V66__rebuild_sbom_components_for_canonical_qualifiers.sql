-- Targeted rebuild of sbom component data written under the old
-- qualifier-stripping purl canonicalization.
--
-- Canonicalization now preserves identity-bearing qualifiers per purl type
-- (apk/deb/bitnami: distro; rpm: distro + epoch; oci: repository_url;
-- julia: uuid; swid: tag_id — see CANONICAL_PRESERVED_QUALIFIERS in
-- common/Utils.java and PRESERVED_QUALIFIERS in rebom's
-- bomComponentExtractor.ts). Rows written before that change carry a
-- stripped canonical_purl, which loses e.g. the Alpine distro branch and
-- makes Dependency-Track match vulnerabilities fixed in other branches
-- (the OSV fixed:0 false-positive class).
--
-- Instead of wiping all derived data (TRUNCATE + org-wide re-enqueue),
-- this migration scopes the rebuild to the affected slice only. It can do
-- that entirely in SQL because artifact_sbom_components.exact_purl keeps
-- the raw qualifier-bearing purl each BOM declared: a row is stale exactly
-- when its exact_purl carries a preserved qualifier for its type while the
-- linked canonical_purl lacks it. The '%?q=' / '%&q=' anchoring matches
-- the qualifier at a purl separator so qualifier names that merely end in
-- the same string can't false-positive. Rows written under the fixed
-- extractor carry the qualifier in the canonical and are never matched,
-- so the pass is idempotent and converges to zero.
--
-- Mechanics of the rebuild (no manual synthetic_dtrack_bucket touch):
--   1. Deleting the affected artifacts' artifact_sbom_components rows
--      re-arms the per-artifact parse — reconcileReleaseSbomComponents
--      skips artifacts whose rows already exist (content-addressed).
--   2. GC-ing now-orphaned sbom_components removes the stripped canonicals
--      from the synthetic-DTrack matchable set: affected buckets fail
--      their content_hash check next tick and re-upload without them
--      (refMap and findings are rebuilt wholesale on submit/ingest).
--   3. Re-enqueueing only the releases that reference an affected
--      artifact (via release_artifact_index) re-parses them through rebom,
--      which computes the corrected qualifier-bearing canonicals; the new
--      rows first-fit back into the freed bucket capacity and those
--      buckets resubmit to Dependency-Track with distro-scoped purls.
--
-- Deployment-order constraint: the rebom running against this backend
-- must already compute qualifier-preserving canonicals, or the re-parse
-- recreates the stripped rows (the idempotency guard makes a re-run
-- possible, but do not rely on it).
--
-- Artifacts referenced only by ARCHIVED releases lose their rows and are
-- not re-parsed — same property the full-wipe approach had (TRUNCATE
-- dropped them; the re-enqueue excluded archived releases).

CREATE TEMP TABLE tmp_v66_affected_artifacts AS
SELECT DISTINCT ac.canonical_artifact_uuid
FROM rearm.artifact_sbom_components ac
JOIN rearm.sbom_components sc ON sc.uuid = ac.sbom_component_uuid
WHERE (
     (ac.exact_purl LIKE 'pkg:apk/%'     AND (ac.exact_purl LIKE '%?distro=%' OR ac.exact_purl LIKE '%&distro=%') AND sc.canonical_purl NOT LIKE '%distro=%')
  OR (ac.exact_purl LIKE 'pkg:deb/%'     AND (ac.exact_purl LIKE '%?distro=%' OR ac.exact_purl LIKE '%&distro=%') AND sc.canonical_purl NOT LIKE '%distro=%')
  OR (ac.exact_purl LIKE 'pkg:bitnami/%' AND (ac.exact_purl LIKE '%?distro=%' OR ac.exact_purl LIKE '%&distro=%') AND sc.canonical_purl NOT LIKE '%distro=%')
  OR (ac.exact_purl LIKE 'pkg:rpm/%'     AND (ac.exact_purl LIKE '%?distro=%' OR ac.exact_purl LIKE '%&distro=%') AND sc.canonical_purl NOT LIKE '%distro=%')
  OR (ac.exact_purl LIKE 'pkg:rpm/%'     AND (ac.exact_purl LIKE '%?epoch=%'  OR ac.exact_purl LIKE '%&epoch=%')  AND sc.canonical_purl NOT LIKE '%epoch=%')
  OR (ac.exact_purl LIKE 'pkg:oci/%'     AND (ac.exact_purl LIKE '%?repository_url=%' OR ac.exact_purl LIKE '%&repository_url=%') AND sc.canonical_purl NOT LIKE '%repository_url=%')
  OR (ac.exact_purl LIKE 'pkg:julia/%'   AND (ac.exact_purl LIKE '%?uuid=%'   OR ac.exact_purl LIKE '%&uuid=%')   AND sc.canonical_purl NOT LIKE '%uuid=%')
  OR (ac.exact_purl LIKE 'pkg:swid/%'    AND (ac.exact_purl LIKE '%?tag_id=%' OR ac.exact_purl LIKE '%&tag_id=%') AND sc.canonical_purl NOT LIKE '%tag_id=%')
);

-- 1. Re-arm the parse for affected artifacts.
DELETE FROM rearm.artifact_sbom_components ac
USING tmp_v66_affected_artifacts a
WHERE ac.canonical_artifact_uuid = a.canonical_artifact_uuid;

-- 2. GC canonicals no longer referenced by any artifact. Also collects any
--    pre-existing orphans — sbom_components is fully derived data, so an
--    unreferenced row is stale by definition and would otherwise keep
--    feeding the synthetic-DTrack buckets.
DELETE FROM rearm.sbom_components sc
WHERE NOT EXISTS (
    SELECT 1 FROM rearm.artifact_sbom_components ac
    WHERE ac.sbom_component_uuid = sc.uuid
);

-- 3. Re-enqueue only the releases that reference an affected artifact
--    (same flow_control marker the V37 tail used; the DTrack scheduler
--    drains ~50 releases per tick).
UPDATE rearm.releases r
SET flow_control = jsonb_set(
        coalesce(r.flow_control, '{}'::jsonb),
        '{sbomReconcileRequestedAt}',
        to_jsonb(now()),
        true)
WHERE r.uuid IN (
        SELECT rai.release_uuid
        FROM rearm.release_artifact_index rai
        JOIN tmp_v66_affected_artifacts a
          ON a.canonical_artifact_uuid = rai.canonical_artifact_uuid)
  AND coalesce(r.record_data->>'status', 'ACTIVE') != 'ARCHIVED';

DROP TABLE tmp_v66_affected_artifacts;
