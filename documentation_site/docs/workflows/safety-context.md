# Safety Context

ReARM imports the safety integrity levels that a manufacturer, a supplier or an assessor declares for software components (IEC 62304 software safety classes, ISO 26262 ASILs, IEC 61508 and EN 50128 SILs, DO-178C DALs, or your own scheme) from a CycloneDX 2.0 document uploaded as a `SAFETY_CONTEXT` artifact. It matches each level to a component of the release's SBOM and shows on each release the levels that apply to it.

For the same import rules applied to vulnerability statements, see [Importing VEX](./importing-vex).

## What it is and what it is not {#what-it-is}

Levels are **declared, not verified**. ReARM records who declared a level, on which document, and who accepted it. It does not check that a classification is right: an accepted level is a record of a declaration and a review, not evidence that the classification was performed correctly. The UI says so wherever accepted levels are shown: "Declared by the issuer; not verified by ReARM."

Safety Context is not a risk-management or classification tool, and it does not replace the safety classification rationale in your own records. ReARM is not a qualified or certified tool under any of these standards.

Levels show on the release page, in the **Safety Context** tab.

## The document: CycloneDX 2.0 {#the-document}

The document is CycloneDX JSON with `specFormat` `"CycloneDX"` and a `specVersion` starting with `2.`. Levels sit in `blueprints[].assets[].classification.safetyIntegrityLevels[]` as `{scheme, level}` pairs. Each asset names its component by `componentRef`, which must be the `refId` of exactly one component in `definitions.components`, and that component must carry a `purl` identity. Other parts of the document (metadata, inventories, assumptions) are kept with the artifact but not imported.

A sample:

<!-- sample: backend/src/test/resources/cdx20/rearm-safety-context-2.0.json -->
```json
{
  "$schema": "https://cyclonedx.org/schema/2.0/cyclonedx-2.0.schema.json",
  "specFormat": "CycloneDX",
  "specVersion": "2.0",
  "serialNumber": "urn:uuid:5d1c7a3e-2b4f-4c6a-9e8d-1f2a3b4c5d6e",
  "version": 1,
  "metadata": {
    "timestamp": "2026-11-08T09:00:00Z",
    "manufacturer": {
      "refId": "acme-medical",
      "name": "Acme Medical"
    }
  },
  "definitions": {
    "components": [
      {
        "refId": "comp-pump-firmware",
        "type": "firmware",
        "name": "acme-pump-firmware",
        "version": "3.2.0",
        "identifiers": [
          {
            "refId": "pump-firmware-identity",
            "party": "acme-medical",
            "identities": [
              {
                "scheme": "purl",
                "value": "pkg:generic/acme/acme-pump-firmware@3.2.0"
              },
              {
                "scheme": {
                  "name": "acme-device-catalogue",
                  "description": "Acme internal device catalogue number."
                },
                "value": "DEV-0042"
              }
            ]
          }
        ],
        "hashes": [
          {
            "alg": "SHA-256",
            "content": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
          }
        ]
      },
      {
        "refId": "comp-globex-tls",
        "type": "library",
        "name": "globex-tls",
        "version": "1.4.2",
        "identifiers": [
          {
            "party": "acme-medical",
            "identities": [
              {
                "scheme": "purl",
                "value": "pkg:maven/com.globex/globex-tls@1.4.2"
              }
            ]
          }
        ]
      }
    ]
  },
  "inventories": [
    {
      "refId": "inv-pump",
      "subject": {
        "ref": "comp-pump-firmware"
      },
      "components": [
        {
          "ref": "comp-globex-tls"
        }
      ]
    }
  ],
  "blueprints": [
    {
      "refId": "bp-pump-deployment",
      "name": "Infusion pump, hospital deployment",
      "modelTypes": [
        "data-flow"
      ],
      "scope": {
        "name": "Therapy command path",
        "description": "Pump firmware and its TLS link to the hospital gateway."
      },
      "assets": [
        {
          "refId": "asset-therapy-path",
          "name": "Therapy command path",
          "componentRef": "comp-pump-firmware",
          "classification": {
            "safetyIntegrityLevels": [
              {
                "scheme": "IEC-62304",
                "level": "class-c"
              }
            ]
          }
        },
        {
          "refId": "asset-tls-link",
          "componentRef": "comp-globex-tls",
          "classification": {
            "safetyIntegrityLevels": [
              {
                "scheme": {
                  "name": "acme-hazard-tier",
                  "description": "Acme internal hazard tiering."
                },
                "level": "tier-2"
              }
            ]
          }
        }
      ],
      "assumptions": [
        {
          "refId": "assume-gateway-pinned",
          "description": "The hospital gateway certificate is pinned in the pump firmware.",
          "validity": "verified",
          "relatedAssets": [
            "asset-tls-link"
          ]
        }
      ]
    }
  ]
}
```

It declares the pump firmware `pkg:generic/acme/acme-pump-firmware@3.2.0` at IEC 62304 Class C (written `class-c`) and the TLS library `pkg:maven/com.globex/globex-tls@1.4.2` at level `tier-2` of a custom scheme `acme-hazard-tier`.

### Limits

| Limit | What happens when it is exceeded |
|---|---|
| document at most 5 MB; JSON nesting at most 64 levels deep | the upload is refused; nothing is imported |
| at most 50 blueprints, 5000 assets, 8 levels per asset, 5000 levels to import in all, 64 KB of JSON per asset | the upload is refused; nothing is imported |
| level text at most 64 characters, ref ids at most 256 characters, purl at most 1024 characters | that level is ignored and counted as errored |

### One proposal per asset and scheme

- The same level written twice for one asset and scheme is one level.
- Two different levels for one scheme on one asset become one staged proposal that cannot be accepted ("The asset declares conflicting levels for this scheme; reject it and correct the document").
- The same asset and scheme declared twice for one scope target: the later entry is ignored.

## Schemes and level order {#schemes-and-level-order}

| `scheme` value (exact, case-sensitive) | Levels, lowest first | Lowest level |
|---|---|---|
| `IEC-62304` | Class A, Class B, Class C | Class A |
| `ISO-26262` | QM, ASIL A, ASIL B, ASIL C, ASIL D | QM |
| `IEC-61508` | SIL 0, SIL 1, SIL 2, SIL 3, SIL 4 | SIL 0 |
| `EN-50128` | SIL 0, SIL 1, SIL 2, SIL 3, SIL 4 | SIL 0 |
| `DO-178C` | DAL E, DAL D, DAL C, DAL B, DAL A | DAL E |

- Level text is matched ignoring case, spaces, `_`, `-`, `.` and `:`, and the scheme's word (ASIL, SIL, DAL or LEVEL, or CLASS), so `class-c`, `Class C` and `C` are the same IEC 62304 level.
- For IEC 61508, `none`, `non-SIL` and `no SIL` read as SIL 0.
- An ISO 26262 decomposed level such as `ASIL B(D)` is kept but not ranked. Any other text under a predefined scheme is also kept but not ranked.
- A scheme written as an object, `{"name": ...}`, is a custom scheme. Its name must be 1 to 64 letters, digits, spaces, `.`, `_` or `-`, and its levels are never ranked.
- A scheme string that is none of the five values above, or a custom name outside that pattern, is ignored with a message.
- Levels compare only within one scheme. Two unranked levels compare equal only when their text matches after the same folding; otherwise they are "not comparable".

EN 50716 replaced EN 50128 for railway software; the CycloneDX scheme value is still `EN-50128`.

## Uploading {#uploading}

From the release page, click **Add Artifact** and choose the type `SAFETY_CONTEXT`. The form shows three settings: **Scope** (default Component), **Import mode** (default Auto-accept) and **Issuer class** (default empty: the upload's own class). From CI, use `rearm addartifact` (see [CI ordering](#ci-ordering) and [Importing VEX and safety context from CI](../integrations/rearmcli#importing-vex-and-safety-context-from-ci)); the three settings are the artifact fields `importScope`, `importMode` and `userIssuerClassOverride`.

**The release must already have an indexed SBOM.** Otherwise the upload is refused and nothing is stored: `Cannot import SAFETY_CONTEXT: release has no SBOM components yet. Upload an SBOM/BOM artifact for this release first.`

**Where the document is attached.** The document may be attached to the release, to one of its deliverables or to its source code entry. Where it is attached sets the upload's own issuer class: attached to the release itself, to its source code entry or to an outbound deliverable (one the release ships), it is Self; attached to any other deliverable, such as an inbound deliverable (something the release received), it is Vendor. Third party is never derived from where a document is attached: choose it with the issuer override below.

With the CLI, attach the document to the release (`--releasearts` or `--artifacts`). The server reports no import result for a source code entry upload, so the CLI refuses a SAFETY_CONTEXT document in `--scearts` before sending anything (exit 1) unless `--allow-import-failure` is on the command line; with the flag it is uploaded unchecked and its summary block reads `NOT REPORTED`.

**Import mode.**

| Mode | What happens |
|---|---|
| Auto-accept (default) | the trust rules below decide each level |
| Stage for review | every level is staged |
| Reject | nothing is imported and no proposal is stored; the levels are counted as auto-rejected |

**Scope.** Component (default), Release, Branch or Organization. A level applies at that scope target for the upload's release: the release, its branch, its component or the whole organization. Resource group is not supported (`Import scope <scope> is not supported for SAFETY_CONTEXT`).

**Issuer override.** The trust order is Third party, then Vendor, then Self. Choosing a less trusted class than the upload's own always applies. Choosing a more trusted class applies only when the uploader holds Finding Analysis write at the chosen scope; otherwise the upload's own class is used and the import says so (`Issuer override <class> ignored: raising trust needs Finding Analysis write at the <scope> scope; the upload's own class <class> was used`). An override is recorded on every level.

## Who can make levels take effect {#who-can-make-levels-take-effect}

Anyone who may add artifacts to the release can upload the document. Levels take effect automatically only when the uploading credential itself holds Finding Analysis write that covers the import's target. Otherwise every level of the upload is staged for review with the reason `NO_FINDING_ANALYSIS_WRITE`, and the import says so: `Finding Analysis write at the <scope> scope is needed for levels to take effect; all levels were staged for review`.

| Import scope | The import's target | A Finding Analysis write grant that covers it |
|---|---|---|
| Release | the upload's release | on that release; on its component, directly or through a perspective that contains it; or on the organization. A grant on a product covers only the product itself for writes |
| Component | the release's component | on that component, directly or through a perspective that contains it; or on the organization |
| Branch | the organization (the branch must be the release's own) | on the organization |
| Organization | the organization | on the organization |
| Resource group | none | none: a SAFETY_CONTEXT upload is refused; a VEX upload records no import result |

An organization Administrator permission covers every target, and so does a global administrator user. Accepting or rejecting a staged level uses the same mapping on the level's own scope target.

The credential is judged, not the person behind it:

- a signed-in user, by the user's own grants and the grants of the user's groups;
- a FREEFORM key, by its grants;
- a personal (USER) key, by what both the key and its owner hold at that moment;
- a federated key, by its trust rule;
- component keys and organization-wide read-write keys never hold Finding Analysis write. CI that should auto-accept uses a FREEFORM key granted Finding Analysis write at the scope it imports to, and no wider;
- after `rearm login`, the key the session is bound to is judged.

Holding Finding Analysis write does not skip the trust rules: a lower or not comparable level is staged for every credential (see [Lower levels and downgrades](#downgrades)).

Accepting or rejecting a staged level needs Finding Analysis write at that level's own scope target, with the same mapping as the table. Accept and reject are done in the UI by a signed-in user.

The same rule applies to VEX imports: see [Importing VEX: Who can make an import take effect](./importing-vex#who-can-make-an-import-take-effect).

### What happens to each level

For each level of an upload, the first step that applies decides:

1. The upload is refused (too large, unreadable, not CycloneDX 2.x, unsupported scope, a limit exceeded, too many pending proposals, the import aborted): nothing is stored, and the summary carries one errored entry with the message.
2. The level is invalid (no scheme or level, too long, unknown scheme, an asset without exactly one component, no usable purl): it is ignored and counted as errored.
3. The level is identical to the accepted level at the same scope target, or to a pending or rejected proposal from the same upload source ([Uploading the same document again](#re-uploading)): it is counted unchanged and nothing is written.
4. The level is staged before the trust rules run when its component is unmatched, ambiguous or the SBOM is still being processed, when the asset declares conflicting levels, or else when the credential lacks Finding Analysis write at the target (`NO_FINDING_ANALYSIS_WRITE`). Its comparison with the current level is still computed and shown.
5. The trust rules in the table below decide.
6. The import mode applies, least permissive wins: Reject turns any outcome into auto-rejected (nothing stored); Stage for review turns an automatic acceptance into a staged level ("Staged by the upload's import mode").
7. An accepted level takes effect, and an earlier accepted level at the same scope target becomes superseded; a staged level becomes a pending proposal.

The trust rules, by issuer class (after the override rule) and by how the level compares with the current one ([Lower levels and downgrades](#downgrades)). "staged: ..." shows the reason the inbox gives under "Why it was staged"; "-" marks a combination that cannot occur.

| Issuer | Level | New | Raised | Unchanged | Not comparable | Lower |
|---|---|---|---|---|---|---|
| Self | ranked, any (the lowest included) | accepted automatically | accepted automatically | accepted automatically | staged: Not comparable with the current level | staged: Lower than the current level |
| Self | unranked (custom scheme, unrecognised text, ASIL decomposition) | accepted automatically | - | accepted automatically | staged: Not comparable with the current level | - |
| Vendor | ranked above the scheme's lowest | accepted automatically | accepted automatically | accepted automatically | staged: Not comparable with the current level | staged: Lower than the current level |
| Vendor | the scheme's lowest (Class A, QM, SIL 0, DAL E) | staged: Vendor, lowest level of the scheme | - | staged: Vendor, lowest level of the scheme | staged: Not comparable with the current level | staged: Lower than the current level |
| Vendor | custom scheme | staged: Vendor, custom scheme | - | staged: Vendor, custom scheme | staged: Not comparable with the current level | - |
| Vendor | ASIL decomposition | staged: Vendor, ASIL decomposition | - | staged: Vendor, ASIL decomposition | staged: Not comparable with the current level | - |
| Vendor | text not recognised for the scheme | staged: Vendor, level not recognised | - | staged: Vendor, level not recognised | staged: Not comparable with the current level | - |
| Third party | any | staged: Third-party issuer | staged: Third-party issuer | staged: Third-party issuer | staged: Not comparable with the current level | staged: Lower than the current level |

Every "accepted automatically" cell needs step 4 not to have staged the level, so it needs Finding Analysis write at the target and a matched component; the import mode can still stage or reject it. A level staged for `NO_FINDING_ANALYSIS_WRITE` is shown by that name.

The upload's own issuer class is only ever Self or Vendor (see [Uploading](#uploading)), so the Third party row applies only when an upload chooses Third party with the issuer override; that is a lowering, so it always applies.

## Which level a release shows {#which-level-a-release-shows}

- A release shows the accepted levels set for the packages in its SBOM at the release, its branch, its component and the organization, one per package version, scheme and asset. When several of these scopes set one, the most specific wins: release over branch over component over organization. The row names the broader level it overrides ("overrides &lt;scope&gt;: &lt;level&gt;").
- A package version with no level of its own shows the level of the latest accepted other version of the same package at those same scope targets, marked "carried from &lt;version&gt;", until a level is declared for it. A level set on another release or another branch is never carried.
- Where several assets declare levels for one component and scheme, the highest ranked is marked.
- **Show superseded levels** lists the levels that an accepted later level replaced at the same scope target.

## Lower levels and downgrades {#downgrades}

Every level is compared, at upload and again when a reviewer accepts it, with the strictest of:

- what the release already shows for that package version at the upload's scope or broader;
- what the release shows carried from another version;
- the latest accepted level of the same package (any version, any scope) in the component, or in the whole organization for an Organization-scope upload;
- the latest accepted level of the package on the release's own chain (release, branch, component, organization) at the upload's scope or broader.

The outcome is New, Raised, Unchanged, Not comparable or Lower, and it is shown with the proposal under "Compared with the current level".

- A Lower or Not comparable level is always staged, for every issuer and every credential, Finding Analysis write included. A reviewer can accept it only with a comment ("Accepting a level lower than, or not comparable with, the current one needs a comment"), and the comparison is redone when the reviewer accepts.
- So a narrower scope cannot quietly lower what a release shows (a Release-scope Class B over a Component-scope Class C is staged), and a new package version cannot quietly start lower (Class B declared for the next version of a package that was Class C is staged).
- The one way a lower level becomes the baseline: a reviewer accepts it with a comment. From then on it is the latest level of that package, and the same level declared for later versions is Unchanged and is accepted automatically where the trust rules allow.
- A level set for another component, or on a release or branch of another component, is never compared, except by an Organization-scope upload.

Where an existing level of the same package sits, and whether a new level is compared with it:

| Existing level | Same package version | Previous version |
|---|---|---|
| at the upload's scope or broader on this release | compared, as what the release shows | compared, as the latest level of the package (and shown carried on the release) |
| narrower than the upload's scope on this release | compared, as the latest level of the package | compared, as the latest level of the package (and shown carried on the release) |
| elsewhere in the same component (another branch or another release) | compared, as the latest level of the package | compared, as the latest level of the package |
| in another component, or on a branch or release of another component | not compared (New), unless the upload is Organization scope | not compared, unless the upload is Organization scope |
| anywhere in the organization, for an Organization-scope upload | compared, as the latest level of the package in the organization | compared, as the latest level of the package in the organization |

"Compared" means, for example, that an existing Class C makes a new Class B Lower (staged), an existing Class B makes it Unchanged, and an existing Class A makes it Raised. When several existing levels apply, the latest accepted one counts.

## Reviewing staged levels {#inbox}

The inbox is in the left navigation: **Finding Analysis**, tab **Safety Context**. It lists the organization's proposals by status (Pending, Accepted, Rejected, Superseded) and needs Finding Analysis read on the organization. With a component or release grant instead, use the release page's **Safety Context** tab, which lists every proposal of that release under "Proposals for this release" and needs Finding Analysis read on the release.

The accept dialog shows:

- the component;
- the level, as normalized and as declared;
- "Compared with the current level";
- "Why it was staged": the first rule that staged the level. When a level is staged for more than one reason, for example unmatched and lower, the reason shown is the first, and the comparison is shown separately;
- the issuer, and whether an override raised it;
- the component match;
- the document serial number and version.

A comment is required to accept a Lower or Not comparable level. For an unmatched or ambiguous level the reviewer must pick the release component it applies to ([Unmatched levels](#unmatched-levels)).

Reject needs a reason. Accept and reject comments are at most 2000 characters. A decided proposal cannot be decided again.

## Unmatched levels {#unmatched-levels}

- **SAFETY_CONTEXT.** A level whose component purl is not in the release's indexed SBOM is still stored as a pending proposal, with component match `UNMATCHED` ("Component not in the release SBOM"). It is `AMBIGUOUS` when the purl matches several SBOM components, and `SBOM_PENDING` ("SBOM still being processed") when the release's inventory is being rebuilt at upload time. All three are counted both in `staged` and in `unmatched` in the import summary. A reviewer accepts such a level by choosing the release component it applies to, or rejects it.
- **VEX.** A statement whose product matches no release in scope creates no proposal and counts only as unmatched.
- **The CLI counts neither as imported.** A SAFETY_CONTEXT upload whose levels are all unmatched ends with `NOTHING IMPORTED: no statement matched a component in the release's SBOM` and exit 3, although its levels wait in the inbox.
- Nothing re-checks an unmatched or `SBOM_PENDING` level later on this release (see [Known limits](#known-limits)).

## Uploading the same document again {#re-uploading}

- A level identical to the accepted level at the same scope target is counted unchanged.
- A level identical to a pending or rejected proposal uploaded to the same release, through the same kind of attachment, with the same issuer class and by the same uploader, is also counted unchanged: it is not staged again, and a rejected level stays rejected. To have it reviewed again, change the document, or decide the pending proposal in the inbox.
- A changed level from the same upload source replaces that source's pending proposal, which becomes Superseded. A pending proposal from another document or uploader for the same component and scheme is flagged as a conflicting declaration on both ("Another declaration for this component and scheme disagrees with this one.").

## CI ordering {#ci-ordering}

Upload the SBOM first, in its own `rearm addartifact` call:

```bash
rearm addartifact --component "$COMPONENT_UUID" --version "$RELEASE_VERSION" \
    --artifacts '[{"filePath": "sbom.cdx.json", "type": "BOM", "bomFormat": "CYCLONEDX", "displayIdentifier": "sbom"}]'
```

Then the SAFETY_CONTEXT document, in a second `rearm addartifact` call with `--wait-for-sbom`:

```bash
rearm addartifact --component "$COMPONENT_UUID" --version "$RELEASE_VERSION" \
    --wait-for-sbom 10m \
    --artifacts '[{"filePath": "safety-context.cdx.json", "type": "SAFETY_CONTEXT", "displayIdentifier": "iec62304-classes", "importScope": "COMPONENT"}]'
```

`--wait-for-sbom` retries the identical upload while the server answers that the release's SBOM is not indexed, waiting 5, 10, 20, then 30 seconds between tries. Nothing is stored while the server refuses. When the time runs out the step fails with exit 1 and `Error: the release's SBOM was not indexed within <duration>; nothing was uploaded. Server: <message>`.

**Exit codes.** See the [exit codes](../integrations/rearmcli#exit-codes) of the CLI page. Exit 0 does not mean that levels took effect: staged levels count as imported, so read `staged=` and `staged-by-reason:` in the summary. A credential without Finding Analysis write gets, for example:

```
import SAFETY_CONTEXT --artifacts[0] "iec62304-classes" artifact 0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b: IMPORTED
  total=2 auto-accepted=0 staged=1 unchanged=1 unmatched=0 errored=0 auto-rejected=0 attestations-deferred=0 awaiting-sbom=-
  staged-by-reason: NO_FINDING_ANALYSIS_WRITE=1
  note: the uploading key does not hold Finding Analysis write at the import scope; these statements wait for review
  message: "Finding Analysis write at the COMPONENT scope is needed for levels to take effect; all levels were staged for review"
import check passed: 1 of 1 VEX/SAFETY_CONTEXT artifacts imported
```

**When a step fails.** After exit 1 nothing was stored: re-run the step, with a longer `--wait-for-sbom` if the SBOM is still being indexed. After exit 3 the document is stored: read the summary block first. If it says `NOTHING IMPORTED: no statement matched a component in the release's SBOM`, the levels are pending as unmatched ([Unmatched levels](#unmatched-levels)), and re-running the same step with the same key reports them unchanged and exits 0 without matching them ([Uploading the same document again](#re-uploading)). Fix the purls in the document or the SBOM and upload the corrected document, or accept the pending levels by choosing their components.

**The import check.** By default `addartifact` exits 3 when a VEX or SAFETY_CONTEXT artifact of the call imported nothing or has no known result; the artifacts are stored.

- With `--allow-import-failure`, `addartifact` exits 0 although an artifact imported nothing or its result is unknown, the summary is still printed, and the final line reads `import check: <f> of <k> VEX/SAFETY_CONTEXT artifacts imported nothing or have no known result; accepted by --allow-import-failure`. It does so for every artifact of that call, so a safety pipeline should not carry the flag routinely: a green step then no longer means the document was imported. It does not apply when the server refuses the CLI's question about which import result fields it reports: then nothing is uploaded and the exit is 1, with or without the flag.
- With the flag, `addartifact` also uploads a VEX or SAFETY_CONTEXT artifact on a source code entry (`--scearts`) or nested under another artifact, unchecked, reported `NOT REPORTED` or `NOT IMPORTED`. Without it, those are refused before anything is sent (exit 1).
- An artifact field the CLI does not know is refused (exit 1) with or without the flag.
- `addrelease`, `addreleases`, `addodeliverable` and `getversion --scearts` store a VEX or SAFETY_CONTEXT artifact but never import it, so they refuse one at any depth, nested ones included, before any request (exit 1). Nothing is sent, so that call does not create or change the release. The error line reads `Error: <ref>: <command> stores a <TYPE> artifact but never imports it, so nothing would take effect; upload it with addartifact once the release's SBOM is indexed, or pass --allow-import-failure to store it without an import`. With `--allow-import-failure` on that command's own line, each such artifact prints `notice: <ref>: <command> stores this <TYPE> artifact but does not import it (accepted by --allow-import-failure)` and the call runs as before: the document is stored and not imported. The recommended fix is not the flag: move the document out of those commands into an `addartifact` step after the SBOM step, with `--wait-for-sbom`, as in the two command blocks above.
- Pipelines built on the ReARM GitHub Actions: `finalize` runs `rearm addrelease` with the `scearts` and `odelartsjson` inputs it is given, and `add-multi-release` runs `rearm addreleases`; neither action has an input that adds `--allow-import-failure`. So do not put a VEX or SAFETY_CONTEXT document in those actions' artifact inputs: such a step fails with exit 1 before anything is sent. Upload the document in a later step that runs `rearm addartifact` (installed by `setup-cli`) with `--wait-for-sbom`, after the step that uploads the SBOM.
- `--allow-import-failure` is read only from the command line. No environment variable and no config file key sets it, so a CI variable cannot turn the check off.

## Messages {#messages}

| Message | When it appears |
|---|---|
| `Cannot import SAFETY_CONTEXT: release has no SBOM components yet. Upload an SBOM/BOM artifact for this release first.` | the release has no indexed SBOM; the upload is refused and nothing is stored |
| `A SAFETY_CONTEXT artifact must be attached to a release, a deliverable or a source code entry of a release` | the upload is refused |
| `SAFETY_CONTEXT document is empty or larger than 5 MB; nothing was imported` | in the import summary; the upload is refused |
| `SAFETY_CONTEXT document is not readable JSON or exceeds the import limits` | in the import summary; the upload is refused |
| `SAFETY_CONTEXT document is not a CycloneDX 2.x JSON document` | in the import summary; the upload is refused |
| `Finding Analysis write at the <scope> scope is needed for levels to take effect; all levels were staged for review` | in the import summary; the credential lacks Finding Analysis write at the target |
| `Import scope <scope> is not supported for SAFETY_CONTEXT` | in the import summary; the upload is refused (Resource group) |
| `Issuer override <class> ignored: raising trust needs Finding Analysis write at the <scope> scope; the upload's own class <class> was used` | in the import summary (the same text for VEX) |
| `The document declares no safety integrity levels; nothing was imported` | in the import summary |
| `This release already has too many pending safety context proposals; review them first` | in the import summary; the upload is refused (more than 10000 pending proposals on the release) |
| `SAFETY_CONTEXT import aborted; nothing was stored. Retry the upload.` | in the import summary; the upload is refused |
| `Accepting a level lower than, or not comparable with, the current one needs a comment` | accept refused |
| `Choose the release component this level applies to` | accept of an unmatched or ambiguous level without a component |
| `The chosen component is not part of this release` | accept with a component outside the release's SBOM |
| `Safety context proposal is not pending (was <status>)` | accept or reject of a proposal already decided |
| `The asset declares conflicting levels for this scheme; reject it and correct the document` | accept of a proposal whose asset declares conflicting levels |

Messages about a single level (too long, unknown scheme, no usable purl, and so on) are listed with the import result and count the level as errored.

## Rollback {#rollback}

An installation that holds SAFETY_CONTEXT artifacts has a minimum rollback version, named in the upgrade note of the release that ships this feature (see [Upgrading](../installation/#upgrading)).

- A build older than the first release that knows the SAFETY_CONTEXT type cannot load releases that hold such an artifact.
- A build from that release on, but older than the release that adds a rule, loads those releases and acts on them without applying that rule: the import review, who may make an import take effect, and the downgrade check. The same holds for who may make a VEX import take effect.
- If a rollback below the minimum cannot be avoided, export and archive the affected releases' SAFETY_CONTEXT (and VEX) artifacts first. Never delete them to make a rollback work.

## Known limits {#known-limits}

- **Levels uploaded before the SBOM is indexed.** With no indexed component at all, the upload is refused and `--wait-for-sbom` retries it. When the release has an inventory that is being rebuilt, a level whose purl is not found yet is staged `SBOM_PENDING`, and one not found otherwise `UNMATCHED`; both count as unmatched, and nothing re-checks them when the SBOM finishes processing: a reviewer accepts each by choosing its component, or rejects it. A later release re-checks `SBOM_PENDING` levels once the SBOM is processed and counts them apart from unmatched ones.
- **Re-uploads.** Uploading an unchanged document again does not re-stage or re-match a pending or rejected level ([Uploading the same document again](#re-uploading)), so a fixed SBOM does not by itself match levels left unmatched. This holds in this release and may change in a later one.
- **SBOM updated in place.** When a new version of an existing SBOM artifact is uploaded in place, the release keeps matching against the components of the content already indexed for that artifact. A later release parses changed content again.
- **No device (product) view.** A product release does not show its components' levels, and uploading a SAFETY_CONTEXT document to a product release is not supported. A later release adds a view per constituent of a product release.
- **Source details on levels.** The release view shows, for every level it lists, the version of the release the level was uploaded on and the names of the uploader and acceptor, also for a level set at a broader scope on a release the reader cannot open. A later release withholds those details on the safety views from readers who cannot read the source release.
- **Transparency Exchange API.** TEA publishes a SAFETY_CONTEXT artifact like any other document artifact, whatever the review state of its levels. A later release publishes such a document only when every level it declares is accepted and applies to that release.
- **Reviewing.** Reviewing is one level at a time; the accept dialog shows the outcome of the comparison but not the current level's value, source or acceptor; there is no pending badge on the release; a release holds at most 10000 pending proposals. This holds in this release and may change in a later one.
- **No withdrawal.** An accepted level cannot be withdrawn; it can only be replaced by a later accepted level.
- **Custom schemes have no order.** A different level of a custom scheme is always "not comparable" and is staged.
- **No export or API review.** Levels cannot be exported, and no API key can read or decide them; accept and reject are UI actions of a signed-in user.
- **Resource group scope.** A Resource group scope is not supported.

## Worked example: IEC 62304 {#worked-example-iec-62304}

The organization Acme Medical has a component `infusion-pump` with branch `main`. Its release 3.2.0 has an SBOM listing `pkg:generic/acme/acme-pump-firmware@3.2.0` and `pkg:maven/com.globex/globex-tls@1.4.2`, each once. CI uses a FREEFORM key granted Finding Analysis write on the `infusion-pump` component (the "CI key"); every step below where a level is accepted automatically needs that grant. The reviewer is a user with Finding Analysis write on the component. Uploads are attached to the release (so the issuer is Self) with Component scope unless said otherwise.

**Step 1. First upload.** CI uploads the SBOM, then the [sample](#the-document) with `--wait-for-sbom 10m` ([CI ordering](#ci-ordering)). The firmware's Class C is matched, the CI key holds write on the component, there is no earlier level (New), and the issuer is Self: accepted automatically. The TLS library's `tier-2` (custom scheme) is Self too: accepted automatically. The CLI prints:

```
import SAFETY_CONTEXT --artifacts[0] "iec62304-classes" artifact 7c9e6679-7425-40de-944b-e07fc1f90ae7: IMPORTED
  total=2 auto-accepted=2 staged=0 unchanged=0 unmatched=0 errored=0 auto-rejected=0 attestations-deferred=0 awaiting-sbom=-
import check passed: 1 of 1 VEX/SAFETY_CONTEXT artifacts imported
```

Release 3.2.0's Safety Context tab shows Class C for the firmware and tier-2 for the TLS library, both Self, accepted automatically.

**Step 2. Next release.** Release 3.3.0's SBOM has the firmware at 3.3.0 and the same TLS library. Before any upload, its tab shows the firmware's Class C "carried from 3.2.0" and the TLS library's tier-2.

**Step 3. The wrong key.** A second pipeline, using a component key by mistake, uploads the sample edited for 3.3.0 (firmware version and purl `@3.3.0`, level `class-b`). A component key holds no Finding Analysis write, so the firmware level is staged with `NO_FINDING_ANALYSIS_WRITE`; its comparison is still recorded: Lower than the Class C. The TLS level is identical to the accepted level, so it is counted unchanged. The CLI prints:

```
import SAFETY_CONTEXT --artifacts[0] "iec62304-classes" artifact 0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b: IMPORTED
  total=2 auto-accepted=0 staged=1 unchanged=1 unmatched=0 errored=0 auto-rejected=0 attestations-deferred=0 awaiting-sbom=-
  staged-by-reason: NO_FINDING_ANALYSIS_WRITE=1
  note: the uploading key does not hold Finding Analysis write at the import scope; these statements wait for review
  message: "Finding Analysis write at the COMPONENT scope is needed for levels to take effect; all levels were staged for review"
import check passed: 1 of 1 VEX/SAFETY_CONTEXT artifacts imported
```

The step exits 0 and is green although nothing took effect. Release 3.3.0 still shows Class C, carried.

**Step 4. The right key, a lower level.** The CI key uploads the same edited document. It comes from another uploader, so it is not counted unchanged. The firmware's Class B is Lower than the Class C, so it is staged ("Lower than the current level") although the key holds write and the issuer is Self. It is staged next to step 3's proposal, and both are flagged as conflicting declarations. The TLS level is unchanged. The summary reads `total=2 auto-accepted=0 staged=1 unchanged=1`, with no `staged-by-reason:` line, and the step exits 0.

**Step 5. Review.** The reviewer opens Finding Analysis, tab Safety Context. Step 4's proposal shows "Lower than the current level". Accepting it without a comment is refused (`Accepting a level lower than, or not comparable with, the current one needs a comment`). With a comment that records the classification rationale (for example: the hazardous function moved to a hardware interlock outside the software, so the firmware is Class B under IEC 62304), it is accepted. The reviewer rejects step 3's proposal with a reason. Release 3.3.0 now shows Class B for firmware 3.3.0; release 3.2.0 still shows Class C for 3.2.0.

**Step 6. The reviewed level is the baseline.** On release 3.4.0, with the firmware at 3.4.0, the CI key uploads the document edited for 3.4.0 with `class-b`. The latest accepted level of the package is now the Class B (accepted after the Class C), so the outcome is Unchanged and the Self level is accepted automatically.

**Step 7. A narrower scope cannot lower it.** On release 3.4.0, someone with write on the component uploads a document declaring the firmware 3.4.0 at Class A with Release scope. What the release shows for that version at Release scope or broader is the Component-scope Class B, so the level is Lower and is staged.

**Step 8. A supplier's statement.** The TLS supplier's own statement arrives as a CycloneDX 2.0 document declaring `globex-tls@1.4.2` at `IEC-62304` "Class A". CI attaches it to release 3.4.0 with `userIssuerClassOverride` `VENDOR`: a lowering from Self, so it always applies (the summary shows `issuer-override: LOWERED`). The level is the lowest of its scheme from a Vendor, so it is staged ("Vendor, lowest level of the scheme"), whatever the credential holds.

## Related {#related}

- [Importing VEX](./importing-vex)
- [ReARM CLI: Importing VEX and safety context from CI](../integrations/rearmcli#importing-vex-and-safety-context-from-ci)
- [Concepts: Issuer class](../concepts/#issuer-class)
- [Users and User Groups Permissions](../configure/user-and-user-group-permissions) (Finding Analysis functions)
- [Functional Safety use case](https://rearmhq.com/use-cases/functional-safety/)
- [Medical Devices use case](https://rearmhq.com/use-cases/medical-devices/)
