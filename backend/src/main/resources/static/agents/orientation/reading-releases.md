<!-- orientation section: reading-releases · core 2026-10-08 -->
## 5. Read-side helpers

When you need the full current state of a session (after an inbox
event, on startup, when debugging an attribution issue):

```bash
rearm agent session show <session-uuid>
```

Returns the session shape with `policyEvents` (each carrying the
embedded `policy` snapshot — `cel`, `description`, `enabled`),
`releases[]`, `pullRequests[]`, `parentSession`, the full `artifacts`
and `commits` lists.

When the inbox points you at a release uuid (typical for
`LIFECYCLE_CHANGE` and `APPROVAL` events), look it up:

```bash
rearm agent release show <release-uuid> --session <session-uuid>
```

Pass `--session <session-uuid>` (or `--client-session-id <id>`, the
value from your commit trailer) — the backend verifies your key owns
the session before returning anything.

Returns `updateEvents[]` (human-readable lifecycle reasons,
pre-aggregated — no need to re-derive from triggers),
`approvalEvents[]` (full approval history with reviewer comments),
and `sourceCodeEntryDetails[]` (per-commit attribution + signature
state). The `updateEvents[].message` field is the most useful single
field — it spells out *why* a CEL gate flipped the release (e.g.
*"Triggered by 'Reject when any commit is not VERIFIED' (CEL: …)"*).

### Reading a release's vulnerabilities and violations

`agent release show` also returns the release's `metrics` — the
security posture from the latest Dependency-Track scan
(`metrics.lastScanned`; null/empty until a scan has completed):

- severity counts — `critical`, `high`, `medium`, `low`, `unassigned`;
- policy-violation totals — `policyViolationsSecurityTotal`,
  `policyViolationsLicenseTotal`, `policyViolationsOperationalTotal`;
- per-finding detail lists:
  - `vulnerabilityDetails[]` — `purl`, `vulnId`, `severity`,
    `analysisState`;
  - `violationDetails[]` — `purl`, `type`, `license`, `analysisState`.

This is how you inspect *which* CVEs / license or policy violations a
release carries — e.g. after a `POLICY_GATE` `LIFECYCLE_CHANGE` whose
`reason` names a vuln threshold (`CEL: release.highVulns > 0`), pull
`vulnerabilityDetails[]` to see the actual findings and decide whether
to bump a dependency, request a VEX, or escalate.

#### Localize findings per artifact — a release has *several* SBOMs

The release-level `metrics` above is an **aggregate**. A single release
almost always carries **multiple** scanned artifacts, and they are not
the same thing:

- the **source-code SBOM** (attached to the source code entry) — your
  dependencies as seen in the repo;
- one or more **deliverable SBOMs** (attached to each built deliverable,
  e.g. the container image) — what actually ships, including base-image
  and OS packages the source SBOM never sees;
- **SARIF** (`CODE_SCANNING_RESULT`) and **VDR** artifacts, which also
  carry findings.

A very common shape: the **source-code SBOM is clean but the deliverable
(container) SBOM is not** — the vulnerabilities live in the base image,
not your code. If you only read the release aggregate you'll see "N
high" with no idea where it came from. **Don't conclude the code is at
fault from the aggregate.** Instead, walk the per-artifact `metrics`
(every artifact carries its own counts + `vulnerabilityDetails` +
`violationDetails`):

- `sourceCodeEntryDetails.artifactDetails[]` — source-code SBOM(s) / SARIF;
- `artifactDetails[]` — release-level artifacts;
- `variantDetails[].outboundDeliverableDetails[].artifactDetails[]` —
  deliverable SBOMs / scan results (the deliverable's `displayIdentifier`
  tells you which image).

Each artifact's `type` / `displayIdentifier` / `bomFormat` identifies
what it is. So you can say "source SBOM: 0 high; container deliverable
`…/rearm-cli`: 6 high" and act on the right layer.

**Suppression quirk (v1).** Release-level `metrics` reflects vulnerability
suppressions applied at **release scope** (a vuln suppressed for this
release at a non-org scope is dropped from the release totals/details);
the **per-artifact** `metrics` are **raw** (artifact scope) and do *not*
see that release-scope suppression. So the release-level and per-artifact
detail lists can legitimately differ — that's expected, not a bug. When
they disagree, the release-level list is the suppression-adjusted view;
the per-artifact list is the unfiltered scan.

**Permissions.** A release **your own session built** (one of the
session's commits traces through to it) is readable with just the
FREEFORM `AGENT` key that owns the session — no extra grant needed,
and `metrics` comes with it. To read a release **not** attributed to
your session, the key additionally needs explicit `RESOURCE` read
permission on that release's component / product (scope `RELEASE` /
`COMPONENT` / `PRODUCT`); without it the lookup is denied. If you hit
`Not authorized` on a release you didn't build, that's the missing
grant — ask the operator rather than retrying.

