"use client";
import { useEffect, useMemo, useState } from "react";
import Flag from "react-world-flags";

interface RegionPrices {
  regionType: RegionType;
  currencySymbol: string;
  startupBase: number;
  startupPerUser: number;
  standardBase: number;
  standardPerUser: number;
  enterprise: string;
}

type RegionType = "US" | "EU" | "CA" | "GB" | "AU" | "SG";

const REGION_STORAGE_KEY = "rearm_pricing_region";

function detectRegionType(): RegionType {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || "";
    let type: RegionType = "US";
    if (tz === "GB" || tz === "GB-Eire") type = "GB";
    else if (tz.includes("Australia")) {
      type = "AU";
    } else if (tz.includes("Singapore")) {
      type = "SG";
    } else if (tz.includes("Europe")) {
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
    return type;
  } catch {
    return "US";
  }
}

function getRegionPricesForType(regionType: RegionType): RegionPrices {
  let currencySymbol = "$";
  let startupBase = 195;
  let startupPerUser = 45;
  let standardBase = 1350;
  let standardPerUser = 65;
  let enterprise = "$75";

  if (regionType === "EU") {
    currencySymbol = "€";
    startupBase = 165;
    startupPerUser = 40;
    standardBase = 1170;
    standardPerUser = 55;
    enterprise = "€65";
  } else if (regionType === "CA") {
    currencySymbol = "C$";
    startupBase = 250;
    startupPerUser = 60;
    standardBase = 1850;
    standardPerUser = 85;
    enterprise = "C$100";
  } else if (regionType === "GB") {
    currencySymbol = "£";
    startupBase = 145;
    startupPerUser = 35;
    standardBase = 1020;
    standardPerUser = 54;
    enterprise = "£60";
  } else if (regionType === "AU") {
    currencySymbol = "A$";
    startupBase = 275;
    startupPerUser = 65;
    standardBase = 1950;
    standardPerUser = 85;
    enterprise = "A$105";
  } else if (regionType === "SG") {
    currencySymbol = "S$";
    startupBase = 245;
    startupPerUser = 55;
    standardBase = 1730;
    standardPerUser = 82;
    enterprise = "S$95";
  }

  return { regionType, currencySymbol, startupBase, startupPerUser, standardBase, standardPerUser, enterprise };
}

export default function PricingPlan() {
  const detectedRegionType = useMemo(() => detectRegionType(), []);
  const [regionSelection, setRegionSelection] = useState<RegionType | null>(null);

  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(REGION_STORAGE_KEY);
      if (stored === "US" || stored === "CA" || stored === "GB" || stored === "EU" || stored === "AU" || stored === "SG") {
        setRegionSelection(stored);
      } else {
        // Default to detected region
        setRegionSelection(detectedRegionType);
      }
    } catch {
      setRegionSelection(detectedRegionType);
    }
  }, [detectedRegionType]);

  useEffect(() => {
    if (regionSelection) {
      try {
        window.localStorage.setItem(REGION_STORAGE_KEY, regionSelection);
      } catch {}
    }
  }, [regionSelection]);

  const effectiveRegionType = regionSelection || detectedRegionType;
  const prices = useMemo(() => getRegionPricesForType(effectiveRegionType), [effectiveRegionType]);
  const [startupUsers, setStartupUsers] = useState(1);
  const [standardUsers, setStandardUsers] = useState(15);
  
  // Calculate Startup price: $99 base for 1 user, then $30 per additional user
  const startupPrice = useMemo(() => {
    const price = prices.startupBase + (startupUsers - 1) * prices.startupPerUser;
    return `${prices.currencySymbol}${price}`;
  }, [startupUsers, prices]);
  
  // Calculate Standard price: base cliff + per user for additional users
  const standardMinUsers = 15;
  const standardPrice = useMemo(() => {
    const price = prices.standardBase + (standardUsers > standardMinUsers ? (standardUsers - standardMinUsers) * prices.standardPerUser : 0);
    return `${prices.currencySymbol}${price}`;
  }, [standardUsers, prices]);
  
  const plans = [
    {
      id: 0,
      title: "ReARM CE",
      amount: "Free",
      type: "Forever",
      bullets: [
        "FOSS ReARM Community Edition",
        "Self-Hosted",
        "Single Organization",
        "Community support",
        "All Core SBOM/xBOM Storage & Retrieval Functionality",
        "Vulnerabilities and Violations via self-managed Dependency-Track Integration"
      ],
      cta: { label: "Documentation", href: "https://docs.rearmhq.com" },
      userSelector: null,
    },
    {
      id: 1,
      title: "ReARM Pro - Starter",
      amount: startupPrice,
      type: "Per Month",
      bullets: [
        "Up to 65GB of storage for compressed artifacts*",
        "Priority Support (8 hours response time)",
        "Managed Dependency-Track",
        "Multi-Perspective Workflow",
        "Approvals & Event Workflows",
        "Marketing Releases",
        "SBOM Enrichment via BEAR",
        "Free 60-day trial*",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
      userSelector: {
        min: 1,
        max: 14,
        value: startupUsers,
        onChange: setStartupUsers,
      },
    },
    {
      id: 2,
      title: "ReARM Pro - Standard",
      amount: standardPrice,
      type: "Per Month",
      bullets: [
        "All in ReARM Pro - Starter",
        "Private VPC / VNet with SSO and unlimited artifact storage, option for on-prem deployment",
        "Enhanced support (24x7, 4 hours response time)",
        "Support for Multi-Organization Workflow",
        "Free 60-day trial*",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
      userSelector: {
        min: standardMinUsers,
        max: 39,
        value: standardUsers,
        onChange: setStandardUsers,
      },
    },
    {
      id: 3,
      title: "ReARM Pro - Enterprise",
      amount: prices.enterprise,
      type: "per write user per month",
      bullets: [
        "All in ReARM Pro - Standard",
        "Premium support (24x7, 1 hour response time)",
        "Option for air-gapped deployment",
        "Free 60-day trial*",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
      userSelector: null,
    },
  ];

  return (
    <div>
      <div className="pricingRegionSelector" aria-label="Regional pricing selector">
        <div className="pricingRegionSelectorLabel">
          Pricing region:
        </div>
        <div className="pricingRegionSelectorButtons" role="group" aria-label="Select pricing region">
          {(
            [
              { value: "US" as const, label: "US", code: "US" },
              { value: "CA" as const, label: "Canada", code: "CA" },
              { value: "GB" as const, label: "UK", code: "GB" },
              { value: "EU" as const, label: "EU", code: "EU" },
              { value: "AU" as const, label: "Australia", code: "AU" },
              { value: "SG" as const, label: "Singapore", code: "SG" },
            ]
          ).map((opt) => (
            <button
              key={opt.value}
              type="button"
              className={`pricingRegionButton ${regionSelection === opt.value ? "pricingRegionButtonActive" : ""}`}
              onClick={() => setRegionSelection(opt.value)}
              aria-pressed={regionSelection === opt.value}
              aria-label={opt.label}
              title={opt.label}
            >
              <span className="pricingRegionButtonIcon">
                <Flag code={opt.code} style={{ width: 24, height: 24, borderRadius: 2 }} />
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="pricingContainer">
        {plans.map((p) => (
          <div key={p.id} className="planCard">
            <h3 className="planTitle">{p.title}</h3>
            <div className="planAmount">{p.amount}</div>
            {p.type && <div className="planType">{p.type}</div>}
            {p.userSelector && (
              <div className="userSelector">
                <label htmlFor={`users-${p.id}`} className="userSelectorLabel">
                  Number of write users ({p.userSelector.min}-{p.userSelector.max}):
                </label>
                <input
                  id={`users-${p.id}`}
                  type="number"
                  min={p.userSelector.min}
                  max={p.userSelector.max}
                  value={p.userSelector.value}
                  onChange={(e) => {
                    const val = parseInt(e.target.value, 10);
                    if (!isNaN(val) && val >= p.userSelector!.min && val <= p.userSelector!.max) {
                      p.userSelector!.onChange(val);
                    }
                  }}
                  className="userSelectorInput"
                />
                <input
                  type="range"
                  min={p.userSelector.min}
                  max={p.userSelector.max}
                  value={p.userSelector.value}
                  onChange={(e) => p.userSelector!.onChange(parseInt(e.target.value, 10))}
                  className="userSelectorRange"
                />
              </div>
            )}
            {p.id === 0 && (
              <div className="userSelector">
                <div className="userSelectorLabel">&nbsp;</div>
              </div>
            )}
            {p.id === 3 && (
              <div className="userSelector">
                <div className="userSelectorLabel">40+ write users</div>
              </div>
            )}
            <ul className="planFeatures">
              {p.bullets.map((b, i) => (
                <li key={i} className="planFeature">
                {b.endsWith("*") ? (
                  <span className="tooltipWrapper">
                    {b}
                    <span className="tooltipText">
                      {b.startsWith("Free 60-day trial")
                        ? <>A ReARM Pro Starter instance is provided during the trial, subject to Terms of Service.<br />After the trial, you may:<br />- continue with a paid ReARM Pro plan;<br />- export your data and switch to a self-hosted FOSS ReARM CE instance;<br />- or cancel altogether.</>

                        : "Usually, enough to store more than 200,000 SBOMs"}
                    </span>
                  </span>
                ) : b}
              </li>
              ))}
            </ul>
            <a href={p.cta.href} target="_blank" rel="noreferrer" className="planCta">
              {p.cta.label}
            </a>
          </div>
        ))}
      </div>
    </div>
  );
}
