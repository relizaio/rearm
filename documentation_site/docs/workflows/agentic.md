# Bootstrap an AI Agent

::: tip Available in ReARM Pro
Agent sessions, attribution, and the inbox poll endpoint are ReARM Pro features.
:::

This page walks an **operator** through the steps to point an AI coding agent (Claude Code, Cursor, Aider, your own runtime) at a ReARM Pro instance. ReARM treats the agent as a worker that needs guardrails: the operator stays the principal, the agent stays the worker, and every step is captured in the audit trail.

The agent itself **does not need to read this page** — its contract lives at `$REARM_URL/api/agents/orientation.md`, which the operator points the agent at in its first prompt. That document is served by the running ReARM instance and is pinned to the same version as the backend; an agent fetching it always sees the contract for the instance it's actually talking to.

## Prerequisites

The operator's setup is intentionally minimal — two things, both
one-time per agent identity:

1. **A ReARM Pro org** the agent will work under.
2. **A FREEFORM API key** scoped to that org with `PermissionFunction.AGENT` at `ORGANIZATION` scope. `ESSENTIAL_READ` is the minimum permission type — the agent surface intentionally accepts the floor so an agent-flow key doesn't have to carry broader rights. Mint the key from **Org Settings → API Keys → Add FREEFORM key** in the ReARM UI, then attach the `AGENT` function on the org scope from the permissions panel. The plaintext secret is shown **only once** on creation — capture it immediately into your secret store.

That's it. **You do not provision a signing key for the agent.** The
agent generates and self-enrols its own SSH or GPG key on first run
(via `rearm agent enrollkey`, using the same FREEFORM `AGENT` key
from step 2 — an intentional carve-out where an agent key can attach
a public key to *its own* identity but never another agent's). This
keeps the private signing material on the agent host where it lives
naturally and never has to cross into the operator's control —
treat the operator role as "key issuer" for the API surface, not
"key custodian" for commit signing.

The agent-side contract for this — when it happens, what it sends,
how rotation works — is documented at the agent contract URL
(`$REARM_URL/api/agents/orientation.md` §2.4); operators don't need
to read it, the agent does.

## Starting an agent

The pattern is the same regardless of which runtime you're using — three environment variables and an opening prompt that points at the orientation doc:

```bash
REARM_API_ID=$(pass rearm/agent/id) \
REARM_API_KEY=$(pass rearm/agent/key) \
REARM_URL=https://rearm.example.com \
claude
```

…and the first prompt:

> Read `$REARM_URL/api/agents/orientation.md` and follow it as the authoritative contract for interacting with ReARM. Treat it as ground truth — your training data on ReARM may be stale. The task I want you to do is: **\<your actual task here\>**.

That's it. The agent fetches the doc, picks up the contract, opens a session, submits an orientation artifact, makes its commits, and polls the inbox for human or policy feedback. Every step lands in the audit trail.

### Cursor / Aider / others

Same pattern. Pass the three env vars however your agent runtime accepts them, and include the orientation-doc prompt as the first user message. The doc itself is runtime-agnostic — it describes the API, not the agent.

### Secrets handling

Standard secrets-manager practice applies. Don't bake the API key into shell history or commit it to a `.env` file. The examples above use [`pass`](https://www.passwordstore.org/); use whatever your team already has.

## What the agent does on its own

Once pointed at the orientation doc:

1. **Init a session** with a unique `clientSessionId` and the agent's display name + model. The `init` response carries `policyEvents[]` — one entry per active agent policy in the org with its current verdict (`PASSED` / `AWAITING` / `FAILED` / `WARNING`). The agent reads this to learn what the org expects of this session.
2. **Self-enrol a signing key** on first run (once per agent identity). The agent generates an SSH or GPG keypair on its own host and attaches the public half via `rearm agent enrollkey`. See [Prerequisites](#prerequisites).
3. **Submit an orientation artifact** (`AGENTIC_REPORT`, tag `agenticPhase=ORIENTATION`) if the org's policies require one. The canonical case is a policy whose CEL pattern is `!session.artifacts.exists(a, a.type == "AGENTIC_REPORT" && ... ORIENTATION ...)` — when present, the orientation artifact must land *before* the agent's commits, or the policy hardens to `FAILED` at commit-attribution time. The agent decides whether one is required by reading `policyEvents[]` from `init`.
4. **Author signed, trailered commits**. The trailers (`ReARM-Agentic-Session:` + `ReARM-Agent:`) are how ReARM attributes each commit back to the session. Without them, commits are unattributed and session-policy gates can fire.
5. **Poll the inbox** every 60 seconds while waiting on downstream events — release lifecycle changes, human approval/disapproval verdicts, post-init policy verdicts. The orientation doc describes the polling shape (`agentSessionInboxProgrammatic`) and the cursor semantics, and explicitly tells the agent not to poll mid-task (only between major tasks).
6. **Submit a final report** (`AGENTIC_REPORT`, tag `agenticPhase=FINAL`) just before closing. Recommended in every session for the operator's audit; *required* when the org has enabled a `CLOSE`-kind policy gating on it (see [Agent policies](#agent-policies)).
7. **Close the session** with `rearm agent session close <uuid>` when work is complete. This locks `CLOSE`-kind policy verdicts to their final value (a missing FINAL report → `FAILED` here, which downstream release-side gates can read via `release.agentSessions.exists(s, s.hasFailedPolicy)`).

## Agent policies

Operators configure CEL-based **agent policies** (Org Settings →
Policies → AI Agent Policies tab) that ReARM evaluates against each
session. Three kinds, distinguished by *when* their verdict locks:

| Kind     | Lifecycle phase verdict locks                                  | Typical use                                                                                  |
| -------- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `INPUT`  | At `session init`. `BLOCK`-severity failures refuse the session outright (status `BLOCKED`). | Allowlists you want enforced before any work happens — model, agent identity, branch.        |
| `OUTPUT` | At commit-attribution time. Verdict hardens when the first commit attributed to the session lands. | "Agent must produce X by commit-time" — e.g. the orientation report.                          |
| `CLOSE`  | At `session close`. Stays `AWAITING` for the whole session and only locks on close. | Gates that depend on artifacts the agent files at the very end — e.g. the FINAL report tag.   |

`CLOSE` exists for the case where the satisfying artifact arrives
*after* the agent's last commit. An `OUTPUT`-kind rule on a FINAL
report would deterministically harden to `FAILED` at commit-
attribution (the report hasn't been filed yet); a `CLOSE`-kind rule
lets the session keep the verdict `AWAITING` until close, so a
correctly-behaved agent can land it just before exit and pass.

Each policy has a `BLOCK` or `WARN` severity:

- `BLOCK` — failure hardens to `FAILED` on the session.
  Downstream release gates can read
  `release.agentSessions.exists(s, s.hasFailedPolicy)` and reject
  the release; `BLOCK` `INPUT` policies refuse the session at init.
- `WARN` — failure records a `WARNING` verdict on the session log
  but never blocks anything.

Each session's `policyEvents[]` (visible in the UI session view and
returned on `init` / `show` / `close` to the agent) records the
current verdict per policy with the evaluation timestamp, so the
audit trail captures both the rule and its outcome.

Policies are entirely operator-configurable — there is no built-in
"default" set. The **Populate Defaults** button on the AI Agent
Policies page seeds a sensible starter pair (orientation OUTPUT +
final-report CLOSE) for a brand-new org; edit / disable / replace
freely.

## Closed-loop disapproval → fix

The canonical demo:

1. Agent opens a session, submits orientation, pushes a signed PR.
2. Reviewer hits **Disapprove** with a comment in the ReARM UI.
3. Agent polls the inbox, sees an `APPROVAL` event with `newValue=DISAPPROVED` and the reviewer's comment in `reason`.
4. Agent **stays in the same session**, addresses the comment, pushes a new signed commit on the same branch (PR auto-updates).
5. Reviewer hits **Approve**.
6. Agent attaches a FINAL report and closes the session.

Every step is in the audit trail: session row, commits with their attribution, releases with `updateEvents[].message` carrying the rejection reason, approval events with comments. The orientation artifact at step 1 and the final report at step 6 bracket the session's audit story.

## When the agent will stop

The orientation doc instructs the agent to **stop and report to the operator** rather than auto-loop when:

- A session is `BLOCKED` by an INPUT policy that the agent can't satisfy on its own. The canonical example is a `agent.model.startsWith("claude")` policy when the agent runs a different model — the agent can't change its own model, so it must escalate.
- A release keeps landing `REJECTED` after multiple fix attempts with the same reason (the policy is checking something the agent can't influence — a third-party CVE count, an external scan result, etc.).
- Attribution keeps landing `REJECTED` (trailer template is wrong, agent uuid mismatch, cross-org — agent bug worth fixing before more commits).

This is the "human stays the principal" guarantee. ReARM doesn't let an agent thrash against a policy in a loop; the agent surfaces the friction back to the operator.

## What the audit trail captures

Each step shows up as a queryable row:

| Event                                  | Where it lands                                                                                              |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Session opened                         | `agent_sessions` row, `status = OPEN`                                                                       |
| Session blocked by INPUT policy        | `agent_sessions` row, `status = BLOCKED`, `policyEvents[]` with the failing verdict                          |
| Orientation artifact attached          | `agent_sessions.artifacts[]` + `agent_sessions.policyEvents[]` (PASSED entry on the orientation policy)      |
| Commit attributed                      | `source_code_entries.{agent, agentSession, attributionState}`                                                |
| Release lifecycle change by CEL gate   | `releases.update_events[]` with `rus=LIFECYCLE`, `message="Triggered by '…' (CEL: …)"`                       |
| Human approval / disapproval           | `releases.approval_events[]` with `state`, `comment`, `wu.lastUpdatedBy`                                     |
| FINAL report attached                  | `agent_sessions.artifacts[]` with tag `agenticPhase=FINAL`                                                   |
| Session closed                         | `agent_sessions.status = CLOSED`, `closedAt` set; `CLOSE`-kind verdicts lock here                            |
| PR linked to session                   | `pull_requests.commits[]` includes an SCE attributed to the session; resolved via `Session.pullRequests`     |
| Agent retried after BLOCKED            | New `agent_sessions` row with `parentSession` pointing at the prior BLOCKED row                              |

In the UI, this surfaces as: the **History** tab on the release shows lifecycle-change events with tooltips carrying the trigger reason, the per-commit row carries agent + session attribution chips, the session view lists every release and PR it touched, and the agent view lists every session it ran.

## What's not in v1

Worth being honest about so you don't build expectations around things that don't exist yet:

- **Signed commits only verify SSH and GPG.** X.509 / sigstore (cosign keyless, Gitsign) signatures land with verdict `ERRORED` — the verdict row is still written so the audit trail is honest, but the signed-commit gate won't pass for them. If your team signs with sigstore, plan to enrol SSH or GPG keys for ReARM-attributed work until the X.509 verifier ships.
- **No VSA emission.** ReARM records its verification verdict per signature internally, but does not yet mint a [SLSA Verification Summary Attestation](https://slsa.dev/spec/v1.0/verification_summary) ("ReARM verified release R passed policy P at time T") that downstream consumers can pin against. If you need a portable, signed verdict to hand off to a registry / deploy gate, that piece is on the roadmap, not in v1.
- **No outbound webhooks** scoped per agent. The agent polls; ReARM doesn't push. Polling at the orientation doc's prescribed 60-second cadence is plenty for any reviewer-driven workflow.
- **No `rearm agent spawn`** in the published CLI. Sub-agent registration is on the API but the convenience command isn't shipped yet; sub-agents currently stamp the root agent's uuid in their trailers.
- **No model-card auto-attach.** ReARM auto-registers a `ModelOntology` row on first session init for any (name, version, vendor) tuple it hasn't seen, but the row's `modelCard` field is left empty. If a policy checks `model.modelCard != ""`, an operator must attach the card on the `ModelOntology` row from the ReARM UI before the agent's session init will pass — the agent can't do it.

## Reference

- **Agent contract**: `$REARM_URL/api/agents/orientation.md` — pinned to the backend version (Pro and CE serve the same document; Pro-only sections are labelled). The agent fetches this on startup; operators don't need to read it, but it's the source of truth on the agent's side of the contract — phase semantics, policy verdicts, signing-key enrolment cadence, and the precise CLI commands the agent will run.
- **CEL surface for agent policies**: `session.*`, `agent.*`, `model.*` — explore via the CEL helper picker inside the policy editor; every helper carries a tooltip with its return shape and an example.
- **Component-level CEL gates** that interact with agent sessions: `release.agentSessions[].hasFailedPolicy`, `release.commits[].attribution.state`, `release.commits[].signature.state`. The first two are agentic-specific; the third is the [signed-commits gate](../concepts/) (works on any commit, not just agent ones).
- **ReARM CLI**: [`rearm agent ...`](../integrations/rearmcli) subcommands are what the agent uses — `session init / show / touch / close / add-artifact / inbox` and `agent enrollkey`. The CLI is the only sanctioned programmatic surface (raw HTTP is WAF-blocked in many deployments); operators don't run these by hand, but the help output is the quickest way to see the current command surface.
