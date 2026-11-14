# Regulatory Packages and Submissions (ReARM Pro)

A regulatory package is what ReARM assembles for one regulator from one release. A package kind names the regime (`FDA_PREMARKET` today), lists the parts the package holds and says which of them are required. Package settings at a scope say which SBOM profile feeds the SBOM part and how each part is produced. A submission is the immutable record of one assembly of a package for one release: version 1 when its first draft is finalized, and one more version each time a later draft is finalized, each with its manifest and its generated documents, kept for as long as the release is.

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

The package settings editor is per scope and kind, generic over the kinds the server lists, with the profile picker for the SBOM part and the per-part options. The organization's package settings are on the Regulatory tab of Organization Settings (not on the Transparency Exchange tab), a component's or product's on its TEA tab, and a perspective's in its TEA dialog on the Perspectives tab of Organization Settings (ReARM Pro). Screenshots and the exact labels follow once the UI task that builds them lands.

The user GraphQL API serves `packageKinds`, `packageSettings(org, scope, object, kind)`, `packageSettingsOfScope(org, scope, object)`, `packageSettingsOfOrg(org)`, `packageSettingsEffective(org, scope, object, kind)`, `packageSettingsEditorView(org, scope, object, kind)`, `savePackageSettings(org, scope, object, kind, settings)` and `deletePackageSettings(org, scope, object, kind)`; there is no programmatic operation for package settings. Deleting an SBOM profile that package settings name is refused while they would stop resolving it.

## Submissions

### What a submission is

There is one submission per release and kind, with versions numbered from 1. A version is immutable: it is never updated or deleted, by the application or by SQL (a database trigger refuses it). A later version is written when a later draft of the release is finalized; it turns the Final version before it `SUPERSEDED`, and the earlier versions stay readable. A cancellation turns a Final version `CANCELLED` and keeps every version and document.

### Drafting, finalizing, cancelling

A version is written only by finalizing a draft: Start draft, Rebuild, Confirm and Finalize, with Return to draft and Discard on the way (see [Drafting a package in the UI](#drafting-a-package-in-the-ui)). Finalize writes the version from the build it verified and generates nothing again. **Cancel** (a reason is required) turns a Final version `CANCELLED`, writes no version and deletes nothing; a version that is not Final cannot be cancelled.

An act answers the act it recorded, the submission, the version and the draft as it left them, or a refusal. Start draft and Finalize need the release at ASSEMBLED or beyond, and neither the release nor its component archived. An act waits up to 30 seconds for any other publish, hide or submission act on the same release, then is refused with `SUBMISSION_IN_PROGRESS` and can be repeated. A failed act leaves no version (what it uploaded is archived).

### The reason

Start draft takes a reason category and a reason text, the text required for every category but "First version"; Discard, Return to draft and Cancel require a reason. A reason is stripped, at most 2048 characters, and holds no control character other than TAB, LF and CR (`REASON_INVALID`). A draft's reason is stored on the version it becomes, in the manifest and in the release History; a cancellation's reason is stored on its CANCEL act and in the History row, and the UI shows it on the version and on the History row. It is **never part of a notification** (see [Notifications and audit](#notifications-and-audit)).

### Where they appear

The product page's Submissions tab lists the releases of one feature set at a time with their submissions by kind, the latest version, the state and the last act, and names the submissions on releases of the product's other feature sets. It has no actions: a release without a version of a kind shows the link "Draft on the release page", or the lifecycle it still needs (Assembled or later).

The release page's Regulatory tab (ReARM Pro; not shown on a document round's release page) is the package page of that release: per kind the submission's state and last act, Start draft while the kind has no draft, Cancel on a Final submission, the draft card, and the version history with each version's manifest, parts to download and the confirmations recorded for it.

#### Drafting a package in the UI

- **Start draft** asks for a reason: "First version" for a kind without a submission; otherwise a response to an FDA request, a correction or other, with a reason text. An earlier Final or Sent version of another release of the component can be named as the one this version replaces (optional). A kind whose package carries the assessment narrative asks for the narrative first. The first rebuild is requested at once.
- **The draft card** shows the draft's state, who started it and why, the rebuild job (requested, running, built or failed, with the failure text), and, once built, the contents (each part with its size, SHA-256 and a download named by the server), the release SBOM version it pins, and what changed against the previous version (components added, removed or with a changed version, support changes, findings). The page reads a rebuild in progress every 5 seconds for up to 15 minutes, then every 30 seconds.
- **The banner** says "Not built yet", "Inputs unchanged", or how many changes there are since the build, with what moved in words (for example "assessment narrative changed" or "files of a release changed"), never the identifiers behind them.
- **Greyed buttons** say why on hover: Confirm and Finalize while a rebuild is in progress or the inputs changed since the build ("Rebuild first"), Discard while a rebuild is requested, Finalize for the person who confirmed when a second confirmer is required, below Assembled, or when the package already has the version the draft planned.
- **Confirm** and **Finalize** each ask to tick the statement "I reviewed the contents and the accepted items and confirm this package is ready for submission." Finalize writes the Final version from the build the card shows; nothing is generated again. **Discard**, **Return to draft** and **Cancel** ask for a reason. A refused act shows the server's sentence and the page reads again.
- **The second confirmer** is a switch on the Regulatory tab of Organization Settings, "Require a second confirmer for regulatory packages", off by default; an organization administrator changes it. When it is on, one person confirms a built draft and a different person finalizes it. A change applies to drafts already open at their next act: a draft confirmed while it was on and finalized after it was switched off records the finalizer alone, and a draft being edited when it is switched on needs a Confirm by one person and a Finalize by another. An open release page reads the setting again within 30 seconds.

The release History tab records each act as a row of scope SUBMISSION: ADDED when a draft is started and for the first version, CHANGED for a later version (also one finalized over a cancelled version), REMOVED when a draft is discarded or a version is cancelled, with the reason shown with the row when one was given.

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

The first is for a user, the second for a FREEFORM or USER key. The answer carries the part's media type as `Content-Type` and a `Content-Disposition` attachment with the file name. Downloading needs the Artifact Download function on the release's component, so a member who may finalize a draft may still be unable to download its parts. A generated document is not an artifact of the release's inventory and never becomes one.

### Permissions

| Act | Needs |
|---|---|
| Start draft, Rebuild, Confirm, Return to draft, Discard, Finalize, Cancel | Read & Write (or higher) covering the release's component: a grant on the component itself, on a perspective that contains it (ReARM Pro; a perspective grant covers every component of the perspective for every act), or the organization-wide type. A release-scoped grant does not suffice, and a Read & Write grant on a product covers the acts on the product's own releases only, not on its components' releases (reach those through a perspective grant). Organization admins have it |
| List a release's submissions, read a submission or a version | Read access covering the release: a grant on the release, on its component, on a perspective that contains the component, on a product that pulls the component in, or the organization-wide type |
| List a product's or component's submissions, preview | Read access covering the component the same way; a release-scoped grant does not suffice |
| Download a part or the manifest | The Artifact Download function in a grant that covers the release's component: on the component, on a perspective that contains it, on a product that pulls it in, or organization-wide |

FREEFORM and USER API keys read through the four programmatic operations below with the same read access, and cannot act on a draft or a submission. EXTERNAL keys (TEA consumer keys) are refused everywhere outside TEA (`401`) and never see a submission. A release, submission, component or artifact of another organization is "not found".

### Refusals

An act or a preview that cannot go ahead answers one of these `SubmissionRefusal` values:

| Value | Meaning |
|---|---|
| `RELEASE_NOT_FOUND` | The release, or its component, does not exist |
| `RELEASE_ARCHIVED` | The release is archived (Start draft and Finalize) |
| `COMPONENT_ARCHIVED` | The release's component is archived (Start draft and Finalize) |
| `LIFECYCLE_TOO_LOW` | The release is below ASSEMBLED (Start draft and Finalize) |
| `ALREADY_SUBMITTED` | No act answers it any more; the value stays in the enum |
| `NOT_SUBMITTED` | Cancel names no existing submission |
| `STALE_HEAD` | The draft changed since it was opened (Confirm and Finalize), or the draft was planned for a version the package already has (Finalize) |
| `REASON_REQUIRED` | Discard, Return to draft or Cancel without a reason; Start draft without a reason category, without a reason text for any category but "First version", or with "First version" for a kind that has a submission |
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

Notification subscriptions can listen for `RELEASE_SUBMISSION_CREATED` (a first version or a later one; the payload's `act` says `FINALIZE`; the version tells a first version from a later one) and `RELEASE_SUBMISSION_WITHDRAWN` (the act is `CANCEL`). The payload carries the release, the submission id, the kind, the state, the version and the act, and no free text: the reason is never rendered in Slack, Teams, e-mail, the inbox or Sentinel, and the e-mail subject is the headline alone, so a webhook consumer that needs the reason reads the version through the API. Every act is a History row and an audit record. See [Notifications](../configure/notifications).

Package notifications now name the act as `FINALIZE` or `CANCEL`. The values `CREATED`, `RESUBMITTED`, `WITHDRAWN` and `CANCELLED` are no longer sent: not in `event.submissionAct` of a subscription filter, not in `act` of a webhook body's payload, not in the Sentinel column `SubmissionAct`. A filter that waits for one of the old values no longer fires. A filter that excludes one of the old values (`!=`, or `!(... in [...])`) now matches every package notification, cancellations included, so a channel that was set up to leave them out starts to receive them: rewrite every filter that names `submissionAct`, and every rule of your own in a webhook receiver or a Sentinel workspace that reads the act; the upgrade's database migration log lists the subscriptions by id. The same two directions apply to `event.submissionState`, whose values became `FINAL` and `CANCELLED` (were `ACTIVE` and `WITHDRAWN`).

| A filter that meant | Write |
|---|---|
| a first version only (was `event.submissionAct == "CREATED"`) | `event.submissionAct == "FINALIZE" && event.submissionVersion == 1` |
| a later version only (was `event.submissionAct == "RESUBMITTED"`) | `event.submissionAct == "FINALIZE" && event.submissionVersion != 1` |
| a cancellation (was `== "WITHDRAWN"` or `== "CANCELLED"`) | `event.submissionAct == "CANCEL"` |
| everything but cancellations (was `!= "WITHDRAWN"`, `!= "CANCELLED"` or `!(event.submissionAct in ["CANCELLED", "WITHDRAWN"])`) | `event.submissionAct == "FINALIZE"` |

### Never public

Submissions are never public. A submission's documents carry no TEA id and no TEA tag, the TEA artifact endpoints answer `404 OBJECT_UNKNOWN` for them, a TEA publication's ride-along artifacts are taken from the release's inventory only, and a PUBLIC TEA publication with Raw artifacts ALL publishes none of them.

### Submissions in the API

The user GraphQL API serves `submissionsOfRelease(release)`, `submissionsOfComponent(component, kind, state)`, `submission(uuid)`, `submissionVersion(submission, version)`, `submissionPreview(release, kind)` and `Release.submissions`, and the acts `startDraft(release, kind, reasonCategory, reasonText, replacesSubmission, replacesVersion)`, `discardDraft(draft, reason)`, `confirmDraft(draft, expectedRevision)`, `finalizeDraft(draft, expectedRevision)`, `rebuildDraft(draft)`, `returnDraftToEditing(draft, reason)` and `cancelSubmission(submission, reason)`.

`createSubmission`, `resubmitSubmission` and `withdrawSubmission` are no longer served (Start draft, Rebuild, Finalize and Cancel replace them), and `Submission.lastActKind` and `SubmissionActResult.outcome` are gone (`Submission.lastAct` and the kind of the answer's `act` replace them). A request that names one of them, an API key's read that selects `lastActKind` included, is refused with a GraphQL validation error and writes nothing.

## Programmatic access

FREEFORM and USER API keys with read access read submissions with `submissionsOfReleaseProgrammatic`, `submissionsOfComponentProgrammatic`, `submissionProgrammatic` and `submissionVersionProgrammatic`, the SBOM profiles behind them with `sbomProfilesProgrammatic` and `sbomProfileEffectiveProgrammatic`, and download the documents at `<your ReARM URL>/api/programmatic/v1/artifact/<artifact uuid>/rawdownload?version=1`. Package settings have no programmatic API, and no key type can act on a draft or a submission. See [Programmatic Access](../configure/programmatic-access).

## Edition differences

Regulatory packages and submissions are ReARM Pro only; the [edition differences](../tea/#edition-differences) of the TEA page cover them together with TEA and SBOM profiles.

## Not implemented

- **Data-driven package definitions.** The kinds and their part lists are code; adding a kind is a server change, and `FDA_PREMARKET` is the only kind.
- **Signing.** Every version has a reserved signature slot that is empty today; the digests in the manifest are the only integrity evidence. Signing waits on a server change.
- **An organization-level submission view.** The product page and the release page are the entries, and the API lists by release and by component only; a view across the organization is a server change.
- **PDF rendering of the addendum and the statement.** Not planned, by operator decision.
- **Programmatic acts.** The programmatic API has no act on a draft or a submission; a server change.
- **CLE in a package.** It waits on the CLE 1.0.0 alignment listed under [Not implemented yet](../tea/#not-implemented-yet) on the TEA page.
