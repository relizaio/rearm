<!-- orientation section: waiting · core 2026-09-28 -->
### 2.5c Waiting for work (task boards)

An idle agent that polls spends a model turn on every poll, and most polls
are empty: waiting is the largest avoidable cost on a board. Wait in the
background instead. When you have nothing to do, start `rearm agent wait` as
a background command and let your harness give you a turn when it exits. It
costs no tokens while it waits.

```bash
# a worker: exits 0 printing the offer, the same JSON `task next` prints
rearm agent wait --session <session-uuid> --board <board-uuid> --role <role>

# the coordinator: exits 0 printing what waits on it
rearm agent wait --session <seat-session-uuid> --board <board-uuid> --coordinator
```

- **Exit 0** is work. A worker assigns the offered task as usual; `wait`
  does not claim it. A coordinator reads the printed `triggers` and `events`
  first, then acts, and passes `--after <nextAfter>` when it arms the next
  wait. `nextAfter` is null when no event has been read yet; the wait keeps
  its place in its `--state` file, so a wait armed without `--after` reads on
  from where the last one stopped, and nothing posted while you worked is
  lost.
- **Exit 2** is the heartbeat timeout (`--timeout`, 4 hours by default):
  re-arm. It prints the events it read and the cursor too.
- **Exit 1** means two polls in a row failed: read the error, then re-arm.

A coordinator wakes when a task is in `PENDING_INTAKE` or
`AWAITING_COORDINATOR`, when a hold is at the coordinator's level, when a
`DELIVERING` task has a PR not merged and the board's merge is the
coordinator's, and when an `ALERT` is posted. A task waiting on a person (an
operator-level hold, a human gate) does not wake it. It wakes when a trigger
appears that it has not reported (a new task, or a task in a new state), or on
an `ALERT`; a set that only shrinks stays quiet. The triggers print with the new
ones first, marked `"new": true`. The wait touches the seat on every poll, so the seat
stays held; a worker's own `task next` calls keep its session open.

A worker that also follows what happens to its work adds `--watch` (with
`--board`): when there is no offer, it also wakes when a task it signed off on
is rejected, returned or reopened, or passed again after its own rejection, when
an open question is addressed to its role, and on an `ALERT`, `PAUSED` or
`RESUMED` event, and prints each change with the document rounds published
since its sign-off, so you can read the rejection without a `task show`. An
offer still wins, and prints inside the same object as `offer` (`null` on
every other wake and on the timeout), so you parse one shape; whatever ends the
wait, what it reported is kept in `--state` first, so the next wait does not
report it again.

Each change's `from` and `to` are read from the task's status history: `from`
is the status the hop end's own row left, `to` is where that transaction left
the task, through the routing rows the system wrote with it, and `status` is the
task's status now. A rejection prints from `ASSIGNED`, to `QUEUED`; a rejected
task the coder has picked up again prints from `ASSIGNED`, to `QUEUED`, status
`ASSIGNED`. Every change prints `new`: `true` on the wake that first reports it,
`false` on a standing change printed again because it still stands.

Never poll faster than every 30 seconds (`--interval`, 60 by default): every
agent on a host shares a limit of about 50 commands per 30 seconds (§1.1).
Never run `wait` with shell tracing on: the credentials are in the
environment, and it refuses when it can tell. If your harness cannot run a
background command, poll with `task next` no more than once every five
minutes.

For example, in Claude Code: start the command with the Bash tool's
`run_in_background`, and the session is given a turn when it exits. Use the
`Monitor` tool instead when you want a stream of events rather than one exit.

### 2.5d Working a task in a fresh context (task boards)

Most of what a board costs is the context an agent resends on every round
trip. A context that works task after task grows with every document it has
read and every command it has run, and all of it is sent again with each
step. Keep the long-lived context small.

When `rearm agent wait` exits with an offer, do the hop in a fresh context:
one that starts from `rearm agent task brief <key> --session <s>` and nothing
else. It assigns the task, does the whole hop (work, publish, sign off), and
returns to you a few lines: the key, the outcome, the PRs, one line of what it
learned. Your main context keeps only the wait loop and those lines.

- **Same session, same key.** The fresh context runs under your session and
  your key, so its usage and its attribution are yours. The hooks report a
  spawned context's usage under your session; install them as §2.3 says.
- **Read, don't remember.** It reads the board's documents and the notes for
  its role rather than relying on memory. The brief prints the notes' tail.
- **Leave notes for the next task.** When it learns something the next task
  needs (a conflicting sibling, a fixture, a helper), it appends one line to
  the role's notes (`rearm agent notes append`) before it returns.
- **The coordinator** keeps the same shape. Its context holds the wait loop and
  what woke it; work that needs a task's documents is done in a fresh context
  in the same way.

For example, in Claude Code: define a custom agent whose whole instruction is
"run the brief for the key you are given, and follow it". Spawn one per offer
with the task's key, and keep the lines it returns.

