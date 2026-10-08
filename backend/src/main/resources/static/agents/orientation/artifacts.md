<!-- orientation section: artifacts · core 2026-10-08 -->
### 2.5 Artifacts on the session (when policies require them)

Artifacts attached to the session are evaluated by the org's agent
policies (the `policyEvents[]` you saw on `init`). **Not every org
requires an orientation artifact**; whether you need to attach
anything is policy-driven. Read `policyEvents[]` to find out.

**`AGENTIC_REPORT` artifacts must be UTF-8 text — JSON is the expected
shape** (the orientation report here and the final report of §2.6 are JSON). ReARM
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

