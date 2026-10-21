# Regulatory Packages and Submissions (ReARM Pro)

A regulatory package is what ReARM assembles for one regulator from one release. A package kind names the regime (`FDA_PREMARKET` today), lists the parts the package holds and says which of them are required. Package settings at a scope say which SBOM profile feeds the SBOM part and how each part is produced. A submission is the immutable record of one assembly of a package for one release: version 1 when it is first created, and one more version on every resubmission, each with its manifest and its generated documents, kept for as long as the release is.

**Submissions are never public.** They are never served on the [Transparency Exchange API](../tea/), no external key can read them, and nothing of a submission appears on TEA, whatever the release's TEA publishing settings say.

Regulatory packages and submissions are ReARM Pro only. A ReARM Community Edition installation offers none: its UI shows no package settings and no submissions, and its server serves none. Today it has no such operation; once the shared code reaches it at a sync from Pro, a submission read answers an empty list and a submission act answers `EDITION_UNSUPPORTED`, while package settings stay ReARM Pro. See the [edition differences](../tea/#edition-differences) of the TEA page.

## Package kinds and parts

`FDA_PREMARKET` is the only package kind. Its parts, in the order the manifest lists them:

| Part | Requirement | Default selection | File suffix | Media type | What it holds |
|---|---|---|---|---|---|
| `AGGREGATE_SBOM` | `REQUIRED` | `INCLUDE` | `aggregate.cdx.json` | `application/vnd.cyclonedx+json` | The release's aggregated SBOM, generated with the SBOM profile the package settings name |
| `VDR` | `REQUIRED` | `INCLUDE` | `vdr.cdx.json` | `application/vnd.cyclonedx+json` | The vulnerability disclosure report of the release |
| `VEX` | `OPTIONAL` | `INCLUDE` | `vex.cdx.json` or `openvex.json` | `application/vnd.cyclonedx+json` or `application/json` | The VEX of the release, in the format `vexFormat` chooses |
| `NARRATIVE` | `REQUIRED` | `INCLUDE` | `narrative.txt` | `text/plain; charset=utf-8` | The FDA assessment narrative: the release's own or the organization default (the file's header says which) |
| `SUPPORT_ADDENDUM` | `REQUIRED` | `INCLUDE` | `support-addendum.csv` | `text/csv; charset=utf-8` | The software support addendum |
| `DEVICE_SUPPORT_STATEMENT` | `OPTIONAL` | `EXCLUDE` | `device-support-statement.html` | `text/html; charset=utf-8` | The device software support statement; product releases only |
| `RAW_ARTIFACTS` | `OPTIONAL` | `EXCLUDE` | no file | - | References to the release's own artifacts of the chosen artifact types |

A required part cannot be excluded. A required part that produces nothing refuses the act; an optional one is recorded as `SKIPPED` with its reason: `NOT_APPLICABLE`, `NO_CONTENT` or `EXCLUDED_BY_SETTINGS`.

`CLE` exists as a part but belongs to no kind: ReARM's CLE export is not CLE 1.0.0 conformant, and a package carries no document that claims a conformance it lacks. The kinds and their part lists are code (see [Not implemented](#not-implemented)).

Every generated file is named `<stem>-<suffix>`, with the stem `<component name>-<release version>-fda-premarket-v<version>`; characters outside `[A-Za-z0-9._-]` become `-`, and the stem is at most 160 characters. For example, version 3 of a submission of release `1.2.0-rc1` of `Widget Pro` has the support addendum `Widget-Pro-1.2.0-rc1-fda-premarket-v3-support-addendum.csv`. These names differ from the file names of the UI's own downloads of the addendum and the statement on the [Exporting Compliance Artifacts](./exporting-compliance-artifacts) page.

## The documents

No part is a PDF: the addendum is CSV and the statement is HTML, by operator decision, and a PDF rendering is not planned.

### The support addendum

The file is titled "FDA software support addendum". A header block (the device, the release, the support window and the counts) comes first, then the eight columns Component, Version, PURL, Level of support, End of support, Justification, Attestation state and Last assessed, with one row per component of the release in a fixed order (display name lower-cased, version, purl, then uuid), so the same data renders the same bytes. An empty release renders its header and the column row. No attester name, note or party is in the file.

The file is RFC 4180 CSV with a UTF-8 byte order mark first (a parser strips U+FEFF) and CRLF line ends; the quoting rule is the one the UI's addendum download uses. A cell whose first character is `=`, `+`, `-`, `@`, TAB or CR gets one apostrophe before it, so a spreadsheet never evaluates it. The server-rendered file also puts one apostrophe before such a character when it follows a `;` or a line break inside a cell, so a bulleted or indented line of a multi-line justification shows `'-` in every reader.

Whenever your spreadsheet's list separator is not a comma (a `;` locale, or a separator set by hand to any other character), open the addendum through the spreadsheet's text import with the comma delimiter (in Excel, Data > From Text/CSV with the delimiter set to Comma), never by double-click. ReARM cannot neutralize a separator set by hand; this import is the reader's answer to it.

The addendum downloaded from the release page's export modal (the "Support addendum (CSV)" of the [Exporting Compliance Artifacts](./exporting-compliance-artifacts) page) neutralizes only a leading `=`, `+`, `-`, `@`, TAB or CR. A double-click in a `;` locale can therefore evaluate a formula that follows a `;` or a line break inside a cell of that file, and the text import is the only protection for it. The server-rendered part of a submission also neutralizes those positions; the browser download does not have that rule, which is a UI change.

The server file and the UI download agree except for the Generated row, the tie order of equal versions and purls, and the apostrophe after a `;` or a line break.

### The device support statement

The "Device software support statement" is generated for a product release only; on a component release the part is skipped with `NOT_APPLICABLE`. It needs the three prose slots of Organization Settings (the submission and labeling text) and is refused, naming the missing one, when one is not authored. It renders the device's support window and the coverage sentences, every URL as text and never as a link, under a Content Security Policy that forbids scripts, styles from outside the document, and images. On unchanged data it is byte-identical.

### The narrative

The narrative is plain text with a header block: the organization, the device, the release and the justification scope, "this release" or "organization default".

### The SBOM and vulnerability parts

The aggregated SBOM uses the named SBOM profile's recipe exactly as a TEA publication would, as a fresh document with its own serial number and version 1, never the TEA aggregate of the release. `VDR` and `VEX` take `suppressedFindings` and `inTriageFindings` (`INCLUDE` or `EXCLUDE`; default `EXCLUDE`), and `VEX` takes `vexFormat` (`CYCLONEDX_VEX` or `OPENVEX`; default `CYCLONEDX_VEX`).

### The raw artifacts

`RAW_ARTIFACTS` is a filter over the release's own artifacts by artifact type, and the part copies no bytes. The manifest lists each artifact as its own `REFERENCED` entry, with the artifact's uuid, version, file name, media type and SHA-256 digest
and no size. An archived, externally stored or digest-less artifact is left out and logged.

## Package settings

Package settings are one row per scope and kind, at the organization, a perspective (ReARM Pro) and a component or product; a component's row is an override or a choice to follow a perspective. They resolve as a whole, like TEA publishing settings, with the same conflict rule, for a product as for a component: a conflict refuses the act with `SETTINGS_CONFLICT`.

The fields:

- the SBOM profile: a name from the SBOM profile catalog, resolved at the component when the package is assembled (`default` in the built-in);
- per-part options: `selection` (`INCLUDE` or `EXCLUDE`) for every part; `suppressedFindings` for `VDR` and `VEX`; `inTriageFindings` and `vexFormat` for `VEX`; `artifactTypes` for `RAW_ARTIFACTS`;
- a description.

The built-in default per kind names `default` with the kind's default selections, so a first submission needs no setup. A save is refused when a required part is excluded, when an included `RAW_ARTIFACTS` has no artifact type, when an option does not apply to its part, or when the named SBOM profile does not resolve at the scope. Package settings carry no visibility: nothing they shape is ever public. What the API enforces: reading package settings needs read access on the scope's object, and `savePackageSettings` and `deletePackageSettings` need ADMIN on it (an ADMIN grant on the component or product, on the perspective, or organization-wide; an organization admin holds it at every scope). In the UI, the package settings editor is editable by organization admins, and a perspective, component or product admin can also save through the user GraphQL API.

The package settings editor is per scope and kind, generic over the kinds the server lists, with the profile picker for the SBOM part and the per-part options. The organization's package settings are on the Profiles tab of Organization Settings (the tab beside Transparency Exchange that also holds the organization's SBOM profiles; not on the Transparency Exchange tab), a component's or product's on its TEA tab, and a perspective's in its TEA dialog on the Perspectives tab of Organization Settings (ReARM Pro). Screenshots and the exact labels follow once the UI task that builds them lands.

The user GraphQL API serves `packageKinds`, `packageSettings(org, scope, object, kind)`, `packageSettingsOfScope(org, scope, object)`, `packageSettingsOfOrg(org)`, `packageSettingsEffective(org, scope, object, kind)`, `packageSettingsEditorView(org, scope, object, kind)`, `savePackageSettings(org, scope, object, kind, settings)` and `deletePackageSettings(org, scope, object, kind)`; there is no programmatic operation for package settings. Deleting an SBOM profile that package settings name is refused while they would stop resolving it.

## Submissions

### What a submission is

There is one submission per release and kind, with versions numbered from 1. A version is immutable: it is never updated or deleted, by the application or by SQL (a database trigger refuses it). A resubmission is a new version, and the earlier versions stay readable. A withdrawal is a tombstone that keeps every version and document. A submission is `ACTIVE` or `WITHDRAWN`.

### Creating, resubmitting, withdrawing

- **Create** (a reason is optional) writes version 1 after every part is generated and uploaded. It is refused with `ALREADY_SUBMITTED` when a submission of that kind exists for the release, withdrawn or not: use resubmit.
- **Resubmit** (a reason is required) writes the next version and re-activates a withdrawn submission. The UI passes the latest version it showed, so that a stale page is refused with `STALE_HEAD` instead of writing a version nobody meant: reload and decide again.
- **Withdraw** (a reason is required) sets the state to `WITHDRAWN` with the date, the actor and the reason, writes no version and deletes nothing. Withdrawing twice changes nothing (`UNCHANGED`).

An act answers `CREATED`, `RESUBMITTED`, `WITHDRAWN` or `UNCHANGED`, or a refusal. Create and resubmit need the release at ASSEMBLED or beyond, and neither the release nor its component archived. An act waits up to 30 seconds for any other publish, hide or submission act on the same release, then is refused with `SUBMISSION_IN_PROGRESS` and can be repeated. A failed act leaves no version (what it uploaded is archived).

### The reason

The reason is optional on create and required on resubmit and withdraw. It is stripped, at most 2048 characters, and holds no control character other than TAB, LF and CR (`REASON_INVALID`). It is stored on the version, in the manifest, on the withdrawn submission and in the release History, and the UI shows it on the version and on the History row. It is **never part of a notification** (see [Notifications and audit](#notifications-and-audit)).

### Where they appear

The product page is the main entry point: its Submissions tab lists the releases of one feature set at a time with their submissions by kind, the latest version, the state, and the create, resubmit and withdraw actions per release, and names the submissions on releases of the product's other feature sets.

The release page carries, in its Components tab after the Transparency Exchange (TEA) section, a section headed "Regulatory submissions" for that release (ReARM Pro; not shown on a document round's release page), with the version history, each version's manifest and parts to download, a resubmit action with a reason field, and withdraw with a confirmation.

The release History tab records each act as a row of scope SUBMISSION: ADDED for the first version, CHANGED for a later version or a re-activation, REMOVED for a withdrawal, with the reason shown with the row when one was given.

There is no organization-level list of submissions (see [Not implemented](#not-implemented)). Screenshots and the exact labels follow once the UI task that builds these screens lands.

### The manifest

Every version has a manifest, a JSON document with these fields in this order, a field that is null left out:

- `manifestVersion` (1), `submission`, `version`, `kind`, `createdDate`, `createdBy` (a user uuid, never a name or an e-mail), `reason`;
- `organization`, `organizationName`, `component`, `componentName`, `componentType`, `release`, `releaseVersion`, `releaseIdentifiers` (`idType:idValue`, in the release's order);
- `settings` (`row`, `revision`, `source`, `sourceObject`): the package settings row used;
- `sbomProfile` (`name`, `row`, `revision`, `source`, `sourceObject`): the SBOM profile used, with no `row` and revision 0 for the built-in; `sourceObject` of either is absent for an ORGANIZATION or DEFAULT source;
- `parts`, a list of entries (`part`, `requirement`, `outcome`, `artifact`, `artifactVersion`, `fileName`, `mediaType`, `size`, `digests` of `alg` and `value`, `skipReason`, `detail`): one entry per `PRODUCED` part, one entry per referenced artifact of a `REFERENCED` `RAW_ARTIFACTS` part (the `part` value repeats), one entry per `SKIPPED` part with no artifact, no size and no digest. `artifactVersion` is a string (`"1"` for a generated document), and `size` an integer on `PRODUCED` entries only.

The manifest of version 1 of an `FDA_PREMARKET` submission of a component release, with package settings stored at the organization that include the statement and the raw artifacts:

```json
{
  "manifestVersion": 1,
  "submission": "55555555-5555-4555-8555-555555555555",
  "version": 1,
  "kind": "FDA_PREMARKET",
  "createdDate": "2026-10-20T12:00:00Z",
  "createdBy": "55555555-5555-4555-8555-555555555555",
  "reason": "Initial premarket submission",
  "organization": "55555555-5555-4555-8555-555555555555",
  "organizationName": "Example Devices",
  "component": "55555555-5555-4555-8555-555555555555",
  "componentName": "Widget Pro",
  "componentType": "COMPONENT",
  "release": "55555555-5555-4555-8555-555555555555",
  "releaseVersion": "1.2.0-rc1",
  "releaseIdentifiers": ["PURL:pkg:generic/widget-pro@1.2.0-rc1"],
  "settings": {
    "row": "55555555-5555-4555-8555-555555555555",
    "revision": 2,
    "source": "ORGANIZATION"
  },
  "sbomProfile": {
    "name": "default",
    "revision": 0,
    "source": "DEFAULT"
  },
  "parts": [
    {
      "part": "AGGREGATE_SBOM",
      "requirement": "REQUIRED",
      "outcome": "PRODUCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "1",
      "fileName": "Widget-Pro-1.2.0-rc1-fda-premarket-v1-aggregate.cdx.json",
      "mediaType": "application/vnd.cyclonedx+json",
      "size": 48213,
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    },
    {
      "part": "VDR",
      "requirement": "REQUIRED",
      "outcome": "PRODUCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "1",
      "fileName": "Widget-Pro-1.2.0-rc1-fda-premarket-v1-vdr.cdx.json",
      "mediaType": "application/vnd.cyclonedx+json",
      "size": 9120,
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    },
    {
      "part": "VEX",
      "requirement": "OPTIONAL",
      "outcome": "PRODUCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "1",
      "fileName": "Widget-Pro-1.2.0-rc1-fda-premarket-v1-vex.cdx.json",
      "mediaType": "application/vnd.cyclonedx+json",
      "size": 2210,
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    },
    {
      "part": "NARRATIVE",
      "requirement": "REQUIRED",
      "outcome": "PRODUCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "1",
      "fileName": "Widget-Pro-1.2.0-rc1-fda-premarket-v1-narrative.txt",
      "mediaType": "text/plain; charset=utf-8",
      "size": 1480,
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    },
    {
      "part": "SUPPORT_ADDENDUM",
      "requirement": "REQUIRED",
      "outcome": "PRODUCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "1",
      "fileName": "Widget-Pro-1.2.0-rc1-fda-premarket-v1-support-addendum.csv",
      "mediaType": "text/csv; charset=utf-8",
      "size": 3306,
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    },
    {
      "part": "DEVICE_SUPPORT_STATEMENT",
      "requirement": "OPTIONAL",
      "outcome": "SKIPPED",
      "skipReason": "NOT_APPLICABLE",
      "detail": "The device support statement describes a DEVICE, so it is generated from a product release. This release is a component release; open the product release that ships it and generate the statement there."
    },
    {
      "part": "RAW_ARTIFACTS",
      "requirement": "OPTIONAL",
      "outcome": "REFERENCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "3",
      "fileName": "widget-pro-1.2.0-rc1-build-attestation.json",
      "mediaType": "application/json",
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    },
    {
      "part": "RAW_ARTIFACTS",
      "requirement": "OPTIONAL",
      "outcome": "REFERENCED",
      "artifact": "55555555-5555-4555-8555-555555555555",
      "artifactVersion": "1",
      "fileName": "widget-pro-1.2.0-rc1-build-log.txt",
      "mediaType": "text/plain; charset=utf-8",
      "digests": [{"alg": "SHA-256", "value": "<64 hex characters>"}]
    }
  ]
}
```

Every uuid above is a placeholder. `digests[].value` is written as a placeholder here and is 64 lowercase hex characters in a stored manifest; the digest algorithm label is
`SHA-256`. `detail` is the server's own text and may differ. A part excluded by the package settings appears as `SKIPPED` with `skipReason` `EXCLUDED_BY_SETTINGS` and no `detail`.

The manifest is serialized once, stored verbatim with its sha256, and uploaded as its own document. It carries ReARM's own identifiers, never TEA ids (nothing of a submission has one), and `createdBy` as a user uuid, never a name or an e-mail.

### Downloading the documents

Every part and the manifest is a generated artifact of the release at version `1`. From the UI, download them in the Regulatory submissions section of the release page. From the API:

```
GET <your ReARM URL>/api/manual/v1/artifact/<artifact uuid>/rawdownload?version=1
GET <your ReARM URL>/api/programmatic/v1/artifact/<artifact uuid>/rawdownload?version=1
```

The first is for a user, the second for a FREEFORM or USER key. The answer carries the part's media type as `Content-Type` and a `Content-Disposition` attachment with the file name. Downloading needs the Artifact Download function on the release's component, so a member who may create a submission may still be unable to download its parts. A generated document is not an artifact of the release's inventory and never becomes one.

### Permissions

| Act | Needs |
|---|---|
| Create, resubmit, withdraw | Read & Write (or higher) covering the release's component: a grant on the component itself, on a perspective that contains it (ReARM Pro; a perspective grant covers every component of the perspective for every act), or the organization-wide type. A release-scoped grant does not suffice, and a Read & Write grant on a product covers the acts on the product's own releases only, not on its components' releases (reach those through a perspective grant). Organization admins have it |
| List a release's submissions, read a submission or a version | Read access covering the release: a grant on the release, on its component, on a perspective that contains the component, on a product that pulls the component in, or the organization-wide type |
| List a product's or component's submissions, preview | Read access covering the component the same way; a release-scoped grant does not suffice |
| Download a part or the manifest | The Artifact Download function in a grant that covers the release's component: on the component, on a perspective that contains it, on a product that pulls it in, or organization-wide |

FREEFORM and USER API keys read through the four programmatic operations below with the same read access, and cannot create, resubmit or withdraw. EXTERNAL keys (TEA consumer keys) are refused everywhere outside TEA (`401`) and never see a submission. A release, submission, component or artifact of another organization is "not found".

### Refusals

An act or a preview that cannot go ahead answers one of these `SubmissionRefusal` values:

| Value | Meaning |
|---|---|
| `RELEASE_NOT_FOUND` | The release, or its component, does not exist |
| `RELEASE_ARCHIVED` | The release is archived (create and resubmit) |
| `COMPONENT_ARCHIVED` | The release's component is archived (create and resubmit) |
| `LIFECYCLE_TOO_LOW` | The release is below ASSEMBLED (create and resubmit) |
| `ALREADY_SUBMITTED` | A submission of that kind exists for the release, withdrawn or not; use resubmit |
| `NOT_SUBMITTED` | A resubmit or withdraw names no existing submission |
| `STALE_HEAD` | The latest version the caller showed is no longer the latest |
| `REASON_REQUIRED` | Resubmit or withdraw without a reason |
| `SUBMISSION_IN_PROGRESS` | Another publish, hide or submission act on the same release held it for 30 seconds; repeat the act |
| `SETTINGS_CONFLICT` | The package settings resolve to a conflict between perspectives |
| `SETTINGS_INVALID` | A stored settings row fails a rule, for example `RAW_ARTIFACTS` included with no artifact type |
| `PROFILE_CONFLICT` | The SBOM profile the package settings name resolves to a conflict at the component |
| `PROFILE_NOT_FOUND` | The SBOM profile the package settings name does not resolve at the component |
| `PART_GENERATOR_MISSING` | The server has no generator for an included part |
| `PART_MISSING` | A required part produced nothing |
| `PART_FAILED` | A part's producer failed, for example a missing prose slot of the statement |
| `GENERATION_FAILED` | The act lost its lease, or a stored document did not read back as uploaded |
| `REASON_INVALID` | The reason is longer than 2048 characters or holds a control character other than TAB, LF and CR |
| `EDITION_UNSUPPORTED` | The server has no submission support (a Community Edition server once the shared code reaches it) |

The preview, `submissionPreview(release, kind)`, reports the plan and the first refusal before anything is generated.

### Notifications and audit

Notification subscriptions can listen for `RELEASE_SUBMISSION_CREATED` (a first version or a resubmission; the payload's `act` says `CREATED` or `RESUBMITTED`) and `RELEASE_SUBMISSION_WITHDRAWN`. The payload carries the release, the submission id, the kind, the state, the version and the act, and no free text: the reason is never rendered in Slack, Teams, e-mail, the inbox or Sentinel, and the e-mail subject is the headline alone, so a webhook consumer that needs the reason reads the version through the API. Every act is a History row and an audit record. See [Notifications](../configure/notifications).

### Never public

Submissions are never public. A submission's documents carry no TEA id and no TEA tag, the TEA artifact endpoints answer `404 OBJECT_UNKNOWN` for them, a TEA publication's ride-along artifacts are taken from the release's inventory only, and a PUBLIC TEA publication with Raw artifacts ALL publishes none of them.

### Submissions in the API

The user GraphQL API serves `submissionsOfRelease(release)`, `submissionsOfComponent(component, kind, state)`, `submission(uuid)`, `submissionVersion(submission, version)`, `submissionPreview(release, kind)` and `Release.submissions`, and the acts `createSubmission(release, kind, reason)`, `resubmitSubmission(submission, reason, expectedLatestVersion)` and `withdrawSubmission(submission, reason)`.

## Programmatic access

FREEFORM and USER API keys with read access read submissions with `submissionsOfReleaseProgrammatic`, `submissionsOfComponentProgrammatic`, `submissionProgrammatic` and `submissionVersionProgrammatic`, the SBOM profiles behind them with `sbomProfilesProgrammatic` and `sbomProfileEffectiveProgrammatic`, and download the documents at `<your ReARM URL>/api/programmatic/v1/artifact/<artifact uuid>/rawdownload?version=1`. Package settings have no programmatic API, and no key type can create, resubmit or withdraw a submission. See [Programmatic Access](../configure/programmatic-access).

## Edition differences

Regulatory packages and submissions are ReARM Pro only; the [edition differences](../tea/#edition-differences) of the TEA page cover them together with TEA and SBOM profiles.

## Not implemented

- **Data-driven package definitions.** The kinds and their part lists are code; adding a kind is a server change, and `FDA_PREMARKET` is the only kind.
- **Signing.** Every version has a reserved signature slot that is empty today; the digests in the manifest are the only integrity evidence. Signing waits on a server change.
- **An organization-level submission view.** The product page and the release page are the entries, and the API lists by release and by component only; a view across the organization is a server change.
- **PDF rendering of the addendum and the statement.** Not planned, by operator decision.
- **Programmatic create, resubmit and withdraw.** A server change.
- **CLE in a package.** It waits on the CLE 1.0.0 alignment listed under [Not implemented yet](../tea/#not-implemented-yet) on the TEA page.
