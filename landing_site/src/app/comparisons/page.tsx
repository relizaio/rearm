import type { Metadata } from "next";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata: Metadata = {
  title: "Comparisons - ReARM by Reliza",
  description: "See how ReARM compares to Dependency-Track, traditional SCA tools, and the differences between ReARM Pro and CE.",
  alternates: { canonical: `${baseUrl}/comparisons/` },
  openGraph: {
    title: "Comparisons - ReARM by Reliza",
    description: "See how ReARM compares to Dependency-Track, traditional SCA tools, and the differences between ReARM Pro and CE.",
    url: `${baseUrl}/comparisons/`,
    type: "website",
    siteName: "ReARM - Release-Level Supply Chain Evidence Platform by Reliza",
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
    intro: "Dependency-Track is a great open-source tool for vulnerability analysis of SBOMs. ReARM integrates with Dependency-Track and builds on top of it, providing a comprehensive Release-Level Supply Chain Evidence Platform.",
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
        left: "Highly configurable auto-integration engine aggregating findings from multiple sources and from comopnents into products",
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
        left: "Managed service with SSO - no infrastructure to maintain. Support for client-hosted deployments, including air-gapped deployments, available for higher tiers.",
        right: "Self-hosted - you manage your own infrastructure.",
      },
      {
        left: "Premium support (up to 24x7 depending on plan).",
        right: "Community support via Discord and GitHub.",
      },
      {
        left: "Managed Dependency-Track instance included.",
        right: "Self-managed Dependency-Track integration.",
      },
      {
        left: "Approval and trigger workflows for release lifecycle.",
        right: "Core BOM storage functionality and retrieval without approval workflows.",
      },
      {
        left: "Workflow for marketing releases (separate versioning schema that may be used for marketing).",
        right: "No marketing release functionality.",
      },
      {
        left: "Support for perspective, multi-organization workflow support (Standard and Enterprise plans)",
        right: "Single organization and single perspective only.",
      },
      {
        left: "On-premise / air-gapped deployment option (Enterprise plan)",
        right: "Self-hosted by default",
      }
    ],
  },
  {
    id: "rearm-vs-guac",
    title: "ReARM vs GUAC",
    intro: "GUAC (Graph for Understanding Artifact Composition) is an open-source project by OpenSSF that aggregates software security metadata into a graph database for querying. While both tools deal with supply chain data, they serve different purposes.",
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
    intro: "Traditional Software Composition Analysis (SCA) tools like Semgrep, Snyk, Black Duck (Synopsys), Checkmarx, Mend (WhiteSource), and Sonatype focus on scanning and finding vulnerabilities. ReARM is not an SCA tool - it is a Release-Level Supply Chain Evidence Platform that integrates with SCA tools.",
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
];

export default function ComparisonsPage() {
  return (
    <main className="comparisonsContainer">
      <div className="comparisonsHeader">
        <h1 className="comparisonsTitle">Comparisons</h1>
        <p className="comparisonsSubtitle">See how ReARM compares to other tools in the supply chain security ecosystem</p>
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
