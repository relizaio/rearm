import React from "react";
import PricingPlan from "@/components/PricingPlan";
export default function Home() {
  return (
    <main className="mainPaddingContainer">
      {/* Hero */}
      <div className="container-fluid container1">
        <div className="row mx-auto" style={{ maxWidth: "925px" }}>
          <div className="col-12 mb-4">
            <h1 className="C1_title">ReARM</h1>
          </div>
          <div className="col-12">
            <p className="mx-auto C1_text" style={{ maxWidth: "611px" }}>
              Supply Chain Security and Asset Management for Releases, SBOMs, xBOMs, Security Artifacts
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
          allow="autoplay; fullscreen; picture-in-picture" 
          title="ReARM Demo Walkthrough"
        />
      </div>

      {/* Supports (TEA) */}
      <div className="container-fluid container2">
        <div className="row">
          <h3 className="text-center C2_title">Supports</h3>
          <p className="C2_text">OWASP Transparency Exchange API</p>
          <div className="d-flex justify-content-center integrationsFlexWrap">
            <img src="/home/tealogo.png" alt="OWASP Transparency Exchange API Logo" className="favAppIcons" />
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
              "dtrack.png",
              "ado.png",
              "github.png",
              "gitlab.png",
              "jenkins.png",
              "slack.png",
              "cosign.png",
              "msteams.png",
              "oci.png",
              "sendgrid.png",
            ].map((file) => (
              <img key={file} src={`/home/${file}`} alt="" className="favAppIcons" />
            ))}
          </div>
        </div>
      </div>

      {/* Alternating features (array2 in CRA) */}
      <div className="container-fluid container4">
        {[
          {
            image: "rearm_release.png",
            title: "Digital Asset Management",
            texts: [
              { text: "ReARM maintains up-to-date inventory of digital assets and provides storage for Artifacts and Metadata, such as SBOMs / xBOMs, and Attestations, per each Release." },
            ],
          },
          {
            image: "compliance.png",
            title: "Regulatory Compliance",
            texts: [
              { text: "ReARM ensures supply chain security compliance with various regulations, including EU CRA, NIS2, DORA, US Executive Orders 14028, 14144, Section 524B of the FD&C Act, India's RBI and SEBI." },
            ],
          },
          {
            image: "rearm_analytics.png",
            title: "Track Vulnerabilities and Violations across your Supply Chain",
            texts: [
              { text: "ReARM integrates with OWASP Dependency-Track to present real-time view of the state of your supply chain." },
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
          <h4 className="mx-auto text-center pricingPlan_title2" style={{ maxWidth: "600px" }}>Fixed predictable rates for any team</h4>
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
