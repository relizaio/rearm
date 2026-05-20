-- Squashed agentic migration. Combines the originally separate
-- V38..V43 series (agents_and_sessions, model_ontologies,
-- agent_policies, agent_identities, signature_verification) into a
-- single forward step so long-lived feature branches can't collide
-- on individual version numbers. Sub-sections preserve the original
-- file boundaries via SQL comments for forensic readability.
--
-- The original V41 included a backfill DO block + temporary
-- (org, lower(name)) unique index that have been dropped from the
-- squash: V41 only made sense when V37 had already shipped the old
-- shape to production. On a fresh deploy of this combined V38 the
-- agents table is created here with the final (org, agentIdentity,
-- lower(name)) uniqueness from the start, so the create-then-swap
-- and the row backfill are pure dead work. The original V42 fixup
-- that rewrote createdType="BACKFILL" → "AUTO" went with the backfill
-- it was patching. The psclaude sandbox — the only environment that
-- had the agentic branch deployed — was reconciled out of band; no
-- other deployment has agentic data, so the loss is zero.


-- ==================================================================
-- ===== originally V38__agents_and_sessions.sql
-- ==================================================================
-- AI-agent monitoring foundation. Two new entities live in the shared
-- (CE-included) layer; full design rationale in
-- backend/ai-plans/agentic/README.md.
--
--   agents          — registered coding agent (Claude Code, Cursor,
--                     etc.) scoped to an org. ROOT agents are what
--                     external coding tools register as; SUB agents
--                     are children a ROOT spawns. Hierarchy carried
--                     in record_data (agentType, rootAgent, subAgents).
--
--   agent_sessions  — one invocation of a ROOT Agent. Many concurrent
--                     sessions per agent allowed on the same API key.
--                     The session's `agent` is the ROOT; sub-agents
--                     contribute commits / artifacts under the same
--                     session. The agent supplies a clientSessionId
--                     (its own natural id) which the commit trailer
--                     references.
--
-- Policies / enforcement intentionally NOT in this migration — those
-- live under service.saas.* and arrive in PR 4 with their own
-- migration (input/output CEL evaluations + verdict log).
CREATE TABLE rearm.agents (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

-- Display-name uniqueness is keyed on (org, agentIdentity, lower(name))
-- — see the index near the bottom of the V41 section. Two different
-- agentIdentities under the same org can each own a "Claude Code".
CREATE INDEX agents_org         ON rearm.agents ((record_data->>'org'));
-- Hierarchy lookups: enumerate sub-agents of a given root, fast.
CREATE INDEX agents_root_agent  ON rearm.agents ((record_data->>'rootAgent'))
    WHERE record_data->>'rootAgent' IS NOT NULL;


CREATE TABLE rearm.agent_sessions (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

-- (org, agent, clientSessionId) is the agent-facing natural key the
-- commit trailer relies on. `agent` here is the ROOT — sub-agents do
-- not own sessions. Different roots can independently pick the same
-- client id without collision. clientSessionId is filled in the service
-- on insert when the caller omits it, defaulting to the row uuid so
-- the column is never NULL in practice.
CREATE UNIQUE INDEX agent_sessions_org_agent_client_id
    ON rearm.agent_sessions ((record_data->>'org'),
                             (record_data->>'agent'),
                             (record_data->>'clientSessionId'));

CREATE INDEX agent_sessions_org    ON rearm.agent_sessions ((record_data->>'org'));
CREATE INDEX agent_sessions_agent  ON rearm.agent_sessions ((record_data->>'agent'));
CREATE INDEX agent_sessions_status ON rearm.agent_sessions ((record_data->>'status'));

-- ==================================================================
-- ===== originally V39__model_ontologies.sql
-- ==================================================================
-- ModelOntology: a row describing one AI/ML model an Agent runs.
-- Carries publisher + name + version + a full CycloneDX ML-BOM
-- model card (jsonb). Multiple Agents reference the same row —
-- the ontology is the canonical model identity, not the agent's
-- private attribute. Full design rationale in
-- backend/ai-plans/agentic/README.md §3.3.
--
-- Auto-upserted on session initialize from (agentVendor, agentModel,
-- agentModelVersion) on the input; the user can attach a fuller
-- CycloneDX model card later via setModelOntologyModelCardProgrammatic.
CREATE TABLE rearm.model_ontologies (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

-- Identity: (org, lower(name), version). Distinct versions of the
-- same model live as separate rows.
CREATE UNIQUE INDEX model_ontologies_org_name_version
    ON rearm.model_ontologies ((record_data->>'org'),
                                lower(record_data->>'name'),
                                (record_data->>'version'));

CREATE INDEX model_ontologies_org ON rearm.model_ontologies ((record_data->>'org'));
-- Group-by lookup for the dashboard "agents by publisher" pivot.
CREATE INDEX model_ontologies_publisher
    ON rearm.model_ontologies (lower(record_data->>'publisher'))
    WHERE record_data->>'publisher' IS NOT NULL;

-- ==================================================================
-- ===== originally V40__agent_policies.sql
-- ==================================================================
-- AgentPolicy (SAAS-only): gates an Agent's session lifecycle via
-- CEL evaluation. INPUT policies evaluate at session init + on
-- artifact attach; OUTPUT policies evaluate when a commit is
-- attributed via the PR 2 trailer parser. BLOCK-severity input
-- failures reject the session-init mutation; everything else
-- records a verdict on the session's policyEvents list.
--
-- Note: this migration ships in the SAAS edition of the schema set
-- only. The CE backend uses the same Flyway scan path and is
-- expected to skip this V if compiled without the saas package —
-- but the schema is shipped together with rearm-core's resources.
-- The table existing on a CE deploy is harmless (no code references
-- it without the saas classes loaded).
CREATE TABLE rearm.agent_policies (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE UNIQUE INDEX agent_policies_org_name
    ON rearm.agent_policies ((record_data->>'org'), lower(record_data->>'name'));

CREATE INDEX agent_policies_org     ON rearm.agent_policies ((record_data->>'org'));
CREATE INDEX agent_policies_kind    ON rearm.agent_policies ((record_data->>'kind'));
CREATE INDEX agent_policies_enabled ON rearm.agent_policies ((record_data->>'enabled'))
    WHERE record_data->>'enabled' = 'true';

-- ==================================================================
-- ===== originally V41__agent_identities.sql
-- ==================================================================
-- Agent identity: the auth-bearing thing that an Agent registers under.
-- Lets us scope Agent uniqueness from (org, lower(name)) to
-- (org, agentIdentity, lower(name)) so two FREEFORM keys can each
-- own a "Claude Code" without colliding on the agents row.
--
-- Identity carries a list of (identity_type, identity_value) pairs —
-- currently only REARM_API_KEY, future-proofed for OIDC, etc. The pairs
-- live in a flat child table because jsonb-array uniqueness across rows
-- isn't expressible without triggers; the flat table also serves the
-- auth-time reverse lookup (given a key uuid, which identity?).

CREATE TABLE rearm.agent_identities (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX agent_identities_org
    ON rearm.agent_identities ((record_data->>'org'));

-- Credentials child. No FK to agent_identities per the rest of rearm's
-- schema conventions. Synthetic uuid PK for JPA simplicity; the real
-- enforcement is the UNIQUE(identity_type, identity_value) constraint
-- which also serves the auth-time reverse lookup.
CREATE TABLE rearm.agent_identity_credentials (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    agent_identity_uuid uuid NOT NULL,
    identity_type text NOT NULL,
    identity_value text NOT NULL,
    created_date timestamptz NOT NULL default now(),
    UNIQUE (identity_type, identity_value)
);

-- Reverse lookup: list a given identity's credentials.
CREATE INDEX agent_identity_credentials_aid
    ON rearm.agent_identity_credentials (agent_identity_uuid);

-- Display-name uniqueness boundary. Two different FREEFORM keys can each
-- own a "Claude Code" under the same org as long as they resolve to
-- distinct agentIdentities.
CREATE UNIQUE INDEX agents_org_identity_name
    ON rearm.agents (
        (record_data->>'org'),
        (record_data->>'agentIdentity'),
        lower(record_data->>'name')
    );

-- ==================================================================
-- ===== originally V43__signature_verification.sql
-- ==================================================================
-- Commit-signature verification. Three new entities are added.
--
-- 1. committers — a person (or bot) whose commits show up in the org's
--    SCEs. Optionally bound to a ReARM user; the binding is not
--    required so the table covers external contributors and ex-employees
--    whose user records were archived.
--
-- 2. signing_keys — polymorphic per-owner public key store. owner_type
--    is AGENT or COMMITTER (the two enrolled identity kinds). format
--    is SSH or GPG in v1; X509 anchors will live in a separate
--    trust_anchors table when that lands in v2 (see ai-plans). identity
--    holds the principal string ssh-keygen expects (email or principal)
--    for SSH; for GPG it carries the long key id. revoked_at is a soft
--    revocation marker — historical verdicts on the SCE side keep their
--    point-in-time decision and stay VERIFIED even after revocation.
--
-- 3. signature_verifications — one row per (subject, signature artifact)
--    verdict. subject_type is SCE in v1, ARTIFACT slot is forward-looking
--    for image/sbom signature attestations. The verdict + signer
--    attribution is the load-bearing field for CEL — policies read
--    record_data->>'verdict' and ->>'ownerUuid' to gate releases.
--
-- Verification semantics. ReARM never trusts the public key embedded in
-- a signature blob — for SSH the verifier builds an allowed_signers file
-- from enrolled keys, for GPG it imports only enrolled pubkeys into the
-- verification keyring. The signature blob proves possession of the
-- private key; the public side is always our registry's view.

CREATE TABLE rearm.committers (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX committers_org
    ON rearm.committers ((record_data->>'org'));

-- (org, lower(email)) uniqueness — email is the admin-side natural
-- key for finding / managing a committer row. The runtime signature
-- match goes through signing_keys.fingerprint, NOT this email; the
-- commit author/committer header is not consulted in v1. The SSH
-- allowed_signers principal lives on signing_keys.identity (typically
-- equal to this email but not enforced). Historical aliases live in
-- record_data->'aliases' rather than colliding on this index.
CREATE UNIQUE INDEX committers_org_email
    ON rearm.committers (
        (record_data->>'org'),
        lower(record_data->>'email')
    );

-- Optional reverse lookup: which committer is bound to a ReARM user?
CREATE INDEX committers_user
    ON rearm.committers ((record_data->>'user'))
    WHERE record_data->>'user' IS NOT NULL;


CREATE TABLE rearm.signing_keys (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX signing_keys_org
    ON rearm.signing_keys ((record_data->>'org'));

-- Owner lookup: "which keys does this agent/committer have?"
CREATE INDEX signing_keys_owner
    ON rearm.signing_keys (
        (record_data->>'org'),
        (record_data->>'ownerType'),
        (record_data->>'ownerUuid')
    );

-- Fingerprint lookup: "given a signature, which enrolled key matches?"
-- The fingerprint normalisation rules (sha256:… for SSH, full long key
-- id for GPG, X.509 sha256 fingerprint) live in SigningKeyService.
-- Partial unique on revoked_at IS NULL lets a fresh key reuse the
-- fingerprint after a revoke.
CREATE UNIQUE INDEX signing_keys_org_fingerprint_active
    ON rearm.signing_keys (
        (record_data->>'org'),
        (record_data->>'fingerprint')
    )
    WHERE record_data->>'revokedAt' IS NULL;


CREATE TABLE rearm.signature_verifications (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL
);

CREATE INDEX signature_verifications_org
    ON rearm.signature_verifications ((record_data->>'org'));

-- Subject lookup: "what verdicts attach to this SCE / artifact?"
CREATE INDEX signature_verifications_subject
    ON rearm.signature_verifications (
        (record_data->>'subjectType'),
        (record_data->>'subjectUuid')
    );

-- Owner lookup: "what has this agent signed?"
CREATE INDEX signature_verifications_owner
    ON rearm.signature_verifications (
        (record_data->>'ownerType'),
        (record_data->>'ownerUuid')
    )
    WHERE record_data->>'ownerUuid' IS NOT NULL;
