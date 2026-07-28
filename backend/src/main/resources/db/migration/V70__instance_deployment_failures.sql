-- Deployment failures reported by ReARM CD, surfaced in the Instance view.
--
-- State, not a log: one row per (instance, namespace, deployment_name,
-- fingerprint). ReARM CD reconciles every 15s, so a persistent failure would
-- otherwise write thousands of rows an hour — instead the dedup key collapses
-- it to a single row carrying first_seen / last_seen / occurrence_count. A
-- recurrence after resolution resets first_seen and the counter, so first_seen
-- always means "this incident started at".
--
-- No FK constraints, per coding_principles.md.
CREATE TABLE IF NOT EXISTS rearm.instance_deployment_failures (
    uuid uuid NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    revision integer NOT NULL DEFAULT 0,
    schema_version integer NOT NULL DEFAULT 0,
    org uuid NOT NULL,
    instance_uuid uuid NOT NULL,
    namespace text NOT NULL,
    deployment_name text NOT NULL,
    fingerprint text NOT NULL,
    phase text NOT NULL,
    failure_class text NOT NULL,
    message text,
    -- Redacted + truncated by the reporting agent before transmit; never
    -- assume raw helm stderr is safe to store.
    detail text,
    feature_set uuid,
    product uuid,
    target_release uuid,
    sender_id text,
    first_seen timestamptz NOT NULL DEFAULT now(),
    last_seen timestamptz NOT NULL DEFAULT now(),
    occurrence_count integer NOT NULL DEFAULT 1,
    resolved_at timestamptz,
    created_date timestamptz NOT NULL DEFAULT now(),
    last_updated_date timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT instance_deployment_failures_dedup
        UNIQUE (instance_uuid, namespace, deployment_name, fingerprint)
);

-- Hot read: open failures for one instance (Instance.deploymentFailures and
-- the derived health chip). Partial so resolved history stays out of it.
CREATE INDEX IF NOT EXISTS instance_deployment_failures_open_idx
    ON rearm.instance_deployment_failures (instance_uuid, last_seen DESC)
    WHERE resolved_at IS NULL;

-- Org-scoped sweeps (retention / future org-wide views).
CREATE INDEX IF NOT EXISTS instance_deployment_failures_org_idx
    ON rearm.instance_deployment_failures (org, last_seen DESC);
