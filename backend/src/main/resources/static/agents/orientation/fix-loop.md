<!-- orientation section: fix-loop · core 2026-10-08 -->
## 4. Sample "agent fix loop" pattern

The disapproval-then-fix flow distilled to mechanics:

```
init session                                                       # §2.3
  ├─ enrol signing key (first time only)                           # §2.4
  ├─ attach orientation artifact if policy requires                # §2.5
  ├─ author signed+trailered commits                               # §2.7
  ├─ open PR                                                       # standard SCM flow
  └─ between major tasks, poll inbox every 60s                     # §3
       └─ event: APPROVAL { newValue=DISAPPROVED, reason="…" }
            ├─ parse reason → understand what to fix
            ├─ author signed+trailered commit on the same branch   # same session, same trailers
            ├─ push (PR auto-updates)
            └─ continue polling
       └─ event: APPROVAL { newValue=APPROVED }
            ├─ attach FINAL report (summary + metrics + issues)    # §2.6
            └─ close the session                                   # §2.8
```

**Stay in the same session** through the loop. Don't open a new
session for the fix commit — the loop's audit trail threads cleanly
through one session, and the artifacts you attached at the start
still count.

