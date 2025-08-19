"use client";
import { useMemo } from "react";

function getRegionPrices() {
  let startup = "$190";
  let standard = "$1990";
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "";
    let type: "US" | "EU" | "CA" | "GB" = "US";
    if (tz === "GB" || tz === "GB-Eire") type = "GB";
    else if (tz.includes("Europe")) {
      switch (tz) {
        case "Europe/Belfast":
        case "Europe/London":
          type = "GB";
          break;
        default:
          type = "EU";
      }
    } else if (tz.includes("America")) {
      switch (tz) {
        case "America/Toronto":
        case "America/Vancouver":
        case "America/Winnipeg":
        case "Canada/Atlantic":
        case "Canada/Central":
        case "Canada/Eastern":
        case "Canada/Mountain":
        case "Canada/Newfoundland":
        case "Canada/Pacific":
        case "Canada/Saskatchewan":
        case "Canada/Yukon":
          type = "CA";
          break;
        default:
          type = "US";
      }
    }
    if (type === "EU") {
      startup = "€150";
      standard = "€1690";
    } else if (type === "CA") {
      startup = "C$240";
      standard = "C$2490";
    } else if (type === "GB") {
      startup = "£130";
      standard = "£1490";
    }
  } catch {}
  return { startup, standard };
}

export default function PricingPlan() {
  const prices = useMemo(() => getRegionPrices(), []);
  const plans = [
    {
      id: 0,
      title: "ReARM CE",
      amount: "Free",
      type: "Forever",
      bullets: [
        "FOSS ReARM Community Edition",
        "Self-Hosted",
        "Community support",
        "All Core SBOM/xBOM storage & retrieval",
        "Vulnerabilities and Violations via self-managed Dependency-Track Integration"
      ],
      cta: { label: "Documentation", href: "https://docs.rearmhq.com" },
    },
    {
      id: 1,
      title: "ReARM Pro - Startup",
      amount: prices.startup,
      type: "Per Month",
      bullets: [
        "Up to 3 team members",
        "Premium support",
        "Managed Dependency-Track Integration",
        "Approvals, Triggers & Marketing Releases",
        "Free 90-day trial",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
    },
    {
      id: 2,
      title: "ReARM Pro - Standard",
      amount: prices.standard,
      type: "Per Month",
      bullets: [
        "Up to 30 team members",
        "Premium support",
        "Managed Dependency-Track Integration",
        "Approvals, Triggers & Marketing Releases",
        "Managed Service with SSO",
        "Free 90-day trial",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
    },
    {
      id: 3,
      title: "ReARM Pro - Enterprise",
      amount: "Contact",
      type: "for pricing",
      bullets: [
        "30+ team members",
        "Premium support",
        "Managed Dependency-Track Integration",
        "Approvals, Triggers & Marketing Releases",
        "Managed Service with SSO, on‑prem installation available",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
    },
  ];

  return (
    <div className="pricingContainer">
      {plans.map((p) => (
        <div key={p.id} className="planCard">
          <h3 className="planTitle">{p.title}</h3>
          <div className="planAmount">{p.amount}</div>
          {p.type && <div className="planType">{p.type}</div>}
          <ul className="planFeatures">
            {p.bullets.map((b, i) => (
              <li key={i} className="planFeature">{b}</li>
            ))}
          </ul>
          <a href={p.cta.href} target="_blank" rel="noreferrer" className="planCta">
            {p.cta.label}
          </a>
        </div>
      ))}
    </div>
  );
}
