-- Agent task boards, the whole schema of the 2026-09-agent-task-boards branch in one migration.
--
-- The branch carried V92-V97 (task boards, role presets, session usages, role history, board events,
-- CLI session hard expiry). main took V92 for sbom_components_latest_version (#708), so the six were
-- squashed into this one, numbered after main's.
--
-- Rerunnable on purpose: every statement is IF NOT EXISTS. An instance that already ran the branch's
-- V92-V97 deletes those rows from flyway_schema_history before this build starts; Flyway then applies
-- main's V92 and this file, and this file changes nothing there. The V96 backfill of the events each
-- board row carried is not repeated: no board exists before this migration on a fresh database, and
-- every instance that had boards already ran it.
--
-- Full designs: backend/ai-plans/agentic/task-boards.md, board-mechanics.md,
-- usage-telemetry-and-model-catalogue.md, and programmatic-auth/00_decisions.md (hard expiry).
--
-- No FOREIGN KEY constraints per repo convention; referential integrity is the service layer's job.

-- ---------------------------------------------------------------------------------------------------
-- Boards, role configs and tasks
--
--   agent_boards            -- unit of workflow governance: sources (wired tracker repos), coordinating
--                              issue pointer, coordinator prompt + singleton seat, two-tier lock, WIP
--                              limits. All configuration is board-level.
--
--   agent_task_role_configs -- BOARD-scoped role definitions (served prompt, advisory order, optional
--                              per-role WIP limit, distinct-agent flag). The coordinator role is
--                              implicit on the board, never a row. Rows with NO board are org-level
--                              presets a new board can seed from.
--
--   agent_tasks             -- one unit of work routed hub-and-spoke through the board's coordinator:
--                              PENDING_INTAKE -> QUEUED -> ASSIGNED -> AWAITING_COORDINATOR -> ... ->
--                              COMPLETED/CANCELLED. Assignment is bound to session liveness; sign-offs
--                              and returns are embedded append-only records.

CREATE TABLE IF NOT EXISTS rearm.agent_boards (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_boards_org_name
    ON rearm.agent_boards ((record_data->>'org'), lower(record_data->>'name'));
CREATE INDEX IF NOT EXISTS agent_boards_org ON rearm.agent_boards ((record_data->>'org'));

CREATE TABLE IF NOT EXISTS rearm.agent_task_role_configs (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_task_role_configs_board_name
    ON rearm.agent_task_role_configs ((record_data->>'board'), lower(record_data->>'name'));
CREATE INDEX IF NOT EXISTS agent_task_role_configs_board
    ON rearm.agent_task_role_configs ((record_data->>'board'));
CREATE INDEX IF NOT EXISTS agent_task_role_configs_org
    ON rearm.agent_task_role_configs ((record_data->>'org'));
-- One preset per name per org. Board-scoped uniqueness stays with agent_task_role_configs_board_name,
-- whose expression is non-null for board rows.
CREATE UNIQUE INDEX IF NOT EXISTS agent_task_role_presets_org_name
    ON rearm.agent_task_role_configs ((record_data->>'org'), lower(record_data->>'name'))
    WHERE record_data->>'board' IS NULL;

CREATE TABLE IF NOT EXISTS rearm.agent_tasks (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

-- (org, externalRef) is the idempotency key for registration; partial because a draft split child may
-- briefly lack a tracker ref.
CREATE UNIQUE INDEX IF NOT EXISTS agent_tasks_org_external_ref
    ON rearm.agent_tasks ((record_data->>'org'), (record_data->>'externalRef'))
    WHERE record_data->>'externalRef' IS NOT NULL;

CREATE INDEX IF NOT EXISTS agent_tasks_org    ON rearm.agent_tasks ((record_data->>'org'));
CREATE INDEX IF NOT EXISTS agent_tasks_board  ON rearm.agent_tasks ((record_data->>'board'));
CREATE INDEX IF NOT EXISTS agent_tasks_status ON rearm.agent_tasks ((record_data->>'status'));
CREATE INDEX IF NOT EXISTS agent_tasks_parent ON rearm.agent_tasks ((record_data->>'parentTask'))
    WHERE record_data->>'parentTask' IS NOT NULL;
-- Session-close release path: find ASSIGNED tasks by their holding session.
CREATE INDEX IF NOT EXISTS agent_tasks_assignment_session
    ON rearm.agent_tasks ((record_data->'assignment'->>'session'))
    WHERE record_data->'assignment'->>'session' IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------
-- Session usage reports: what a session consumed, in which model, attributed to which task.
--
-- One row per (delta, model, hosting, context band) -- D11. A single report from the CLI carries several
-- lines when a delta spans models or crosses a pricing threshold, and each line becomes one row that
-- prices under exactly one pricing entry.
--
-- Real columns for everything filtered or summed (D5): rollups are index-assisted sums rather than jsonb
-- extraction. Everything else (windowStart/End, modelDeclared, variants, reasoningLevel, raw,
-- attribution, childOf) lives in record_data.
--
-- Append-only: rows are never updated after insert except `task`, which late attribution may set.

CREATE TABLE IF NOT EXISTS rearm.agent_session_usages (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    session uuid NOT NULL,
    agent uuid NOT NULL,
    task uuid NULL,
    board uuid NULL,
    model uuid NOT NULL,
    source text NOT NULL,
    hosting text NOT NULL default 'DIRECT',
    -- The FLOOR of the pricing band these requests fall in, in tokens: 0 for the base band, 200000 above
    -- the long-context threshold. A number rather than the client's label ('0', '200k'): the label put
    -- formatting into a unique key and made every comparison a string match on something inherently
    -- ordered. Part of the key: a delta crossing a threshold lands as one row per band.
    context_band bigint NOT NULL default 0,
    -- Client-side monotonic sequence; a transcript byte offset in the CLI, so 64-bit.
    client_seq bigint NOT NULL,
    reported_at timestamptz NOT NULL default now(),
    -- API requests (distinct message ids) summed into this row. The base of the oversize guard: a fixed
    -- token cap would refuse a legitimate first-run backfill, which can carry billions of cache-read
    -- tokens.
    requests integer NOT NULL default 0,
    input_tokens bigint NOT NULL default 0,
    output_tokens bigint NOT NULL default 0,
    cache_read_tokens bigint NOT NULL default 0,
    cache_write_tokens bigint NOT NULL default 0,
    -- Largest and smallest single-request context (input + cache read + cache write) in this row. The max
    -- selects the pricing entry (D10); the pair says whether the row straddles a threshold.
    max_request_context_tokens bigint NOT NULL default 0,
    min_request_context_tokens bigint NOT NULL default 0,
    turns integer NOT NULL default 0,
    tool_calls integer NOT NULL default 0,
    wall_seconds integer NOT NULL default 0,
    -- The client's own cost figure, kept beside the derived one and never summed into it.
    reported_cost_micros bigint NULL,
    record_data jsonb NOT NULL
);

-- Idempotency: a retried delta re-sends the same lines under the same sequence and inserts nothing.
-- Model, hosting and band are in the key because one delta legitimately produces several rows.
CREATE UNIQUE INDEX IF NOT EXISTS agent_session_usages_seq
    ON rearm.agent_session_usages (session, client_seq, model, hosting, context_band);
-- Task rollups. Partial: rows attributed to no task are the coordinator and unattributed cases.
CREATE INDEX IF NOT EXISTS agent_session_usages_task
    ON rearm.agent_session_usages (task) WHERE task IS NOT NULL;
-- Period rollups for an org and for a board.
CREATE INDEX IF NOT EXISTS agent_session_usages_org
    ON rearm.agent_session_usages (org, reported_at);
CREATE INDEX IF NOT EXISTS agent_session_usages_board
    ON rearm.agent_session_usages (board, reported_at) WHERE board IS NOT NULL;
-- Session rollup rebuild reads every row of one session in report order.
CREATE INDEX IF NOT EXISTS agent_session_usages_session
    ON rearm.agent_session_usages (session, reported_at);

-- ---------------------------------------------------------------------------------------------------
-- Which roles an agent has done on a board, for poll ordering (D32).
--
-- A table rather than a map on the board's record_data. It is written on every sign-off, and the
-- sign-off path holds the TASK row lock -- writing the board there would invert the lock order the
-- document publish path depends on (board before task, always). And it grows with every agent-role pair
-- a board has ever seen, inside a jsonb column read whole by every poll.
--
-- Advisory data: it orders a poll and never decides eligibility, so a row that is missing or stale costs
-- a worse choice of task, never a wrong one. Dropped with the board.
CREATE TABLE IF NOT EXISTS rearm.agent_role_history (
    board uuid NOT NULL,
    agent uuid NOT NULL,
    role uuid NOT NULL,
    last_signed_off_at timestamptz NOT NULL DEFAULT now(),
    -- How many times, so "most recent first" can fall back to "most often" without a second query.
    sign_offs integer NOT NULL DEFAULT 1,
    PRIMARY KEY (board, agent, role)
);

-- The primary key serves "what has this agent done HERE"; this index serves the ordering read after it.
CREATE INDEX IF NOT EXISTS agent_role_history_board_agent
    ON rearm.agent_role_history (board, agent, last_signed_off_at DESC);

-- ---------------------------------------------------------------------------------------------------
-- A board's event feed as a log, read since a point (operator decision 6, 2026-09-25).
--
-- The board row keeps its newest 50 events for the dashboard, trimmed on every post. Every event is also
-- written here, in the board save's transaction, with a seq a client keeps as its cursor. A table rather
-- than a longer list on the row: every reader of the board loads record_data whole, including the polls.
CREATE TABLE IF NOT EXISTS rearm.agent_board_events (
    uuid uuid PRIMARY KEY,
    org uuid NOT NULL,
    board uuid NOT NULL,
    -- The cursor: strictly increasing in insertion order, so "after seq" never skips an event written in
    -- the same instant as another.
    seq bigserial NOT NULL,
    kind text NOT NULL,
    message text NOT NULL,
    actor jsonb,
    event_at timestamptz NOT NULL,
    created_date timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS agent_board_events_board_seq ON rearm.agent_board_events (board, seq);

-- ---------------------------------------------------------------------------------------------------
-- The hard end of a device-login session (RD3-7): approval time plus the key's sessionMaxMinutes, or the
-- approver's shorter choice. The sliding expires_date never moves past it, access tokens end at it, and a
-- refresh after it is refused. Null means no key-level bound: the 90-day cap from approval.
ALTER TABLE rearm.cli_sessions ADD COLUMN IF NOT EXISTS hard_expires_date timestamptz NULL;
