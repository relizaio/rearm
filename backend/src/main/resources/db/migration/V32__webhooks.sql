-- Inbound webhook reception. Each row is a public endpoint at
-- /api/programmatic/v1/webhook/{org}/{slug} bound to a GITHUB Integration
-- with the WEBHOOK capability asserted. The encrypted secret is the
-- HMAC-SHA256 key paired with what the operator configured on the GitHub
-- App side; the controller verifies X-Hub-Signature-256 against the
-- secret before parsing the body.
CREATE TABLE rearm.webhooks (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

-- Slug is the public-URL discriminator. Globally unique scoping is
-- (org, slug) since the URL has org_uuid baked in, not a flat slug
-- namespace. UI prevents collisions but DB enforces it as the source
-- of truth.
CREATE UNIQUE INDEX webhooks_org_slug
    ON rearm.webhooks ((record_data->>'org'), (record_data->>'slug'));

CREATE INDEX webhooks_org ON rearm.webhooks ((record_data->>'org'));
CREATE INDEX webhooks_integration ON rearm.webhooks ((record_data->>'integration'));

-- Idempotency for inbound deliveries. GitHub redelivers retries with the
-- same X-GitHub-Delivery UUID; the controller checks (webhook, deliveryId)
-- here before dispatching to handlers and short-circuits on a hit. A
-- nightly prune in SaasSchedulingService drops rows older than 30 days —
-- TTL is fine because GH's redelivery window is much shorter than that.
CREATE TABLE rearm.webhook_deliveries (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE UNIQUE INDEX webhook_deliveries_webhook_delivery_id
    ON rearm.webhook_deliveries ((record_data->>'webhook'),
                                  (record_data->>'deliveryId'));

CREATE INDEX webhook_deliveries_created_date
    ON rearm.webhook_deliveries (created_date);
