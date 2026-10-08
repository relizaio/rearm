<!-- orientation section: final-report · core 2026-10-08 -->
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

Ship it and close the session in one call:

```bash
cat > /tmp/final.json <<'JSON'
{ "agentic_phase": "FINAL", "summary": "…", "tasks_received": [...], … }
JSON

rearm agent session close --final /tmp/final.json
```

`--final` files the report as an `AGENTIC_REPORT` artifact (display id `final`, tag
`agenticPhase=FINAL`), then closes the session (§2.8); a failed upload stops before the close and leaves
the session open.
