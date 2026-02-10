import React from "react";
import Link from "next/link";
import PricingPlan from "@/components/PricingPlan";
export default function Home() {
  return (
    <main className="mainPaddingContainer">
      {/* Hero */}
      <div className="container-fluid container1">
        <div className="row mx-auto" style={{ maxWidth: "925px" }}>
          <div className="col-12 mb-4">
            <h1 className="C1_title">ReARM - Supply Chain Evidence Store</h1>
          </div>
          <div className="col-12">
            <p className="mx-auto C1_text" style={{ maxWidth: "600px" }}>
              SBOMs, xBOMs and every other artifact - stored per release for 10+ years, versioned and audit-ready
            </p>
          </div>
        </div>
        <div className="d-flex justify-content-center">
          <a href="https://demo.rearmhq.com" target="_blank" style={{ textDecoration: "none" }}>
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
            ].map((item) => (
              <a key={item.file} href={item.url} target="_blank" rel="noopener noreferrer" title={item.title}><img src={`/home/${item.file}`} alt={item.title} className="favAppIcons" /></a>
            ))}
          </div>
        </div>
      </div>

      {/* Alternating features (array2 in CRA) */}
      <div className="container-fluid container4">
        {[
          {
            image: "rearm_release.png",
            title: "Asset Management & Evidence Store",
            texts: [
              { text: "ReARM is a system of record that collects, stores for 10+ years, versions, and traces all digital artifacts required to prove the integrity, safety, and compliance of software, firmware, and hardware throughout their lifecycle. This includes SBOMs, HBOMs, other xBOMs, VEX, VDR, BOV, SARIF, attestations, build metadata, and more." },
            ],
          },
          {
            image: "compliance.png",
            title: "Regulatory Compliance",
            texts: [
              { text: "ReARM acts as a central SBOM/xBOM and security artifact repository and digital evidence store for all your releases and ensures supply chain security compliance with various regulations, including EU CRA, NIS2, DORA, US Executive Orders 14028, 14144, Section 524B of the FD&C Act, India's RBI and SEBI." },
            ],
          },
          {
            image: "rearm_analytics.png",
            title: "Track Vulnerabilities and Violations across your Supply Chain",
            texts: [
              { text: "ReARM integrates with various cyber security tools to present real-time security posture of your component and product releases." },
            ],
          },
          {
            image: "create_component.png",
            title: "Automated Versioning and Change Logs for your Releases",
            texts: [
              { text: "Choose desired versioning schema, connect to your CI and let ReARM do the rest!" },
            ],
          },
          {
            image: "auto_integrate.png",
            title: "Automated Bundling into Products",
            texts: [
              { text: "ReARM automatically bundles your Components into Products and supports multi-level nesting." },
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
              <img src={`/home/${item.image}`} alt="" style={{ width: "100%" }} />
            </div>
          </div>
        ))}
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
            <a href="https://calendly.com/pavel_reliza/demo" target="_blank" rel="noopenernoreferrer" className="contactUs-btn_ContactUs fw-bold">
              Book Private Demo
            </a>
          </div>
        </div>
      </div>
    </main>
  );
}
