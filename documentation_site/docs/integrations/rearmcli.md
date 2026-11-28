## Rearm CLI

Open source Rearm CLI project can be found at <https://github.com/relizaio/rearm-cli>. Documentation can be found in the same repository.

This project provides variety of common integration scenarios with Reliza's ReARM and can be used in any home-made or 3rd party tool.

For how the CLI authenticates — signing in with an API key id and secret, signing in from your
browser with no secret to store, authenticating a GitHub Actions job with its own identity token
(`--auth github-oidc`), and `rearm whoami` / `rearm logout` — see
[Programmatic Access](../configure/programmatic-access). The browser and federated-identity flows
are **preview** functionality that has not shipped in a released version yet.

The CLI also carries the **`rearm agent`** subcommand family
(`session init / show / touch / close / add-artifact / inbox`,
plus `enrollkey`) — these are what an AI coding agent runs against
a ReARM Pro instance after the operator hands it the FREEFORM
`AGENT` key. See [Bootstrap an AI Agent](../workflows/agentic) for
the operator-facing setup and the agent-side contract URL.

- **`rearm bomutils score`** scores an SBOM file locally against the CISA 2026, NTIA 2021 and
  FDA minimum elements and writes the same report the ReARM UI shows; see
  [Scoring an SBOM for CISA 2026 and FDA Readiness](../workflows/sbom-scoring#from-the-command-line).

### Rebuilds and SBOM serial numbers

A rebuild uploads a new SBOM. If your generator pins `serialNumber`, it must also increment
`version` per build, otherwise the upload is refused; alternatively let the generator mint a fresh
serial number.

The refusal names the serial number and both versions, and applies to `rearm addrelease --rebuild`
and to any `rearm addartifact` that re-uses an existing serial. It exists because a stored raw SBOM
is immutable: ReARM keeps the bytes it was given as the record of what was actually uploaded and
scanned, so a second upload claiming the same identity cannot replace them. A higher version of
a serialNumber that other components of the organization hold is refused unless the key can write
to all of them. See
[Who may upload a new version of an SBOM](../workflows/exporting-compliance-artifacts#who-may-upload-a-new-version-of-an-sbom).

### Importing VEX and safety context from CI

A VEX document or a SAFETY_CONTEXT document (a CycloneDX 2.x JSON snapshot of declared safety levels)
is uploaded with `rearm addartifact` as an artifact of type `VEX` or `SAFETY_CONTEXT`, and the server
imports it into the release. The CLI prints the result of each import and fails the step when an
artifact imported nothing, so a green step means the document was imported.

#### SBOM first

The release's SBOM must be indexed before the import runs. Upload the SBOM in its own `addartifact`
(or `addrelease`) call, then the VEX or SAFETY_CONTEXT document; an SBOM in the same call does not
count. `--wait-for-sbom 10m` retries the upload while the release has no indexed SBOM, for at most
that long; nothing is stored while the server refuses. `0`, the default, does not wait.

```bash
rearm addartifact --component "$COMPONENT_UUID" --version "$RELEASE_VERSION" \
    --artifacts '[{"filePath": "sbom.cdx.json", "type": "BOM", "bomFormat": "CYCLONEDX", "displayIdentifier": "sbom"}]'

rearm addartifact --component "$COMPONENT_UUID" --version "$RELEASE_VERSION" \
    --wait-for-sbom 10m \
    --artifacts '[{"filePath": "vendor-vex.cdx.json", "type": "VEX", "displayIdentifier": "vendor-vex", "importScope": "COMPONENT", "importMode": "STAGE"}]'
```

A SAFETY_CONTEXT document is uploaded the same way:

```bash
rearm addartifact --component "$COMPONENT_UUID" --version "$RELEASE_VERSION" \
    --wait-for-sbom 10m \
    --artifacts '[{"filePath": "safety-context.cdx.json", "type": "SAFETY_CONTEXT", "displayIdentifier": "iec62304-classes", "importScope": "COMPONENT"}]'
```

#### Import fields

| Field | Values | Default |
|---|---|---|
| `importScope` | `ORG`, `RESOURCE_GROUP`, `COMPONENT`, `BRANCH`, `RELEASE` | `COMPONENT` |
| `importMode` | `AUTO_ACCEPT`, `STAGE`, `REJECT` | `AUTO_ACCEPT` |
| `userIssuerClassOverride` | `SELF`, `VENDOR`, `THIRD_PARTY` | derived by the server |

`vexScope` and `vexImportMode` are deprecated aliases of `importScope` and `importMode`; when both are
set, the generic field wins. An artifact field the CLI does not know is refused before anything is
sent. See [Importing VEX](../workflows/importing-vex#uploading-a-vex-artifact) for what scope, mode and
issuer class mean.

#### Who can make an import take effect

Any key that may add artifacts to the release can upload the document. A statement or level takes
effect automatically only when the uploading key itself holds Finding Analysis write at the import
scope: on the release for `RELEASE`, on the component for `COMPONENT`, at the organization for
`BRANCH` and `ORG`; `RESOURCE_GROUP` never takes effect automatically. Otherwise it is staged for
review (reason `NO_FINDING_ANALYSIS_WRITE` in the summary); other reasons can stage it too, and the
summary names them. Component keys and organization-wide read-write keys never hold Finding Analysis
write: use a FREEFORM key granted it at the scope you import to. A personal (USER) key holds only what
both the key and its owner hold now. A `userIssuerClassOverride` that raises trust (`THIRD_PARTY`,
then `VENDOR`, then `SELF`) also needs that permission; without it the override is ignored and the
summary says so. After `rearm login`, the key the session is bound to is judged. For the Finding
Analysis write grant each scope needs, see
[Who can make levels take effect](../workflows/safety-context#who-can-make-levels-take-effect).

#### The import summary

stdout keeps the JSON answer. For each VEX or SAFETY_CONTEXT artifact the CLI prints the server's
import summary on stderr, then a final check line. A FREEFORM key without Finding Analysis write that
uploads a VEX to a release with an indexed SBOM gets, for example:

```
import VEX --artifacts[0] "vendor-vex" artifact 0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b: IMPORTED
  total=12 auto-accepted=0 staged=9 unchanged=0 unmatched=3 errored=0 auto-rejected=0 attestations-deferred=0 awaiting-sbom=-
  staged-by-reason: NO_FINDING_ANALYSIS_WRITE=9
  note: the uploading key does not hold Finding Analysis write at the import scope; these statements wait for review
import check passed: 1 of 1 VEX/SAFETY_CONTEXT artifacts imported
```

An artifact counts as imported when the server stored at least one of its statements or levels
(auto-accepted, staged, auto-rejected or unchanged). Statements that matched no component of the
release's SBOM, errored statements and an unreadable document import nothing; the block then reads
`NOTHING IMPORTED: ...` with the reason, followed by the server's messages. A count the server does not
report prints as `-`, as `awaiting-sbom` does above.

A SAFETY_CONTEXT level that matched no component is still stored as a pending proposal: it is counted
in both `staged` and `unmatched`, and not counted as imported. See
[Unmatched levels](../workflows/safety-context#unmatched-levels).

#### Exit codes

| Exit | Meaning |
|---|---|
| 0 | every VEX or SAFETY_CONTEXT artifact imported something (or the upload completed and `--allow-import-failure` was given) |
| 1 | the call did not complete or nothing was sent: an input error, the server refused the call, the server refused the CLI's question about which import result fields it reports, a transport error, or `--wait-for-sbom` ran out |
| 3 | the upload completed, but an artifact imported nothing or its result is unknown; the artifacts are stored, so a re-run uploads them again |

When an artifact fails the check the final line reads
`import check failed: <f> of <k> VEX/SAFETY_CONTEXT artifacts imported nothing or have no known result; the artifacts are stored (exit 3). Pass --allow-import-failure to accept this.`

#### `--allow-import-failure`

With `--allow-import-failure` on the command line, `addartifact` exits 0 even when an artifact imported
nothing or its result is unknown, and still prints the summary; the final line then reads
`import check: <f> of <k> VEX/SAFETY_CONTEXT artifacts imported nothing or have no known result; accepted by --allow-import-failure`.

The flag does not cover a server that refuses the CLI's question about which import result fields it reports: the CLI then uploads nothing and exits 1 with or without the flag, and re-running the step is safe.

The flag is read only from the command line. No environment variable and no config file key sets it,
so a CI variable cannot turn the check off: put the flag on the step's own command.

#### What is refused before any request

- an artifact field the CLI does not know;
- `--artifacts` together with `--releasearts`, `--deliverablearts` or `--scearts` (put release
  artifacts in `--releasearts`);
- a VEX or SAFETY_CONTEXT artifact on a source code entry (`--scearts`) or nested under another
  artifact, unless `--allow-import-failure` is given, because its result cannot be checked. Use
  `--releasearts`: the import runs against the release either way. With the flag, such an artifact is
  uploaded unchecked and its block reads `NOT REPORTED` (source code entry) or `NOT IMPORTED` (nested);
- a `digestRecords` scope `AS_UPLOADED`, `RAW_OCI_STORAGE` or `REARM`: ReARM computes those.

`addrelease`, `addreleases`, `addodeliverable` and `getversion` store a VEX or SAFETY_CONTEXT artifact
but never import it, so they refuse one unless `--allow-import-failure` is given; with the flag each
such artifact gets a notice and the call runs as before. Upload the document with `addartifact` once
the release's SBOM is indexed.
