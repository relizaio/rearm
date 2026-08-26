-- ---------------------------------------------------------------------------
-- Producer-side coalesce staging for instance-deployment notifications.
--
-- A single logical deploy lands as SEVERAL instance-actual saves seconds apart
-- (UNDEPLOYED -> NEW -> IN_PROGRESS -> CONVERGED), each bumping revision_actual.
-- Notifying per save reproduces the legacy flicker. Instead, each actual save
-- records/extends one in-flight row here (keyed by instance) with a trailing
-- flush_deadline; a scheduled, advisory-locked flush
-- (SaasSchedulingService.flushInstanceDeploymentCoalesce) emits ONE settled
-- notification per deploy carrying the net diff from base_revision to the
-- current revision_actual. See ai-plans/instance-event-notifications.md sec 6.
--
-- base_record_data is the pre-burst InstanceDataActual snapshot (the "old" side
-- of compareInstancesActual); storing it here avoids reconstructing it from the
-- instance audit at flush time. first_seen_at anchors a hard max-wait cap so a
-- never-settling deploy still notifies; flush_deadline is the trailing debounce
-- boundary (reset on each save, capped at first_seen_at + max-wait).
--
-- One row per instance at most (unique index): concurrent actual saves for one
-- instance are serialised by the instance write lock, so find-or-extend is race
-- free. The flush_deadline index backs the due-batch scan.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.instance_deployment_coalesce (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    instance_uuid uuid NOT NULL,
    base_revision integer NOT NULL,
    base_record_data jsonb NOT NULL,
    first_seen_at timestamptz NOT NULL default now(),
    flush_deadline timestamptz NOT NULL,
    -- Failed-flush counter. A deterministic flush error (e.g. a missing release)
    -- would recur every tick, so the row is dropped after a bounded number of
    -- attempts rather than retried forever -- retry-then-dead-letter, matching
    -- notification_outbox_events.enrichment_attempt_count. A transient error
    -- (DB blip) simply gets another attempt instead of losing the notification.
    attempt_count integer NOT NULL default 0
);

CREATE UNIQUE INDEX instance_deployment_coalesce_instance_uuid_idx
    ON rearm.instance_deployment_coalesce (instance_uuid);

CREATE INDEX instance_deployment_coalesce_flush_deadline_idx
    ON rearm.instance_deployment_coalesce (flush_deadline);
