-- ---------------------------------------------------------------------------
-- V63 -- name legacy base Slack/Teams integrations so they surface as channels.
--
-- Legacy Integration(SLACK|MSTEAMS, identifier='base') rows predate the
-- notifications framework and were reused in place (see V45) and re-subscribed
-- (see V46). But the entire new read/resolve surface -- the catalog list
-- (NotificationDataFetcher.toChannelResult), the channel-group resolver, and
-- the subscription resolver -- keys "is this integration a notification
-- channel?" on name != null. A legacy base row has a null name, so it is
-- dispatched-to (via the V46 backfilled subscription) yet invisible and
-- unmanageable in the UI: the Catalog card shows "Not configured" and the
-- operator can't see, test, re-enable, or edit it.
--
-- Give those rows a name so they are recognized as channels. Value-only, no
-- FK constraints (per coding_principles.md). The Slack SECRET-format fix
-- (legacy rows stored only the /services path fragment, expanded to a full URL
-- at dispatch by SlackWebhookUrlValidator.normalize) is code, not data -- this
-- migration deliberately does NOT touch the encrypted secret.
--
-- Idempotent: only rows whose name is still null/blank are set, so a
-- hand-named channel is never clobbered and a re-run is a no-op.
-- ---------------------------------------------------------------------------

UPDATE rearm.integrations
SET record_data = jsonb_set(
        record_data,
        '{name}',
        to_jsonb(CASE record_data->>'type'
                     WHEN 'SLACK'   THEN 'Slack (migrated)'
                     WHEN 'MSTEAMS' THEN 'Microsoft Teams (migrated)'
                 END)),
    last_updated_date = now()
WHERE record_data->>'identifier' = 'base'
  AND record_data->>'type' IN ('SLACK', 'MSTEAMS')
  AND (record_data->>'name' IS NULL OR record_data->>'name' = '');

-- Re-enable base Slack channels that an INTERIM (fragment-rejecting) build
-- auto-disabled with the not-a-Slack-host reason. The dispatch-time
-- SlackWebhookUrlValidator.normalize now expands their /services fragment, so
-- they can deliver again -- but naming them alone would leave them disabled,
-- so "migrated base channels keep delivering" would silently not hold for any
-- org that ran the interim build (e.g. anyone who upgraded before this fix).
--
-- Scoped to that EXACT auto-disable reason so an operator's manual disable
-- (null / different reason) is preserved. A base row whose secret is a
-- genuinely non-Slack URL simply re-disables on the next dispatch: normalize
-- -> isValid still fails there, BEFORE any POST, so re-enabling is safe.
-- Clears the now-stale disabledReason as part of the re-enable.
UPDATE rearm.integrations
SET record_data = (record_data - 'disabledReason')
                  || jsonb_build_object('isEnabled', true),
    last_updated_date = now()
WHERE record_data->>'identifier' = 'base'
  AND record_data->>'type' = 'SLACK'
  AND (record_data->>'isEnabled') = 'false'
  AND record_data->>'disabledReason' LIKE 'Webhook URL is not a Slack host%';
