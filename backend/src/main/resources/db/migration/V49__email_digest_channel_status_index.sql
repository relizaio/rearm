-- Email digest (Phase 5, rolling-cap batching). BATCHED delivery rows
-- reuse notification_deliveries as the per-channel digest queue. The
-- fan-out's open-batch probe and the flush job's per-channel drain both
-- filter on (channel_uuid, status); the existing status-led index
-- (status, next_attempt_at) doesn't serve a channel-scoped lookup.
-- Partial on the digest-relevant status to keep the index tiny — only
-- BATCHED rows are ever probed by channel + status.
CREATE INDEX notification_deliveries_channel_batched_idx
    ON rearm.notification_deliveries (channel_uuid, next_attempt_at)
    WHERE status = 'BATCHED';
