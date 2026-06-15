-- ---------------------------------------------------------------------------
-- Phase 2b-1: fold notification_channels into integrations.
--
-- Notification channels and CI integrations were two pure-JSONB tables with
-- identical shape but separate secret storage. We unify them: a notification
-- channel becomes an `integrations` row, so secrets live in one place
-- (single rotation surface) and the "destination" concept stops being
-- duplicated across two entities.
--
-- Migration is value-only (no FK constraints exist on either side). We
-- PRESERVE each channel's UUID as its new integration row's UUID, so every
-- existing reference stays valid with zero JSONB rewriting:
--   * notification_deliveries.record_data->>'channelUuid'
--   * notification_subscriptions routes[].channels[] / channelGroups[]
--   * notification_channel_groups channels[]
-- all keep resolving — the worker just reads from `integrations` instead of
-- `notification_channels`.
--
-- Field mapping (NotificationChannelData -> IntegrationData):
--   org              -> org               (verbatim)
--   type             -> type              (MS_TEAMS renamed to MSTEAMS; the
--                                          IntegrationType enum spells Teams
--                                          without the underscore. Others —
--                                          SLACK/EMAIL/WEBHOOK/SENTINEL —
--                                          map 1:1.)
--   identifier        = uuid::text        (synthetic; satisfies the
--                                          (org,type,identifier) unique index
--                                          since channel uuids are unique)
--   status           -> isEnabled bool    (ENABLED -> true, else false)
--   encryptedSecret  -> secret            (verbatim; same EncryptionService
--                                          ciphertext, no re-encrypt)
--   configData       -> parameters        (verbatim map)
--   name             -> name              (verbatim)
--   resourceGroup    -> resourceGroup     (verbatim)
--
-- Legacy Integration(SLACK/MSTEAMS,"base") rows from the old CI-notification
-- path are NOT touched here — they are reused in place. The release-event
-- backfill that points subscriptions at them is Phase 2b-2.
-- ---------------------------------------------------------------------------

INSERT INTO rearm.integrations
    (uuid, revision, schema_version, created_date, last_updated_date, record_data)
SELECT
    nc.uuid,
    nc.revision,
    0,
    nc.created_date,
    nc.last_updated_date,
    jsonb_strip_nulls(jsonb_build_object(
        'uuid',          nc.uuid::text,
        'org',           nc.record_data->>'org',
        'type',          CASE nc.record_data->>'type'
                             WHEN 'MS_TEAMS' THEN 'MSTEAMS'
                             ELSE nc.record_data->>'type'
                         END,
        'identifier',    nc.uuid::text,
        'isEnabled',     (COALESCE(nc.record_data->>'status', 'ENABLED') = 'ENABLED'),
        'secret',        nc.record_data->>'encryptedSecret',
        'parameters',    COALESCE(nc.record_data->'configData', '{}'::jsonb),
        'name',          nc.record_data->>'name',
        'resourceGroup', nc.record_data->>'resourceGroup'
    ))
FROM rearm.notification_channels nc;

DROP TABLE rearm.notification_channels;
