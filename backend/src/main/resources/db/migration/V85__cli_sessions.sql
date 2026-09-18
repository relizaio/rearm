-- CLI browser login (device authorization): a login request that becomes a session on approval.
-- Access tokens are the existing one-hour JWS bound to the key; the refresh token here is what keeps
-- a CLI logged in (sliding 30 days, hard cap 90 days from approval). Only hashes are stored.
CREATE TABLE rearm.cli_sessions (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    status text NOT NULL,
    user_code text NOT NULL,
    device_code_hash text NULL,
    refresh_token_hash text NULL,
    api_key uuid NULL,
    "user" uuid NULL,
    org uuid NULL,
    requested_from text NULL,
    created_date timestamptz NOT NULL default now(),
    approved_date timestamptz NULL,
    delivered_date timestamptz NULL,
    expires_date timestamptz NOT NULL,
    last_used_date timestamptz NULL,
    revoked_date timestamptz NULL
);

CREATE UNIQUE INDEX cli_sessions_user_code_pending_idx ON rearm.cli_sessions (user_code) WHERE status = 'PENDING';
CREATE INDEX cli_sessions_device_code_idx ON rearm.cli_sessions (device_code_hash) WHERE device_code_hash IS NOT NULL;
CREATE INDEX cli_sessions_refresh_idx ON rearm.cli_sessions (refresh_token_hash) WHERE refresh_token_hash IS NOT NULL;
CREATE INDEX cli_sessions_user_idx ON rearm.cli_sessions ("user", status);
CREATE INDEX cli_sessions_api_key_idx ON rearm.cli_sessions (api_key, status);
