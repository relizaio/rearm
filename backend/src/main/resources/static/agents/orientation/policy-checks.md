<!-- orientation section: policy-checks · core 2026-10-08 -->
## 6. CEL surface (what policies can check)

Operator-authored agent policies evaluate against three variables on
your session: `session.*`, `agent.*`, `model.*`. Policies use **match-
to-block** semantics — the CEL describes the *failure* condition.
CEL true → policy *fires* (FAILED / WARNING / AWAITING). CEL false →
PASSED. Common shapes:

```
!session.artifacts.exists(a, a.type == "AGENTIC_REPORT")           # orientation missing → block
agent.model != "claude-opus-4-7"                                   # disallowed model → block
session.policyEvents.exists(p, p.state == "FAILED")                # any FAILED verdict → block
```

Component-level CEL gates evaluate against the release shape:

```
release.commits.exists(c, c.signature.state != "VERIFIED")         # signed-commit gate
release.commits.exists(c, c.attribution.state == "REJECTED")       # attribution gate
release.agentSessions.exists(s, s.hasFailedPolicy)                 # session-policy gate
```

The CEL helper in the ReARM UI's policy edit page is the
authoritative reference — every field with sample expressions. Ask
the operator to share the relevant policy if a verdict is unclear.

