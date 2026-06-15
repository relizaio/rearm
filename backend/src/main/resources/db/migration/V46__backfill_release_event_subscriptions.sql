-- ---------------------------------------------------------------------------
-- Phase 2b-2: backfill default release-event subscriptions.
--
-- Before this phase, release create / lifecycle / BOM-diff notifications were
-- dispatched inline by the legacy NotificationService straight to each org's
-- Integration(SLACK|MSTEAMS, identifier='base') row — there was no
-- subscription object; every org with a "base" Slack/Teams integration
-- implicitly received them.
--
-- Phase 2b-2 routes those same events through the outbox → subscription →
-- channel pipeline (RELEASE_CREATED / RELEASE_LIFECYCLE_CHANGED /
-- RELEASE_BOM_DIFF). To preserve behaviour for existing orgs, we materialise
-- one ACTIVE subscription per org that has a legacy base Slack/Teams
-- integration, with:
--   * no filter            -> matches every release event (filter==null is
--                             "match all" in NotificationFanOutService)
--   * a single route with no severity / env / lifecycle / perspective gate
--     whose channels[] are the org's base SLACK + MSTEAMS integration uuids
--     (release events carry no canonical severity, so a gateless route is
--     required for them to deliver).
--
-- No FK constraints (per coding_principles.md). The channels[] entries are
-- the integration row uuids that V45 preserved 1:1 from the old channel uuids,
-- so the fan-out worker resolves them directly.
--
-- Idempotency: skips any org that already owns a subscription referencing one
-- of the three release event types, so a hand-created subscription isn't
-- duplicated.
-- ---------------------------------------------------------------------------

INSERT INTO rearm.notification_subscriptions
    (uuid, revision, schema_version, created_date, last_updated_date, record_data)
SELECT
    gen_random_uuid(),
    0,
    0,
    now(),
    now(),
    jsonb_build_object(
        'org',        base.org_id,
        'name',       'Default release notifications (migrated)',
        'status',     'ACTIVE',
        'eventTypes', jsonb_build_array(
                          'RELEASE_CREATED',
                          'RELEASE_LIFECYCLE_CHANGED',
                          'RELEASE_BOM_DIFF'),
        'routes',     jsonb_build_array(
                          jsonb_build_object('channels', base.channel_uuids))
    )
FROM (
    SELECT
        i.record_data->>'org'                         AS org_id,
        jsonb_agg(to_jsonb(i.uuid::text)
                  ORDER BY i.record_data->>'type')    AS channel_uuids
    FROM rearm.integrations i
    WHERE i.record_data->>'identifier' = 'base'
      AND i.record_data->>'type' IN ('SLACK', 'MSTEAMS')
      AND NOT EXISTS (
          SELECT 1
          FROM rearm.notification_subscriptions ns
          WHERE ns.record_data->>'org' = i.record_data->>'org'
            AND ns.record_data->'eventTypes' ?| array[
                    'RELEASE_CREATED',
                    'RELEASE_LIFECYCLE_CHANGED',
                    'RELEASE_BOM_DIFF']
      )
    GROUP BY i.record_data->>'org'
) base;
