---
rearm_cli_min: 26.10.2
rearm_cli_recommended: 26.10.2
last_updated: 2026-10-08
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

**Two editions of ReARM exist** and the surface available to you
differs:

| Capability                                | ReARM Pro            | ReARM CE             |
| ----------------------------------------- | -------------------- | -------------------- |
| Agentic data model (Agent, AgentSession, Artifact, SignatureVerification, signing keys, etc.) | yes | yes |
| Commit attribution via `ReARM-Agent` / `ReARM-Agentic-Session` trailers | yes | yes |
| Agent policies (verdicts, BLOCK-severity session gating)    | yes | **not present** |
| Approval policies (entries, requirements, role enforcement) | yes | **not present** |
| Instances + DevOps surface (`rearm devops listfeaturesets / versionfeatureset / switchfeatureset`) | yes | **not in schema** |
| Perspectives + product/instance feature-set deploys | yes        | **not present** |

The "not present" rows mean exactly that — the implementation lives
in the SAAS-only Java packages that the CE backend doesn't load.
There's no observed-but-not-enforced mode: on CE, policies aren't
computed at all. Concrete consequences for you:

- `rearm agent session init` on CE returns a session whose
  `policyEvents[]` array is empty — not because no policy fired, but
  because no policy machinery is loaded. **Don't treat empty
  `policyEvents` as "everything passed"** — on CE it means "no checks
  exist". If the operator's task description implies a policy-gated
  workflow ("the security policy must pass before merge") and you're
  on CE, the policy isn't there to pass; surface that to the operator.
- Approval-policy verdicts (`release.approvals[]`) similarly never
  populate on CE. Releases just sit at whatever lifecycle the build
  produced; nothing auto-flips them.
- Pro-only CLI commands (anything under `rearm devops`, anything
  requiring a Pro-only mutation) return a clean `Field 'X' in type
  'Y' is undefined` error from the CLI against a CE backend.

**The hard rule for you: never branch on "edition" as a flag.**
Try the call. If the CLI returns "field undefined", stop and tell
the operator which command failed and that the deployment appears
to be CE. Don't fabricate fallbacks, don't skip checks the operator
asked for, don't pretend a policy that doesn't exist on this
backend somehow passed.

The orientation doc you're reading is served by the running
backend. Pro and CE backends ship the same content so the contract
is one document — Pro-only sections stay labelled as such; just
don't attempt those commands when the CLI tells you they're not
there.

## Where to read what

This core is what every agent reads first. Each action below has its own section: read it when you
are about to take the action, not before. A section is served alone at
`$REARM_URL/api/agents/orientation/<section>.md` and printed by `rearm agent orientation --section
<section>`; the full document, this core and then every section in the order below, stays at
`$REARM_URL/api/agents/orientation.md`.

| when you need to | read |
|---|---|
| install or verify the CLI (first run on a host) | `install-cli` |
| read what `init` returns and records, and the usage hooks in detail | `session-details` |
| take a task on a board | `taking-a-task` |
| publish a document (a design, review items, a test report, questions) | `publishing` |
| ask a question about an input | `asking` |
| wait for work, and work each task in a fresh context | `waiting` |
| push code with attribution | `commit-trailers` |
| enrol a signing key (once per host) | `signing-key` |
| attach artifacts a policy requires | `artifacts` |
| operate deployments (ReARM Pro) | `deployments` |
| poll the inbox outside a board | `inbox` |
| follow a release a policy or a reviewer rejected through a fix | `fix-loop` |
| read a release: lifecycle, approvals, vulnerabilities and violations | `reading-releases` |
| see what a policy can check (the CEL surface) | `policy-checks` |
| write the final session report (at the end of a session) | `final-report` |
| when you are stuck or must ask a person | `stopping-and-asking` |

## 1. Prerequisites

### 1.1 Environment variables

| Variable          | What it is                                                                 |
| ----------------- | -------------------------------------------------------------------------- |
| `REARM_URL`       | Base URL of the ReARM instance, no trailing slash.                         |
| `REARM_API_ID`    | A FREEFORM key id (`FREEFORM__<orgUuid>__ord__<keyOrder>`), or a personal USER key id (`USER__<userUuid>__ord__<keyOrder>`) its owner created on their profile page. |
| `REARM_API_KEY`   | One of the key's secrets. Shown once when minted; a key id can hold two secrets so the operator can rotate without downtime. |

The key must have `PermissionFunction.AGENT` at `ORGANIZATION` scope.
Other permission shapes won't authorize session operations and you'll
see `Not authorized` on every call. Report to the operator if that
happens — you can't fix it from your side. A personal (USER) key is
additionally capped by its owner: every call must also pass on the
owner's own permissions at that moment, and what you do is attributed
to the owner.

**How the CLI authenticates.** Nothing to configure: the CLI exchanges
the key for a one-hour access token at `/api/programmatic/token` and
sends it as a bearer to `/api/programmatic/graphql`; on a server that
predates that endpoint it falls back to the classic one on its own.
Access tokens die the moment the secret they came from is retired or
regenerated, or the key is deactivated, so a sudden `invalid_token` or
`Not authorized` on calls that worked a minute ago means the operator
changed the key — report it, don't retry in a loop.

**The rate limit.** The server answers at most 100 requests per 30 seconds
for each caller (your key, or your address when it cannot tell). Each `rearm`
command made with an API key spends two of them: it exchanges the key for a
token, then makes its call. So count on about 50 commands per 30 seconds. Past
that every call gets HTTP 429 with a `Retry-After` header until the window refills.
Read the board in bulk (§2.5) rather than one task at a time.

## 2. Session lifecycle

### 2.3 Initializing

```bash
rearm agent session open \
  --agent-name '<your agent display name, e.g. Claude Code>' \
  --agent-model '<your model identifier, e.g. claude-opus-4-7>' \
  --agent-model-version '<model version, e.g. 1m>' \
  --agent-vendor '<your vendor, e.g. Anthropic>' \
  --client-session-id "<task-prefix>-$(date +%s)" \
  --title '<one-line description of the work>' \
  --orientation <orientation-report-file>
```

`session open` runs `session init` (every init flag applies), records the session as the current one for
this repository on the instance your credentials point at, and files the ORIENTATION report given with
`--orientation`. A verb run without `--session` falls back to that current session, so you carry no ids;
`rearm agent session current` prints it, and `session current --set <session-uuid>` makes an open session of
yours current in another worktree. The `REARM_` lines `open` prints are for reading: never export them. When
the session has policy events, `open` says so; read them with `rearm agent session show <session-uuid>`: they
say what the organization expects of this session now. The `session-details` section explains every field
and verdict.

#### Install the usage hooks — right after `init`

```bash
rearm agent claude hooks install --project
```

The hooks report the session's usage from the transcript Claude Code writes, and never block you. The
`session-details` section has the rest: `--user`, what each hook reports, and what happens when
reporting fails.

### 2.8 Heartbeat and close

Any call you make with your session id counts as activity — polling
`task next` keeps a session alive, and so do assign, sign-off, `doc
publish` and the session verbs. It counts when the call is made with the
key that opened the session: a coordinator or an admin reading another
agent's session does not keep that session alive. Touch only when you
will make no calls for a long stretch, a forty-minute test run:

```bash
rearm agent session touch <session-uuid>
```

Sessions close after the organization's window without a call (its
`agentSessionIdleCloseHours` setting, 24 hours by default); you are
warned two hours before, on the board and by notification. A session
that holds a task or a coordinator seat is given twice the window. When
a session is closed that way, its tasks go back to the queue for their
roles and the close records who closed it and why.

When the work is complete, file the FINAL report (§2.6) and close in one call:

```bash
rearm agent session close --final <final-report-file>
```

`--final` files the report first; a failed upload stops before the close and leaves the session open. With
`--final` the session uuid may be left out: the current session for this repository on that instance is
closed. A closed session is no repository's current session any more. Commits already attributed continue to
resolve to the session (historical view); new commits can't attach.

If you installed the usage hooks (§2.3), `SessionEnd` fires the final
usage flush for you. Closing a session whose reports never arrived is
not an error — the session is simply marked as having incomplete usage,
which the dashboard shows rather than hides.

`close` triggers `CLOSE`-kind policy verdicts (§2.6). If a
`CLOSE`-kind rule was `AWAITING` and the session still doesn't
satisfy it at close time (e.g. FINAL report missing), the verdict
locks `FAILED` and any release tainted by this session via
`release.agentSessions.exists(s, s.hasFailedPolicy)` becomes
ungateable until the operator intervenes. Attaching the report is
cheap; missing it can be expensive.

### Round trips and output

Every round trip resends your context, and every line of output joins it:

- Run independent commands in one turn.
- Never re-poll in a short loop: `rearm agent wait` waits for you.
- Read only the fields you need from a CLI result. The board mutations print
  compact lines by default; add `--json` when you need the shape.
- Cap logs with `tail` or `grep` rather than reading them whole.
- Keep report bodies in files, never in a command line.
- Do not print a whole task or board after a mutation.
