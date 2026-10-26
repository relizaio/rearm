# Exporting Compliance Artifacts

## Description

ReARM provides structured exports for the compliance artifacts attached to your releases:

| Export | Format | Source | Where |
|---|---|---|---|
| **SBOM** | CycloneDX JSON / CSV / Excel, SPDX | Merged release SBOM via Rebom | Release view |
| **VDR** | CycloneDX 1.6 JSON or PDF | Vulnerability data from release metrics | Release view |
| **VEX** | CycloneDX 1.6 VEX or OpenVEX 0.2.0 JSON | Triage decisions on release vulnerabilities | Release view |
| **OBOM** | CycloneDX JSON | Operational BOM from outbound deliverable | Release view |
| **CLE** | Common Lifecycle Enumeration (JSON) | Lifecycle events for the release | Release view |
| **Support** | Support addendum (CSV or PDF), Device support statement (PDF) | Recorded support attestations and the device support window | Release view; the statement is also per-shipment from Distribution |
| **BOV** | CycloneDX 1.6 JSON or PDF | Findings data from the Findings Modal | Findings Modal |

All exports except BOV and the Support documents are server-generated. BOV is assembled
client-side from the currently displayed findings, and so are the Support documents -- they are
rendered in the browser from one collector. The **device support statement** refuses rather than
emit a partial document when the labeling text it must carry is unauthored; the addendum tolerates
unauthored prose and refuses for other reasons, such as a component count it cannot reconcile.

## Accessing Exports

Open any release and click the **download icon** in the release header. A modal opens with a
**BOM Type** selector: **SBOM**, **OBOM**, **VDR**, **VEX**, **CLE** and **Support**.

**Support** is not an encoding of the BOM -- it is a different set of documents, built from the
release's support attestations and your organization's labeling text rather than from the
component graph. Picking it replaces the format list with the support documents and hides the
BOM-shaping options, which do not apply to them:

| Support document | Contents |
|---|---|
| **Support addendum (CSV)** | Every component in the release with its level of support, the end-of-support date where one is attested and the justification where none is |
| **Support addendum (PDF)** | The same document, typeset |
| **Device support statement (PDF)** | A plain-language statement for patients, caregivers and biomedical engineers. Product releases only, and only once all three organization labeling statements have been authored |

The **Support addendum (CSV)** downloaded here is rendered in the browser and neutralizes only a cell's leading `=`, `+`, `-`, `@`, TAB or CR, with one apostrophe before it. Whenever your spreadsheet's list separator is not a comma (a `;` locale, or a separator set by hand to any other character), open the file through the spreadsheet's text import with the comma delimiter (in Excel, Data > From Text/CSV with the delimiter set to Comma), never by double-click: a double-click in a `;` locale mis-splits the columns and can evaluate a formula that follows a `;` or a line break inside a cell, and the text import is the only protection for this file. The server-rendered addendum of a regulatory package also neutralizes those positions; the browser download does not have that rule, which is a UI change. See [the support addendum of a regulatory package](./regulatory-packages#the-support-addendum) for the full rule.

On a component release the device support statement is not offered: it is a statement about a
device, and the product release that ships the component is where it is generated.

## SBOM Export

Exports the merged SBOM for the release. Options:

| Option | Description |
|---|---|
| **SBOM Configuration** | Which merged SBOM variant to export (`SBOM`, `TEST`, `BUILD_TIME`, etc.) |
| **Structure** | `FLAT` or `NESTED` (CycloneDX hierarchy) |
| **Top-level only** | Strip transitive dependencies, keep direct dependencies only |
| **Ignore Dev** | Exclude development dependencies |
| **Exclude coverage types** | Exclude artifacts tagged as Dev, Test, or Build-Time coverage |
| **Media Type** | `JSON`, `CSV`, or `Excel` |
| **Include support metadata** | Whether this export carries the support attestations. See [Per-export metadata options](#per-export-metadata-options) |
| **Include internal metadata** | Whether this export keeps ReARM's own markers. See [Per-export metadata options](#per-export-metadata-options) |
| **Leave out file components** | Drops components of type `file` (for example a container image's file inventory, which has no version or supplier) from the exported BOM and from the score. CISA 2026 allows an SBOM to exclude non-code files. The exported file records how many were left out in the metadata property `reliza:export:fileComponentsExcluded`; it applies to every media type. On the API: `releaseSbomExport(excludeFileComponents: true)` |

When a document is produced for a channel rather than by hand, the same choices (structure, dependency depth, coverage, support and internal metadata) are stored once as a named SBOM profile: the Transparency Exchange API publishes one aggregated SBOM per SBOM profile its TEA publishing settings name (see [SBOM profiles](../tea/#sbom-profiles)), and a [regulatory package](./regulatory-packages)'s SBOM part is generated with the SBOM profile its package settings name.

Click **Export** to download the file.

### Component releases without an SBOM

Component releases of a product that have no SBOM (or whose SBOMs are all excluded by the coverage
filter) appear in the product export as components with the property `reliza:sbom:missing`,
listed in the root's dependencies, and a `compositions` entry with `aggregate: unknown` states that
their own dependencies are unknown. `reliza:export:componentsWithoutSbom` on the document counts
them. Such a component's bom-ref is `urn:rearm:release:<release uuid>`; the property's value says
why ReARM holds no SBOM for it: `NO_SBOM_ARTIFACT` (none was uploaded) or `COVERAGE_EXCLUDED` (every
SBOM it has was left out by **Exclude coverage types**). It carries the release's purl when the
release records one, and none otherwise. A component release's first SBOM, or a replaced SBOM,
rebuilds the merged exports of every product above it, nested products included, so a placeholder
leaves the product export once its release has an SBOM. A product none of whose component releases has an SBOM
still has nothing to export. From the ReARM release after 26.10.52 on; product exports cached
before it are rebuilt on their next request. CSV and Excel list these components as rows too; the
property and the `compositions` entry are in the CycloneDX JSON only.

### What the nodes ReARM makes carry

ReARM writes three kinds of node into an export itself: a component release's own node (the root
of its merged SBOM), a product's root, and the placeholders of component releases without an SBOM
(see [Component releases without an SBOM](#component-releases-without-an-sbom)). Everything else in
an export is what was uploaded, and ReARM never replaces uploaded data.

**Supplier.** A node ReARM makes names as supplier the publisher of the release's deliverable when
one is recorded, else the component's **Supplier**, else the organization for its own (INTERNAL)
software, else nobody (EXTERNAL software). The root of an uploaded SBOM takes the same rule when it
is uploaded, and keeps a supplier its generator wrote. When a change of the component's supplier
shows, as the field states it:

> Applies to the nodes ReARM makes at the next export and to SBOMs uploaded after it is set. A root
> uploaded earlier keeps the supplier recorded at its upload; a newly generated SBOM (a new
> serialNumber) uploaded to a release made after the change carries the new value in that release's
> export. An identical file is answered with the stored row, unchanged.

**Hashes.** A release's node carries the hashes of the release's one hashed outbound deliverable
when the deliverable's purl type agrees with the node's, and none otherwise. The root of an SBOM
attached to a source code entry carries its commit in `pedigree.commits`, not as a hash.

**Declared licence.** A licence can be declared at three levels: on a release, on its component, and
as the organization default. The first level that declares one, in that order, is the licence on the
release's node and on its placeholder in a product; a licence URL comes only from the level that won,
and a release that declares its own licence carries no URL. A value is an SPDX licence expression:
ids from the [SPDX licence list](https://spdx.org/licenses/) in any case (stored in the list's
casing, `apache-2.0` becomes `Apache-2.0`), a trailing `+`, `LicenseRef-` followed by letters,
digits, dots and hyphens for a licence that is not on the list, the operators `AND`, `OR` and `WITH`
in upper case, and parentheses, at most 512 characters. `NOASSERTION`, `NONE`, `DocumentRef-` and
licence names are refused. The export writes it as follows:

- a single SPDX id:

  ```json
  "licenses": [{ "license": { "id": "Apache-2.0" } }]
  ```

- a single `LicenseRef-` id with a URL:

  ```json
  "licenses": [{ "license": { "name": "LicenseRef-Acme-Commercial", "url": "https://example.com/licence.txt" } }]
  ```

- anything else (a compound expression, or a `LicenseRef-` id without a URL), with no URL:

  ```json
  "licenses": [{ "expression": "MIT OR Apache-2.0" }]
  ```

On a product export, a component release's declared licence stands on its node; licence ids that
another uploaded SBOM lists for the same purl appear beside it, while a listed expression or
`NOASSERTION` does not. A root whose generator wrote licences, `NOASSERTION` included, keeps them.
ReARM never reads a LICENSE file or an artifact to find a licence.

**When a change shows.** A release's own exports show a change at their next request. A change of
a release's licence also rebuilds the product exports above it. A change of a release's preferred
PURL identifier (set on the release, or filled from its component with "Propagate To Releases With
Missing Identifiers" on the component page), of its artifacts, source code entry or deliverables (a
CI rebuild included), or of a product's supplier name, discards the stored exports of the release and
of every product above it, and the next export of each rebuilds it; a release whose last SBOM is
detached is listed as a component without an SBOM again. A change of the organization default, or of
a component's supplier name, does not: a product exported before it serves its stored export until
something rebuilds it (an SBOM added below it, a release licence change below it, or a ReARM upgrade that
regenerates merged exports). Likewise a release exported before an upload keeps serving its stored
export until a product above it is rebuilt or the release's declared facts change.

**Where to set them.** In the UI: **Organization Settings**, **Admin Settings**, **Default declared
licence** (organization administrators); a component's settings, **Declared licence and supplier**;
a release's **Meta** tab, **Declared licence for this release**. On the API:
`updateOrganizationSettings` (`defaultLicenseExpression`, `defaultLicenseUrl`); `updateComponent`,
`createComponent` and `createComponentProgrammatic` (`licenseExpression`, `licenseUrl`,
`supplierName`); `updateRelease` and `addReleaseProgrammatic` (`licenseExpression`). The ReARM CLI
and the Terraform provider gain them in a later release.

**The root of an SBOM uploaded against the release.** It is folded into the release's node in
the export: the node keeps the upload's description, supplier and licence, the declared licence
and the deliverable digest fill what the upload left out, and the folded root's bom-ref is
recorded on the node as `reliza:export:foldedRoot`.

**A component release named and versioned like the product.** Its node keeps its own supplier,
licence and hash in the product's export and is never folded into the product root. Because the
two would share a purl, the component node's purl carries a `rearm_input` qualifier naming the
serial number of the component's own merged SBOM, and its bom-ref is that serial number followed
by `#` and the purl without the qualifier. Two component releases that share a name and version
under one product are kept apart the same way.

**A nested product's node.** In the export of a product that bundles another product, the bundled
product's node, and the roots of the SBOMs attached to that product's release and to its source
code entries, carry only what that product's own export states. An SBOM of another release in the bundle that lists the bundled
product by its ReARM purl is kept as its own node with the `rearm_input` qualifier, as above, and
never adds to the bundled product's node. A listing of a component release by its ReARM purl still
folds into that release's node.

**What still fails.** The root of an SBOM attached to a deliverable the release ships or to the
release's source code entry keeps its own entry, and carries the release's declared licence where its
generator wrote none; a licence the generator wrote, `NOASSERTION` or `NONE` included, is kept.
Such a root is a direct dependency of the release's node in the export, so the structure check
`structure.no-orphans` does not name it. Component License still fails on such a root when the
release declares no licence, and then on the release's node too. Component Hash still fails on the
root of an SBOM attached to a source code entry, whose commit is a pedigree fact, not a hash. See
[Reading the report](./sbom-scoring#reading-the-report).

### Listed licences a merge dropped

When an SBOM in a product lists one of the product's component releases with a licence expression
and the merge keeps another licence on that node (the licence the release declares, or the licence
its own SBOM carries), the node records each dropped expression as a property
`reliza:license:dropped`, one entry per expression, with the text as the listing wrote it. Nothing
is recorded for `NOASSERTION`, `NONE` or an expression the node still holds. Licence ids are not
recorded: they are merged beside kept ids or names, and dropped beside a kept expression, where only
the server log names them. The property stays in the export when internal metadata is left out, and
is inside the signed document. From the ReARM release after 26.10.52 on; product exports cached
before it are rebuilt on their next request.

### Who may upload a new version of an SBOM

::: tip Available in both ReARM Community Edition and ReARM Pro
The rule in this subsection, its error code, its `GUARD` event and the check on listing an existing artifact apply in both editions. The upgrade note at the end of this subsection applies to both editions too.
:::

A CycloneDX `serialNumber` identifies one SBOM within your organization, and several components may hold the same one. A component holds it when one of its releases, deliverables or commits lists an artifact with that `serialNumber`, directly or inside another artifact. Uploading the same `serialNumber` with a higher `version` makes that version the one ReARM reads for every release that holds the `serialNumber`; whether a stored export shows it follows [When a change shows](#what-the-nodes-rearm-makes-carry). From the ReARM release after 26.10.52 on, such an upload is accepted when the uploader has write permission on the whole organization (an organization admin, an organization read-write key, or a Free Form or personal key with read-write on the organization), or write permission on every component that holds the `serialNumber`; write permission on a release or a branch is not write permission on its component. Otherwise it is refused before anything is stored, with the error code `SBOM_SERIAL_NUMBER_UPDATE_NOT_PERMITTED` and a message that never names the components that hold it. When the target release already exists, the refusal is recorded on it as a `GUARD` update event; a refused `rearm addrelease` creates no release. A component's own key can therefore rebuild an SBOM that only its component holds, also after identical rebuilds. An SPDX document uploaded as the next version of a stored SBOM follows the same rule.

A new `serialNumber` is accepted from anyone who may upload to the target. Three kinds of existing `serialNumber` need write permission on the whole organization for a new version: one that no component holds any more (every artifact with it was replaced or removed, or only agent sessions hold it); the `serialNumber` of a merged release or product SBOM, which ReARM mints itself; and one whose first upload never completed because the request that carried it failed after the SBOM was stored. In the last case, uploading the identical first file once more makes it the component's own again. An identical file uploaded again is not a new version: it is attached and answered with the stored document, and its component then holds the `serialNumber`, so later new versions also need write permission on that component. It lets its component's key upload new versions later only when the uploader could already have uploaded a new version itself. Listing an existing artifact on a release by reference follows the same test: it needs write permission on every other component that holds the artifact, or on the whole organization when nothing holds it.

Builds of the ReARM Pro main branch from 16 October 2026 tried a per-component owner of each `serialNumber` instead; no release shipped it. Its error code `SBOM_SERIAL_NUMBER_OWNED_BY_OTHER_COMPONENT`, the `sbomLineageOwner` query and the `reassignSbomLineageOwner` mutation are gone.

**Upgrading to the release after 26.10.52.** Before you change any image, run three read-only queries. The first is for every installation of either edition. The other two find something only where a main build from 16 October 2026 ran.

- The duplicate-number query, in the ReARM database (`rearm-postgresql` on Docker Compose, `<release>-postgresql` on Helm, or your external database). It lists the tasks of an agent task board that share a task number:

  ```sql
  SELECT t.record_data->>'board' AS board, (t.record_data->>'number')::int AS number,
         t.record_data->>'key' AS task_key, t.uuid AS task, t.record_data->>'title' AS title, t.created_date
  FROM rearm.agent_tasks t
  WHERE t.record_data->>'number' IS NOT NULL
    AND ((t.record_data->>'board'), (t.record_data->>'number')::int) IN (
        SELECT record_data->>'board', (record_data->>'number')::int FROM rearm.agent_tasks
        WHERE record_data->>'number' IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1)
  ORDER BY board, number, t.created_date;
  ```

  From this release on, two tasks of one board never share a number (database migration 96), and the backend does not start while a board already has such a pair. Earlier releases could store one: a board change saved while a task was being registered on that board could set the board's task counter back, and the next task then took a number already in use. The query returns no rows on an installation without agent task boards, and on one where migration 96 already ran.
- The 97/98 query, in the ReARM database: `SELECT version, description FROM public.flyway_schema_history WHERE version IN ('97','98');` Only ReARM Pro main builds wrote these rows; on Community Edition this query returns no rows.
- The rebom query, in the rebom database (`rebom-postgresql` on Docker Compose, `<release>-rebom-postgres` on Helm, or your external database): `SELECT to_regclass('rebom.bom_lineage') AS lineage_table, (SELECT count(*) FROM rebom.flyway_schema_history WHERE version = '9') AS v9_rows;` This finds something, on either edition, only where a rebom image built from the main branch from 16 October 2026 ran; the published Helm charts and Docker Compose file name earlier rebom images.

If the duplicate-number query returns rows, do not upgrade yet. Rows with the same `board` and `number` are tasks of that board with the same number, usually also the same key, and this release's backend would stop at start with Flyway reporting `could not create unique index "agent_tasks_board_number"`. ReARM has no action that renumbers a task, so the repair is an edit of task rows in the ReARM database, and which task keeps the number is for an administrator of the organization that owns the board to decide: the key is what the board's documents, notes and pull request titles quote. The usual repair keeps the number on the task registered first and gives each later task of the pair a number above the highest number on its board, with the key `<board prefix>-<number>`; deleting a task instead loses the task and its history. Make the edit while the backend is stopped and after a backup of the ReARM database, and ask first if you are unsure: on the [issue tracker](https://github.com/relizaio/rearm/issues) for ReARM Community Edition, or through your ReARM Pro support contact. The board's task counter needs no change: this release draws each new number above the highest number on the board. Upgrade once the query returns no rows. If you already upgraded and the backend stopped with that Flyway message, migration 96 was not applied: stop the backend, repair the pairs the same way, and start it again.

If all three queries return no rows (the rebom query a null `lineage_table` and `v9_rows` 0), upgrade as usual: take the backend and rebom images of this release together, as this release's Helm chart or Docker Compose file names them, and do not run this release's backend beside an earlier rebom, which refuses some of its SBOM uploads. The backend's first start builds two database indexes before it serves, so on a large database it takes longer than usual; on Helm, if the backend pod restarts before it becomes ready, raise `backend.startupFailureThreshold`.

If the 97/98 query or the rebom query found something, the installation ran a main build from 16 October 2026 and may need two database repairs, and their order matters: a ReARM Pro backend from such a build must never run beside this release's rebom, or a key with write permission on one component could replace an SBOM that other components hold. Follow these steps in order:

1. Before you change any image, stop the backend and rebom, and wait until neither has a running pod or container. On Helm: `kubectl -n <namespace> scale deployment/<release>-backend deployment/<release>-rebom-backend --replicas=0`. On Docker Compose: `docker compose stop rearm-core rebom-backend`. If a deployment tool starts them again, set their replicas to 0 in the tool's source or pause its sync, and stop them again before step 2.
2. If the 97/98 query returned rows, delete them only if they are exactly `97` `sbom signing keys` and `98` `artifacts internal bom index`: `DELETE FROM public.flyway_schema_history WHERE version IN ('97','98');`. Never delete a `97` or `98` row with another description. Migrations 94 and 95 then find their table and index in place. Without this step, this release's backend stops at start with Flyway reporting `Detected resolved migration not applied to database: 94, 95, 96`.
3. Start this release's rebom and backend together. On Helm: `helm upgrade` with this release's chart, then scale either deployment back to its replica count if it stays at 0. On Docker Compose: this release's compose file, then `docker compose up -d`. Never start this release's rebom beside an earlier backend.
4. Once both are ready, and only if the rebom query found the table or a `v9_rows` count above 0, run in the rebom database: `DROP TABLE IF EXISTS rebom.bom_lineage; DELETE FROM rebom.flyway_schema_history WHERE version = '9';`
5. From step 1 on, only go forward. Do not run `helm rollback`, do not go back to an earlier compose file, and never start an earlier rebom or backend on these databases. If this release's backend does not become ready, stop it again (step 1) and upgrade to a later release.

If you already upgraded and this release's backend stops with the Flyway message in step 2, start at step 1. On ReARM Pro, your earlier backend may then have kept serving beside this release's rebom until you stopped it, and it did not apply the rule above in that time: a key with write permission on one component could have uploaded a new version of an SBOM that other components hold, and every release that holds it now reads that version. Once both are running again, list the new versions of existing `serialNumber`s stored since this release's rebom first started (replace `<start>` with that time; if you are not sure, use the time you began the upgrade or earlier: an earlier time only lists more rows), in the rebom database:

```sql
SELECT b.organization, lower(replace(b.meta->>'serialNumber', 'urn:uuid:', '')) AS serial_number, b.meta->>'bomVersion' AS bom_version, b.created_date
FROM rebom.boms b
WHERE b.created_date >= '<start>'
  AND EXISTS (SELECT 1 FROM rebom.boms o
              WHERE o.organization = b.organization
                AND replace(o.meta->>'serialNumber', 'urn:uuid:', '') = replace(b.meta->>'serialNumber', 'urn:uuid:', '')
                AND o.created_date < b.created_date)
ORDER BY b.created_date;
```

For each row it returns, find the artifacts of that organization that carry its `serial_number` and the releases that list them, in the ReARM database (an artifact with no release here is held by a deliverable or a commit):

```sql
SELECT a.uuid AS artifact, a.created_date, a.last_updated_date, r.uuid AS release
FROM rearm.artifacts a
LEFT JOIN rearm.releases r ON r.record_data->'artifacts' @> to_jsonb(a.uuid::text)
WHERE a.record_data->>'org' = '<organization>'
  AND a.record_data->>'type' = 'BOM'
  AND a.record_data->'internalBom'->>'id' = '<serial_number>'
ORDER BY a.created_date;
```

The **History** tab of each release shows who added or updated each artifact and when; for an artifact that came with the release, who created the release. Where a version in that time came from a user or key that should not have changed that SBOM, upload a corrected SBOM with the same `serialNumber` and a higher `version` as an organization administrator.

### Scoring the export

Beside **Export**, **Score** checks the document the export would give you, with the same content options,
against the CISA 2026 minimum elements and the FDA premarket SBOM expectations, and shows the
report in the same dialog. It applies to the CycloneDX 1.6 (JSON) format. See
[Scoring an SBOM for CISA 2026 and FDA Readiness](./sbom-scoring).

The score ignores the two metadata switches (**Include support metadata** and **Include internal
metadata**): it scores the document the organization setting gives, with the support facts
included when the organization's support disclosure is ENABLED and ReARM's markers kept. The
other options, **Leave out file components** included, apply to the score as to the export.

The `fda` support checks (level of support and end of support) do not judge files, cryptographic
assets, data, machine-learning models or devices: those components are not software packages a
supplier supports, and the report says how many it skipped.

### Signing the export

When the organization has an SBOM signing key, every CycloneDX JSON export is signed with it: the
document carries a root JSF `signature` and the property `reliza:export:signed` `true`; without a
key it says `false` and why, in `reliza:export:unsignedReason`. After an export the dialog shows
one line saying whether the file you received is signed, and CSV and Excel exports carry no
signature. See [Signing exported SBOMs](./signing-exported-sboms).

## Per-export metadata options

One release is often the source of two documents with opposite requirements. An [FDA premarket
submission](./regulatory-packages) (ReARM assembles one as a regulatory package) wants the support attestations and does not care that ReARM assembled the file; a BOM
handed to a customer should read as **your** content, with the tooling provenance out of it.

The two switches beside **Top Level Dependencies Only** answer those questions per export rather
than per organization. They are also available on the API:
`releaseSbomExport(includeSupportMetadata:, includeInternalMetadata:)`, and as
`?includeSupportMetadata=` / `?includeInternalMetadata=` on the artifact download endpoints.

They change the **CycloneDX JSON** export. They are still *sent* and still *validated* for the
`CSV` and `Excel` encodings -- asking for a disclosure your organization has disabled is refused
whatever the format -- but they cannot change those two files: Rebom renders them from a fixed
column list (name, version, purl, license, author) that carries neither component properties nor
document metadata, so there is nothing of ReARM's in them either way. The export modal says so
beside the switches when CSV or Excel is selected.

**Omitting an argument is not the same as sending `false`.** An omitted argument means "behave as
before": the organization setting decides the support disclosure, and ReARM's markers are kept.
Every caller written before these arguments existed -- including `rearm-cli` -- is unchanged.

### Include support metadata

**Shown whenever the server offers the option**, and **off by default**. The organization
setting says the disclosure is *allowed*; it does not say every download wants it, so each export
opts in. The switch follows the setting of the organization that **owns the release**, which may
differ from the organization selected in the header. When that setting is off the switch is
**greyed out**, with a hint that support disclosure is off for this organization and that an
organization admin turns it on in **Organization Settings -> Support disclosure export**. While
ReARM checks the setting, or when it cannot, the switch is greyed out and the export follows the
organization setting. A greyed-out switch sends nothing, so the organization setting decides. A
server that does not offer the option at all (a ReARM CE installation before its next sync) shows
no switch, and says so.

| Setting | Effect |
|---|---|
| **On** | The export carries the support attestations described in [Support Attestations in Exports](#support-attestations-in-exports) |
| **Off** | The export is served with the support namespaces stripped, and carries no disclosure marker |

Asking for support metadata through the API on an organization that has the setting disabled is
**refused with an error** rather than quietly served without it: a document that silently came
back without the disclosure is indistinguishable from one where nothing was attested.

### Include internal metadata

Always available, and independent of the organization's support setting -- it controls ReARM's
own markers, not the disclosure.

Defaults to **off**.

| Setting | Effect |
|---|---|
| **On** | ReARM's own markers stay in the document |
| **Off** | They are removed, leaving the manufacturer's own content |

What "ReARM's own markers" means, precisely:

| Removed when off | Note |
|---|---|
| `reliza:*` properties outside the support namespaces | Today `reliza:containerSafeVersion`, `reliza:devops:integrationType` and `reliza:rearmImport:*`. Anything ReARM adds later is internal by default |
| `reliza:export:releaseNode` | rebom's stamp on the node it made for a release. The server reads it to mark the release's own entries (`reliza:support:selfComponent`) before the strip. A copy in an uploaded SBOM never reaches a merged export or a processed download: rebom removes it when the upload is stored. The raw download serves the uploaded document as it was, and no support statement is made on it |

**Not removed: what your SBOM generator wrote.** It is your content, and this setting does not
touch it. Component properties (including cdxgen's `SrcFile`, `ResolvedUrl` and similar working
data, prefixed `internal:` from cdxgen 13), component evidence and cdxgen's npm registry analysis
are exported as uploaded, in merged release exports and single-artifact downloads alike.
Formulation, annotations and services also appear in a **single-artifact download**. A **merged
release export** leaves those three sections out, with either setting: the merge does not
carry them over from its inputs. Build-machine paths in particular are not
removed: if an SBOM must not disclose them, remove them before you upload it (for cdxgen, see its
options for excluding evidence and formulation).

**Always kept: `metadata.tools`.** ReARM's own entry (`io.reliza` / `rearm`) names the ReARM
version that produced the export (the deployment's ReARM version; `26.08.95` when the deployment
does not set one), and stays with both settings: ReARM generated the merged
document and wrote any support disclosure in it, so a reader needs to see it among the tools.
The tools that produced the SBOMs you uploaded -- your SBOM generator, your build plugin, your
scanner -- are carried into merged and aggregated exports beside it.

**Not removed**, because they are the disclosure rather than provenance about ReARM:
`reliza:support:*`, `reliza:device:*` (the device's own 524B support window), and the
`reliza:bomref:` / `reliza:claim:` / `reliza:evidence:` / `reliza:assessor:` identifiers that the
`declarations` block points at. Those are governed by the other switch, and when it is off they
are already gone.

**Not removed either: `reliza:export:fileComponentsExcluded`.** It is on the document only when
**Leave out file components** was on, and it is the file's own record that file components were
left out of it, and how many. It describes the document you hold rather than ReARM's working
state, so a reader of an export with internal metadata off can still tell that the inventory is
not complete.

**Not removed either: `reliza:sbom:missing` and `reliza:export:componentsWithoutSbom`.** They mark
the components that stand for component releases ReARM holds no SBOM for, and count them (see
[Component releases without an SBOM](#component-releases-without-an-sbom)). Without them a reader
could not tell a release without an SBOM from one whose SBOM lists nothing.

**Not removed either: the signing record.** `reliza:export:signed`, with
`reliza:export:signatureKeyId` on a signed export or `reliza:export:unsignedReason` on an unsigned
one, says whether the file is signed (see [Signing exported SBOMs](./signing-exported-sboms)). It
is part of the signed content and describes the document you hold, so it stays with either setting.

**With both switches off, no other `reliza:` property remains**, not even the
`reliza:support:disclosure` marker, which is withheld when a caller sends
`includeSupportMetadata: false`. What remains of ReARM is its entry under `metadata.tools` and,
where rebom recorded one, the top-level `bom` external reference to the document as uploaded
("Source document as uploaded by the producer", a `urn:cdx:` BOM-Link to ReARM's stored copy).

That marker exists to disambiguate an *absent* support property: "we hold no attestation for
this component" versus "this document asserts nothing about support". The ambiguity is only real
in a document that is making a support statement, and a caller who declined the disclosure is
not making one -- so there is nothing for the marker to qualify.

**It is still stamped when the organization setting is off** and the caller said nothing. That
document is the product of an organization-wide policy its reader had no part in choosing, and
it is exactly where "why is there no support data here?" needs an answer. The distinction is
between silence you asked for and silence that was decided for you.

The strip itself never changes: an uploader's forged `reliza:support:*` is removed from every
served document either way. Only the marker is withheld.

### When a server does not support them

These arguments reach a ReARM CE installation only at its next sync from Pro. Against a backend
that does not declare them, the export modal falls back to a request without them, tells you once
that the options were ignored, and then says so permanently beside the metadata options rather
than leaving them looking live. **Omitting the arguments on the API has the same effect and is not an
error** -- it is the pre-existing contract.

### The raw download is never affected

The **raw artifact download** serves the bytes as uploaded, validated against the checksum ReARM
advertises for that URL. Neither switch reaches it, and neither is accepted as a parameter on it.

## Support Attestations in Exports

Releases can carry per-component **support attestations** -- the manufacturer's statement of
how long a component is supported, and at what level. Where an attestation exists, ReARM can
weave it into the SBOM it serves, so a reviewer reads the support position out of the artifact
itself rather than out of the UI.

This affects the **merged SBOM export**, the **single-artifact CycloneDX download** and the
**SPDX-augmented download**. It is controlled by one organization setting, and it is **off by
default**.

### Turning it on

**Organization Settings -> Support disclosure export.** This is the organization-wide default and
the ceiling: while it is off, exports carry no support facts at all, whatever the coverage gauge on
the release page says, and an individual export cannot opt back in (see
[Per-export metadata options](#per-export-metadata-options)). The gauge
states the setting beside the coverage figure for exactly this reason: full attestation coverage
and an export that carries none of it are not a contradiction, they are the default.

### What a consumer sees

Two layers, both emitted together and both governed by that single setting.

**Component properties**, on each attested component:

| Property | Meaning |
|---|---|
| `reliza:support:levelOfSupport` | The attested level. Never emitted without `reliza:support:assessedAt` beside it |
| `reliza:support:assessedAt` | When the assessment was made (not when the row was written) |
| `reliza:support:status` | Derived from the milestone dates and the current date |
| `reliza:support:justification` | The stated basis for the claim |
| `reliza:support:party` | Whether the manufacturer is a first or third party to the component |
| `reliza:support:source:<milestone>` | Where each milestone date came from |
| `cdx:lifecycle:milestone:endOfSupport` (and `endOfLife`, `endOfGuaranteedSupport`) | Standard CycloneDX milestone dates |
| `reliza:support:selfComponent` | `true` on the organization's own release or artifact listed in the export: its row is a root, or ReARM's merge wrote the node as a release's root and the organization lists its purl nowhere as a component. The fda support checks of `rearm bomutils score` do not judge it |

**A `declarations` block** at the document level, in CycloneDX's own attestation vocabulary:

- `claims[]` -- what was attested about which component. A claim targets a component's
  `bom-ref`; where a component arrived without one, ReARM assigns a namespaced
  `reliza:bomref:` identifier so the claim has something to point at, and removes it again on
  any export that does not carry the claim.
- `evidence[]` -- the attested value, the assessment instant, and the person who recorded it.
  Milestone dates appear here too, each with its own assessment instant.
- `assessors[]` -- the assessing organization and whether the assessment is third-party.
- `attestations[]` -- the join between an assessor and the claims it made, so a reader can tell
  who asserted what.

This requires CycloneDX 1.6; a BOM served at an earlier spec version carries the properties only.

The two layers describe the same facts under the same names **and the same values**, so a
consumer can reconcile them: `reliza:support:party` reads `FIRST_PARTY`/`THIRD_PARTY` in both.
The CycloneDX party role (`manufacturer`/`supplier`) appears only where it belongs, on the
assessor.

### The disclosure marker

A JSON BOM ReARM serves carries `reliza:support:disclosure` on `metadata` **unless whoever
requested it declined the support disclosure**. When the marker is there it has one of two values.
**Read it before drawing a conclusion from an absent property:**

| Value | Meaning |
|---|---|
| `derived-non-attested-current-state` | Support facts were injected. A component with no support property has no attestation on file |
| `provenance-stripped-no-disclosure` | **Nothing was asserted.** The document says nothing about support -- including about components that DO have an end-of-support date recorded |
| *no marker at all* | The requester asked for a document without the support disclosure (`includeSupportMetadata: false`), so there is no support statement for a marker to qualify. Also the case for the raw download |

With `derived-non-attested-current-state` the document also carries three counts in
`metadata.properties`, as decimal strings, written even when 0. A component is identified when it
has a purl, or a purl-shaped `bom-ref` (some tools, Trivy for Go's `stdlib` for example, put the
purl only there), or a cpe. Every component node is counted, nested ones and `metadata.component`
included.

| Property | Meaning |
|---|---|
| `reliza:support:unidentifiedComponents` | How many components could not be identified, so no support fact can be matched to them |
| `reliza:support:unassessedComponents` | How many components were identified but carry no support property, because nobody has assessed them (or the assessment was withdrawn), and are not the organization's own |
| `reliza:support:selfComponents` | How many components are the organization's own; they are in neither other count |

None of them appears with `provenance-stripped-no-disclosure` or without a marker.

Reading the second as the first is how a reviewer concludes a device has no out-of-support parts
when it does, and reading the third as either is how they conclude the server made a claim it did
not. The values are deliberately distinct so that absence and silence cannot be confused.

The marker is about **who decided**, not about what the document contains. An export the
*organization* excluded support from is marked, because its reader had no part in that policy and
is owed the answer to "why is there no support data here?". An export whose own requester declined
it is not, because the person holding the file is the one who asked for it that way. Both are
stripped identically -- only the statement differs.

Note that the export modal now defaults the support switch **off**, so a document downloaded from
the UI without touching it is the declined case and carries no marker. When the release's
organization setting is off the switch is greyed out and sends nothing, so that document is marked
`provenance-stripped-no-disclosure`. Scripted callers that omit
the argument are unaffected: omission means "the organization decides" and is marked as before.

Support facts are **derived current state, not a frozen attestation**: they are computed when the
document is served, so the same release exported on two dates can report different
`reliza:support:status` values from unchanged underlying dates.

### Uploaded BOMs cannot forge these

The `reliza:support:*` and `reliza:device:*` property namespaces and the `declarations` block are
**server-owned**. On every egress above -- and on the raw artifact download, and whether or not
the setting is on -- ReARM strips any of them found in an uploaded BOM before serving it. Their
presence in a document ReARM served therefore means ReARM put them there.

The strip is deliberately not tied to the setting: it is a security control, while injection is a
content choice. An organization that has never turned injection on is still protected from an
uploaded BOM carrying a forged attestation under its name.

The CSV and Excel media types of the SBOM export do not carry component properties at all, so no
support facts appear in them.

## VDR Export

Exports vulnerability disclosure data as a [CycloneDX 1.6 VDR](https://cyclonedx.org/capabilities/vdr/).

### Snapshot Options

VDR exports can optionally be scoped to a historical point in time:

| Option | Description |
|---|---|
| **Include Suppressed** | Include findings in `FALSE_POSITIVE` or `NOT_AFFECTED` state |
| **Up To Date** | Snapshot findings as of a specific date |
| **Target Lifecycle** | Snapshot findings as of when the release reached a specific lifecycle stage |
| **Target Approval** | *(ReARM Pro)* Snapshot findings as of a specific approval entry |

When no snapshot option is set, the export reflects the current state of findings.

### Output Formats

- **JSON** — CycloneDX 1.6 VDR JSON file
- **PDF** — Formatted vulnerability report PDF

## VEX Export

Exports a [Vulnerability Exploitability eXchange](https://www.cisa.gov/sites/default/files/2023-04/minimum-requirements-for-vex-508c.pdf) document. Where the VDR enumerates every known vulnerability, the VEX is the negative-advisory complement: it tells consumers which CVEs **do not affect** this release and why, and which previously-known CVEs are now **fixed**.

### Output Formats

| Format | Description |
|---|---|
| **CycloneDX 1.6 VEX (JSON)** | CycloneDX VEX-only BOM — vulnerabilities with `analysis` blocks, no component tree |
| **OpenVEX 0.2.0 (JSON)** | [OpenVEX](https://openvex.dev/) statements, strict L15-compliant |

### Statement Selection

Each finding's [analysis state](./auditing-findings#analysis-states) maps to VEX as follows:

| Analysis State | CDX `analysis.state` | OpenVEX `status` |
|---|---|---|
| `EXPLOITABLE` | `exploitable` | `affected` |
| `NOT_AFFECTED` | `not_affected` | `not_affected` |
| `FALSE_POSITIVE` | `false_positive` | `not_affected` |
| `RESOLVED` | `resolved` | `fixed` |
| `IN_TRIAGE` | `in_triage` | `under_investigation` |

`NOT_AFFECTED`, `FALSE_POSITIVE`, and `RESOLVED` are **always included** so consumers see decided non-actionable findings. `IN_TRIAGE` (and findings with no analysis at all) are **excluded by default** per CISA guidance — toggle **Include In-Triage** to include them.

### Historically Resolved Findings

VEX exports also include CVEs that affected **prior versions** on the same branch but are no longer present. ReARM walks the release's branch lineage (and fork-point ancestry) and emits any CVE that disappeared along the way as `resolved` (CDX) / `fixed` (OpenVEX). For Product releases the walk recurses through child component releases.

Each historically-resolved CDX entry carries two ReARM properties:

- `rearm:vex:resolvedInRelease` — UUID of the release where the CVE was last present
- `rearm:vex:resolvedInVersion` — version string of that release

This produces a complete remediation history and pre-empts the consumer question "why did this CVE disappear from your VEX?".

### Snapshot Options

VEX exports can optionally be scoped to a historical point in time:

| Option | Description |
|---|---|
| **Current State** | No historical filter (default) |
| **By Date** | Statements as of a specific date and time |
| **First Scanned** | Statements as of when this release was first scanned (estimated from artifact creation + 6 hours when no firstScanned timestamp is recorded) |
| **By Lifecycle** | Statements as of when the release reached a specific lifecycle stage |
| **By Approval** | *(ReARM Pro)* Statements as of a specific approval entry |

Snapshots apply to both current-state and historically-resolved entries.

### Output Naming

Files are named `<release-uuid>-vex[-<snapshot-suffix>].cdx.json` for CycloneDX or `.openvex.json` for OpenVEX.

## OBOM Export

Exports the Operational BOM derived from the release's outbound deliverable. No configuration options — click **Export OBOM** to download.

## BOV / Findings Export (Findings Modal)

Findings can be exported from the **Findings Modal** in two formats:

- **PDF** — formatted findings report (default)
- **CycloneDX 1.6 (JSON) BOV** — a [CycloneDX 1.6 BOV](https://cyclonedx.org/capabilities/bov/) assembled client-side from the currently displayed findings

Select the desired format in the modal before clicking **Export**.

### BOV-specific options

When **CycloneDX 1.6 (JSON) BOV** is selected, additional options become available:

| Option | Description |
|---|---|
| **Include Suppressed** | Include `FALSE_POSITIVE` / `NOT_AFFECTED` findings |
| **Include Analysis** | Embed the current triage state for each vulnerability |

Violations and Weaknesses are not included in the BOV — the spec covers only vulnerabilities.

## Download Log

The **Download Log** (accessible under **Organization Settings → Download Log**, visible to org admins only) records SBOM, VDR, and VEX download events in your organization. Each entry shows:

- **Download type** (`SBOM_EXPORT`, `VDR_EXPORT`, `VEX_EXPORT`)
- **Metadata options** -- the two per-export flags exactly as the caller sent them. An omitted
  argument is recorded as absent rather than as `false`, so an auditor reconstructing what a
  submitted document contained can tell "the default applied" from "the caller asked for less"
- **Subject** — the release the export was generated from, linked to its release page
- **Config details** — the export parameters used (structure, media type, snapshot options, etc.)
- **Downloaded by** — the user who triggered the export
- **IP address** and **timestamp**

> **Scope:** The Download Log only records server-side exports from the Release view (SBOM, VDR, VEX). Client-side exports such as BOV and PDF are not logged, nor are OBOM exports.

The log is useful for compliance audits to demonstrate when and how vulnerability or SBOM data was accessed.
