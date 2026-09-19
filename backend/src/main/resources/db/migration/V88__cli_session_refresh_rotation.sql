-- Refresh token rotation for CLI sessions: every refresh retires the presented token and issues a
-- new one. The retired hash stays for a short grace window (a client that crashed between receiving
-- and persisting the new token, or retried a timed-out request, gets the current token again);
-- presenting it after the window means two holders and revokes the session.
ALTER TABLE rearm.cli_sessions ADD COLUMN previous_refresh_token_hash text NULL;
ALTER TABLE rearm.cli_sessions ADD COLUMN rotated_date timestamptz NULL;
CREATE INDEX cli_sessions_previous_refresh_idx ON rearm.cli_sessions (previous_refresh_token_hash) WHERE previous_refresh_token_hash IS NOT NULL;
CREATE INDEX cli_sessions_pending_expiry_idx ON rearm.cli_sessions (expires_date) WHERE status = 'PENDING';
