-- ============================================================================
-- V42__notifications.sql — Phase 1a of the notifications framework.
-- See ai-plans/notifications/notifications-framework.md for the full design.
--
-- This migration introduces five tables that back the producers → outbox →
-- subscriptions → channels pipeline. No FOREIGN KEY constraints anywhere,
-- per ai-agents/coding_principles.md.
--
-- DT-webhook receiver (notification_webhook_inbound) is deferred to v1.1
-- and intentionally NOT created here.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. notification_subscriptions — per-org rule. Pure-JSONB shape, mirrors
--    approval_policies exactly. The data class (NotificationSubscriptionData)
--    carries org, resourceGroup, status, name, eventTypes, filter, routes,
--    dedupWindowMinutes, rateLimit.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.notification_subscriptions (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX notification_subscriptions_org_idx
    ON rearm.notification_subscriptions ((record_data->>'org'));
CREATE INDEX notification_subscriptions_status_idx
    ON rearm.notification_subscriptions ((record_data->>'status'));
CREATE INDEX notification_subscriptions_resource_group_idx
    ON rearm.notification_subscriptions ((record_data->>'resourceGroup'));

-- ---------------------------------------------------------------------------
-- 2. notification_channels — per-org destination. Pure-JSONB. The encrypted
--    secret (Slack URL, SMTP creds, Sentinel client-secret, webhook HMAC key)
--    is stored inside record_data via EncryptionService — never returned
--    through GraphQL list/get endpoints.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.notification_channels (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX notification_channels_org_idx
    ON rearm.notification_channels ((record_data->>'org'));
CREATE INDEX notification_channels_type_idx
    ON rearm.notification_channels ((record_data->>'type'));
CREATE INDEX notification_channels_resource_group_idx
    ON rearm.notification_channels ((record_data->>'resourceGroup'));

-- ---------------------------------------------------------------------------
-- 3. notification_outbox_events — event waiting for fan-out by the outbox
--    worker. Hybrid shape: status, org, event_type, dedup_key, occurred_at
--    promoted to columns because the worker's hot query is
--      WHERE status='PENDING' ORDER BY occurred_at LIMIT 50
--    and JSONB-path predicates would defeat the planner here. Producer
--    insert path is in the same transaction as the underlying business
--    state change (see §4.3 of the design doc).
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.notification_outbox_events (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    event_type text NOT NULL,
    status text NOT NULL default 'PENDING',
    dedup_key text,
    occurred_at timestamptz NOT NULL default now(),
    -- REAL = produced by a real event emitter; SYNTHETIC = produced by
    -- SyntheticEventService (channel test, Quick Start verify, integration
    -- test harness). The fan-out worker propagates this value to every
    -- delivery row it writes (§7.11 of the design doc).
    origin text NOT NULL default 'REAL',
    -- Phase 2d channel-test bypass: when non-null, fan-out skips
    -- subscription / CEL / severity-gate evaluation and writes a single
    -- delivery row to this exact channel uuid. Used by the operator
    -- "Test channel" mutation to exercise the full pipeline (outbox →
    -- fan-out → channel dispatcher) end-to-end without depending on a
    -- matching subscription. NULL for every non-test event.
    channel_test_target uuid,
    record_data jsonb NOT NULL
);

-- Worker scan: pull next PENDING batch ordered by occurred_at.
CREATE INDEX notification_outbox_events_status_occurred_idx
    ON rearm.notification_outbox_events (status, occurred_at);
CREATE INDEX notification_outbox_events_org_idx
    ON rearm.notification_outbox_events (org);
CREATE INDEX notification_outbox_events_dedup_idx
    ON rearm.notification_outbox_events (dedup_key)
    WHERE dedup_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. notification_deliveries — one row per (event × channel) attempt.
--    Hybrid shape: lookup/queue columns explicit; payload digest, headers,
--    rendered diagnostic info in record_data.
--
--    acked_at / snoozed_until reserved here for the v2 inbox per §8 of the
--    design doc; they stay nullable in v1.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.notification_deliveries (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    outbox_event_uuid uuid NOT NULL,
    -- Nullable for the channel-test path (Phase 2d): a "Test channel"
    -- press creates a delivery row that has no associated subscription
    -- (it bypasses subscription matching entirely). Real fan-out always
    -- writes a non-null subscription_uuid. Queries joining
    -- notification_subscriptions can LEFT JOIN to keep test rows in
    -- history views, or filter them out with WHERE subscription_uuid IS
    -- NOT NULL.
    subscription_uuid uuid,
    channel_uuid uuid NOT NULL,
    status text NOT NULL default 'PENDING',
    dedup_key text,
    attempt_count integer NOT NULL default 0,
    next_attempt_at timestamptz NOT NULL default now(),
    -- Set when status transitions to SENT/ACKED. Dedup window anchors here,
    -- NOT on created_date — a delivery created day-0 but only successfully
    -- SENT on day-5 should anchor the next dedup probe at day-5.
    sent_at timestamptz,
    last_error text,
    -- REAL = produced by a real event emitter; SYNTHETIC = produced by the
    -- synthetic-injection primitive (channel test, Quick Start verify,
    -- integration test harness). Enum-shaped per coding_principles.md so
    -- adding REPLAY etc. later doesn't require a boolean-fork rewrite.
    origin text NOT NULL default 'REAL',
    acked_at timestamptz,
    snoozed_until timestamptz,
    record_data jsonb NOT NULL
);

-- Channel worker scan: WHERE status='PENDING' AND next_attempt_at <= NOW().
CREATE INDEX notification_deliveries_status_next_attempt_idx
    ON rearm.notification_deliveries (status, next_attempt_at);
CREATE INDEX notification_deliveries_org_idx
    ON rearm.notification_deliveries (org);
CREATE INDEX notification_deliveries_outbox_event_idx
    ON rearm.notification_deliveries (outbox_event_uuid);
CREATE INDEX notification_deliveries_subscription_idx
    ON rearm.notification_deliveries (subscription_uuid);
CREATE INDEX notification_deliveries_channel_idx
    ON rearm.notification_deliveries (channel_uuid);

-- Dedup lookup at fan-out time: was the same (subscription, channel, key)
-- already delivered within the dedup window? Partial index keeps it small
-- since most deliveries have a dedup key but ad-hoc/test sends may not.
CREATE INDEX notification_deliveries_dedup_idx
    ON rearm.notification_deliveries (subscription_uuid, channel_uuid, dedup_key)
    WHERE dedup_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. notification_reads — per-(user × delivery) mark-as-read state for the
--    inbox MVP. Unique on (user, delivery) so toggling read twice is a no-op.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.notification_reads (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    user_uuid uuid NOT NULL,
    delivery_uuid uuid NOT NULL,
    read_at timestamptz NOT NULL default now(),
    record_data jsonb,
    CONSTRAINT notification_reads_user_delivery_unique UNIQUE (user_uuid, delivery_uuid)
);

CREATE INDEX notification_reads_user_idx
    ON rearm.notification_reads (user_uuid);
CREATE INDEX notification_reads_delivery_idx
    ON rearm.notification_reads (delivery_uuid);
