# Importing VEX

VEX (Vulnerability Exploitability eXchange) lets you record analysis decisions about a CVE — whether it's exploitable, why a workaround applies, what mitigation is in place — in a structured machine-readable form. ReARM imports VEX documents from CycloneDX-VEX 1.6 and OpenVEX 0.2.0, matches each statement to your release inventory, and turns it into a Finding Analysis (or stages it for human review when policy or context warrants).

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

You can pick narrower than the default; the import won't let you go wider than what your binding context supports. If you accept a VEX-driven analysis at one scope and a broader-scope analysis already exists with a *different suppression class* (e.g., the org-wide row says NOT_AFFECTED, the new VEX says EXPLOITABLE), the import flags the conflict and stages the proposal for review instead of silently overriding.

### Import mode

What happens to each statement after parsing.

| Mode | Behaviour |
|---|---|
| **Auto-accept** (default) | Each parsed statement immediately becomes a Finding Analysis (or a Mitigation Attestation when conditional). The trust gate may still demote individual statements to STAGE if their issuer class warrants it. |
| **Stage for review** | Every statement lands as a `PENDING` proposal in the VEX Proposals inbox; you accept or reject each one. |
| **Reject** | Used for malformed-batch testing — every statement is rejected without writing anything. |

The import mode and the trust gate compose with **least-permissive wins**: picking Auto-accept doesn't widen past the gate's STAGE verdict for low-trust issuers. Picking Stage or Reject overrides the gate downward.

### Issuer class

Defaults to **Vendor**, the cautious choice. Vendor's "exploitable" claims auto-accept, but vendor's "not affected" claims stage for review — that catches cases where a vendor is downplaying a finding you actually care about. Switch to **Self** when you authored the VEX about your own code (your own suppressions auto-accept). Switch to **Third party** for fully-untrusted VEX — everything stages.

ReARM also derives an issuer class from binding context (see [Concepts → Issuer class](../concepts/#issuer-class)); the dropdown's value is what wins. Leave the dropdown blank to use the derived value instead.

## What happens after upload

The VEX document gets parsed (CycloneDX-VEX or OpenVEX, auto-detected from content). Each statement runs through the import pipeline:

1. **Match** the statement's product PURL to releases in your org's inventory.
2. **Resolve verdict** by composing the trust gate, your import-mode pick, and the broader-scope conflict guard.
3. **Commit** the verdict — auto-accept writes a Finding Analysis (or a Mitigation Attestation when the claim is conditional), stage creates a `PENDING` proposal, reject records the rejection without writing.

Statements that don't match any release in your inventory are reported as `unmatched` and don't produce proposals. Doc-level parse errors abort the whole import.

The VEX Proposals inbox (left nav → **VEX Proposals**) shows every staged proposal across the org; per-release proposals also appear in the **VEX** tab on the release page.

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

Direct API import isn't currently exposed as a separate mutation — every VEX import goes through the artifact-upload path (`addArtifactManual` / `addArtifactProgrammatic` with `type: VEX`). For CI pipelines, the [rearm-cli](https://github.com/relizaio/rearm-cli)'s `addrelease` command and the GitHub Actions wrappers cover this: upload the VEX file as an artifact with `type: VEX` and the import pipeline triggers automatically.
