-- ---------------------------------------------------------------------------
-- Phase 13: notification_channel_groups — named, cross-type collections of
-- notification channels. A group lets a subscription route reference
-- "Security oncall" (= Slack #sec + Teams #leadership + Email security@)
-- as one identifier instead of repeating the channel-uuid list across
-- every subscription that targets the same destination set.
--
-- Resolution is at fan-out time (NotificationFanOutService.applyRoutes):
-- a route's `channelGroups` list is expanded to its member channel
-- UUIDs and merged with the route's direct `channels` list (deduped).
-- The group is a naming convenience, not a dispatch unit — each member
-- channel still gets its own delivery row with independent retry,
-- dedup, and status tracking.
--
-- See ai-plans/notifications/notifications-framework.md §11.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.notification_channel_groups (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX notification_channel_groups_org_idx
    ON rearm.notification_channel_groups ((record_data->>'org'));
CREATE INDEX notification_channel_groups_resource_group_idx
    ON rearm.notification_channel_groups ((record_data->>'resourceGroup'));
