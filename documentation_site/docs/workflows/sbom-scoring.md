---
sidebarDepth: 2
---

# Scoring an SBOM for CISA 2026 and FDA Readiness

ReARM can check an SBOM against the CISA 2026 minimum elements and the FDA premarket SBOM
expectations, and show you, check by check, what the document has and what it is missing. The
check runs on the document you would hand over: the release BOM export, or a BOM artifact as
ReARM serves it. Nothing is stored: each score is taken when you ask for it.

SBOM scoring reaches a ReARM CE installation at its next sync from Pro; until then the buttons
answer "SBOM scoring is not available on this server".

## What a score is

A score report has one section per **profile** (a published list of required SBOM content). For
each profile it gives:

- a **verdict**: **NOT READY** when at least one REQUIRED check fails; otherwise **UNKNOWN** when a
  REQUIRED check could not be evaluated (an engine error), so the answer is not known; otherwise
  **READY**;
- a **score** from 0 to 100: over the REQUIRED checks that passed or failed, the average share of
  what passed (for a component check, the share of components that have the field), rounded
  down, so 100 means every such check passed in full; shown as "—" when there is no such check;
- the **checks**, each with a status:
  - **PASS**: the document has what the check asks for;
  - **FAIL**: it does not, for the document or for the components listed;
  - **NOT ASSESSED**: the check cannot be decided from the document alone (for example a practice
    of the SBOM author, or something the format cannot represent); it counts neither for nor
    against the score;
  - **ERROR**: the engine could not evaluate the check.

Every check has a **level**: **REQUIRED** checks decide the verdict and the score, **INFO** checks
are reported for your information only and never change either.

## The profiles

ReARM scores both profiles on every request.

| Profile | What it checks | Source |
|---|---|---|
| `cisa-2026` | The 17 data fields of the CISA 2026 Minimum Elements, Table 1 | CISA et al., [2026 Minimum Elements for a Software Bill of Materials (SBOM)](https://www.cisa.gov/sites/default/files/2026-07/2026_cisa_sbom_minimum_elements_508c.pdf), version 2.1, 2026-07-29 |
| `fda` | The NTIA 2021 baseline attributes (author, timestamp, supplier, component name, version, hash, unique identifier, relationship) plus each component's **level of support** and **end-of-support date** | FDA, [Cybersecurity in Medical Devices: Quality Management System Considerations and Content of Premarket Submissions](https://www.fda.gov/media/119933/download), section V.A.4(b), 2026-02-03; baseline from NTIA, [Framing Software Component Transparency, second edition](https://www.ntia.gov/files/ntia/publications/ntia_sbom_framing_2nd_edition_20211021.pdf), section 2.2, 2021-10-21 |

The FDA level of support and end-of-support date are read from the properties ReARM writes into
an export when it includes support metadata (`reliza:support:levelOfSupport` and
`cdx:lifecycle:milestone:endOfSupport`). An SBOM from another tool, or an export without support
metadata, fails those two checks until the components are assessed in ReARM and exported with
support metadata.

## Where to score

### The release BOM export

Open a release, click the export icon, choose **SBOM** and the **CycloneDX 1.6 (JSON)** format, set
the options as you would for the export, and click **Score** (beside **Export**). The report opens
in the same dialog.

The score is of exactly the document **Export** would give you with the same options: SBOM
configuration, structure, top-level dependencies only, ignore optional dependencies, the coverage
type filter and the support and internal metadata options all change the scored document. Scoring
applies to JSON SBOM exports only; for CSV and Excel the button is disabled. If you change an
option after scoring, the dialog says "Options changed; score again" until you score again.

### A BOM artifact

In a release's artifact tables, a downloadable artifact of type BOM has a **Score SBOM** icon beside
its download icon. It scores the artifact's **augmented document, latest version** (the document
ReARM serves as the augmented download) and opens the report in its own dialog.

Both need the same permission as downloading the document: if you can download it, you can score
it.

## Reading the report

The header names the document's format, spec version and serialization and how many components it
has. Under it is one tab per profile with the verdict, the score, a summary line ("9 passed, 1
failed, 1 not assessed") and a link to the source document.

The check table lists failed checks first, then errors, then not assessed, then passed; REQUIRED
before INFO. Its columns:

| Column | Meaning |
|---|---|
| **Status** | PASS, FAIL, NOT ASSESSED or ERROR |
| **Check** | The check's title and its id; for a failed check the engine's note and the **remedy**, for a check not assessed the reason, for an error the engine's message |
| **Level** | REQUIRED or INFO |
| **Result** | Passed out of total (components for a component check, 1 for a document check); "—" when not assessed or in error |
| **Reference** | The section of the source document the check comes from |

A failed component check expands to the list of failing components (the purl or name of each,
sorted). The engine lists at most 20; a longer list is cut there and says "list truncated by the
engine", while the Result column still counts every component.

**Structure checks**, collapsed below the profiles, report on the document itself (for example
whether every component is in the dependency graph). They are run on CycloneDX documents only and
do not affect any profile's verdict or score.

When the document was scored with file components skipped (`--skip-files` on the command line), the
header says how many file components were skipped and not counted.

**Download JSON** saves the report exactly as the engine wrote it; **Copy JSON** copies the same
text. It is the same JSON report the command line writes.

## Errors

| You see | Meaning |
|---|---|
| The server does not know the profiles this UI asked for | The server is older than this UI; update the server |
| This SBOM is too large to score on this server | The document is over the scoring service's size limit; export a smaller document |
| The scoring engine cannot score this document | A format or spec version the engine does not read |
| This artifact is not a scorable SBOM | The artifact is not of type BOM, or has no stored SBOM |
| Scoring took longer than 3 minutes and was stopped | Scoring is limited to 3 minutes; try again, or export a smaller document (top level only, exclude dev) |
| SBOM scoring is not available on this server right now | The scoring engine is missing on this server's platform (for example arm64) or the scoring service is down |
| The scoring engine failed | The engine stopped with an error; try again |
| You are not authorized to score this SBOM | You lack the permission to download this document |
| SBOM scoring is not available on this server | This server does not have SBOM scoring (an older version) |
| unsupported report version N; update ReARM UI | The server's engine writes a newer report than this UI reads; the raw JSON can still be downloaded |

## From the command line

The same engine is in the [ReARM CLI](../integrations/rearmcli), and runs locally with no server
and no credentials:

```bash
rearm bomutils score -f <sbom file> --profile cisa-2026 --profile fda --format json
```

- `-f` the SBOM file (CycloneDX JSON or XML 1.0 to 1.7, SPDX JSON, YAML or tag-value 2.1 to 2.3);
  without it, standard input;
- `--profile` repeatable: `cisa-2026` (the default), `ntia-2021` or `fda`;
- `--format` `text` (the default) or `json`; the JSON report is the document the UI shows;
- `--fail-on-not-ready` exits 3 when a requested profile is NOT_READY or UNKNOWN (the report is
  still written), for use as a CI gate;
- `--skip-files` (from the release of the CLI that adds it) leaves `type: file` components out of
  the per-component checks and records that in the report.

Exit codes: 0 scored, whatever the verdict; 1 unsupported or unparsable input; 2 unknown
`--profile` or `--format` value; 3 with `--fail-on-not-ready`, as above.

## What is not assessed

Some requirements cannot be decided from one file, and the report says so rather than guessing:

- the CISA 2026 practices (accommodation of updates, coverage, distribution and delivery,
  explicitly identifying unknowns, frequency, machine-processable data) are practices of the SBOM
  author, always NOT ASSESSED;
- the FDA expectation on known vulnerabilities and their assessment is delivered as a VDR or VEX
  with the submission, not in the SBOM: always NOT ASSESSED;
- fields a format cannot represent: in SPDX 2.x the SBOM author signature, generation context and
  SBOM version (CISA 2026) and the level of support (FDA); an SPDX 2.x document is therefore
  NOT READY under both profiles by the documents' own terms;
- an SBOM author signature is checked for presence only; it is not verified.
