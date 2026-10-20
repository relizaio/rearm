-- SBOM signing keys (SCORE-32, child 31a of SCORE-31): one Ed25519 key per organization signs merged
-- SBOM exports. A key is CURRENT, RETIRED (replaced by a rotation, still published for verification)
-- or REVOKED (no longer vouched for). The private key is stored in record_data only as
-- EncryptionService ciphertext; the public half is served by the public JWKS endpoint.
--
-- No FOREIGN KEY constraints per repo convention; the org lives in record_data like every other
-- record_data table, with an expression index.

CREATE TABLE IF NOT EXISTS rearm.sbom_signing_keys (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);
CREATE INDEX IF NOT EXISTS sbom_signing_keys_org
    ON rearm.sbom_signing_keys ((record_data->>'org'));
-- One CURRENT key per organization; the service enforces it too, the index wins a race.
CREATE UNIQUE INDEX IF NOT EXISTS sbom_signing_keys_org_current
    ON rearm.sbom_signing_keys ((record_data->>'org'))
    WHERE record_data->>'status' = 'CURRENT';
