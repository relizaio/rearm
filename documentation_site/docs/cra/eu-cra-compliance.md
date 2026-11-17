# EU CRA Compliance

The EU [Cyber Resilience Act](https://eur-lex.europa.eu/eli/reg/2024/2847/oj) (Regulation (EU) 2024/2847, "CRA") sets cybersecurity requirements for products with digital elements placed on the EU market. These include SBOMs, vulnerability handling, security updates for a declared support period, and technical documentation kept for at least 10 years.

The [OpenChain CRA Compliance Requirements & Checklist v1.0](https://github.com/OpenChain-Project/CRA-Compliance/blob/main/1.0/CRA_Checklist_Requirement_v1.0.md) is a community-maintained readiness checklist for these obligations, published by the OpenChain Project under CC-BY-4.0. This page maps its items to what ReARM provides today.

::: warning Not legal advice
ReARM is a tool for producing, keeping and sharing CRA evidence. Using it does not by itself make a product CRA compliant, and it is not a conformity assessment. Many checklist items are organizational: policies, roles, the risk assessment itself, the EU Declaration of Conformity and CE marking. Those remain your organization's responsibility. Consult qualified counsel for regulatory decisions.
:::

Coverage levels used below:
- **Supported**: ReARM provides the capability the item asks for.
- **Partial**: ReARM provides part of it; the row says which part, and the rest is listed under [Future work](#future-work).

## SBOMs and component management (checklist section 3)

| Checklist item | Coverage | How ReARM helps |
|---|---|---|
| 3.1.1–3.1.4 Machine-readable SBOM for every release, in SPDX or CycloneDX, produced by the pipeline | Supported | CI integrations attach the SBOMs your build generates to each release ([GitHub Actions](../integrations/githubActions), [GitLab](../integrations/gitlab), [Azure DevOps](../integrations/ado), [Jenkins](../integrations/jenkins), [ReARM CLI](../integrations/rearmcli)). ReARM merges them and exports the release SBOM as CycloneDX or SPDX ([Exporting Compliance Artifacts](../workflows/exporting-compliance-artifacts)). |
| 3.1.5 Know within 24 hours whether an actively exploited CVE affects a shipped product | Supported | [Supply Chain Forensics](../workflows/supply-chain-forensics) finds every release that ships a component, version or CVE. |
| 3.1.6 SBOM format and top-level dependencies; organization SBOM profile with product- and component-level metadata | Supported | ReARM writes product-level metadata (name, version, supplier, identifier, hash where a deliverable is hashed, declared licence) into the release and product nodes of the export. Component-level fields come from the SBOMs your build uploads and are kept as uploaded. [SBOM scoring](../workflows/sbom-scoring) against the CISA 2026 profile shows which of them are missing. |
| 3.1.7 Hardware Bill of Materials alongside the SBOM | Supported | HBOMs are first-class release artifacts. ReARM parses an uploaded CycloneDX HBOM into the release's hardware components (device and firmware nodes), and hardware deliverables carry CycloneDX-aligned production metadata: parties and their roles, country of origin, and manufacture date. |
| 3.2.1, 3.2.3 SBOM completeness gate and minimum-field validation before release | Supported | [SBOM scoring](../workflows/sbom-scoring) checks an SBOM against the CISA 2026 and FDA profiles in the UI or from the CLI, whose exit status can serve as a CI gate. |
| 3.2.4 SBOM quality metrics tracked over time | Partial | Scores are calculated on request. Keeping a history of scores to track quality over time is future work. |
| 3.3.1 Verified source information | Supported | Each release records its source repository and commit. |
| 3.3.2 Checksums of release artifacts | Supported | Deliverables are recorded with their digests, for example container image digests. |
| 3.3.3 Track component changes between releases | Supported | Release changelogs show SBOM component changes (added, removed and updated components) and finding changes between releases. |
| 3.3.4 Signing or attestation of release artifacts | Supported | CI signs release artifacts with [Cosign and Sigstore](../integrations/githubActionsCosign), and ReARM stores the signature, certificate and signed payload with the release, alongside any attestations you upload. ReARM [signs exported SBOMs](../workflows/signing-exported-sboms) so consumers can verify them, and [verifies commit signatures](../workflows/committers). |
| 3.3.10 Chain of custody from source commit to released artifact | Supported | Each release links its source commit (with signature verification), the build that produced it, and its deliverables with their digests and signatures, giving a traceable record from commit to shipped artifact for post-incident analysis. |
| 3.4.5, 3.4.8 SAST on every release candidate, with findings resolved or risk-accepted; security testing evidence retained in the Technical File | Supported | The ReARM GitHub Action can run CodeQL in the build, and SARIF from any SAST or DAST tool can be attached to a release. Results become Weakness findings (CWE-based, with severity) that are triaged and risk-accepted through [finding analysis](../workflows/auditing-findings). The SARIF, test reports and pentest reports are retained with the release as part of its record. |
| 3.4.9, 7.1.2 Threat model and cybersecurity risk assessment kept current for the version on the market | Supported | Threat models and risk assessments are stored as typed release artifacts (`THREAT_MODEL`, `RISK_ASSESSMENT`), so each version carries the documents that apply to it. |
| 3.4.10, 3.4.12 Release security gate; no known exploitable vulnerabilities at release | Supported | Action guards are CEL rules set per organization, perspective or component. In BLOCK mode they refuse a release's promotion through its lifecycle, or its approvals, while a condition such as open critical findings holds; in WARN mode they record the failure. VEX triage separates exploitable findings from not-affected ones. [Approval policies](../configure/approval-queues) (ReARM Pro) add required sign-offs. |
| 3.6.1, 9.1.3 Inventory of third-party software; supplier SBOMs | Supported | Third-party software is tracked as external components with their own releases, the SBOMs you receive, and supplier VEX [imported](../workflows/importing-vex) with its issuer class. Exports mark component releases that came without an SBOM. |

## Vulnerability handling (checklist section 4)

| Checklist item | Coverage | How ReARM helps |
|---|---|---|
| 4.1.1, 4.1.2 Continuous monitoring of shipped components during the support period | Supported | The [Dependency-Track integration](../integrations/dtrack) re-checks every release's components for new vulnerabilities, and ReARM keeps the results per release. |
| 4.1.3 Triage intake within a documented SLA | Partial | [Notifications](../configure/notifications) to Slack, Microsoft Teams, Microsoft Sentinel (ReARM Pro) and other channels when a new vulnerability affects your releases, and triage through [finding analysis](../workflows/auditing-findings). Vulnerability records show when each vulnerability was first seen, and KEV entries show CISA's remediation due date. Triage SLAs and overdue tracking are future work. |
| 4.1.4, 4.1.6 EPSS and KEV enrichment; a KEV listing triggers assessment | Supported | Findings carry EPSS and KEV data, and a CVE newly added to the KEV catalog raises a [notification](../configure/notifications). |
| 4.1.8 Vulnerability KPIs | Partial | The dashboard charts findings over time across releases. Mean-time-to-detect and mean-time-to-remediate reporting is future work. |
| 4.2.1, 4.3.5 Exploitability assessment and a vulnerability register | Supported | [Finding analysis](../workflows/auditing-findings) records state, justification and response per vulnerability, with history, scoped to a release, component or organization. |
| 4.2.2–4.2.4 Machine-readable, versioned VEX | Supported | VEX export as CycloneDX 1.6 VEX or OpenVEX 0.2.0 per release, plus a CycloneDX VDR. Supplier VEX can be [imported](../workflows/importing-vex). |

## Support period, Technical File and sharing (checklist sections 6–7)

| Checklist item | Coverage | How ReARM helps |
|---|---|---|
| 6.1.1, 6.1.4 Declared support period and end-of-support communication | Supported | Releases carry end-of-support and end-of-life dates with their provenance. These are published as Common Lifecycle Enumeration (CLE) events, as CycloneDX `endOfSupport` / `endOfLife` milestones in SBOM exports, and in the support documents ([Exporting Compliance Artifacts](../workflows/exporting-compliance-artifacts)). |
| 7.1.1, 7.1.3 Technical File that can be produced on request | Supported | Each release holds its Technical File contents as typed artifacts: SBOMs, HBOMs, VEX and VDR, SARIF and test reports, threat model, risk assessment, certifications, development and user documentation. All are exportable on request. [Regulatory packages](../workflows/regulatory-packages) (ReARM Pro) assemble them into submission packages; FDA premarket packages are supported today. |
| 7.1.4 Technical File SBOMs are the pipeline's own artifacts | Supported | The SBOMs stored are the ones your CI uploaded, not transcriptions. |
| 7.1.5 Retention of at least 10 years | Supported | Artifacts are stored with releases for long-term retention. |
| 7.2.1, 7.2.2, 7.2.4 SBOMs and advisories for authorities, customers and integrators | Supported | The [Transparency Exchange API](../tea/) serves published SBOMs and other artifacts to the parties you authorize. VEX and VDR exports cover exploitability statements. |

## Currently outside ReARM's scope

ReARM keeps these documents with each release (risk assessment, declaration of conformity, security.txt and others) and provides the affected-release and VEX data Article 14 reports draw on; drafting and submitting them stays with your organization.

- **Organizational items:** CRA policy, roles, training, the conformity-assessment route, EU Declaration of Conformity, CE marking and authorised representative (checklist sections 2 and 7.3–7.4).
- **CRA Article 14 reporting** to the Single Reporting Platform, and the reporting RACI (4.4–4.5). ReARM provides the affected-release data and VEX/VDR content those reports draw on; the submissions themselves are made by your organization.
- **Product security properties** under Annex I Part I (3.4.1–3.4.4, 3.4.13–3.4.21) and the secure update mechanism (6.1.3). These are properties of your product, not of release tooling.

## Future work

CRA coverage will be extended in future ReARM versions. Pending items:

- the EU Vulnerability Database (EUVD) as a monitored vulnerability feed (4.1.5);
- a BSI TR-03183 SBOM scoring profile, alongside the CISA 2026 and FDA profiles;
- SBOM quality history, to track scores over time (3.2.4);
- triage SLAs with overdue tracking, and remediation-time KPIs (4.1.3, 4.1.8);
- a CRA Technical File regulatory package, alongside the FDA premarket package;
- a drafted CRA document bundle: templates for the risk assessment, EU Declaration of Conformity, vulnerability disclosure policy and Article 14 reports, filled from the data ReARM already holds.

This page will be updated as these capabilities are added.
