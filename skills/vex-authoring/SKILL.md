---
name: vex-authoring
description: >
  Author CycloneDX-VEX or OpenVEX documents that import cleanly into ReARM.
  Use when generating, fixing, or validating a VEX file for upload to a ReARM
  release, or when a VEX upload reported zero imported statements, unmatched
  statements, or skipped entries.
---

# Authoring VEX documents for ReARM

ReARM imports VEX (Vulnerability Exploitability eXchange) documents in two
formats, auto-detected from content:

- **CycloneDX-VEX** 1.4 - 1.7 JSON. The bundled parser formally targets
  1.6, but the VEX subset ReARM reads (vulnerabilities[], analysis,
  affects, ratings) is unchanged in 1.7, so 1.7 documents import
  (verified). Constructs introduced in 1.7 outside that subset are
  ignored -- do not encode load-bearing VEX data in 1.7-only fields.
- **OpenVEX** 0.2.0

Import happens synchronously during artifact upload (artifact type `VEX` on a
release). Each statement is matched against the release's SBOM inventory and
becomes either a Finding Analysis (auto-accept) or a PENDING proposal in the
VEX Proposals inbox (stage). The import outcome (total / staged /
auto-accepted / unmatched / errored counts plus error messages) is reported
back on upload and persisted on the VEX artifact.

This skill covers the **producer side**: what the document must contain so
statements actually import. For the reviewer side (scopes, import modes,
trust gate, proposals inbox), see
`documentation_site/docs/workflows/importing-vex.md`.

## Before you write anything: two hard prerequisites

1. **The target release must already have an SBOM artifact** with reconciled
   components. The VEX matcher matches against that inventory; importing VEX
   into an SBOM-less release fails loud.
2. **Product identifiers must match the SBOM's PURLs exactly, version
   included.** ReARM canonicalizes PURLs (drops subpath and most qualifiers)
   but **preserves the version**. If the SBOM says
   `pkg:npm/minimist@1.2.6`, a statement about `pkg:npm/minimist` (no
   version) or `pkg:npm/minimist@1.2.5` will NOT match and is counted as
   `unmatched`. Copy `purl` values verbatim from the SBOM you are writing
   VEX against.

## CycloneDX-VEX requirements

### Document envelope

```json
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.6",
  "serialNumber": "urn:uuid:<uuid4>",
  "version": 1,
  "vulnerabilities": [ ... ]
}
```

### Every vulnerabilities[] entry MUST have an analysis block

This is the single most common authoring mistake. In CycloneDX, a
`vulnerabilities[]` entry **without** `analysis` is a plain vulnerability
report (what a scanner emits), not a VEX statement -- it carries no
exploitability assertion, so ReARM skips it and reports it as
"skipped: no analysis block". A document whose entries all lack `analysis`
imports **zero** statements.

```json
"analysis": {
  "state": "not_affected",
  "justification": "code_not_reachable",
  "detail": "optional free-text explanation of the assessment"
}
```

- `state` -- required. One of:
  `not_affected`, `exploitable`, `in_triage`, `resolved`,
  `resolved_with_pedigree` (imported as resolved), `false_positive`.
- `justification` -- provide it whenever `state` is `not_affected`. One of:
  `code_not_present`, `code_not_reachable`, `requires_configuration`,
  `requires_dependency`, `requires_environment`, `protected_by_compiler`,
  `protected_at_runtime`, `protected_at_perimeter`,
  `protected_by_mitigating_control`.
- `response` -- optional array, e.g. `["will_not_fix"]`, `["update"]`.

### affects[].ref must be resolvable

Each entry's `affects[]` names the product(s) the statement is about. Two
accepted forms:

1. **Direct identifier** -- a `pkg:` PURL or `cpe:` string:
   `"affects": [{"ref": "pkg:npm/minimist@1.2.6"}]`
2. **bom-ref into this same document** -- the ref must match the `bom-ref`
   of an entry in the VEX document's own top-level `components[]` array, and
   that component must carry a `purl`:

   ```json
   "components": [
     {"type": "library", "name": "minimist", "version": "1.2.6",
      "purl": "pkg:npm/minimist@1.2.6", "bom-ref": "comp-minimist"}
   ],
   "vulnerabilities": [
     {"id": "CVE-...", "affects": [{"ref": "comp-minimist"}],
      "analysis": {...}}
   ]
   ```

   Refs pointing into *another* document -- e.g. the SBOM's BOM-Link form
   `urn:cdx:<serial>/1#<ref>` -- do NOT resolve and the statement matches
   nothing.

### Include ratings[] so severity resolves

Every accepted analysis needs a severity. Put the severity in the statement:

```json
"ratings": [{"severity": "high"}]
```

With multiple ratings, ReARM takes the highest. If the statement has no
rating, ReARM falls back to existing Finding Analysis rows for the same CVE,
then to the org's canonical vulnerability record; if all three are empty an
auto-accept import demotes the statement to STAGE with a SEVERITY_MISSING
banner. Supplying `ratings[]` avoids the demotion.

### Minimal complete CycloneDX-VEX example

```json
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.6",
  "serialNumber": "urn:uuid:2c1f4a60-0000-4000-8000-000000000001",
  "version": 1,
  "vulnerabilities": [
    {
      "id": "CVE-2021-44906",
      "ratings": [{"severity": "critical"}],
      "affects": [{"ref": "pkg:npm/minimist@1.2.5"}],
      "analysis": {
        "state": "not_affected",
        "justification": "code_not_reachable",
        "detail": "The vulnerable prototype-pollution path is never invoked with attacker-controlled input."
      }
    }
  ]
}
```

## OpenVEX requirements

OpenVEX statements are validated per the 0.2.0 spec. Each statement needs a
vulnerability name (CVE id), product `@id`s (PURLs -- same exact-match rule
as above), and a valid `status`:

| status | Required | Forbidden |
|---|---|---|
| `not_affected` | `justification` OR `impact_statement` | `action_statement` |
| `affected` | `action_statement` | `justification`, `impact_statement` |
| `fixed` | -- | `justification`, `impact_statement`, `action_statement` |
| `under_investigation` | -- | `justification`, `impact_statement`, `action_statement` |

Valid `justification` values (OpenVEX vocabulary):
`component_not_present`, `vulnerable_code_not_present`,
`vulnerable_code_not_in_execute_path`,
`vulnerable_code_cannot_be_controlled_by_adversary`,
`inline_mitigations_already_exist`.

Statements that violate these rules are counted as errored with a per-
statement message; the rest of the document still imports. Note OpenVEX has
no severity field, so the fallback chain above always applies -- expect
SEVERITY_MISSING demotions for CVEs the org has never seen.

## Conditional justifications defer to attestation

Not every accepted statement applies immediately. ReARM distinguishes
**code properties** (true regardless of deployment) from **conditional
claims** (true only while some consumer-side control is in place):

- `not_affected` with one of `requires_configuration`,
  `requires_environment`, `protected_by_compiler`, `protected_at_runtime`,
  `protected_at_perimeter`, `protected_by_mitigating_control`
- `exploitable` with a `workaround` (text or a `workaround_available`
  response)

These are accepted but their Finding Analysis write is **deferred behind a
PENDING Mitigation Attestation**: someone must attest that the environmental
control / workaround is actually in place (Mitigation Attestations inbox)
before the analysis applies and finding counts change. This is by design,
not a dropped statement -- do not "fix" it by swapping the justification to
`code_not_reachable` or `code_not_present`; use those only when they are the
true assessment. `not_affected` with `code_not_present` /
`code_not_reachable` applies immediately.

## Interpreting the import outcome

The upload response (and the VEX artifact record) carries the outcome
summary. Symptom-to-fix table:

| Outcome | Cause | Fix |
|---|---|---|
| "No VEX statements imported" / total = 0 | No `vulnerabilities[]` entries carry an `analysis` block, or the document is not parseable as CDX/OpenVEX | Add `analysis` to each entry (see above); check the envelope |
| "N vulnerability entries skipped: no analysis block" | Those entries are scanner findings, not VEX statements | Add `analysis.state` (+ `justification` for not_affected) reflecting the real assessment |
| N `unmatched`, no proposals | Product PURLs / versions do not correspond to any component in the release's SBOM | Copy PURLs verbatim (including version) from the SBOM; check bom-refs resolve inside the VEX document itself |
| "doc parse failed: ..." | Malformed JSON or wrong schema | Validate the JSON; check `bomFormat` / OpenVEX `@context` |
| "Cannot import VEX: release has no SBOM components yet" | Upload order | Upload and reconcile the SBOM first, then the VEX |
| Statement staged despite Auto-accept | Trust gate (issuer class) or missing severity demoted it | Expected; supply `ratings[]` and review the proposal, or adjust issuer class if you authored the VEX yourself |
| Statement accepted but finding state / counts unchanged | Conditional claim (environmental justification or workaround) -- analysis write deferred behind a PENDING Mitigation Attestation | By design; attest the control in the Mitigation Attestations inbox and the analysis applies. See "Conditional justifications defer to attestation" |

Do not invent an `analysis` state to force an import: `state` is an
assertion about exploitability. If no assessment exists yet, use
`in_triage` (CycloneDX) or `under_investigation` (OpenVEX) -- that is
exactly what those states are for.

## Self-check before uploading

Run this against the VEX file (and the target SBOM) to catch the common
failures locally:

```sh
python3 - vex.cdx.json sbom.cdx.json <<'EOF'
import json, sys
vex = json.load(open(sys.argv[1]))
sbom_purls = set()
if len(sys.argv) > 2:
    sbom = json.load(open(sys.argv[2]))
    sbom_purls = {c.get("purl") for c in sbom.get("components", []) if c.get("purl")}
own_refs = {c.get("bom-ref"): c.get("purl") for c in vex.get("components", [])}
problems = []
vulns = vex.get("vulnerabilities", [])
if not vulns:
    problems.append("no vulnerabilities[] entries at all")
for v in vulns:
    vid = v.get("id", "?")
    if not v.get("analysis", {}).get("state"):
        problems.append(f"{vid}: missing analysis.state (entry will be skipped)")
    if v.get("analysis", {}).get("state") == "not_affected" and not v["analysis"].get("justification"):
        problems.append(f"{vid}: not_affected without justification")
    if not v.get("ratings"):
        problems.append(f"{vid}: no ratings[] (severity may need manual review)")
    for a in v.get("affects", []):
        ref = a.get("ref", "")
        purl = ref if ref.startswith(("pkg:", "cpe:")) else own_refs.get(ref)
        if not purl:
            problems.append(f"{vid}: affects ref '{ref}' resolves to no PURL")
        elif sbom_purls and purl not in sbom_purls:
            problems.append(f"{vid}: '{purl}' not in SBOM inventory (will be unmatched)")
print("\n".join(problems) if problems else "OK: all statements look importable")
EOF
```

## Uploading

- **UI**: release page -> Add Artifact -> type `VEX` -> attach the file.
  Extra controls appear: Scope (default Component), Import mode (default
  Auto-accept; pick "Stage all for review" to keep a human in the loop),
  Issuer class (default Vendor).
- **CLI / CI**: `rearm-cli addartifact` with `"type": "VEX"` in the
  artifacts JSON; optional `vexScope`, `vexImportMode`,
  `userIssuerClassOverride` fields (rearm-cli 26.05+). Full example in
  `documentation_site/docs/workflows/importing-vex.md`.
