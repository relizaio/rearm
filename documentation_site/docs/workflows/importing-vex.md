# Importing VEX

VEX (Vulnerability Exploitability eXchange) lets you record analysis decisions about a CVE — whether it's exploitable, why a workaround applies, what mitigation is in place — in a structured machine-readable form. ReARM imports VEX documents from CycloneDX-VEX 1.4 – 1.7 and OpenVEX 0.2.0, matches each statement to your release inventory, and turns it into a Finding Analysis (or stages it for human review when policy or context warrants). (The VEX subset ReARM reads — `vulnerabilities[]` with `analysis`, `affects`, `ratings` — is unchanged in CycloneDX 1.7; constructs introduced in 1.7 outside that subset are ignored on import.)

For the underlying triage model and how Finding Analysis records work, start with [Auditing Findings](./auditing-findings).

## When to import VEX vs. triage manually

| You have… | Use |
|---|---|
| A vendor-supplied VEX document about a deliverable they shipped to you | **Import VEX** — vendor's claim flows through with the right provenance |
| A self-authored VEX document from your own SAST/internal review pipeline | **Import VEX** — bulk-applies your decisions in one step |
| A handful of findings in the UI to assess case-by-case | [Manual triage](./auditing-findings) is faster |
| A bulk decision that doesn't fit VEX vocabulary | Manual triage with a broader scope |

The two paths interoperate: VEX-imported analyses land in the same Finding Analysis store as manually-created ones, and the standard scope hierarchy applies.

## Uploading a VEX artifact

VEX enters the system as a **regular artifact upload**. From a release page, click **Add Artifact**, choose `VEX` for the artifact type, then attach the VEX document. The form surfaces three additional controls when type=`VEX`:

### Scope

How broadly the VEX claim applies. Default is **Component** — most VEX claims target a specific component family (a vendor's "this code path isn't reachable" usually holds for every release of the same component).

| Scope | When to pick |
|---|---|
| **Component** | Default. The claim applies across all branches/releases of one component. |
| **Organization** | The claim applies to every release of every component in the org. Pick when re-using one analysis across the whole portfolio. |
| **Branch** | The claim applies across all releases on one branch. |
| **Release** | The claim applies to this release only. Pick this when the VEX is dogfooding one specific build's analysis. |

Any scope can be picked; whether its statements take effect automatically depends on who uploads (see [Who can make an import take effect](#who-can-make-an-import-take-effect)). Organization scope applies a statement only to a package the organization has. Resource group is not supported. If you accept a VEX-driven analysis at one scope and a broader-scope analysis already exists with a *different suppression class* (e.g., the organization-wide row says NOT_AFFECTED, the new VEX says EXPLOITABLE), the import flags the conflict and stages the proposal for review instead of silently overriding. An existing analysis at the same scope and object with the other suppression class also stages the statement.

### Import mode

What happens to each statement after parsing.

| Mode | Behaviour |
|---|---|
| **Auto-accept** (default) | Each parsed statement immediately becomes a Finding Analysis (or a Mitigation Attestation when conditional). The trust gate may still demote individual statements to STAGE if their issuer class warrants it, and so does the [severity resolver](#severity) when a statement has no rating and no fallback. A statement takes effect only when the uploader holds Finding Analysis write at the chosen scope (see [Who can make an import take effect](#who-can-make-an-import-take-effect)). |
| **Stage for review** | Every statement lands as a `PENDING` proposal in the VEX Proposals inbox; you accept or reject each one. |
| **Reject** | Used for malformed-batch testing — every statement is rejected without writing anything. |

The import mode and the trust gate compose with **least-permissive wins**: picking Auto-accept doesn't widen past the gate's STAGE verdict for low-trust issuers. Picking Stage or Reject overrides the gate downward.

### Issuer class

The form defaults to **Vendor**, the cautious choice. Vendor's "exploitable" claims auto-accept, but vendor's "not affected" claims stage for review: that catches cases where a vendor is downplaying a finding you actually care about. **Self** is for VEX you authored about your own code (your own suppressions auto-accept). **Third party** is for fully untrusted VEX: everything stages.

The upload also has its own class, from where the VEX is attached (see [Concepts: Issuer class](../concepts/#issuer-class)): Self for the release, its source code entry or an outbound deliverable (one the release ships); Vendor for any other deliverable, such as an inbound deliverable (something the release received). Third party is never derived from where a document is attached; it is only chosen in the dropdown.

A less trusted class than the upload's own always applies, so the default Vendor on a release upload applies. A more trusted class applies only when the uploader holds Finding Analysis write at the chosen scope; otherwise the upload's own class is used and the import summary says `Issuer override <class> ignored: raising trust needs Finding Analysis write at the <scope> scope; the upload's own class <class> was used`. Leave the dropdown empty to use the upload's own class.

## What happens after upload

The VEX document gets parsed (CycloneDX-VEX or OpenVEX, auto-detected from content). Each statement runs through the import pipeline:

1. **Match** the statement's product PURL to releases in your org's inventory.
2. **Resolve verdict**, in this order: the trust gate and your import-mode pick; the broader-scope and same-scope conflict guards; a missing severity; a severity change; the uploader's permission (see [Who can make an import take effect](#who-can-make-an-import-take-effect)).
3. **Commit** the verdict — auto-accept writes a Finding Analysis (or a Mitigation Attestation when the claim is conditional), stage creates a `PENDING` proposal, reject records the rejection without writing.

Statements that don't match any release in your inventory are reported as `unmatched` and don't produce proposals. Doc-level parse errors abort the whole import.

The VEX Proposals inbox lives as a tab on the **Finding Analysis** page (left nav → **Finding Analysis** → **VEX Statement Proposals** tab) and shows every staged proposal across the org. Per-release proposals also appear in the **VEX** tab on the release page; click the eye icon to review one — it opens in a new browser tab so you can keep the queue open while triaging.

## Who can make an import take effect {#who-can-make-an-import-take-effect}

Anyone who may add artifacts to the release can upload a VEX document. A statement is accepted automatically only when all of these hold:

- the trust gate (see [Issuer class](#issuer-class)) and the import mode allow it;
- no analysis at a broader scope, or at the same scope, has the other suppression class;
- a severity is known (see [Severity](#severity));
- the statement does not change the finding's severity, unless the issuer is Self;
- the uploading credential itself holds Finding Analysis write that covers the import's target.

Otherwise it is staged, and the proposal and the upload summary name the reasons:

| Reason | Shown as |
|---|---|
| `NO_FINDING_ANALYSIS_WRITE` | staged: you need Finding Analysis write at this scope |
| `SAME_SCOPE_CONFLICT` | staged: an existing analysis at this scope says otherwise |
| `BROADER_SCOPE_CONFLICT` | staged: an analysis at a broader scope already exists with a different suppression class |
| `SEVERITY_MISSING` | staged: the statement has no severity and none could be derived |
| `SEVERITY_CHANGE` | staged: the statement changes the severity |

The Finding Analysis write grant each scope needs:

| Import scope | The import's target | A Finding Analysis write grant that covers it |
|---|---|---|
| Release | the upload's release | on that release; on its component, directly or through a perspective that contains it; or on the organization. A grant on a product covers only the product itself for writes |
| Component | the release's component | on that component, directly or through a perspective that contains it; or on the organization |
| Branch | the organization (the branch must be the release's own) | on the organization |
| Organization | the organization | on the organization |
| Resource group | none | none: a VEX upload records no import result; a SAFETY_CONTEXT upload is refused |

An organization Administrator permission covers every target, and so does a global administrator user.

The credential is judged, not the person behind it: a signed-in user by the user's own grants and the grants of the user's groups; a FREEFORM key by its grants; a personal (USER) key by what both the key and its owner hold at that moment; a federated key by its trust rule. Component keys and organization-wide read-write keys never hold Finding Analysis write, so CI that should auto-accept uses a FREEFORM key granted Finding Analysis write at the scope it imports to, and no wider. After `rearm login`, the key the session is bound to is judged.

Accepting or rejecting a VEX proposal needs Finding Analysis write at the proposal's scope, with the same mapping.

Organization administrators find an **Import audit** in the VEX Statement Proposals tab. It lists accepted statements at Organization or Branch scope, or with a suppressing state, whose accepting credential cannot be shown to hold Finding Analysis write there now. Its Yes column reads the grants the audit can see; it is not a statement that the accepting person is still active, so do not treat a Yes as proof.

The same rule applies to SAFETY_CONTEXT imports: see [Safety Context: Who can make levels take effect](./safety-context#who-can-make-levels-take-effect).

## Severity

Severity is **required** on every Finding Analysis row, so every accepted VEX proposal needs one. ReARM resolves it from three sources, taking the first non-null:

1. **The inbound `ratings[]` array** in the VEX statement. CycloneDX `ratings[].severity` is mapped to ReARM's bucket (`critical` → CRITICAL, `high` → HIGH, `medium` → MEDIUM, `low` → LOW, `info` / `none` / `unknown` → UNASSIGNED). When multiple ratings are present (e.g., CVSS-v2 alongside CVSS-v3) ReARM picks the **highest** — a vendor's worst-case call is the safer default for the reviewer to accept or override. OpenVEX 0.2.0 has no severity in its spec, so OpenVEX statements always reach the next step.

2. **Existing Finding Analysis rows** for the same `(org, location, findingId)`. The narrowest-scope match wins (RELEASE → BRANCH → COMPONENT → ORG). If you've already triaged this CVE on a sibling release, that decision's severity carries over.

3. **The canonical vulnerability record** for the CVE in your org's vulnerability table. ReARM merges severity across upstream sources (NVD > GitHub > OSV > VulnDB, see [How a Vulnerability's Severity Is Chosen](./auditing-findings#how-a-vulnerabilitys-severity-is-chosen)) into a single per-org row that's available to the lookup.

If all three come up empty (a CycloneDX VEX with no ratings, no prior analysis at any scope, and no canonical vuln record for the org) and your import mode is **Auto-accept**, ReARM demotes the statement to **STAGE** with a `SEVERITY_MISSING` demotion banner on the proposal. The reviewer fills in severity via **Modify** before accepting. (STAGE and REJECT import modes are unaffected — they were going to surface to a reviewer anyway.)

A null severity at accept time is rejected at the service boundary with a clear error — the Accept button on the review pane disables when severity isn't set, with a hint pointing at the Modify form.

## Conditional mitigations

Some VEX statuses are *conditional* — they depend on a runtime control rather than a code property. Examples:

- `NOT_AFFECTED` with justification `protected_at_perimeter` — depends on the perimeter being intact at deploy time
- `EXPLOITABLE` with a workaround — depends on the workaround being in place

For these, ReARM defers the Finding Analysis write until you (or someone you assign) **attests** that the mitigation is actually in place. The attestation goes in the **Mitigation Attestations** inbox; from there you can record evidence and attest, or waive (permanently abandoning the deferred write).

While an attestation is `PENDING`, the originating proposal shows a yellow "deferred" banner. After ATTESTED, the deferred Finding Analysis fires; after WAIVED, the banner switches to "permanently abandoned" so reviewers know the row was never written.

## Reviewing a staged proposal

Open the VEX Proposals inbox or click into a proposal from the per-release VEX tab. The review pane shows:

- **The original VEX statement** — verbatim JSON from the source document. Locked — preserved forever for audit and round-trip.
- **The proposed ReARM analysis** — what fields would be written if you accept (with translation notes when OpenVEX vocabulary was expanded to a less-specific CDX form). **Editable** via the **Modify** button.
- **Context banner (top of page)** — if a finding-analysis row already exists at this proposal's exact scope, a blue banner shows its current state with an *Open in Finding Analysis* link. Visible regardless of proposal status, so even REJECTED proposals surface the rendered state they didn't override.
- **Existing analyses for this finding** — context table of any Finding Analysis rows that already exist at other scopes for the same `(location, findingId, type)`. If your accept would conflict with one (different suppression class), a warning banner explains the conflict.

Three terminal verbs:

- **Modify** (PENDING only) — switches the right pane into editable form. Adjust state, justification, details, severity, responses, recommendation, or workaround; **Save changes** records the edits via `updateVexStatementProposal`. The proposal stays PENDING. Then click Accept to apply your edited version (not the vendor's original) to the Finding Analysis row.
- **Accept** — writes the analysis (or creates an attestation, if conditional). Optional comment is captured for the audit log.
- **Reject** — records the audit trail without writing. **Requires a comment** explaining why. If an analysis row already exists at this 5-tuple, a yellow warning makes it clear Reject is audit-only and won't unwind the prior decision.

Completed proposals (ACCEPTED, REJECTED) show *Acted by &lt;user&gt; at &lt;time&gt;* + the reviewer comment, so the audit trail is self-explanatory.

## Round-trip and export

Imported VEX statements are preserved verbatim on the proposal as `sourceStatementJson` so a downstream consumer can verify what was imported. ReARM's VEX export (`releaseVexExport` / **Export VEX** on the release page) re-emits Finding Analysis decisions as either CycloneDX-VEX 1.6 or OpenVEX 0.2.0, with `x_rearm_*` extension properties carrying ReARM-specific metadata (scope, mitigation attestation status) so a round-trip is non-lossy when re-importing.

## Programmatic import

Direct API import isn't exposed as a separate mutation — every VEX import goes through the artifact-upload path. Both the **multipart manual** (`addArtifactManual`, used by the UI) and the **programmatic** (`addArtifactProgrammatic`, used by [rearm-cli](https://github.com/relizaio/rearm-cli) and CI integrations) paths trigger the import pipeline when the artifact's `type` is `VEX` — whether the artifact is attached to the release, a deliverable (`--deliverablearts`), or a source-code entry (`--scearts`).

`rearm-cli addartifact` prints the import summary of each VEX it uploads and checks it, for artifacts attached to the release or to a deliverable. The server does not report the import result of a source-code entry upload, so the CLI refuses a VEX in `--scearts` unless `--allow-import-failure` is given; put it in `--releasearts` instead (the import runs against the release either way).

CI example:

```sh
rearm-cli addartifact \
  --component "$COMPONENT_UUID" \
  --version "$RELEASE_VERSION" \
  --wait-for-sbom 10m \
  --artifacts '[{
    "filePath": "vex.cdx.json",
    "type": "VEX",
    "bomFormat": "CYCLONEDX",
    "storedIn": "REARM",
    "displayIdentifier": "vendor-vex-1",
    "importScope": "COMPONENT",
    "importMode": "STAGE"
  }]'
```

The three controls the UI upload form exposes, [Scope](#scope), [Import mode](#import-mode) and [Issuer class](#issuer-class), are set on the artifact JSON with the optional `importScope`, `importMode` and `userIssuerClassOverride` fields. Omit them to accept the defaults (`COMPONENT` scope, `AUTO_ACCEPT` mode, issuer class derived from binding context). `vexScope` and `vexImportMode` are deprecated aliases of `importScope` and `importMode`; when both are set, the generic field wins. The CLI refuses an artifact field it does not know before sending anything, so a misspelled field fails the step instead of falling back to a default.

`addrelease`, `addreleases`, `addodeliverable` and `getversion` store a VEX artifact but never import it, so the CLI refuses a VEX on those commands unless `--allow-import-failure` is given (with the flag it is stored without an import). Upload the VEX with `addartifact` once the release's SBOM is indexed.

**Precondition:** the release must already have at least one SBOM artifact (so the VEX matcher has an inventory to match against). The upload fails loud with *"Cannot import VEX: release has no SBOM components yet"* if you try to import VEX into an SBOM-less release. Upload the SBOM in its own, earlier call; an SBOM in the same call as the VEX does not count. `--wait-for-sbom 10m` makes `addartifact` retry the upload until the SBOM is indexed (nothing is stored while the server refuses).

**Result and exit code:** the CLI prints each VEX artifact's import summary on stderr and exits `3` when an artifact imported nothing or its result is unknown (the artifact is still stored). See [Importing VEX and safety context from CI](../integrations/rearmcli#importing-vex-and-safety-context-from-ci) for the summary, the exit codes, `--allow-import-failure`, and which keys make an import take effect.
