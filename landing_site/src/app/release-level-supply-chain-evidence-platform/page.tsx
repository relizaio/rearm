import React from "react";
import type { Metadata } from "next";
import Link from "next/link";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata: Metadata = {
  title: "What Is a Release Governance Platform for the Agentic Era? | ReARM",
  description: "Understanding the evolution from source code repository SCA scans wrapped as SBOMs to Release Governance Platform for the Agentic Eras and why this matters for modern software security.",
  alternates: {
    canonical: `${baseUrl}/release-level-supply-chain-evidence-platform/`,
  },
  openGraph: {
    title: "What Is a Release Governance Platform for the Agentic Era?",
    description: "Understanding the evolution from source code repository SCA scans wrapped as SBOMs to Release Governance Platform for the Agentic Eras and why this matters for modern software security.",
    url: `${baseUrl}/release-level-supply-chain-evidence-platform/`,
    siteName: "ReARM - Release Governance Platform for the Agentic Era",
    type: "article",
  },
  twitter: {
    card: "summary_large_image",
    title: "What Is a Release Governance Platform for the Agentic Era?",
    description: "Understanding the evolution from source code repository SCA scans wrapped as SBOMs to Release Governance Platform for the Agentic Eras and why this matters for modern software security.",
  },
};

export default function ReleaseEvidencePlatform() {
  return (
    <main className="mainPaddingContainer">
      <article style={{ maxWidth: "900px", margin: "0 auto", padding: "40px 20px" }}>
        <h1 style={{ fontSize: "2.5rem", marginBottom: "1.5rem", textAlign: "center" }}>
          What Is a Release Governance Platform for the Agentic Era?
        </h1>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            The Problem: What does SBOM actually represent?
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Organizations today face a critical challenge in managing software supply chain security. 
            While SBOMs (Software Bill of Materials) have become a regulatory requirement under frameworks 
            like the EU Cyber Resilience Act (CRA), NIS2, and US Executive Order 14028, most teams struggle 
            to see the benefits in practice.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            That is because SBOMs are often generated at the source code repository level during regular SCA scans.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            In other words, to tick some checkboxes on compliance forms, many tools would simply wrap
            SCA scans in one of the major SBOM formats (CycloneDX of SPDX). This approach fails to capture the true 
            state of the released product. It is also easy to notice that frequently such SCA scans are done outside of the release
            management process. Thererefore, it is impossible to answer a simple question, what does such SBOM actually represent?
          </p>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            Other Related Problems
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            In practical scenarios, there are several specific "simple" questions that are very difficult to answer with legacy tooling:
          </p>
          <ul style={{ fontSize: "1.1rem", lineHeight: "2", marginBottom: "1rem", paddingLeft: "2rem" }}>
            <li>What is the exact security posture of version 1.0.3 of product X today?</li>
            <li>What was the security posture of that same version 3 months ago when it was shipped to a key customer?</li>
            <li>Has any of Shai-Hulud 2.0 infected dependencies ever entered organization's supply chain and if so, in which releases?</li>
            <li>Has the log4shell CVE ever appeared anywhere across organization?</li>
          </ul>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            Why Release-Level Evidence Matters
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            The release is the fundamental unit of software delivery. It's what you deploy to production, 
            what customers install, and what regulators ask about during audits. Release-level evidence 
            management aligns with how organizations actually operate.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Further, modern regulations such as CRA require organizations to maintain evidence at the release level. Similarly, 
            many contract requirements also demand release-level evidence.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            It is also important to note that supply chain represents a Directed Acyclic Graph with releases including other releases as components. 
            This is why we establish notion of Product Releases and Component Releases, where Product Releases are understood as what is shipped to the customer,
            while Component Releases are elements of such Product Releases.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Next, while the evidence is frequently collected at component release level, it is important to be able to aggregate this evidence at the product release level.
            Thus, we usually understand per-release security posture as a combination of security posture of all components and their dependencies. And we need 
            to be able to reason about security posture on each level.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            This various relationships have become a basis of the <strong>Product-Component release metadata organization model</strong>, described with additional details by 
            creators of ReARM <a href="https://worklifenotes.com/2024/10/08/release-metadata-organization-model/" target="_blank" title="Release Metadata Organization Model">here</a>.
          </p>

        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            What a Supply Chain Evidence Platform Is
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            A Release Governance Platform for the Agentic Era is a system of record that organizes all supply 
            chain artifacts and security evidence around the release as the primary entity. It also recognizes specific 
            elements within releases, such as Source Code Entries and Deliverables (or Distributions).
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            It provides:
          </p>
          <ul style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem", paddingLeft: "2rem" }}>
            <li><strong>Unified evidence repository:</strong> SBOMs, HBOMs, xBOMs, VEX, VDR, SARIF, attestations, 
            and build metadata. All stored per release.</li>
            <li><strong>Release hierarchy:</strong> Products composed of components, with evidence automatically 
            aggregated and propagated through the hierarchy.</li>
            <li><strong>Automated versioning:</strong> Intelligent version bumping and changelog generation for 
            every release based on code, dependency, and security changes.</li>
            <li><strong>Security posture tracking:</strong> Unified view of vulnerabilities and policy violations 
            across releases, with deduplication and scoped auditing.</li>
            <li><strong>Long-term retention:</strong> Immutable storage of all raw evidence for 10+ years to meet 
            regulatory requirements. And separate storage of augmented or enriched evidence artifacts.</li>
            <li><strong>API-first integration:</strong> Standards-based APIs (like OWASP Transparency Exchange API) 
            to integrate with existing CI/CD and security tools.</li>
          </ul>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            How ReARM Implements Release-Level Evidence Management
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            <Link href="/" style={{ color: "#0066cc", textDecoration: "none" }}>ReARM</Link> was built from 
            the ground up as a Release Governance Platform for the Agentic Era. Here's how it works:
          </p>
          
          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            1. Release-First Data Model
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Every artifact, SBOM, security finding, and piece of evidence is associated with a specific release. 
            Products are composed of component releases, creating a complete hierarchy that mirrors your actual 
            software architecture.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            2. Automated Evidence Collection
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM integrates with your CI/CD pipeline to automatically collect and version all evidence as part 
            of your build process. SBOMs, security scan results, signatures, attestations. Everything is captured and stored 
            with the release and attributed to its proper release element.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            3. Unified Security Posture
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM aggregates findings from Dependency-Track, CodeQL, and other security tools into a 
            single view per release. ReARM then allows to track how findings change over time with rich changelogs and 
            scoped auditing at organization, product, component, or release level. ReARM findings include vulnerabilities, Weaknesses
            (SAST/DAST and other scanning results), Licensing Violations and other policy violations.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            4. Intelligent Versioning and Changelogs
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM automatically generates comprehensive changelogs for every release, covering 
            source code changes, SBOM component changes, and security finding changes. ReARM is also capable to act
            as a versioning authority, automatically generating version numbers based on chosen versioning schema (such as <a href="https://semver.org/" target="_blank" rel="noopener noreferrer" style={{ color: "#0066cc", textDecoration: "none" }}>SemVer 2.0.0</a>).
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            5. Standards-Based Integration
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM implements the <a href="https://github.com/cyclonedx/transparency-exchange-api" target="_blank" 
            rel="noopener noreferrer" style={{ color: "#0066cc", textDecoration: "none" }}>OWASP Transparency 
            Exchange API</a>, ensuring interoperability with the broader supply chain security ecosystem.
          </p>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            The Path Forward
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            As software supply chain regulations continue to evolve and mature, the industry is moving beyond 
            simple SBOM generation toward comprehensive evidence management. Organizations need platforms that 
            can answer auditor questions, support incident response, and provide long-term traceability - all 
            organized around the release as the fundamental unit of delivery.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            A Release Governance Platform for the Agentic Era isn't just a better SBOM tool, it's a new category 
            of infrastructure that aligns with how modern software is actually built, delivered, and deployed.
          </p>
        </section>

        <section style={{ marginTop: "4rem", padding: "2rem", backgroundColor: "#f8f9fa", borderRadius: "8px", textAlign: "center" }}>
          <h3 style={{ fontSize: "1.5rem", marginBottom: "1rem" }}>
            Ready to See ReARM in Action?
          </h3>
          <p style={{ fontSize: "1.1rem", marginBottom: "1.5rem" }}>
            Experience how release-level evidence management transforms supply chain security and compliance.
          </p>
          <div style={{ display: "flex", gap: "1rem", justifyContent: "center", flexWrap: "wrap" }}>
            <a href="https://demo.rearmhq.com" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }}>
              <button className="btn_usingFree">Try Public Demo</button>
            </a>
            <Link href="/#homePagePricing" style={{ textDecoration: "none" }}>
              <button className="btn_usingFree" style={{ backgroundColor: "#fff", color: "#0066cc", border: "2px solid #0066cc" }}>
                View Pricing
              </button>
            </Link>
          </div>
        </section>
      </article>
    </main>
  );
}
