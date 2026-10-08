<!-- orientation section: publishing · core 2026-10-08 -->
### 2.5a Publishing documents (task boards)

On a task board, a hop ends with a **document**, not just a sign-off note. If
your role declares a required output, the server refuses your sign-off until
you have published it — and the refusal costs you the hop, because the task
stays assigned and you have to publish and sign off again.

Write each round at the path the brief's `next <TYPE>: <path>` line names (`rearm agent task brief`): the
round a publish would cut, relative to the documents repository's root and already including any board
root, such as `boards/<board>/` on a repository several boards share, so write the file exactly there.
`doc publish` without `--file` publishes that path. A republish in the same hop needs no `--file` either:
when this hop already published the newest round of the type, the brief's line ends `(republish = new
version of round <n>)`, and the publish defaults to that round's path and lands as a new version of it.
`--file` always wins.

Write the files, commit them in the board's documents repository, then:

```bash
rearm agent doc publish --session <session-uuid> \
  --type BOARD_REVIEW_ITEMS --task <task-uuid> \
  [--repo /path/to/documents-checkout]

rearm agent task signoff <task-uuid> --session <session-uuid> \
  --outcome PASSED --note 'what you concluded'
```

`doc publish` remembers the release it created, so `signoff` sends it as an
output without you copying uuids. Pass `--outputs <uuid,...>` explicitly if you
are signing off from a different machine.

With `--json`, `doc publish` prints one JSON value on stdout and nothing on
stderr on success: the release, with `checks` (`verdict`, `counts`, `blocking`,
`lines`; null for a document without elements) and any `notices`. With
`--check` it prints `{check: true, checks}`. A refusal still goes to stderr,
with exit 1 and nothing on stdout.

**Advisory rounds.** When a review item on a task another role holds is yours to
answer -- a design consequence the architect amends, say -- publish the
amendment on that task with `doc publish --advisory`. It has to be a prose type
a role you have held on this board produces, on a task still being worked. The
round is assembled at once, the board posts an INFO event, and the holder's
inputs lead with it; routing does not move. Without `--advisory`, publishing on
a task you do not hold is refused, and an index type (BOARD_REVIEW_ITEMS,
BOARD_TEST_REPORT, BOARD_QUESTIONS) is never advisory.

**Documents published while you work.** The offer `task next` or `rearm agent
wait` prints is not the document list: after assign, read the task. The server
refuses a sign-off that does not acknowledge a document published on the task
since your assignment, naming each; `task show --session <session>` is the
acknowledgement, so run it, read what it names, and sign off again. A show
without `--session` acknowledges nothing. On a host where the session was not
opened the CLI has no record of what you read, so a sign-off there after a
document lands is refused once; the show records it, and the next sign-off
passes.

A BOARD_REVIEW_ITEMS or BOARD_TEST_REPORT index needs no `about`: when you reject, the
board sends open items back to whoever made the work, the newest earlier hop by
a role that is not a reviewer. Name a specification in `about` only to send them
elsewhere, for example to the architect rather than the coder.

**Which repository.** The documents repository is usually NOT the repository you
are working in. The CLI resolves it in this order: `--repo` if given; the
current directory if its `origin` matches the board's documents repo; the path
you used last for this board; otherwise it refuses and names the repo it wants.
Reading HEAD from your code checkout would pin a code commit onto a document,
which is why this is not guessed.

**Commit first.** A document release pins a commit, so the CLI refuses to
publish while either file has uncommitted changes.

**Never rewrite what you published.** A document release pins the commit you
published from; the board, the reviewer's pinned inputs and the attribution all
read that commit. In the documents repository: never force-push; never `--amend`
or rebase a commit that has been pushed; if your push is rejected because someone
else pushed first, `git pull --no-rebase` (a merge commit is fine — it carries
your trailers too) and push again. Fix a bad round by publishing the next round,
never by rewriting the last one. Your code branches are yours to rebase until a
reviewer or tester has seen them; from then on, merge instead (below). The
documents repository is shared history from the first push. The CLI refuses to
publish a commit no remote branch has: push first.

**Parallel tasks.** Sibling tasks merge while yours waits, so every role keeps
the PRs mergeable. The coder merges the current integration base into each PR
branch before every sign-off, never rebases or force-pushes a branch a reviewer
has seen, and names the base tip beside each PR head; a pin of another
repository's head moves only forward. The tester (and reviewer) tests each PR
merged with the current base tip and names both; a PR that does not merge is a
priority 1 review item. Whoever the board's `delivery.merge` names merges, by its
method and in its order (undeclared: the coordinator if it covers PR_MERGE,
else the role that carries it, else a person; a merge commit at the tested
head, in the notes' order); the coordinator re-checks every other DELIVERING task's PRs after
each merge, and reopens one that no longer merges to the coder with the
conflict named. The routing-rules block of every served prompt states the
procedure.

**Retries are safe.** Publishing is idempotent on the task, the type, the commit
and the file digest, so a timed-out publish that you re-run returns the same
release rather than opening a new round.

**No lifecycle on publish.** A document is published as `DRAFT`. When your hop
signs off, with either outcome, the board promotes the releases you list as
outputs to `ASSEMBLED`, and that is what hands them to the next role. Do not
pass `--lifecycle`: anything other than `DRAFT` is refused. A hop you return
leaves its drafts as drafts. A reviewer's pass promotes what it reviewed (the
documents pinned as its inputs) to `READY_TO_SHIP`, which on a board means
reviewed; a rejection promotes nothing.

> **CLI version.** `agent doc publish` and the `--outputs` flag need
> the `26.10.2` pinned in §1.2 (`rearm_cli_min`) or later.
> If either reports an unknown command or flag, do not conclude anything
> from that alone — check what you have:
>
> ```bash
> rearm version
> rearm agent --help          # does `doc` appear in the subcommand list?
> ```
>
> Then **tell the operator what those printed.** Without the command you
> cannot publish, and a role that declares a required output cannot be
> signed off, so this is a stop-and-report rather than something to work
> around. Do not upgrade the CLI on your own; the pin exists so every
> agent on a host runs a verified binary.

#### The review item index

A `BOARD_REVIEW_ITEMS` or `BOARD_TEST_REPORT` release carries an index beside the
markdown. This shape is **validated by the server**, so it is part of the API
contract rather than a style suggestion:

```json
{
  "kind": "BOARD_REVIEW_ITEMS",
  "round": 2,
  "verdict": "REJECTED",
  "reviewItems": [
    { "id": "F-3", "priority": 1, "status": "OPEN",
      "title": "Null dereference when a release has no parents",
      "location": { "path": "backend/src/.../ReleaseService.java", "line": 412 } },
    { "id": "F-4", "priority": 2, "status": "OPEN",
      "title": "The requirement does not say what a cycle is reported as",
      "location": { "element": "REQ-F-012" } },
    { "id": "F-1", "priority": 2, "status": "RESOLVED",
      "title": "Missing index on agent_session_usages.board" }
  ],
  "tested": [{ "pr": "https://github.com/relizaio/rearm/pull/396", "head": "9b3b1ed6" }]
}
```

- `tested` names the commit you reviewed or tested for each PR the task links,
  7 to 40 hex characters. It is required on a tester's pass: a `BOARD_TEST_REPORT`
  that passes must name the head it tested for every PR the task links, and the
  board refuses it otherwise. When a PR moves past the head that pass named, the
  board sends the task back to the role that passed it. A reviewer may name the
  heads it looked at; they are informative, and the board holds the PRs to the
  tester's. Any head named must be of a PR the task links.
- `verdict` is `PASSED` or `REJECTED`. A test report uses the same envelope with
  `kind: BOARD_TEST_REPORT` and adds `counts { passed, failed, skipped }`, with one
  entry per failed case.
- `priority` is an integer, 1 highest, at most your org's level count (3 unless
  an operator changed it). Out of range is refused.
- `status` is `OPEN`, `RESOLVED`, `ACCEPTED` (the operator took the risk) or
  `WITHDRAWN` (retracted). You will also read `POLICY_ACCEPTED` on rounds the
  board cut itself: the loop ran out — a cycle cap, a no-progress stop or a
  budget — and the task completed with the item still open. Nobody looked at
  it, which is precisely what distinguishes it from `ACCEPTED`. **Never write
  it yourself**; it is the board's word, not a verdict you may reach.
- `id` is stable for the life of the task. Ids are free-form; `F-<n>` is the
  convention.
- `location` is optional: `path` and `line` for code, `ref` for a document
  section. Name the element when the document has one: `"element": "REQ-F-012"`;
  path and line are filled in from its index, and an id no document of the task
  or its inputs defines is refused with the nearest ones.
- `decidedBy` and `decidedIn` record who last decided a review item (accepted,
  withdrew or re-prioritised it, or filed it by hand) and in which round.
  **The server writes them.** Leave them out, or repeat what the previous
  round had; any other value is refused. When your round changes a review item's
  priority, or its status in any way but `OPEN` to `RESOLVED`, the server
  records your session as the decider.
- A review item a **person** decided keeps its status and priority. You may resolve
  it (`OPEN` to `RESOLVED`) once the fix lands, and nothing else. Review items a
  person filed have ids `P-<n>`; carry them forward like any other.
- `"correction": true` marks an item a person filed while **accepting** a gate:
  work the task was accepted past. It never blocks routing or completion, but it
  is open work: carry it forward and address it in your next round like any
  review item, `RESOLVED` when done. **The server writes the flag.** Leave it out or
  repeat it; it is carried from the previous round either way, and claiming it
  for an item that was not a correction is refused. A reviewer who finds a
  correction matters more than that re-raises it as a new review item under a new id.
- Everything richer — reasoning, diffs, assertion output — goes in the markdown
  under a heading that starts with the review item id, e.g.
  `### F-3: Null dereference when a release has no parents`.

**Every round must repeat every review item the previous round left open.** The
server refuses a round that drops one, and names the ids. This is the rule most
worth remembering, because breaking it fails silently in the other direction:
only the newest round is ever read, so a dropped review item would disappear from
the record while the problem still stands. Anything the previous round marked
`RESOLVED`, `ACCEPTED` or `WITHDRAWN` may be dropped — that is what keeps the
list short.

Carry an open review item forward under the **same id**; mark it `RESOLVED` in the
round where the fix landed; mint a new id only for something genuinely new.
One exception: a review item the producer fixed in part is `RESOLVED` in that round,
and what remains is re-raised under a new id whose title names the old one
(`F-4 (rest of F-1): …`), because the same id still open reads as no progress.

Your role's own prompt, served to you at assignment, says what your role is
expected to produce and when to pass or reject. Where it is more specific than
this section, follow it — this section is the contract the server enforces, not
the judgement your role applies.

