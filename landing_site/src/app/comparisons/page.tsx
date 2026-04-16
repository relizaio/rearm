import type { Metadata } from "next";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata: Metadata = {
  title: "Comparisons - ReARM by Reliza",
  description: "See how ReARM compares to Dependency-Track, SCA tools, GUAC, Chainloop, and SBOM management tools - and how it differs from each by operating at the release governance layer.",
  alternates: { canonical: `${baseUrl}/comparisons/` },
  openGraph: {
    title: "Comparisons - ReARM by Reliza",
    description: "See how ReARM compares to Dependency-Track, SCA tools, GUAC, Chainloop, and SBOM management tools - and how it differs from each by operating at the release governance layer.",
    url: `${baseUrl}/comparisons/`,
    type: "website",
    siteName: "ReARM - Release Governance Platform for the Agentic Era by Reliza",
  },
};

type ComparisonSection = {
  id: string;
  title: string;
  intro: string;
  leftLabel: string;
  rightLabel: string;
  points: { left: string; right: string }[];
};

const sections: ComparisonSection[] = [
  {
    id: "rearm-vs-dependency-track",
    title: "ReARM vs Dependency-Track 4",
    intro: "Dependency-Track is a great open-source tool for continuous SBOM analysis and vulnerability monitoring. ReARM integrates with Dependency-Track and builds on top of it. Dependency-Track tells you what is risky in an SBOM. ReARM tells you whether a release is allowed to ship, why, and what evidence supports that decision years later.",
    leftLabel: "ReARM",
    rightLabel: "Dependency-Track 4",
    points: [
      {
        left: "Stores raw BOMs in both CycloneDX and SPDX formats, VEX, VDR, BOV, SARIF, signatures, attestations, build metadata, and any other artifacts per release.",
        right: "Ingests CycloneDX SBOMs and performs vulnerability and policy violation analysis. Does not preserve raw artifacts.",
      },
      {
        left: "Release-centric structure with versioned releases stored within Product-Component model with full release history, audit trail, and provenance details for each artifact.",
        right: "SBOM-centric flat project structure.",
      },
      {
        left: "Highly configurable auto-integration engine aggregating findings from multiple sources and from components into products",
        right: "Limited single-parent hierarchy.",
      },
      {
        left: "Own finding audit engine with various scopes (organization-wide, product-level, feature set-level, component-level, branch-level, release-level). Supports all findings, including those from Dependency-Track and other sources.",
        right: "Limited audit capabilities supporting SBOM-level only scope for Dependency-Track findings only.",
      },
      {
        left: "Approval and lifecycle management for releases (ReARM Pro).",
        right: "No release approval workflows.",
      },
      {
        left: "Supports its own SBOM augmentation and enrichment logic (via Reliza's BEAR project). Stores augmented and enriched SBOMs alongside original raw SBOMs.",
        right: "Relies on SBOM as provided.",
      },
      {
        left: "Proprietary deduplication logic for SBOM data and findings significantly improves operability and reduces infrastructure footprint. ReARM tolerates full outage and data loss on Dependency-Track with an option to rebuild based on ReARM data.",
        right: "Limited deduplication logic for findings only. Significantly larger infrastructure footprint required.",
      },
      {
        left: "Rich changelog capabilities, including findings over time, and changes over time.",
        right: "Shows current view with changelog represented only in graphs.",
      }
    ],
  },
  {
    id: "rearm-pro-vs-rearm-ce",
    title: "ReARM Pro vs ReARM CE",
    intro: "ReARM Community Edition is a fully functional FOSS version. ReARM Pro adds managed infrastructure, premium support, and advanced features for teams and enterprises.",
    leftLabel: "ReARM Pro",
    rightLabel: "ReARM CE",
    points: [
      {
        left: "Managed service with SSO - no infrastructure to maintain. Option for on-premise (Standard and Enterprise plans) or air-gapped deployment (Enterprise plan).",
        right: "Self-hosted - you manage your own infrastructure.",
      },
      {
        left: "Premium support (up to 24x7 with 1 hour response time depending on plan).",
        right: "Community support via Discord and GitHub.",
      },
      {
        left: "Managed Dependency-Track instance included.",
        right: "Self-managed Dependency-Track integration.",
      },
      {
        left: "Approval and event workflows for release lifecycle, marketing release workflow.",
        right: "Core BOM storage functionality and retrieval without approval or marketing release workflows.",
      },
      {
        left: "Reliza-managed SBOM enrichment via BEAR.",
        right: "Option to self-manage SBOM enrichment.",
      },
      {
        left: "Support for perspectives (all plans) and multi-organization workflow (Standard and Enterprise plans).",
        right: "Single organization and single perspective only.",
      },
      {
        left: "Future ReARM Pro functionality when it becomes available.",
        right: "ReARM CE remains the FOSS version with core features.",
      },
    ],
  },
  {
    id: "rearm-vs-guac",
    title: "ReARM vs GUAC",
    intro: "GUAC (Graph for Understanding Artifact Composition) is an open-source project by OpenSSF that aggregates software security metadata into a graph database for querying. GUAC is a graph for understanding supply chain relationships. ReARM is a release governance product for operating and approving releases in production environments.",
    leftLabel: "ReARM",
    rightLabel: "GUAC",
    points: [
      {
        left: "Release-centric evidence store: artifacts are stored per versioned release with full provenance, audit trail, and lifecycle management.",
        right: "Graph-based aggregation engine: ingests metadata from multiple sources into a queryable knowledge graph.",
      },
      {
        left: "Stores raw artifacts (SBOMs, VEX, VDR, SARIF, attestations, signatures) compressed for 10+ years with full traceability.",
        right: "Ingests and normalizes metadata but does not preserve original raw artifacts.",
      },
      {
        left: "Product-Component model with multi-level nesting, automated bundling, and configurable auto-integration engine.",
        right: "Flat graph structure linking artifacts, packages, and vulnerabilities without a product/release hierarchy.",
      },
      {
        left: "Built-in finding audit engine with scoped auditing (organization, product, component, branch, release levels).",
        right: "Provides graph queries to explore relationships between artifacts and vulnerabilities but no built-in audit workflow.",
      },
      {
        left: "Production-ready platform with managed service option (ReARM Pro), UI, approval workflows, and premium support.",
        right: "Research-oriented project primarily offering a CLI and API. No managed service or built-in UI for end users.",
      },
      {
        left: "Integrates with Dependency-Track for continuous vulnerability monitoring with proprietary deduplication and changelog tracking.",
        right: "No continuous monitoring workflow.",
      },
      {
        left: "Supports OWASP Transparency Exchange API (TEA) and VDR export in CycloneDX and PDF formats.",
        right: "Focuses on GUAC ontology and CertifyVuln/CertifyGood graph predicates. No TEA or VDR export support.",
      },
      {
        left: "SBOM enrichment and augmentation via Reliza's BEAR integration, storing enriched SBOMs alongside originals.",
        right: "Integrates with multiple data sources (deps.dev, OSV, SLSA) for graph enrichment. Enriches graph data by correlating multiple sources but does not produce enriched SBOMs.",
      },
    ],
  },
  {
    id: "rearm-vs-sca-tools",
    title: "ReARM vs Traditional SCA Tools",
    intro: "Traditional Software Composition Analysis (SCA) tools like Semgrep, Snyk, Black Duck (Synopsys), Checkmarx, Mend (WhiteSource), and Sonatype focus on scanning and finding vulnerabilities. SCA tools find issues. ReARM governs the release. ReARM is not an SCA tool - it is a Release Governance Platform that integrates with SCA tools and turns their findings into governed release decisions.",
    leftLabel: "ReARM",
    rightLabel: "SCA Tools (Semgrep, Snyk, Black Duck, Checkmarx, Mend, Sonatype)",
    points: [
      {
        left: "Stores and versions all supply chain artifacts (SBOMs, SARIF, VEX, VDR, attestations) produced by any tool in a release-centric model.",
        right: "Generate point-in-time scan results and vulnerability reports.",
      },
      {
        left: "Tool-agnostic - ingests outputs from any SCA, SAST, or DAST tool.",
        right: "Typically, locked to their own scanning engine and data format.",
      },
      {
        left: "Release-centric model: artifacts are tied to specific versioned releases with detailed provenance within the release (release as a whole, source code entry, deliverable).",
        right: "Project or repository-centric scanning, often without release-level tracking.",
      },
      {
        left: "Long-term artifact retention (10+ years) and continuous monitoring for vulnerabilities, including for old releases, for compliance and audit.",
        right: "Focus on point-in-time scanning results.",
      },
      {
        left: "Aggregates findings from multiple tools into a unified view with changelogs.",
        right: "Each tool usually provides its own siloed view of vulnerabilities.",
      },
      {
        left: "Provides search capabilities across entire supply chain evidence base, such as identifying instances of specific dependency or vulnerability.",
        right: "Usually, limited to point-in-time scan results.",
      },
      {
        left: "Own finding audit engine with various scopes (organization-wide, product-level, feature set-level, component-level, branch-level, release-level). Supports all findings from various sources.",
        right: "Usually, limited to their own finding with no understanding of scopes.",
      },
      {
        left: "Product-level aggregation with multi-level component nesting.",
        right: "Typically, analyze individual repositories or container images.",
      },
    ],
  },
  {
    id: "rearm-vs-chainloop",
    title: "ReARM vs Chainloop",
    intro: "Chainloop describes itself as a governance layer for modern software delivery, focused on centralized guardrails, artifact management, real-time visibility, and compliance. ReARM provides collaboration platform that connects various stakeholders ivolved in the release management process. Chainloop governs delivery signals. ReARM governs release decisions.",
    leftLabel: "ReARM",
    rightLabel: "Chainloop",
    points: [
      {
        left: "Release-centric system of record: every artifact, approval, and finding is tied to an explicitly versioned release with full provenance and lifecycle history.",
        right: "Delivery-process governance layer: focuses on centralizing signals, attestations, and guardrails around the CI/CD workflow itself.",
      },
      {
        left: "Explicit Product-Component release hierarchy with multi-level nesting, automated bundling, and aggregated posture across product versions.",
        right: "Operates at the workflow and artifact-signal level.",
      },
      {
        left: "Rich multi-scoped changelog functionality, including code-level changes, SBOM component changes, vulnerability, violation and weakness changes.",
        right: "Changelog functionality as a part of release management is not the primary use case.",
      },
      {
        left: "Approval workflows and lifecycle management: releases move through configurable gates requiring stakeholder sign-off before promotion or deployment.",
        right: "Policy automation and guardrails enforced at delivery time, but no explicit multi-stakeholder release approval model.",
      },
      {
        left: "Long-term raw artifact retention (10+ years): SBOMs, VEX, VDR, SARIF, attestations, and build metadata stored immutably per release for regulatory compliance.",
        right: "Focuses on attestation collection and policy enforcement during delivery.",
      },
      {
        left: "Per-release security posture with historical snapshots: answer questions about any past version's vulnerability state at any point in time.",
        right: "Real-time visibility into delivery pipeline health; per-release security posture is not the primary use case.",
      },
      {
        left: "Deployment gating based on release approval status: CI/CD queries ReARM to confirm a release has passed all required gates before it can be promoted.",
        right: "Policy guardrails block non-compliant delivery pipelines, but release-level approval state is not the gate.",
      },
      {
        left: "FOSS Community Edition (ReARM CE) available for self-hosting with core functionality, including UI.",
        right: "Open-source core does not include UI.",
      },
    ],
  },
  {
    id: "rearm-vs-sbom-management-tools",
    title: "ReARM vs SBOM Management Tools",
    intro: "SBOM management tools such as Manifest, Cybeats, and Interlynk focus on SBOM generation, ingestion, enrichment, and supply chain visibility. These tools help you understand the supply chain. ReARM helps you control the release built from it.",
    leftLabel: "ReARM",
    rightLabel: "SBOM Management Tools (Manifest, Cybeats, Interlynk)",
    points: [
      {
        left: "Release governance and system of record: every release has an explicit approval state, lifecycle history, and evidence trail that determines whether it is allowed to ship.",
        right: "SBOM operations and visibility: focuses on SBOM ingestion, enrichment, inventory, risk aggregation, and supplier/upstream transparency.",
      },
      {
        left: "Active control plane: enforces policies, requires approvals, and gates deployments based on release status.",
        right: "Visibility layer: surfaces risks and provides transparency but does not gate deployments or manage release approvals.",
      },
      {
        left: "Product-Component release hierarchy: tracks how components compose into products across versioned releases with aggregated posture at every level.",
        right: "SBOM-centric aggregation; no explicit product/release versioning model or active release lifecycle management.",
      },
      {
        left: "Long-term raw artifact retention (10+ years): SBOMs, VEX, VDR, SARIF, attestations stored immutably per release for audit and regulatory requirements.",
        right: "SBOM storage and enrichment focused on current inventory; retention depth, scope of accepted artifacts and immutability vary by tool.",
      },
      {
        left: "Per-release historical security posture with point-in-time snapshots: answer what the vulnerability state was for any specific version at any past moment.",
        right: "Current risk visibility across the software inventory; historical per-release posture snapshots are not the primary focus.",
      },
      {
        left: "Integrates with any SCA, SAST, or DAST tool via a tool-agnostic ingestion model; stores SARIF, VEX, VDR alongside SBOMs.",
        right: "Primarily integrates with SBOM sources and upstream supplier data; broader security artifact types vary by tool.",
      },
      {
        left: "FOSS Community Edition (ReARM CE) available for self-hosting with core functionality.",
        right: "Typically commercial platforms; open-source options limited.",
      },
    ],
  },
];

export default function ComparisonsPage() {
  return (
    <main className="comparisonsContainer">
      <div className="comparisonsHeader">
        <h1 className="comparisonsTitle">Comparisons</h1>
        <p className="comparisonsSubtitle">Scanners visualize points in time. Graphs organize delivery signals. ReARM governs the release.</p>
        <p className="comparisonsSubtitle" style={{ marginTop: "0.5rem", fontSize: "0.95rem", opacity: 0.8 }}>Each comparison below is anchored on three questions: What decision does this tool make? At what level does it operate - scan, artifact, graph, or release? Does it preserve a release approval trail and block deployment based on release status?</p>
      </div>

      <nav className="comparisonsToc">
        {sections.map((s) => (
          <a key={s.id} href={`#${s.id}`} className="comparisonsTocLink">{s.title}</a>
        ))}
      </nav>

      {sections.map((section) => (
        <section key={section.id} id={section.id} className="comparisonsSection">
          <h2 className="comparisonsSectionTitle">{section.title}</h2>
          <p className="comparisonsSectionIntro">{section.intro}</p>
          <div className="comparisonsTable">
            <div className="comparisonsTableHeader">
              <div className="comparisonsTableHeaderCell comparisonsTableHeaderLeft">{section.leftLabel}</div>
              <div className="comparisonsTableHeaderCell comparisonsTableHeaderRight">{section.rightLabel}</div>
            </div>
            {section.points.map((point, i) => (
              <div key={i} className="comparisonsTableRow">
                <div className="comparisonsTableCell comparisonsTableCellLeft">{point.left}</div>
                <div className="comparisonsTableCell comparisonsTableCellRight">{point.right}</div>
              </div>
            ))}
          </div>
        </section>
      ))}
    </main>
  );
}
