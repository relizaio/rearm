<!-- orientation section: session-details · core 2026-10-08 -->
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

#### Initializing in detail


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

The response carries three fields the rest of the session uses.
`session open` keeps them as this repository's current session (§2.3),
so the verbs read them from there; after a bare `init`, record them
yourself:

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

#### Your tool's own session id

ReARM records the id **your tool** gives this conversation, beside the
session, so a human can get from a commit back to the conversation that
wrote it. Under Claude Code the CLI does this for you: `init` reads
`$CLAUDE_CODE_SESSION_ID` and sends it as the provider session. Nothing
to do unless one of these applies:

- **You also have a hosted session id.** Claude Code running under a
  web or remote bridge has a second id, `session_…` — the one the human
  sees and shares. The CLI does not look for it. If you are Claude Code,
  it is `bridgeSessionId` in `~/.claude/sessions/$CLAUDE_PID.json`; pass
  it as `--provider-remote-session-id`. If that file or key is absent,
  you are running locally and there is no remote id.
- **You are not Claude Code**, or the variable is unset. Pass
  `--provider <tool> --provider-session-id <id>` yourself if your tool
  exposes one. Do not invent one.
- **You resumed work in a new conversation** and are continuing an
  existing OPEN ReARM session rather than opening a new one. Record the
  new conversation against it:
  `rearm agent session update-meta <session-uuid>` — it reports the
  current provider session the same way `init` does, and appends
  rather than replaces, so the session keeps both.

`--no-provider-session` opts out. `--require-provider-session` makes
the command fail when no provider session id can be found, for
operators who want every session traceable; without it, a missing id is
not an error and the session opens without one.

#### What the session records about how it was opened

`init` also records how you signed in and from where: the auth method
(key secret, CLI login, or federated), the person the session is
attributed to when anything names one, the IP the server saw, and what
the CLI reports about your machine — hostname, OS, time zone and client
version. Hostname and IP are shown only to org admins and to the
session's owner. `--no-device-info` stops the CLI reporting the device;
the server's own observations are recorded regardless.

A session opened with a shared key secret says which key, never who.
If the work should be attributable to a person, sign in with
`rearm login` instead.

#### The usage hooks in detail


ReARM records what your session costs: tokens, turns, and the money
those came to. You do not report that by hand. Two Claude Code hooks
do it for you, and installing them is one command:

```bash
rearm agent claude hooks install --project
```

`--project` writes `.claude/settings.json` in the repository you are
working in; `--user` writes `~/.claude/settings.json` instead. Existing
hooks are preserved and the command is added once, so running it twice
is safe. `rearm agent claude hooks uninstall` removes exactly what `install`
added and nothing else.

What you get: a `Stop` hook that reports each turn's usage as it
happens, and a `SessionEnd` hook that sends the final flush. Both read
the transcript Claude Code already writes — no extra token cost, and
nothing to remember mid-task.

The commands live under `rearm agent claude` because they know the shape
of Claude Code's transcript and hook payloads. Anything an agent of any
kind needs — reporting usage, attributing it to a task — stays at
`rearm agent`.

**These hooks never block you.** Any failure — server down, network
timeout, bad credentials — exits 0 with one line on stderr. If usage
reporting breaks, your work continues; you just lose the numbers for
that stretch.

If you are **not** Claude Code, there are no hooks to install. Report
explicitly instead, at whatever interval suits you:

```bash
rearm agent session usage <session-uuid> \
  --source self-reported \
  --model '<model identifier>' \
  --input-tokens <n> --output-tokens <n> \
  --turns <n> --wall-seconds <n>
```

Usage lands against your current task automatically when you hold
exactly one assignment — `task assign` records it, `task signoff` and
`task return` clear it. Hold two open assignments and the CLI reports
none of them, leaving the server to decide; that is the honest answer,
not a bug.

> **CLI version.** `agent claude hooks` and `agent session usage` need
> the `26.10.2` pinned in §1.2 (`rearm_cli_min`) or later.
>
> If `rearm agent claude hooks install` reports an unknown command, do
> not conclude anything from that alone — check what you actually have:
>
> ```bash
> rearm version
> rearm agent --help          # does `claude` appear in the subcommand list?
> ```
>
> Then **tell the operator what those printed**, naming the version and
> whether the subcommand exists. An older CLI that predates usage
> reporting and a newer one where the commands moved are different
> problems with different fixes, and only the operator can act on
> either. Carry on with your actual task in the meantime — a session
> that reports no usage is a gap in the numbers, not a reason to stop
> working.
>
> Do not upgrade the CLI on your own; the pin exists so every agent on a
> host runs a verified binary.
