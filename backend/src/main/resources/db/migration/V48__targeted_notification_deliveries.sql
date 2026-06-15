-- Per-user targeted notification deliveries (approval-request events).
-- Targeted rows are written by the fan-out directly in SENT status with no
-- subscription/channel routing: subscription_uuid and channel_uuid are null,
-- target_user identifies the single user the inbox row is visible to.
ALTER TABLE rearm.notification_deliveries ALTER COLUMN channel_uuid DROP NOT NULL;
ALTER TABLE rearm.notification_deliveries ADD COLUMN target_user uuid;

CREATE INDEX notification_deliveries_target_user_idx
    ON rearm.notification_deliveries (target_user)
    WHERE target_user IS NOT NULL;

-- Resolve-marks-read lookup: APPROVAL_RESOLVED fan-out finds a request's
-- targeted rows by (org, dedup_key). The existing dedup index leads with
-- subscription_uuid/channel_uuid (both null on targeted rows), so it
-- can't serve this probe.
CREATE INDEX notification_deliveries_targeted_dedup_idx
    ON rearm.notification_deliveries (org, dedup_key)
    WHERE target_user IS NOT NULL;
