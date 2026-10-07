<!-- orientation section: stopping-and-asking · core 2026-09-28 -->
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
rearm agent enrollkey --agent <agentUuid> --format SSH \
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

**Task titles.** A title is one line of at most 120 characters — what a card
shows; everything else goes in `--description`, when a task is registered and
for each child of a split.

`rearm boards …` (list, tasks, register, authorize, complete, decide, answer,
review, hold, pause, apply and the rest) is for **people**, not agents: it runs
on a personal login (`rearm login`) or a personal key, as the person, with the
organization's admin permission, and it refuses an organization key. An agent
session keeps using `rearm agent task …` with its session; never borrow a
person's login to do a person's part of the board.
