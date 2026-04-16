import React from "react";
import type { Metadata } from "next";
import Link from "next/link";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata: Metadata = {
  title: "What Is a Release Governance Platform for the Agentic Era? | ReARM",
  description: "Understanding why release governance - approvals, policies, and control - is the missing layer in modern software supply chain security, and why evidence alone is not enough.",
  alternates: {
    canonical: `${baseUrl}/release-governance-platform/`,
  },
  openGraph: {
    title: "What Is a Release Governance Platform for the Agentic Era?",
    description: "Understanding why release governance - approvals, policies, and control - is the missing layer in modern software supply chain security, and why evidence alone is not enough.",
    url: `${baseUrl}/release-governance-platform/`,
    siteName: "ReARM - Release Governance Platform for the Agentic Era",
    type: "article",
  },
  twitter: {
    card: "summary_large_image",
    title: "What Is a Release Governance Platform for the Agentic Era?",
    description: "Understanding why release governance - approvals, policies, and control - is the missing layer in modern software supply chain security, and why evidence alone is not enough.",
  },
};

export default function ReleaseGovernancePlatform() {
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
            In other words, to tick some checkboxes on compliance forms, many tools simply wrap
            SCA scans in one of the major SBOM formats (CycloneDX or SPDX). This approach fails to capture the true
            state of the released product. Such scans are also frequently done outside of the release
            management process, making it impossible to answer a basic question: what does this SBOM actually represent?
          </p>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            Evidence Is Not Enough
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Even when evidence is collected correctly at the release level, storing it passively is not sufficient.
            In the Agentic Era, where AI can produce an order-of-magnitude more code than human developers ever could,
            the volume and velocity of releases is accelerating dramatically. Code production is no longer the bottleneck -
            release control is.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Organizations need more than an archive. They need an active control plane that can:
          </p>
          <ul style={{ fontSize: "1.1rem", lineHeight: "2", marginBottom: "1rem", paddingLeft: "2rem" }}>
            <li>Enforce policies before a release is promoted or shipped</li>
            <li>Require approvals from the right stakeholders at each lifecycle stage</li>
            <li>Block releases that violate security, licensing, or compliance rules</li>
            <li>Track who approved what, when, and under what conditions</li>
            <li>Answer auditor and incident-response questions instantly, across all past releases</li>
          </ul>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            This is the distinction between a passive evidence store and a Release Governance Platform.
          </p>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            Related Operational Problems
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            In practical scenarios, there are several "simple" questions that are very difficult to answer with legacy tooling:
          </p>
          <ul style={{ fontSize: "1.1rem", lineHeight: "2", marginBottom: "1rem", paddingLeft: "2rem" }}>
            <li>What is the exact security posture of version 1.0.3 of product X today?</li>
            <li>What was the security posture of that same version 3 months ago when it was shipped to a key customer?</li>
            <li>Has any Shai-Hulud 2.0 infected dependency ever entered the organization's supply chain, and if so, in which releases?</li>
            <li>Has the log4shell CVE ever appeared anywhere across the organization?</li>
            <li>Which releases were approved for production, by whom, and when?</li>
            <li>Was a policy violation overridden before this release shipped - and who authorized it?</li>
          </ul>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            Why the Release Is the Right Unit of Governance
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            The release is the fundamental unit of software delivery. It is what gets deployed to production,
            what customers install, and what regulators ask about during audits. Governance must be organized
            around releases - not repositories, branches, or individual commits.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Modern regulations such as CRA require organizations to maintain evidence and control records at
            the release level. Many contract requirements demand the same.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            The supply chain also forms a Directed Acyclic Graph: releases include other releases as components.
            This is why ReARM distinguishes between Product Releases (what is shipped to customers) and Component
            Releases (the building blocks of those products). Evidence, policy checks, and approvals propagate
            through this hierarchy, giving a complete picture of security posture at every level.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            These relationships form the basis of the <strong>Product-Component release metadata organization model</strong>,
            described in detail by the creators of ReARM{" "}
            <a href="https://worklifenotes.com/2024/10/08/release-metadata-organization-model/" target="_blank" title="Release Metadata Organization Model">here</a>.
          </p>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            What a Release Governance Platform Is
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            A Release Governance Platform is an active control plane that organizes all supply chain artifacts,
            security evidence, policies, and approvals around the release as the primary entity. It is not a
            passive archive - it is the system that decides whether a release is allowed to move forward.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            It provides:
          </p>
          <ul style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem", paddingLeft: "2rem" }}>
            <li><strong>Policy enforcement:</strong> Automated checks that block or flag releases violating
            security, licensing, or organizational rules - enforced at each lifecycle stage.</li>
            <li><strong>Approval workflows:</strong> Configurable gates requiring sign-off from the right
            stakeholders before a release can be promoted or shipped.</li>
            <li><strong>Unified evidence repository:</strong> SBOMs, HBOMs, xBOMs, VEX, VDR, SARIF, attestations,
            build metadata, and other artifacts - all stored per release, not per repository.</li>
            <li><strong>Release hierarchy:</strong> Product releases composed of component releases, with evidence, policies,
            and approvals aggregated and applied through the hierarchy.</li>
            <li><strong>Security posture tracking:</strong> Unified view of vulnerabilities and policy violations
            across releases, with deduplication and scoped auditing.</li>
            <li><strong>Automated versioning:</strong> Intelligent version bumping and changelog generation for
            every release based on code, dependency, and security changes.</li>
            <li><strong>Long-term retention:</strong> Immutable storage of all raw evidence for 10+ years to meet
            regulatory requirements, alongside enriched or augmented evidence artifacts.</li>
            <li><strong>API-first integration:</strong> Standards-based APIs (including OWASP Transparency Exchange API)
            to integrate with existing CI/CD and security tools.</li>
          </ul>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            How ReARM Implements Release Governance
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            <Link href="/" style={{ color: "#0066cc", textDecoration: "none" }}>ReARM</Link> was built from
            the ground up as a Release Governance Platform for the Agentic Era. Here is how it works:
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            1. Release-First Data Model
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            Every artifact, SBOM, security finding, approval record, and piece of evidence is associated with
            a specific release. Products are composed of component releases, creating a complete hierarchy that
            mirrors your actual software architecture.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            2. Policy Enforcement and Approval Gates
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM enforces configurable policies at each release lifecycle stage. Releases that violate
            security policies, contain unlicensed dependencies, or fail compliance checks can be automatically
            blocked or flagged. Approval workflows ensure the right stakeholders authorize each promotion,
            with a full audit trail of who approved what and when.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            3. Automated Evidence Collection
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM integrates with your CI/CD pipeline to automatically collect and version all evidence as part
            of your build process - SBOMs, security scan results, signatures, attestations. Everything is captured,
            stored with the release, and attributed to its proper release element.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            4. Unified Security Posture
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM aggregates findings from Dependency-Track, CodeQL, and other security tools into a
            single view per release, tracking how findings change over time with rich changelogs and
            scoped auditing at organization, product, component, or release level. Findings include
            vulnerabilities, weaknesses (SAST/DAST), licensing violations, and other policy violations.
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            5. Intelligent Versioning and Changelogs
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM automatically generates comprehensive changelogs for every release, covering
            source code changes, SBOM component changes, and security finding changes. ReARM can also act
            as a versioning authority, automatically generating version numbers based on the chosen schema
            (such as{" "}
            <a href="https://semver.org/" target="_blank" rel="noopener noreferrer" style={{ color: "#0066cc", textDecoration: "none" }}>SemVer 2.0.0</a>).
          </p>

          <h3 style={{ fontSize: "1.4rem", marginBottom: "0.8rem", marginTop: "1.5rem" }}>
            6. Standards-Based Integration
          </h3>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            ReARM implements the{" "}
            <a href="https://github.com/cyclonedx/transparency-exchange-api" target="_blank"
            rel="noopener noreferrer" style={{ color: "#0066cc", textDecoration: "none" }}>OWASP Transparency
            Exchange API</a>, ensuring interoperability with the broader supply chain security ecosystem.
          </p>
        </section>

        <section style={{ marginBottom: "3rem" }}>
          <h2 style={{ fontSize: "1.8rem", marginBottom: "1rem", marginTop: "2rem" }}>
            The Path Forward
          </h2>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            As AI accelerates code production and software supply chain regulations continue to mature,
            the industry is moving beyond simple SBOM generation - and beyond passive evidence storage -
            toward active release governance. Organizations need platforms that can enforce policies,
            require approvals, answer auditor questions, support incident response, and provide long-term
            traceability, all organized around the release as the fundamental unit of delivery.
          </p>
          <p style={{ fontSize: "1.1rem", lineHeight: "1.8", marginBottom: "1rem" }}>
            A Release Governance Platform for the Agentic Era is the control plane that creates shared environment
            for multiple stakeholders, ensuring that every release is authorized, evidenced, and traceable.
          </p>
        </section>

        <section style={{ marginTop: "4rem", padding: "2rem", backgroundColor: "#f8f9fa", borderRadius: "8px", textAlign: "center" }}>
          <h3 style={{ fontSize: "1.5rem", marginBottom: "1rem" }}>
            Ready to See ReARM in Action?
          </h3>
          <p style={{ fontSize: "1.1rem", marginBottom: "1.5rem" }}>
            Experience how release governance transforms supply chain security, approvals, and compliance.
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
