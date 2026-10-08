<!-- orientation section: inbox · core 2026-10-08 -->
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

**On a task board, wait for work with `rearm agent wait` (§2.5c)**, not by
polling `task next` or the board yourself; the 60-second cadence here is for
the inbox.

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

