---
rearm_cli_min: 26.05.19
rearm_cli_recommended: 26.05.19
last_updated: 2026-05-28
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

### 1.2 Install the CLI — `26.05.19` exactly

The CLI is the only sanctioned programmatic surface (raw HTTP is
WAF-blocked in many deployments). Use **`26.05.19`** — older versions
lack the `rearm agent session ...` / `rearm agent enrollkey`
subcommands and the release vulnerability/violation output this doc
relies on.

```bash
# Pick the right asset for your platform (see Linux x86_64 below)
VERSION=26.05.19
ASSET=rearm-${VERSION}-linux-amd64.zip
curl -fsSL -O https://d7ge14utcyki8.cloudfront.net/rearm-download/${VERSION}/${ASSET}

# Verify the SHA-256 of the downloaded zip BEFORE unpacking.
# Hashes are for the install zip, NOT the binary inside.
sha256sum "${ASSET}"
# Compare against the line for your platform in the table below.
```

Hashes for `26.05.19` (verbatim from
`https://d7ge14utcyki8.cloudfront.net/rearm-download/26.05.19/sha256sums.txt`):

```
86aca7d26831ba2223f92474c0fb09df358ad1cb3d5900014fe01d37a65be62f  rearm-26.05.19-darwin-amd64.zip
9acfe21f43b16d58f49da4d8f07325db018d0da37862b2e613db8cf043c38308  rearm-26.05.19-darwin-arm64.zip
a58de1a83cc458247b9d5d8a4e2ce656b08b65751ca15dd57e67a1aecc870187  rearm-26.05.19-freebsd-386.zip
639e3375fad9f22e223b2e361ea5d9a0f0609c21d11edf107f08857b274f593e  rearm-26.05.19-freebsd-amd64.zip
3882c4bc67bb265fc3b2624499243661d716642e3a499cba5694012c04eef394  rearm-26.05.19-freebsd-arm.zip
63b22ec64430e1f9396e02a022f597564c5c975d8f4c36a86eb2f6be550eaa23  rearm-26.05.19-linux-386.zip
3b89e52b43b2ea4ff46fd243a435e07eb0420e19d80452c87cc7c8e52ee45901  rearm-26.05.19-linux-amd64.zip
e464245a5c328a7a1256a25655df43fb24dfcf2600b17115c50d58f3144bff27  rearm-26.05.19-linux-arm.zip
1371acff4d6bbe76aa31bccd24db0c1d6450016c8e4447d6c02ff6409afb08cb  rearm-26.05.19-linux-arm64.zip
df5da80633fe048a0ef1a1accb69d0597f4936a3a91b6093c04079e9d01d6d4b  rearm-26.05.19-openbsd-386.zip
4b1f91715c300de72ec70e343fb0b7acf84efb7780c5729dc0d76b4d60ebf548  rearm-26.05.19-openbsd-amd64.zip
121bf114740bf012b6456e6c31ebd0733bcdc3eeaca3e7ba6179e5877783bc03  rearm-26.05.19-solaris-amd64.zip
0be42938ddf2832a0aec75702145810168ba6f125588f86af4d38276da35a251  rearm-26.05.19-windows-386.zip
92b5a19fe8956a30fc399c708ae51049e3e1c922cb529ae6170d10faa267acf2  rearm-26.05.19-windows-amd64.zip
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

**`AGENTIC_REPORT` artifacts must be UTF-8 text — JSON is the expected
shape** (the orientation and final reports below are JSON). ReARM
renders them in an in-app read-only viewer that pretty-prints JSON and
otherwise shows the raw text, so operators can read a report without
downloading it. Don't attach binary blobs (zips, images, compiled
output) under the `AGENTIC_REPORT` type — they won't render and defeat
the point of the report. Binary deliverables belong on a release or
component as their own artifact type, not as a session report.

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
| `agenticPhase=FINAL`               | End-of-session summary. See §2.6.                                                    |

Policies pattern-match on tags, so use the canonical names if you
want the corresponding policy gates to fire. If your org's policies
don't reference any of these, tags are still preserved on the
artifact for the operator's audit.

**Never put secrets in any artifact you attach.** Reports are
operator-readable and persist beyond the session; treat them like
any other audit log. Strip API keys, FREEFORM secrets, passwords,
private SSH/GPG material, signed JWTs, internal connection strings,
and customer data before you write the file. If a stack trace or
command output naturally captures credentials (env-var dumps, curl
`-v` traces, raw `kubectl` secret reads), redact those lines before
attaching — `<redacted>` is fine, the operator just needs to see
*that* a value existed there.

### 2.6 Final session report

A FINAL-phase `AGENTIC_REPORT` summarises the session for the
operator and for any audit done after the fact. **Always ship one**
just before you call `rearm agent session close`, even if no
explicit policy demands it — the operator's review is easier when
every session ends with a self-summary in the same shape.

**Some orgs enforce this with a `CLOSE`-kind agent policy.** Unlike
`OUTPUT`-kind policies (which harden their verdict at commit-
attribution time), `CLOSE` policies stay `AWAITING` for the whole
session and only lock at `session close`. Operators use them when
the satisfying artifact arrives *after* the agent's last commit —
exactly the FINAL-report case. A typical CEL looks like
`!session.artifacts.exists(a, a.type == "AGENTIC_REPORT" &&
a.tags.exists(t, t.key == "agenticPhase" && t.value == "FINAL"))`.
Read `policyEvents[]` on `init` — if you see this rule `AWAITING`
on a `CLOSE`-kind policy, the session will be marked `FAILED` at
close without a FINAL report, and downstream release-side gates
(e.g. `release.agentSessions.exists(s, s.hasFailedPolicy)`) will
reject the release.

**Timing: attach the FINAL report as your last action, just before
`session close`.** Earlier attachments are fine too, but the
operator-facing report is most useful when it captures the
genuinely final state — including the last commit's outcome and any
late-breaking issues.

**Contents.** Keep the JSON small and honest. A useful shape:

```json
{
  "agentic_phase": "FINAL",
  "summary": "1-3 sentences on what was accomplished and the current state.",
  "tasks_received": [
    "verbatim from the operator's prompt, one per item"
  ],
  "tasks_completed": [
    {"task": "<as above>", "outcome": "DONE | PARTIAL | SKIPPED | BLOCKED"}
  ],
  "metrics": {
    "commits_authored": 3,
    "files_changed": 7,
    "tests_run": 142,
    "tests_passed": 142,
    "tests_failed": 0,
    "artifacts_attached": 2,
    "inbox_events_handled": 1,
    "iterations": 4
  },
  "issues_encountered": "Free-text. CI was flaky on the first push and re-running it cleared it; one failing test was pre-existing and out of scope — see commit abc1234. Nothing else surprising."
}
```

The `metrics` block uses the numbers you actually have at session
end — pick the ones you tracked. **Don't guess or fabricate values
for fields you didn't measure** (omit the key instead). Useful
counts you usually do have: commits authored, files changed,
artifacts attached, inbox events handled, iterations / self-revision
rounds. Counts you have when you ran them yourself: tests run /
passed / failed, lint warnings, type-check errors. Counts you can
read off ReARM at close: `releases[].length` and
`pullRequests[].length` via `rearm agent session show <uuid>`.

`tasks_received` should be the operator's prompt restated in their
words, not your paraphrase — the operator should be able to grep
their original ask against this field.

`tasks_completed[].outcome` is one of:

- `DONE` — finished as asked.
- `PARTIAL` — finished part of the task; describe what's left in
  `issues_encountered` or in the per-item entry as a nested
  `"note": "…"` field.
- `SKIPPED` — intentionally not done; explain why.
- `BLOCKED` — couldn't proceed because of an external factor (CI
  outage, policy you couldn't satisfy, missing credentials).
  Recovery requires operator action — name what.

`issues_encountered` is free-text. Be honest. The operator wants to
know about flaky retries, the test you turned off, the
recommendation you skipped, the workaround you used. A clean report
that says "nothing surprising" when there *was* something surprising
is worse than a messy report that surfaces the surprise — the
operator finds out either way.

**Re-check before attach: no secrets, no customer data, no internal
URLs that aren't already public.** If anything in `issues_encountered`
came from a tool output (a build log, a kubectl describe, a curl
trace), reread it for credentials and redact before saving.

Ship it:

```bash
cat > /tmp/final.json <<'JSON'
{ "agentic_phase": "FINAL", "summary": "…", "tasks_received": [...], … }
JSON

rearm agent session add-artifact <session-uuid> \
  --file /tmp/final.json \
  --type AGENTIC_REPORT \
  --display-id final \
  --tag agenticPhase=FINAL
```

Then proceed to `rearm agent session close` (§2.8).

### 2.7 Commit trailers

Every commit you author MUST carry two trailers in the commit
message's **trailer block** — the final paragraph of the message,
each trailer on its own line:

```
<your normal commit subject>

<your normal commit body — any length, any structure>

ReARM-Agentic-Session: <clientSessionId-you-picked-at-init>
ReARM-Agent: <root-agent-uuid-from-init>
```

- `ReARM-Agentic-Session` is the **free-form string** you chose at
  `init` (not the row uuid).
- `ReARM-Agent` is the root-agent uuid returned by `init`.
- Keys are case-insensitive; values run to the first whitespace.
- Order between the two trailers does not matter; both must be
  present.

Sign the commit with the key you enrolled in §2.4. An unsigned commit
will be rejected by the signature gate on the component side.

#### Why placement matters — read once, never get it wrong

ReARM extracts these via
`git log --pretty='… %(trailers:key=ReARM-Agent,key=ReARM-Agentic-Session,unfold,separator=%x20)'`.
That `%(trailers:…)` placeholder invokes git's **standard trailer
parser**, which has one hard rule: it only inspects the **last
paragraph** of the commit message — where "paragraph" means a run of
non-empty lines bounded by blank lines or the end of the message.
Anything earlier is body prose and is silently discarded.

If the parser sees no `ReARM-Agent` / `ReARM-Agentic-Session`
trailers, the `addrelease` call ships only the bare subject and your
commit lands as `signature=UNKNOWN_KEY`, **unattributed to any agent
or session** — even though the trailers are visible to a human
reading the message. There is no second, more lenient parser
downstream.

#### Self-check before every push

```bash
git log -1 --format=%B | git interpret-trailers --parse
```

Both `ReARM-Agentic-Session:` and `ReARM-Agent:` MUST appear in the
output. If only one appears, or only `Co-Authored-By:` appears, or
the output is empty — the commit is broken. Amend the commit
message (`git commit --amend`) and re-check before pushing.

#### Common mistakes that silently break attribution

| What you did                                                              | Why it fails                                                              |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| Put both trailers on one line (`ReARM-Agentic-Session: foo ReARM-Agent: bar`) | Git accepts only **one** trailer per line. The second key is treated as the first trailer's value. |
| Put the trailers at the **top** of the body (right after the subject)     | They're in the first paragraph, not the last — the parser ignores them.   |
| Put them in the **middle** of the body                                    | Same — only the final paragraph counts as the trailer block.              |
| Put them at the end but separated from another trailer (e.g. `Co-Authored-By:`) by a blank line | The blank line splits the trailer block in two; only the truly final paragraph is parsed. |
| Mixed them into a paragraph with prose sentences                          | Git rejects the whole paragraph as non-trailer because most of it isn't `Key: Value`. |

If you include `Co-Authored-By:` or any other trailer (from a coding
tool's signature, from a hook), **all trailers must live in the same
final paragraph, contiguous, one per line — no blank lines between
them**:

```
... body ...

ReARM-Agentic-Session: auth-bug-fix-1779124086
ReARM-Agent: 8a44b1ce-7e29-4a6f-9c87-1f0a45e9d8b1
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

#### What ReARM does with valid trailers

The trailers surface back as `SourceCodeEntry.agent` and
`SourceCodeEntry.agentSession` once a CI run picks up the commit via
`rearm getversion --scearts …` or `rearm addrelease`.

### 2.8 Heartbeat and close

While you're active, you don't need to do anything — the session
auto-tracks `lastActivityAt` on every state change. If you're going
quiet for a stretch but the work isn't done, send a heartbeat so the
dashboard's "connected" pill stays honest:

```bash
rearm agent session touch <session-uuid>
```

When the work is complete, **ship the FINAL report first (§2.6)**,
then close:

```bash
rearm agent session close <session-uuid>
```

Idempotent. Commits already attributed continue to resolve to the
session (historical view); new commits can't attach.

`close` triggers `CLOSE`-kind policy verdicts (§2.6). If a
`CLOSE`-kind rule was `AWAITING` and the session still doesn't
satisfy it at close time (e.g. FINAL report missing), the verdict
locks `FAILED` and any release tainted by this session via
`release.agentSessions.exists(s, s.hasFailedPolicy)` becomes
ungateable until the operator intervenes. Attaching the report is
cheap; missing it can be expensive.

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
  ├─ author signed+trailered commits                               # §2.7
  ├─ open PR                                                       # standard SCM flow
  └─ between major tasks, poll inbox every 60s                     # §3
       └─ event: APPROVAL { newValue=DISAPPROVED, reason="…" }
            ├─ parse reason → understand what to fix
            ├─ author signed+trailered commit on the same branch   # same session, same trailers
            ├─ push (PR auto-updates)
            └─ continue polling
       └─ event: APPROVAL { newValue=APPROVED }
            ├─ attach FINAL report (summary + metrics + issues)    # §2.6
            └─ close the session                                   # §2.8
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
rearm agent release show <release-uuid> --session <session-uuid>
```

Pass `--session <session-uuid>` (or `--client-session-id <id>`, the
value from your commit trailer) — the backend verifies your key owns
the session before returning anything.

Returns `updateEvents[]` (human-readable lifecycle reasons,
pre-aggregated — no need to re-derive from triggers),
`approvalEvents[]` (full approval history with reviewer comments),
and `sourceCodeEntryDetails[]` (per-commit attribution + signature
state). The `updateEvents[].message` field is the most useful single
field — it spells out *why* a CEL gate flipped the release (e.g.
*"Triggered by 'Reject when any commit is not VERIFIED' (CEL: …)"*).

### Reading a release's vulnerabilities and violations

`agent release show` also returns the release's `metrics` — the
security posture from the latest Dependency-Track scan
(`metrics.lastScanned`; null/empty until a scan has completed):

- severity counts — `critical`, `high`, `medium`, `low`, `unassigned`;
- policy-violation totals — `policyViolationsSecurityTotal`,
  `policyViolationsLicenseTotal`, `policyViolationsOperationalTotal`;
- per-finding detail lists:
  - `vulnerabilityDetails[]` — `purl`, `vulnId`, `severity`,
    `analysisState`;
  - `violationDetails[]` — `purl`, `type`, `license`, `analysisState`.

This is how you inspect *which* CVEs / license or policy violations a
release carries — e.g. after a `POLICY_GATE` `LIFECYCLE_CHANGE` whose
`reason` names a vuln threshold (`CEL: release.highVulns > 0`), pull
`vulnerabilityDetails[]` to see the actual findings and decide whether
to bump a dependency, request a VEX, or escalate.

#### Localize findings per artifact — a release has *several* SBOMs

The release-level `metrics` above is an **aggregate**. A single release
almost always carries **multiple** scanned artifacts, and they are not
the same thing:

- the **source-code SBOM** (attached to the source code entry) — your
  dependencies as seen in the repo;
- one or more **deliverable SBOMs** (attached to each built deliverable,
  e.g. the container image) — what actually ships, including base-image
  and OS packages the source SBOM never sees;
- **SARIF** (`CODE_SCANNING_RESULT`) and **VDR** artifacts, which also
  carry findings.

A very common shape: the **source-code SBOM is clean but the deliverable
(container) SBOM is not** — the vulnerabilities live in the base image,
not your code. If you only read the release aggregate you'll see "N
high" with no idea where it came from. **Don't conclude the code is at
fault from the aggregate.** Instead, walk the per-artifact `metrics`
(every artifact carries its own counts + `vulnerabilityDetails` +
`violationDetails`):

- `sourceCodeEntryDetails.artifactDetails[]` — source-code SBOM(s) / SARIF;
- `artifactDetails[]` — release-level artifacts;
- `variantDetails[].outboundDeliverableDetails[].artifactDetails[]` —
  deliverable SBOMs / scan results (the deliverable's `displayIdentifier`
  tells you which image).

Each artifact's `type` / `displayIdentifier` / `bomFormat` identifies
what it is. So you can say "source SBOM: 0 high; container deliverable
`…/rearm-cli`: 6 high" and act on the right layer.

**Suppression quirk (v1).** Release-level `metrics` reflects vulnerability
suppressions applied at **release scope** (a vuln suppressed for this
release at a non-org scope is dropped from the release totals/details);
the **per-artifact** `metrics` are **raw** (artifact scope) and do *not*
see that release-scope suppression. So the release-level and per-artifact
detail lists can legitimately differ — that's expected, not a bug. When
they disagree, the release-level list is the suppression-adjusted view;
the per-artifact list is the unfiltered scan.

**Permissions.** A release **your own session built** (one of the
session's commits traces through to it) is readable with just the
FREEFORM `AGENT` key that owns the session — no extra grant needed,
and `metrics` comes with it. To read a release **not** attributed to
your session, the key additionally needs explicit `RESOURCE` read
permission on that release's component / product (scope `RELEASE` /
`COMPONENT` / `PRODUCT`); without it the lookup is denied. If you hit
`Not authorized` on a release you didn't build, that's the missing
grant — ask the operator rather than retrying.

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

# --- release inspection (lifecycle, approvals, vulnerabilities/violations) ---
rearm agent release show <release-uuid> --session <session-uuid>

# --- deployment / devops (ReARM Pro only — read §10 before using) ---
rearm devops listfeaturesets --instanceuri <sandbox-url> --namespace <ns>
rearm devops versionfeatureset --product <product-uuid> --overrides '[…]'
rearm devops switchfeatureset --instanceuri <sandbox-url> --product <product-uuid> \
  --featureset <new-fs-uuid> --namespace <ns>
```

## 10. Deployment operations (ReARM Pro only)

`rearm devops` commands change which build a running ReARM instance
serves. They are powerful and wrong-instance mistakes are
**visible to other people** — switching a shared sandbox or a
production instance to a feature-branch build is a real incident
that takes operator effort to recover from.

**Hard constraint: do not run any `rearm devops` command unless the
operator's task description explicitly names the target instance
(by URI or instance UUID).** "Test on the sandbox" is not specific
enough — ask "which sandbox URI / instance UUID?" before proceeding.
If the operator can't or won't name a specific instance, stop. Don't
guess. Don't pick "the only one I happen to know about" or "the one
my key seems to work against".

ReARM CE does not ship these commands. If `rearm devops
listfeaturesets` returns `Field 'listFeatureSetsOfInstance' in type
'Mutation' is undefined` (or similar), you are on CE and no devops
operation can proceed regardless of how the task is phrased — tell
the operator the edition mismatch.

### 10.1 Discovery — `listfeaturesets`

Always your first call. It confirms (a) you're talking to the
instance the operator named, (b) the FREEFORM key you were given can
actually see the deployment, and (c) the product + current feature
set match what the operator described:

```bash
rearm devops listfeaturesets \
  --instanceuri "<sandbox base URL the operator gave you>" \
  --namespace "<k8s namespace, typically 'rearm'>"
```

Returns the product UUID and the current + available feature-set
UUIDs. **Record the current `currentFeatureSet.uuid`** before doing
anything else — that's the rollback target if the operator needs to
revert.

### 10.2 Versioning — `versionfeatureset`

Creates a new feature set that pins one or more component branches.
The override branches must already exist on the components (i.e. CI
must have run on that branch at least once and produced a release):

```bash
rearm devops versionfeatureset \
  --product "<product-uuid from listfeaturesets>" \
  --overrides '[
    {"vcsUri":"<vcs uri>","repoPath":"<component sub-path>","branch":"<branch-name>"}
  ]'
```

Returns `{uuid, name, autoIntegrate, component}` for the new
feature set.

### 10.3 Switching — `switchfeatureset`

Retargets the named instance at the new feature set. The in-cluster
reconciler picks up the change within a minute or two and rolls
matching pods; the **pod image tag is the source of truth** for
what's actually running, not the GraphQL response:

```bash
rearm devops switchfeatureset \
  --instanceuri "<sandbox base URL>" \
  --product "<product-uuid>" \
  --featureset "<new feature-set uuid from §10.2>" \
  --namespace "<k8s namespace>"
```

Re-run `listfeaturesets` to confirm `currentFeatureSet.uuid`
matches. If after 10 min the pod image tag hasn't changed, the
reconcile is wedged — surface to the operator with the timestamp of
the switch and the instance URI.

### 10.4 Pre-flight checklist

Before any `rearm devops` operation, you should be able to answer
all of these. If you can't tick all six, stop and ask:

- [ ] The operator's task explicitly named this instance (URI or UUID).
- [ ] `listfeaturesets` against that instance succeeded with the FREEFORM key the operator provided.
- [ ] The response's `product` and `currentFeatureSet` match the operator's description.
- [ ] You've recorded the previous `currentFeatureSet.uuid` so rollback is one command away.
- [ ] You understand the change rolls a running pod within minutes — there is no preview mode.
- [ ] You are on **ReARM Pro** — `listfeaturesets` succeeded at all (not a CE backend).

---

If you find a behavior the doc doesn't describe, the doc is wrong.
Surface the gap to the operator so it gets fixed in the next ReARM
release rather than guessing.
