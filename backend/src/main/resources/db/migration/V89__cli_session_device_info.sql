-- What the CLI reported about the device at login time (host name, operating system, time zone,
-- client version) and what the server observed (client IP), shown on the approval page and in the
-- session lists so the approver can tell whether the request is the one they started.
ALTER TABLE rearm.cli_sessions ADD COLUMN device_info jsonb NULL;
