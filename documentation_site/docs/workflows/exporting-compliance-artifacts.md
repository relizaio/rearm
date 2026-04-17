# Exporting Compliance Artifacts

## Description

ReARM provides structured exports for the compliance artifacts attached to your releases. Four export types are available:

| Export | Format | Source | Where |
|---|---|---|---|
| **SBOM** | CycloneDX JSON / CSV / Excel, SPDX | Merged release SBOM via Rebom | Release view |
| **VDR** | CycloneDX 1.6 JSON or PDF | Vulnerability data from release metrics | Release view |
| **OBOM** | CycloneDX JSON | Operational BOM from outbound deliverable | Release view |
| **BOV** | CycloneDX 1.6 JSON or PDF | Findings data from the Findings Modal | Findings Modal |

All exports except BOV are server-generated. BOV is assembled client-side from the currently displayed findings.

## Accessing Exports

Open any release and click the **download icon** in the release header. A modal opens with tabs for **SBOM**, **VDR**, and **OBOM**.

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

Click **Export** to download the file.

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

The **Download Log** (accessible under **Organization Settings → Download Log**, visible to org admins only) records SBOM and VDR download events in your organization. Each entry shows:

- **Download type** (`SBOM_EXPORT`, `VDR_EXPORT`)
- **Subject** — the release the export was generated from, linked to its release page
- **Config details** — the export parameters used (structure, media type, snapshot options, etc.)
- **Downloaded by** — the user who triggered the export
- **IP address** and **timestamp**

> **Scope:** The Download Log only records downloads that go through the OCI or Rebom service (i.e., server-side SBOM and VDR exports from the Release view). Client-side exports such as BOV and PDF are not logged, nor are OBOM exports.

The log is useful for compliance audits to demonstrate when and how vulnerability or SBOM data was accessed.
