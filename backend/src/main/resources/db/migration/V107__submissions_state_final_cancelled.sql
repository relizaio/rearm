-- Submission states FINAL and CANCELLED replace ACTIVE and WITHDRAWN (2026-11-FDA-WORKFLOW board,
-- FDAW-8 = FDAW-1 A2: parent decision D6, resolution F-8 item 2, FDAW-8 functional review F-1).
-- Ships in the same deploy as the enum change. Rerunnable: every statement touches only rows that
-- still carry an old value, so a second run writes nothing.
--
-- A controlled change of package records:
-- * every head in ACTIVE becomes FINAL and every head in WITHDRAWN becomes CANCELLED, in the column
--   and in the record's "state" key (the record is read through a mapper that refuses an unknown
--   enum value), and the record gains "legacyState" = the old value;
-- * each migrated head gets one MIGRATED act (cause MIGRATION, no actor, no person data) with
--   fromState, toState, latestVersion, beforeHash, afterHash and, for WITHDRAWN, the withdrawal
--   triple copied from the record (the triple stays in the head record, read-only);
-- * the stored notification payloads of the two submission event types are rewritten the same way
--   ("state" only; "act" keeps WITHDRAWN, which stays a value of SubmissionActOutcome);
-- * submission versions are untouched, the V97 trigger on submission_versions stays enabled, and no
--   rearm.audit row is written for the migration;
-- * the counts and the notification subscriptions whose CEL filter names submissionState or
--   submissionAct (they no longer match the old values) are printed as NOTICEs in the Flyway log.
--
-- The hashes are sha256 over the UTF-8 of uuid::text || state || latest_version::text ||
-- record_data::text, with record_data::text PostgreSQL's canonical jsonb text (not the application
-- mapper's output), so they are recomputed in SQL. For a MIGRATED act over head :s:
--   afterHash (valid while no later act has saved the head):
--     SELECT encode(sha256(convert_to(uuid::text || state || latest_version::text || record_data::text,
--            'UTF8')), 'hex') FROM rearm.submissions WHERE uuid = :s;
--   beforeHash (the pre-migration row rebuilt from legacyState):
--     SELECT encode(sha256(convert_to(uuid::text || (record_data->>'legacyState') || latest_version::text
--            || ((record_data - 'legacyState') || jsonb_build_object('state', record_data->>'legacyState'))::text,
--            'UTF8')), 'hex') FROM rearm.submissions WHERE uuid = :s;

DO $$
DECLARE
    active_count BIGINT;
    withdrawn_count BIGINT;
    payload_count BIGINT;
    subscription_count BIGINT;
    subscription_uuids TEXT;
BEGIN
    SELECT count(*) FILTER (WHERE state = 'ACTIVE'), count(*) FILTER (WHERE state = 'WITHDRAWN')
      INTO active_count, withdrawn_count
      FROM rearm.submissions WHERE state IN ('ACTIVE', 'WITHDRAWN');

    WITH before AS (
        SELECT uuid, state AS old_state,
               encode(sha256(convert_to(uuid::text || state || latest_version::text || record_data::text, 'UTF8')), 'hex') AS before_hash
        FROM rearm.submissions WHERE state IN ('ACTIVE', 'WITHDRAWN')
    ), moved AS (
        UPDATE rearm.submissions s
           SET state = CASE b.old_state WHEN 'ACTIVE' THEN 'FINAL' ELSE 'CANCELLED' END,
               record_data = s.record_data
                   || jsonb_build_object('state', CASE b.old_state WHEN 'ACTIVE' THEN 'FINAL' ELSE 'CANCELLED' END,
                                         'legacyState', b.old_state)
          FROM before b WHERE s.uuid = b.uuid
        RETURNING s.uuid, s.org, s.release, s.latest_version, b.old_state, s.state AS new_state, b.before_hash, s.record_data,
               encode(sha256(convert_to(s.uuid::text || s.state || s.latest_version::text || s.record_data::text, 'UTF8')), 'hex') AS after_hash
    )
    INSERT INTO rearm.submission_acts (uuid, org, submission, release, version, kind, actor, cause, caused_by, acted_at, record_data)
    SELECT gen_random_uuid(), org, uuid, release, latest_version, 'MIGRATED', NULL, 'MIGRATION', NULL, now(),
           jsonb_build_object('fromState', old_state, 'toState', new_state, 'latestVersion', latest_version,
                              'beforeHash', before_hash, 'afterHash', after_hash)
           || CASE WHEN old_state = 'WITHDRAWN'
                   THEN jsonb_build_object('withdrawnDate', record_data->'withdrawnDate', 'withdrawnBy', record_data->'withdrawnBy',
                                           'withdrawReason', record_data->'withdrawReason')
                   ELSE '{}'::jsonb END
    FROM moved;

    UPDATE rearm.notification_outbox_events
       SET record_data = record_data
           || jsonb_build_object('state', CASE record_data->>'state' WHEN 'ACTIVE' THEN 'FINAL' ELSE 'CANCELLED' END)
     WHERE event_type IN ('RELEASE_SUBMISSION_CREATED', 'RELEASE_SUBMISSION_WITHDRAWN')
       AND record_data->>'state' IN ('ACTIVE', 'WITHDRAWN');
    GET DIAGNOSTICS payload_count = ROW_COUNT;

    RAISE NOTICE 'submissions migrated: % ACTIVE -> FINAL, % WITHDRAWN -> CANCELLED; % notification payloads rewritten',
        active_count, withdrawn_count, payload_count;

    SELECT count(*), string_agg(uuid::text, ',')
      INTO subscription_count, subscription_uuids
      FROM rearm.notification_subscriptions
     WHERE record_data->'filter'->>'celExpression' ~ '\msubmission(State|Act)\M';
    RAISE NOTICE 'notification subscriptions whose filter names submissionState or submissionAct: % (uuids: %)',
        subscription_count, coalesce(subscription_uuids, '');
END $$;
