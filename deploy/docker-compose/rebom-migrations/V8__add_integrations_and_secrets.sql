CREATE TABLE rebom.secrets (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY DEFAULT gen_random_uuid(),
    created_date timestamptz NOT NULL DEFAULT now(),
    last_updated_date timestamptz NOT NULL DEFAULT now(),
    encrypted_value text NOT NULL,
    organization uuid NULL
);

CREATE TABLE rebom.integrations (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY DEFAULT gen_random_uuid(),
    created_date timestamptz NOT NULL DEFAULT now(),
    last_updated_date timestamptz NOT NULL DEFAULT now(),
    config jsonb NOT NULL DEFAULT '{}',
    organization uuid NULL
);

CREATE UNIQUE INDEX integration_type_org_unique_index ON rebom.integrations ( (config->>'type'), organization );
