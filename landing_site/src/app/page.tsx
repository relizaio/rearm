import React from "react";
import Link from "next/link";
import Script from "next/script";
import PricingPlan from "@/components/PricingPlan";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

const homepageJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      "@id": "https://reliza.io",
      name: "Reliza",
      url: "https://reliza.io",
      logo: {
        "@type": "ImageObject",
        url: `${baseUrl}/rearm.png`,
      },
    },
    {
      "@type": "SoftwareApplication",
      "@id": baseUrl,
      name: "ReARM",
      description: "Release Governance Platform for the Agentic Era",
      applicationCategory: "DevSecOps",
      url: baseUrl,
      publisher: {
        "@id": "https://reliza.io",
      },
    },
  ],
};
export default function Home() {
  return (
    <main className="mainPaddingContainer">
      <Script
        id="homepage-jsonld"
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(homepageJsonLd) }}
      />
      {/* Hero */}
      <div className="container-fluid container1">
        <div className="row mx-auto" style={{ maxWidth: "1100px" }}>
          <div className="col-12 mb-4">
            <h1 className="C1_title d-flex align-items-center justify-content-center" style={{ gap: "16px" }}>
              <img src="/home/logo_reliza_birds.png" alt="ReARM" style={{ height: "1em", width: "auto" }} />
              ReARM
            </h1>
            <h1 style={{ textAlign: "center" }}>
              <Link href="/release-level-supply-chain-evidence-platform" style={{ color: "inherit", textDecoration: "none" }}>
                Release Governance Platform for the Agentic Era
              </Link>
            </h1>
          </div>
          <div className="col-12">
            <p className="mx-auto C1_text">
              Per-release SBOMs, xBOMs and every other artifact - stored for 10+ years, versioned and audit-ready
            </p>
          </div>
        </div>
      </div>

      {/* Agentic AI callout */}
      <div className="container-fluid" style={{ padding: "64px 24px", background: "#18214d" }}>
        <div className="mx-auto text-center" style={{ maxWidth: "950px" }}>
          <p style={{ color: "#a8b4ff", fontWeight: 600, fontSize: "0.95rem", letterSpacing: "0.08em", textTransform: "uppercase", marginBottom: "20px" }}>Built for the Agentic Era</p>
          <h2 style={{ color: "white", fontWeight: 700, fontSize: "2rem", lineHeight: 1.35, marginBottom: "24px" }}>
            Agentic AI has accelerated development by an order of magnitude. Traditional release management has not kept up.
          </h2>
          <p style={{ color: "#c8d0f0", fontSize: "1.1rem", lineHeight: 1.8, margin: 0 }}>
            When dozens - or hundreds - of AI agents are shipping code simultaneously, you need release controls that scale with them. ReARM gives you the visibility, governance, and evidence chain to manage releases at any velocity, without sacrificing compliance or security.
          </p>
        </div>
      </div>

      {/* Hero CTA */}
      <div className="container-fluid container1" style={{ paddingTop: "32px", paddingBottom: "32px" }}>
        <div className="d-flex justify-content-center">
          <a href="https://demo.rearmhq.com" target="_blank" rel="noopener noreferrer" style={{ textDecoration: "none" }}>
            <button className="btn_usingFree">Try Public Demo</button>
          </a>
        </div>
      </div>

      {/* Demo video */}
      <div className="d-flex justify-content-center">
        <iframe 
          className="videoContainer" 
          src="https://d7ge14utcyki8.cloudfront.net/ReARM_Demo_Video.mp4" 
          width="100%" 
          height="100%" 
          allow="fullscreen; picture-in-picture" 
          title="ReARM Demo Walkthrough"
        />
      </div>

      {/* Questions ReARM Can Answer */}
      <div className="container-fluid" style={{ padding: "60px 24px", background: "#f8f9fc" }}>
        <div className="row mx-auto" style={{ maxWidth: "900px" }}>
          <h2 className="text-center mb-5" style={{ fontWeight: 700, fontSize: "1.75rem" }}>Questions ReARM Can Answer Quickly</h2>
          {[
            {
              q: "What is the exact security posture of version 1.0.3 of product X?",
              a: "ReARM tracks every vulnerability, license violation, and policy finding per release - so you can see the current and historical security posture of any specific version.",
            },
            {
              q: "Are we ready to ship version 1.0.4 of product X?",
              a: "ReARM Pro is the system of record for release approvals and lifecycle management. Your CI/CD pipeline queries ReARM to determine the latest release that has passed all required approval gates - and only promotes or deploys that version. No approved status in ReARM, no deployment.",
            },
            {
              q: "Can we prove to an auditor that every shipped release was reviewed and approved?",
              a: "Every approval in ReARM Pro is immutably recorded with a timestamp, approver identity, and the evidence it was based on. Your release approval workflow is also your audit trail.",
            },
            {
              q: "Has any Shai-Hulud-infected dependency ever entered our supply chain, and if so, in which releases?",
              a: "ReARM's cross-release SBOM search lets you query any component or dependency across your entire release history - instantly identifying which releases were affected and when.",
            },
            {
              q: "Has the Log4Shell CVE ever appeared anywhere across our organization?",
              a: "ReARM aggregates findings from all tools and all releases organization-wide, so you can search for any CVE across your entire product and component portfolio.",
            },
          ].map((item, i) => (
            <div key={i} className="col-12 mb-4">
              <div style={{ background: "white", borderRadius: "12px", padding: "28px 32px", boxShadow: "0 2px 12px rgba(0,0,0,0.07)" }}>
                <p style={{ fontWeight: 700, fontSize: "1.05rem", marginBottom: "10px", color: "#18214d" }}>❓ {item.q}</p>
                <p style={{ margin: 0, color: "#444", lineHeight: 1.7 }}>{item.a}</p>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Alternating features (array2 in CRA) */}
      <div className="container-fluid container4">
        {[
          {
            image: "rearm_release.png",
            title: "Asset Management & Evidence Platform",
            texts: [
              { text: "ReARM is a system of record that collects, stores for 10+ years, versions, and traces all digital artifacts required to prove the integrity, safety, and compliance of software, firmware, and hardware throughout their lifecycle. This includes SBOMs, HBOMs, other xBOMs, VEX, VDR, BOV, SARIF, digital signatures, attestations, build metadata, and more." },
            ],
          },
          {
            image: "compliance.png",
            title: "Regulatory Compliance",
            texts: [
              { text: "ReARM provides a central repository for SBOMs, xBOMs, and security artifacts across all your releases. It ensures supply chain compliance with EU CRA, NIS2, DORA, US Executive Orders 14028 and 14144, Section 524B of the FD&C Act, and India's RBI and SEBI regulations." },
            ],
          },
          {
            image: "rearm_analytics.png",
            title: "Know exact security posture of each release and changes over time",
            texts: [
              { text: "ReARM aggregates findings from Dependency-Track and other security tools into a unified view. Track vulnerabilities and policy violations across releases with scoped auditing, deduplication, and rich changelogs showing how your security posture evolves over time." },
            ],
          },
          {
            image: "license_violations.png",
            title: "License Compliance",
            texts: [
              { text: "ReARM allows to track license compliance for all your releases and BOMs with ability to triage and audit violations across various scopes, just like any other finding." },
            ],
          },
          {
            image: "create_component.png",
            title: "Get Automated Versioning and Changelogs for your Releases",
            texts: [
              { text: "ReARM automates version bumping and changelog generation for every release. ReARM provides changelogs for source code changes, SBOM component changes and security finding changes. Choose your versioning schema, connect your CI pipeline, and ReARM handles the rest - tracking every artifact and evidence entry per release." },
            ],
          },
          {
            image: "auto_integrate.png",
            title: "Automated Bundling into Products",
            texts: [
              { text: "ReARM automatically bundles your Component Releases into Product Releases and supports multi-level nesting. Evidence and findings propagate from components to products automatically, at any scale - including the release velocity of agentic AI teams." },
            ],
          },
          {
            image: "finding_analysis.png",
            title: "Finding Management System With Scopes",
            texts: [
              { text: "ReARM includes a comprehensive finding management system with support for multiple scopes (organization-wide, product-level, component-level, release-level). It supports all types of findings, including Vulnerabilities, Weaknesses, and License Compliance Violations. Findings are aggregated per-release across all evidences supplied to ReARM." },
            ],
          },
          {
            image: "sbom_augmentation.png",
            title: "Agentic SBOM Enrichment and Augmentation",
            texts: [
              { text: "ReARM includes Reliza BEAR, an agentic SBOM enrichment and augmentation tool that automatically enriches your SBOMs with additional metadata, including supplier, copyright and license information." },
            ],
          },
          {
            image: "rearm_approvals.png",
            title: "Approval and Lifecycle Management",
            texts: [
              { text: "ReARM Pro provides rich capabilities for managing approvals and lifecycles of your releases. Both manual and automated approvals are supported." },
            ],
          },
        ].map((item, i) => (
          <div key={i} className={`${i % 2 === 0 ? "row" : "row flex-row-reverse"} component1Main align-items-center`}>
            <div className="col-12 col-sm-6">
              <div className="textContent">
                <h3 className="featureTitle">{item.title}</h3>
                {item.texts.map((text, idx) => (
                  <React.Fragment key={idx}>
                    <p className="featureText">{text.text}</p>
                    <br className="d-none d-sm-block" />
                  </React.Fragment>
                ))}
              </div>
            </div>
            <div className="col-12 col-sm-6 imageCard">
              <img src={`/home/${item.image}`} alt={item.title} style={{ width: "100%" }} />
            </div>
          </div>
        ))}
      </div>

      {/* Supports (TEA) */}
      <div className="container-fluid container2">
        <div className="row">
          <h3 className="text-center C2_title">Supports</h3>
          <p className="C2_text">OWASP Transparency Exchange API</p>
          <div className="d-flex justify-content-center integrationsFlexWrap">
            <a href="https://github.com/cyclonedx/transparency-exchange-api" title="OWASP Transparency Exchange API" target="_blank" rel="noopener noreferrer"><img src="/home/tealogo.png" alt="OWASP Transparency Exchange API" className="favAppIcons" /></a>
          </div>
        </div>
      </div>

      {/* Integrates */}
      <div className="container-fluid container2">
        <div className="row">
          <h3 className="text-center C2_title">Integrates</h3>
          <p className="C2_text">with your favorite tools</p>
          <div className="d-flex justify-content-center integrationsFlexWrap">
            {[
              { file: "dtrack.png", url: "https://dependencytrack.org", title: "Dependency-Track" },
              { file: "cdx.png", url: "https://cyclonedx.org", title: "CycloneDX" },
              { file: "spdx.png", url: "https://spdx.org", title: "SPDX" },
              { file: "ado.png", url: "https://dev.azure.com", title: "Azure DevOps" },
              { file: "github.png", url: "https://github.com", title: "GitHub" },
              { file: "gitlab.png", url: "https://gitlab.com", title: "GitLab" },
              { file: "jenkins.png", url: "https://www.jenkins.io", title: "Jenkins" },
              { file: "nvd.png", url: "https://nvd.nist.gov", title: "NVD" },
              { file: "osv.png", url: "https://osv.dev", title: "OSV" },
              { file: "sonatype_oss.png", url: "https://ossindex.sonatype.org", title: "Sonatype OSS Index" },
              { file: "snyk.png", url: "https://snyk.io", title: "Snyk" },
              { file: "slack.png", url: "https://slack.com", title: "Slack" },
              { file: "cosign.png", url: "https://github.com/sigstore/cosign", title: "Sigstore Cosign" },
              { file: "msteams.png", url: "https://www.microsoft.com/en-us/microsoft-365/microsoft-teams/group-chat-software", title: "Microsoft Teams" },
              { file: "oci.png", url: "https://www.opencontainers.org", title: "Open Container Initiative" },
              { file: "sendgrid.png", url: "https://sendgrid.com", title: "SendGrid" },
              { file: "semgrep.png", url: "https://semgrep.dev", title: "Semgrep" },
              { file: "checkov.png", url: "https://www.checkov.io", title: "Checkov" },
              { file: "gmail.png", url: "https://gmail.com", title: "Gmail" },
              { file: "shiftleftcyber.png", url: "https://shiftleftcyber.io", title: "ShiftLeftCyber" },
              { file: "clearlydefined.png", url: "https://clearlydefined.io", title: "ClearlyDefined" },
              { file: "depsdev.png", url: "https://deps.dev", title: "deps.dev" },
            ].map((item) => (
              <a key={item.file} href={item.url} target="_blank" rel="noopener noreferrer" title={item.title}><img src={`/home/${item.file}`} alt={item.title} className="favAppIcons" /></a>
            ))}
          </div>
        </div>
      </div>

      {/* Trusted */}
      <div className="container-fluid container2">
        <div className="row">
          <h3 className="text-center C2_title">Clients and Partners</h3>
          <div className="d-flex justify-content-center integrationsFlexWrap">
            {[
              { file: "investottawa.png", url: "https://www.investottawa.ca/", title: "Invest Ottawa" },
              { file: "kdm.png", url: "https://kdmanalytics.com", title: "KDM Analytics" },
              { file: "iq.png", url: "https://www.iqinnovationhub.com", title: "IQ Innovation Hub" },
              { file: "wysdom.png", url: "https://wysdom.ai", title: "Wysdom.AI" },
              { file: "ovh.png", url: "https://ovhcloud.com", title: "OVHcloud" },
              { file: "wicwac.png", url: "https://wicwac.com", title: "WicWac" },
              { file: "semperis.png", url: "https://www.semperis.com", title: "Semperis" },
            ].map((item) => (
              <a key={item.file} href={item.url} target="_blank" rel="noopener noreferrer" title={item.title}><img src={`/home/${item.file}`} alt={item.title} className="favAppIcons" /></a>
            ))}
          </div>
        </div>
      </div>

      {/* Pricing & Plans */}
      <div className="mainPaddingContainer">
        <div id="homePagePricing" className="container-fluid container6">
          <h4 className="pricingPlan_title1 text-center">Pricing & Plans</h4>
          <h4 className="mx-auto text-center pricingPlan_title2" style={{ maxWidth: "600px" }}>Fixed predictable rates for any team<Link href="/pricing-faq" className="pricingHelpIcon" title="Pricing FAQ">?</Link></h4>
          <PricingPlan />
          
          {/* Questions section */}
          <div className="integration-getStarted text-center" style={{ marginTop: "60px" }}>
            <p className="mb-3 fw-semibold mb-4">Questions about product or pricing?</p>
            <h1 className="text-center mx-auto" style={{ maxWidth: "650px" }}>Book demo with us!</h1>
            <a href="https://calendly.com/pavel-reliza/30min" target="_blank" rel="noopener noreferrer" className="contactUs-btn_ContactUs fw-bold">
              Book Private Demo
            </a>
          </div>
        </div>
      </div>
    </main>
  );
}
