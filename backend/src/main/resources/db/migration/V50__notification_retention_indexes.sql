-- V50 — notification retention (Phase 6c of the notifications plan).
-- The daily retention sweep deletes per-org rows by age:
--   DELETE ... WHERE org = :org AND created_date < :cutoff
-- Composite (org, created_date) indexes keep that scan off the heap on
-- both tables. notification_reads is purged via a join on delivery_uuid,
-- which is already indexed (notification_reads_delivery_idx, V42).

CREATE INDEX notification_outbox_events_org_created_idx
    ON rearm.notification_outbox_events (org, created_date);

CREATE INDEX notification_deliveries_org_created_idx
    ON rearm.notification_deliveries (org, created_date);

-- The composite indexes subsume V42's single-column org indexes (any
-- WHERE org = ... query can use the composite's prefix); drop the old
-- ones to save write amplification on the hot insert paths.
DROP INDEX rearm.notification_outbox_events_org_idx;
DROP INDEX rearm.notification_deliveries_org_idx;
