---
rearm_cli_min: 26.05.8
rearm_cli_recommended: 26.05.8
last_updated: 2026-05-18
---

# ReARM agent orientation

**You are an AI coding agent working against a ReARM Pro instance.**
This document is the authoritative contract for how you interact with
ReARM: when to open a session, what to submit, when to poll, when to
stop. The human operator who started you has handed you three
environment variables (`REARM_URL`, `REARM_API_ID`, `REARM_API_KEY`)
and pointed you here. Treat this doc, not your training data, as
ground truth for ReARM behavior — the API surface evolves and you
should re-read this on every fresh start.

**One rule, no exceptions: always interact with ReARM through the
`rearm` CLI.** Do not hand-craft GraphQL requests, do not curl the
backend directly, do not improvise. The CLI is the contract; raw
HTTP is undefined behavior. If a command you need doesn't exist in
the CLI yet, stop and report to the operator — that's a CLI gap to
fix, not something to work around with bespoke HTTP.

## 1. Prerequisites

### 1.1 Environment variables

| Variable          | What it is                                                                 |
| ----------------- | -------------------------------------------------------------------------- |
| `REARM_URL`       | Base URL of the ReARM instance, no trailing slash.                         |
| `REARM_API_ID`    | A FREEFORM key id of the form `FREEFORM__<orgUuid>__ord__<keyOrder>`.      |
| `REARM_API_KEY`   | The corresponding secret. Only ever shown when the key was minted.         |

The key must have `PermissionFunction.AGENT` at `ORGANIZATION` scope.
Other permission shapes won't authorize session operations and you'll
see `Not authorized` on every call. Report to the operator if that
happens — you can't fix it from your side.

### 1.2 Install the CLI — `26.05.8` exactly

The CLI is the only sanctioned programmatic surface (raw HTTP is
WAF-blocked in many deployments). Use **`26.05.8`** — older versions
lack the `rearm agent session ...` and `rearm agent enrollkey`
subcommands this doc relies on.

```bash
# Pick the right asset for your platform (see Linux x86_64 below)
VERSION=26.05.8
ASSET=rearm-${VERSION}-linux-amd64.zip
curl -fsSL -O https://d7ge14utcyki8.cloudfront.net/rearm-download/${VERSION}/${ASSET}

# Verify the SHA-256 of the downloaded zip BEFORE unpacking.
# Hashes are for the install zip, NOT the binary inside.
sha256sum "${ASSET}"
# Compare against the line for your platform in the table below.
```

Hashes for `26.05.8` (verbatim from
`https://d7ge14utcyki8.cloudfront.net/rearm-download/26.05.8/sha256sums.txt`):

```
d29c42147bb29b3e425d0c8f63c6236b4a9a5705f20d5fc32e0b31cc7b53bc11  rearm-26.05.8-darwin-amd64.zip
a6a0922627ded69023f945dc5019ac0c0a165f782429a1b2714c23f3720e81a1  rearm-26.05.8-darwin-arm64.zip
ef446ad3e40e5ee927e5dc387ebc28ba85233341dcb0682ed49ad38610af355c  rearm-26.05.8-freebsd-386.zip
221e3c1a18b7cb7ef096be55cd497a5ed0d4311a2a13b0637078a3ce1c495c9a  rearm-26.05.8-freebsd-amd64.zip
86e1688cc7d35f58a0233e7652dcfaaff987c5a59c8d9c827d33e6f94c27617a  rearm-26.05.8-freebsd-arm.zip
e94eb24f0f90ad98a6192cf5b5a47b3f6c74a9d734a51bafe53c8913ba9b7d6d  rearm-26.05.8-linux-386.zip
8512c3d874500da335be9d2e53455efc7b5979410174f307f568667fb54f0851  rearm-26.05.8-linux-amd64.zip
2dd014842755c6b1f29e9b2c1a93b8f4fccd443b0920927125250604430beff8  rearm-26.05.8-linux-arm.zip
59779bb4be5f3d7a5c5d7a523c02dc99d6be102ddc310b0efd433436fcae05ed  rearm-26.05.8-linux-arm64.zip
413ba1ea9b9e03741936379c3d09490b638c6b2d76d1e2aa1263839b6d7b5ff8  rearm-26.05.8-openbsd-386.zip
becdc5676bbc6ba7c75d296c2c61b3f2f0b680c5efa44c664bd76bd516380afd  rearm-26.05.8-openbsd-amd64.zip
53d3cea21e3cd5a9e6fffa898ff022f0229253b9eb649b3b4c3c0b3d34a531cb  rearm-26.05.8-solaris-amd64.zip
0556b354eb5ca5b45ed8f14b4749fb4ee4e0e38892898f6858e2b99468175ad2  rearm-26.05.8-windows-386.zip
6f74508b6161ae02123ab6ee123f4136b9950204b1fc0c21f2a15fd5df441d1b  rearm-26.05.8-windows-amd64.zip
```

If the hash doesn't match, **stop**. Don't run an unverified binary.
Re-fetch from the canonical URL or escalate to the operator.

Confirm the CLI is on your `PATH`:

```bash
rearm version
```

If the version reports older than `rearm_cli_min` (front-matter at
the top of this doc), stop and ask the operator to bump.

## 2. Session lifecycle

### 2.1 When to open a session

**Open one session per cohesive piece of work.** Examples of one
session each: fixing a security finding, adding a feature, continuing
a prior task on the same branch.

Don't open a session if you're not going to write code or attach
artifacts. Read-only investigation doesn't need a session.

### 2.2 Picking a `clientSessionId`

`clientSessionId` is **permanently unique** within `(org, agent)`. A
value previously used by any session — OPEN, CLOSED, or BLOCKED —
can never be reused. ReARM will reject `rearm agent session init`
with a `clientSessionId-conflict` error.

It is **a free-form string** (not a UUID). The on-the-wire commit
trailer carries this exact string, so allowed characters are
`^[A-Za-z0-9._-]+$` — no whitespace, no slashes, no colons. A
typical pattern is `<short-task-id>-<unix-timestamp>` (e.g.
`auth-bug-fix-1779124086`); the timestamp guarantees uniqueness,
the short prefix makes it readable in the dashboard.

### 2.3 Initializing

The flags below use `Claude Code` / `claude-opus-4-7` / `Anthropic`
as **examples only** — substitute your actual agent name, model
identifier, and vendor. ReARM auto-registers an Agent + ModelOntology
row on first use of a (name, model, vendor) tuple, so be consistent
across runs.

```bash
rearm agent session init \
  --agent-name '<your agent display name, e.g. Claude Code>' \
  --agent-model '<your model identifier, e.g. claude-opus-4-7>' \
  --agent-model-version '<model version, e.g. 1m>' \
  --agent-vendor '<your vendor, e.g. Anthropic>' \
  --client-session-id "<task-prefix>-$(date +%s)" \
  --title '<one-line description of the work>'
```

Capture the response. Three fields you must record for the rest of
the session:

| Field             | Why you need it                                                                                |
| ----------------- | ---------------------------------------------------------------------------------------------- |
| `uuid`            | Session row uuid — what you pass to every other `rearm agent session ...` command.             |
| `status`          | **Must equal `OPEN`** to proceed. See §6 for any other value.                                  |
| `agent`           | Root-agent uuid — what goes in your `ReARM-Agent:` commit trailer (and in `agent enrollkey`).  |

The response also includes `policyEvents[]`: one entry per active
agent policy in the org with its current verdict (`PASSED`, `AWAITING`,
`FAILED`, `WARNING`). **Read this on every init.** It tells you what
the org expects of your session right now — for example, if the
operator has enabled an "orientation report required" policy, you'll
see an `AWAITING` verdict pointing at it, and you'll know to submit an
orientation artifact (§2.4) before authoring commits.

Each `policyEvents[].policy` carries the policy's `cel` expression
and `description` — use these to decide whether a failing/pending
policy is recoverable on your side or needs operator action (§6).

### 2.4 Enrol your signing key (first run, once per agent)

You sign commits with an SSH or GPG key. ReARM matches the signature
against keys enrolled under your agent uuid; if no key is enrolled,
commits land as `signature.state=UNVERIFIED` and the component-level
signed-commits gate will reject the release.

The agent enrols its **own** key — operators don't have to do this
ahead of time:

```bash
# SSH key example. The principal goes in your allowed_signers entry
# and must match what you set in git config's user.signingkey /
# user.email when signing.
rearm agent enrollkey \
  --org '<orgUuid from REARM_API_ID>' \
  --agent '<agent uuid from init response>' \
  --format SSH \
  --pubkey-file ~/.ssh/agent_signing_key.pub \
  --identity '<your.email@your-vendor.example>'
```

The fingerprint is auto-derived locally via `ssh-keygen -lf`
(or `gpg --with-colons --show-keys` for `--format GPG`). Pass
`--fingerprint` explicitly if the local tool isn't available.

If a key is already enrolled and you're rotating it, enrol the new
one and then revoke the old via the operator (CLI doesn't yet have
a self-revoke; that's an operator JWT path).

### 2.5 Artifacts on the session (when policies require them)

Artifacts attached to the session are evaluated by the org's agent
policies (the `policyEvents[]` you saw on `init`). **Not every org
requires an orientation artifact**; whether you need to attach
anything is policy-driven. Read `policyEvents[]` to find out.

The canonical case is an `AWAITING` verdict on an "orientation-report"
policy whose CEL is something like `!session.artifacts.exists(a,
a.type == "AGENTIC_REPORT")` (match-to-block: the expression matches
— i.e., the policy *fires* — when no AGENTIC_REPORT is attached). To
satisfy it, upload a JSON brief and bind it to the session in one
command:

```bash
cat > /tmp/orient.json <<'JSON'
{
  "agentic_phase": "ORIENTATION",
  "plan": "1-3 sentences on what you're going to do",
  "context": {
    "task_source": "operator-prompt | github-issue | …",
    "target_branch": "main",
    "task_id": "JIRA-1234 or similar"
  }
}
JSON

rearm agent session add-artifact <session-uuid> \
  --file /tmp/orient.json \
  --type AGENTIC_REPORT \
  --display-id orient \
  --tag agenticPhase=ORIENTATION
```

The artifact is owned by the session (`belongsTo=AGENT_SESSION`) — it
does not appear on any release or component. The single command does
both the upload and the bind; you cannot attach a pre-existing
release / SCE artifact to a session by uuid (that's intentional —
artifacts that originate elsewhere don't belong on a session).

Common tags to know about:

| Tag                                | When to set it                                                                       |
| ---------------------------------- | ------------------------------------------------------------------------------------ |
| `agenticPhase=ORIENTATION`         | Initial plan/brief at session start.                                                 |
| `agenticPhase=CHECKPOINT`          | Mid-session progress update.                                                         |
| `agenticPhase=FINAL`               | End-of-session summary.                                                              |

Policies pattern-match on tags, so use the canonical names if you
want the corresponding policy gates to fire. If your org's policies
don't reference any of these, tags are still preserved on the
artifact for the operator's audit.

### 2.6 Commit trailers

Every commit you author needs the canonical two-line trailer block:

```
<your normal commit subject>

<your normal commit body>

ReARM-Agentic-Session: <clientSessionId-you-picked>
ReARM-Agent: <root-agent-uuid-from-init>
```

`ReARM-Agentic-Session` is the **free-form string** you chose at
`init` (not the row uuid). Both trailers are case-insensitive on the
key and the value runs to first whitespace. Order doesn't matter,
both must be present.

Sign the commit with the key you enrolled in §2.4. An unsigned commit
will be rejected by the signature gate on the component side.

The trailers surface back as `SourceCodeEntry.agent` and
`SourceCodeEntry.agentSession` once a CI run picks up the commit via
`rearm getversion --scearts …` or `rearm addrelease`.

### 2.7 Heartbeat and close

While you're active, you don't need to do anything — the session
auto-tracks `lastActivityAt` on every state change. If you're going
quiet for a stretch but the work isn't done, send a heartbeat so the
dashboard's "connected" pill stays honest:

```bash
rearm agent session touch <session-uuid>
```

When the work is complete, close:

```bash
rearm agent session close <session-uuid>
```

Idempotent. Commits already attributed continue to resolve to the
session (historical view); new commits can't attach.

## 3. Polling the inbox

While the session is OPEN and you're waiting on a downstream event
(release lifecycle flip, human approval verdict, post-init policy
re-evaluation), poll the inbox:

```bash
rearm agent session inbox <session-uuid> --since '<last-cursor>'
```

Each event carries an opaque `cursor` (ISO-8601 timestamp today;
might be richer later — don't parse it). Pass the most recent
event's `cursor` on the next call's `--since` to fetch strictly
newer events. First call: omit `--since`.

**Cadence: 60 seconds.** Faster wastes ReARM CPU without buying
latency anyone cares about; slower makes the human reviewer's
disapproval feedback feel sluggish.

**Don't poll during major tasks.** If you're in the middle of a
non-trivial piece of work — a multi-file refactor, a long reasoning
chain, a complex test-fixing pass — finish the task first, then poll.
The inbox is a *between-tasks* signal, not an *interrupt-me-mid-
thought* signal. A reviewer's verdict that lands while you're heads-
down on a refactor can wait the extra few minutes until you reach a
natural checkpoint; reading it earlier just splits your attention
and degrades the work. Conversely, when you're idle (waiting on a
human decision, waiting on CI to finish), the 60-second cadence
applies — that's exactly the loop the inbox is designed for.

### Event kinds and what to do with them

| `kind`            | `source`        | What it means                                                                                                                          | Default action                                                                                                                                  |
| ----------------- | --------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `LIFECYCLE_CHANGE`| `RELEASE_AUTO`  | A release minted from your commits is now `PENDING` / `ASSEMBLED` / etc.                                                               | Informational. ASSEMBLED on a release attributed to your session usually means your work landed; you might be done.                              |
| `LIFECYCLE_CHANGE`| `POLICY_GATE`   | A CEL gate flipped the release (typically to `REJECTED`). `reason` carries the trigger name + the matched CEL.                          | Read `reason`; if you can recover (e.g. attach a missing artifact, fix a signature), do so and push a new commit. If not, escalate.              |
| `LIFECYCLE_CHANGE`| `HUMAN`         | An operator manually flipped lifecycle.                                                                                                | Read `reason` if present; treat as authoritative — don't undo.                                                                                   |
| `APPROVAL`        | `HUMAN`         | A reviewer hit Approve or Disapprove. `newValue` carries the state, `reason` is the reviewer's comment.                                | DISAPPROVED with a comment is the canonical "fix-loop" signal: open a follow-up PR addressing the comment. APPROVED is informational.            |
| `POLICY_VERDICT`  | `POLICY_GATE`   | A non-PASSED PolicyEvent landed on your session itself (post-init re-evaluation).                                                       | Check `reason` and the embedded `policy.cel` to decide if recoverable (attach a missing artifact and retry) vs structural (see §6).              |

### What's NOT in the inbox

- PASSED policy verdicts (intentional — agents only get the
  problems, not the green ticks).
- Events on releases that aren't attributed to this session.
- Approval *role* changes (the policy itself is mutated by the
  operator). If you need the current policy surface, `rearm agent
  session show <uuid>` returns the full state including
  `policyEvents[].policy` snapshots.

## 4. Sample "agent fix loop" pattern

The disapproval-then-fix flow distilled to mechanics:

```
init session                                                       # §2.3
  ├─ enrol signing key (first time only)                           # §2.4
  ├─ attach orientation artifact if policy requires                # §2.5
  ├─ author signed+trailered commits                               # §2.6
  ├─ open PR                                                       # standard SCM flow
  └─ between major tasks, poll inbox every 60s                     # §3
       └─ event: APPROVAL { newValue=DISAPPROVED, reason="…" }
            ├─ parse reason → understand what to fix
            ├─ author signed+trailered commit on the same branch   # same session, same trailers
            ├─ push (PR auto-updates)
            └─ continue polling
       └─ event: APPROVAL { newValue=APPROVED }
            └─ session work is done — close the session
```

**Stay in the same session** through the loop. Don't open a new
session for the fix commit — the loop's audit trail threads cleanly
through one session, and the artifacts you attached at the start
still count.

## 5. Read-side helpers

When you need the full current state of a session (after an inbox
event, on startup, when debugging an attribution issue):

```bash
rearm agent session show <session-uuid>
```

Returns the session shape with `policyEvents` (each carrying the
embedded `policy` snapshot — `cel`, `description`, `enabled`),
`releases[]`, `pullRequests[]`, `parentSession`, the full `artifacts`
and `commits` lists.

When the inbox points you at a release uuid (typical for
`LIFECYCLE_CHANGE` and `APPROVAL` events), look it up:

```bash
rearm agent release show <release-uuid>
```

Returns `updateEvents[]` (human-readable lifecycle reasons,
pre-aggregated — no need to re-derive from triggers),
`approvalEvents[]` (full approval history with reviewer comments),
and `sourceCodeEntryDetails[]` (per-commit attribution + signature
state). The `updateEvents[].message` field is the most useful single
field — it spells out *why* a CEL gate flipped the release (e.g.
*"Triggered by 'Reject when any commit is not VERIFIED' (CEL: …)"*).

## 6. CEL surface (what policies can check)

Operator-authored agent policies evaluate against three variables on
your session: `session.*`, `agent.*`, `model.*`. Policies use **match-
to-block** semantics — the CEL describes the *failure* condition.
CEL true → policy *fires* (FAILED / WARNING / AWAITING). CEL false →
PASSED. Common shapes:

```
!session.artifacts.exists(a, a.type == "AGENTIC_REPORT")           # orientation missing → block
agent.model != "claude-opus-4-7"                                   # disallowed model → block
session.policyEvents.exists(p, p.state == "FAILED")                # any FAILED verdict → block
```

Component-level CEL gates evaluate against the release shape:

```
release.commits.exists(c, c.signature.state != "VERIFIED")         # signed-commit gate
release.commits.exists(c, c.attribution.state == "REJECTED")       # attribution gate
release.agentSessions.exists(s, s.hasFailedPolicy)                 # session-policy gate
```

The CEL helper in the ReARM UI's policy edit page is the
authoritative reference — every field with sample expressions. Ask
the operator to share the relevant policy if a verdict is unclear.

## 7. When to stop and ask the operator

**This is the most important section. Read it carefully.**

ReARM gives you several signals that a session can't be salvaged
without operator action. Recognise them and stop — don't loop, don't
auto-retry with a different model, don't pretend the policy doesn't
exist.

### 7.1 `session.status = BLOCKED`

A `BLOCK`-severity INPUT policy failed at `init`. The session row is
persisted (so the operator can see the rejected attempt and its
`policyEvents`), but commits can't bind to it. You **cannot** make it
OPEN; mint a fresh session if and only if you can fix the cause.

Before retrying, decide: is the failure something you can fix
yourself, or does it require operator action? Look at
`session.policyEvents[].policy.cel`:

Each CEL below is what *fires* — i.e., when the expression is true,
the policy blocks the session:

| Example policy CEL (block when…)                                  | Recoverable by you?                                                  |
| ----------------------------------------------------------------- | -------------------------------------------------------------------- |
| `!session.artifacts.exists(a, a.type == "AGENTIC_REPORT")`        | **YES** — submit the orientation and retry with a new session id.    |
| `!agent.model.startsWith("claude")`                               | **NO** — your intrinsic property doesn't match. Stop and report.     |
| `model.modelCard == ""`                                           | **MAYBE** — depends if you have a way to attach a model card.         |
| `session.startedAt > commercial_hours_end`                        | **NO, but** could retry later. Don't busy-loop; report to operator.   |

When you can't recover unilaterally, exit with a non-zero status and
a clear message naming the policy and its message. Don't loop. Don't
pick a different model. Don't pretend the policy doesn't exist.

### 7.2 Repeated `attribution.state = REJECTED`

Your commits keep landing as `attributionState=REJECTED` despite
trailers being present. Common causes:

- `clientSessionId` in the trailer doesn't match what you used at
  `init` — a typo or stale value. Re-read the session via
  `rearm agent session show <uuid>` and confirm the `clientSessionId`
  field matches the trailer you're stamping.
- Agent uuid in the trailer points at a different agent (you have
  several registered).
- Cross-org — the agent's org doesn't match the commit's component
  org.

Each is an agent-side bug. Fix the trailer template and move on, but
stop attempting commits until you understand the cause.

### 7.3 Lifecycle stuck at REJECTED on multiple retries

If you fix the reported issue and push, but the release still goes
REJECTED with the *same* `reason`, stop. The policy may be checking
something you can't influence (vuln count on a third-party dependency,
an external scan result). Report to the operator with the release
uuid, the triggering CEL, and the fix you tried.

## 8. Self-check on startup

When you bootstrap, fetch this doc fresh from the running ReARM
instance via the `rearm` CLI — the content is pinned to the backend
version, so a curl will always match the API surface the instance
exposes.

Check the front-matter at the top of this doc:

- `rearm_cli_min` — if your installed `rearm` CLI version is below
  this, stop and ask the operator to bump (or install per §1.2).

## 9. Quick command index

```bash
# --- session lifecycle ---
rearm agent session init --agent-name … --agent-model … --client-session-id … --title …
rearm agent session show <uuid>
rearm agent session touch <uuid>
rearm agent session close <uuid>

# --- signing-key self-enrolment (first run, once per agent) ---
rearm agent enrollkey --org <orgUuid> --agent <agentUuid> --format SSH \
  --pubkey-file <path> --identity '<allowed_signers principal>'

# --- artifacts on the session (when policies require) ---
rearm agent session add-artifact <session-uuid> --file <path> --type <Type> --tag k=v

# --- inbox (60s cadence, only between major tasks) ---
rearm agent session inbox <session-uuid> --since '<last cursor>'

# --- release inspection after an inbox event ---
rearm agent release show <release-uuid>
```

---

If you find a behavior the doc doesn't describe, the doc is wrong.
Surface the gap to the operator so it gets fixed in the next ReARM
release rather than guessing.
