-- Re-backfill the SBOM reconcile queue.
--
-- V25's backfill ran but the marks were silently overwritten on every
-- subsequent release UPDATE: the Release entity wasn't @DynamicUpdate, so
-- Hibernate's all-columns flush during artifact mutations rewrote
-- flow_control back to NULL even though a @Modifying SQL UPDATE had set it.
-- The companion code change (@DynamicUpdate on Release) makes the marks
-- stick going forward; this migration re-seeds the queue for releases that
-- got nuked.
--
-- Idempotent — only marks NULL rows, archived releases stay archived.
UPDATE rearm.releases
SET flow_control = jsonb_build_object('sbomReconcileRequestedAt', now())
WHERE flow_control IS NULL
  AND coalesce(record_data->>'status', 'ACTIVE') != 'ARCHIVED';
