<!-- orientation section: commit-trailers · core 2026-09-28 -->
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

Commit with `rearm agent git commit --session <session-uuid> -m <subject>
[-m <body>]... -- <path>...` and merge with `rearm agent git merge
--session <session-uuid> <ref>`. `git commit` stages the paths you name;
both sign with the key you enrolled in §2.4 and write the trailer block. The
trailers come from the session; a block written by hand is still
accepted. Give `--co-author '<name> <email>'`, `--signing-key` and
`--signing-format` once: they are kept per agent. `--no-co-author` writes
the two ReARM trailers alone for one commit. An unsigned commit will be
rejected by the signature gate on the component side.

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

`git commit` and `git merge` run this check on the commit they write; run
it yourself on a commit you wrote by hand:

```bash
git log -1 --format=%B | git interpret-trailers --parse
```

Both `ReARM-Agentic-Session:` and `ReARM-Agent:` MUST appear in the
output. If only one appears, or only `Co-Authored-By:` appears, or
the output is empty — the commit is broken. Amend the commit
message (`git commit --amend`) and re-check before pushing — only if you have
not pushed; after a push, fix it with a new commit, never by rewriting one.

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

