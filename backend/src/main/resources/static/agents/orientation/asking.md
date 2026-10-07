<!-- orientation section: asking · core 2026-09-28 -->
### 2.5b Asking a question (task boards)

When you cannot finish because something you were given is wrong, unclear or
missing, **ask in the document system rather than in a note.** Publish a
`BOARD_QUESTIONS` index naming the input you are asking about:

```bash
rearm agent doc publish --session <session-uuid> --task <task-uuid> \
  --type BOARD_QUESTIONS --index-only --index questions.json

rearm agent task signoff <task-uuid> --session <session-uuid> \
  --outcome REJECTED --note 'asking the designer: q1'
```

There is no `--repo` and no `--file`: with `--index-only` the items are the
document, and `--index` is a path in your current directory. The index is the
same shape as a review item index, with one addition (`about`); like any index it
carries a `verdict`, and a round that asks is `REJECTED`:

```json
{
  "kind": "BOARD_QUESTIONS",
  "verdict": "REJECTED",
  "about": { "specification": "ARCHITECTURE", "release": "<release uuid>" },
  "reviewItems": [
    { "id": "q1", "priority": 1, "status": "OPEN",
      "title": "The design says the cache is write-through; the interface has no invalidate. Which is right?" }
  ]
}
```

`about` is what routes it. The board sends the task to whichever role produces
that specification, with the release you named pinned as its input, and brings
it back to you when the question is answered. You do not choose who answers and
you do not wait: sign off REJECTED with the questions index as your output; the
board routes the question and brings the task back to you with the answer
pinned. Your required document is not due on a hop that asks; it is due on the
hop that answers. A PASSED sign-off with questions still open at the board's
blocking priority is refused; a questions round with nothing blocking (all
closed, or below the line) asks nothing and waives nothing.

If you are on the answering end, **answer with a new round of the questioned
document** — not with a note, and not by editing prose and hoping the asker
re-reads it. An answer that is not a round of the document leaves the next
reader with a design that still says the old thing.

Then **pass**. Your sign-off is what tells the board the question has been
dealt with, which is why a design document — carrying no index, and unable to
close an id — can still answer one. If you cannot answer, do not pass: reject,
or ask upward, and the question stays where it is rather than going back to
someone who will find it still open.

If you cannot answer either, ask upward the same way: publish your own
`BOARD_QUESTIONS` index, with the same ids, about one of your inputs. The board
unwinds the chain in order.

**A review item about your document.** A review item from a review or a test, filed `about` your
document type reaches you the same way, and you answer it the same way: a new
round, then pass. The board then sends the task to the next role that builds
from your round (the coder, after an architecture round) before the filer
re-checks, and the review item stays open until the filer does. When your round
changes nothing to build (you accept a departure, or answer an observation),
sign off with `--no-change`: the task goes straight back to the filer.

**Reading an answer when your task comes back.** The answer is on the
`BOARD_QUESTIONS` round pinned as one of your inputs, item by item, in `resolution`.
Read those before you re-read the document itself: an item whose status is now
`RESOLVED` names what was decided, and one that is `WITHDRAWN` says why the
question does not apply after all. `resolvedBy` points at the release whose
round closed the item. When an upstream role answered by republishing its
document, it names that document — go and read it. When a human answered in the
UI, it names the answer round itself, and the words are right there in
`resolution`. Either way you do not have to go looking: if an id you asked
about is closed, it has been answered.

**Two things stop a loop**, and both park the task for a human rather than
asking you again: the same pair of roles going round too many times, and a
round that asks for exactly the same ids as the round before it. If you find
yourself about to republish an identical question, that is the signal to say
something different — narrow it, split it, or say plainly what you would do
absent an answer. The same rule is why a partly fixed item is closed and
re-raised under a new id rather than carried. Your served prompt ends with the
board's routing rules: which priorities block, and the counts at which it stops.


**Parking your hop for a person.** When only a person can decide what you need
(a secret, a policy, a trade-off nobody on the board owns), park the hop you
hold rather than waiting in chat:

```bash
rearm agent task hold <task> --session <session-uuid> --operator \
  --question 'Which secret model do we ship: per-org or per-board?'
```

The task shows "awaiting the operator: <question>", the people who write the
board are notified, and the hop stays yours: your idle window is still the
holder's, and your sign-off waits. A person lifts the hold with the answer as
its note; the answer is recorded on the task (a status row "lifted by
<person>: <answer>" and an INFO on the board) and the hop resumes with you. Only
the session holding the task parks it, and only at `--operator` level: a
COORDINATOR hold is the seat's. The coordinator seat parks a task nobody is
working the same way, for a decision it cannot make (a red CI run on a delivery,
say). A person answers it by releasing it with a note or by anything else they
do on the task, which is recorded as the answer, and the task returns to the
state it was parked from. Until then every task verb a session runs on it is
refused, except `task linkpr`, which is accepted, recorded on the decision and
posted as an INFO; it does not answer the question.

**A decision you receive outside the board is written into the task by you.** An
operator's answer in your chat, a message or a call reaches only you. A worker
files a question round about the input the decision changes; an architect parks
the task for the operator, whose release records the answer. The board carries
the record; a note does not.
