-- Federated identity trust rules: an org trusts identity tokens from an external issuer
-- (GitHub Actions first) that match the rule's matcher, and grants either a scope template
-- evaluated against the token's claims or the permissions of one Free Form key.
-- The identities materialised per repository live in rearm.api_keys (object_type FEDERATED).
CREATE TABLE rearm.federated_trust_rules (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    name text NOT NULL,
    provider text NOT NULL,
    issuer text NOT NULL,
    status text NOT NULL,
    version integer NOT NULL default 1,
    matcher jsonb NOT NULL,
    grant_spec jsonb NOT NULL,
    pinned_owner_id text NULL,
    expires_date timestamptz NULL,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    last_used_date timestamptz NULL,
    created_by uuid NULL,
    last_updated_by uuid NULL
);

CREATE INDEX federated_trust_rules_org_idx ON rearm.federated_trust_rules (org, status);
CREATE INDEX federated_trust_rules_issuer_idx ON rearm.federated_trust_rules (issuer, status);
