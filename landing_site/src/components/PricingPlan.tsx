"use client";
import { useEffect, useMemo, useState } from "react";
import Flag from "react-world-flags";

interface RegionPrices {
  regionType: RegionType;
  currencySymbol: string;
  startupBase: number;
  startupPerUser: number;
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
  let startupBase = 107;
  let startupPerUser = 38;
  let standardPerUser = 60;
  let enterprise = "$68";

  if (regionType === "EU") {
    currencySymbol = "€";
    startupBase = 92;
    startupPerUser = 32;
    standardPerUser = 51;
    enterprise = "€58";
  } else if (regionType === "CA") {
    currencySymbol = "C$";
    startupBase = 149;
    startupPerUser = 53;
    standardPerUser = 83;
    enterprise = "C$95";
  } else if (regionType === "GB") {
    currencySymbol = "£";
    startupBase = 80;
    startupPerUser = 28;
    standardPerUser = 44;
    enterprise = "£51";
  } else if (regionType === "AU") {
    currencySymbol = "A$";
    startupBase = 165;
    startupPerUser = 57;
    standardPerUser = 90;
    enterprise = "A$103";
  } else if (regionType === "SG") {
    currencySymbol = "S$";
    startupBase = 139;
    startupPerUser = 50;
    standardPerUser = 78;
    enterprise = "S$89";
  }

  return { regionType, currencySymbol, startupBase, startupPerUser, standardPerUser, enterprise };
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
  const [standardUsers, setStandardUsers] = useState(20);
  
  // Calculate Startup price: $99 base for 1 user, then $30 per additional user
  const startupPrice = useMemo(() => {
    const price = prices.startupBase + (startupUsers - 1) * prices.startupPerUser;
    return `${prices.currencySymbol}${price}`;
  }, [startupUsers, prices]);
  
  // Calculate Standard price: $66 per user, no minimum
  const standardPrice = useMemo(() => {
    const price = standardUsers * prices.standardPerUser;
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
        "Up to 100GB of storage for compressed artifacts*",
        "Priority Support (response within 8 hours)",
        "Managed Dependency-Track",
        "Managed Single Organization Service",
        "Approvals & Triggers",
        "Marketing Releases",
        "Free 90-day trial",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
      userSelector: {
        min: 1,
        max: 19,
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
        "Unlimited storage",
        "Premium support (24x7)",
        "Managed Dependency-Track",
        "Private Managed Service with SSO and unlimited storage",
        "Approvals & Triggers",
        "Marketing Releases",
        "Support for Multi-Organization Workflow",
        "Free 90-day trial",
      ],
      cta: { label: "Contact Sales", href: "mailto:sales@reliza.io" },
      userSelector: {
        min: 20,
        max: 39,
        value: standardUsers,
        onChange: setStandardUsers,
      },
    },
    {
      id: 3,
      title: "ReARM Pro - Enterprise",
      amount: prices.enterprise,
      type: "per user per month",
      bullets: [
        "Premium support (24x7)",
        "Managed Dependency-Track",
        "Private Managed Service with SSO and unlimited storage, or on‑prem deployment (air-gap ready)",
        "Approvals & Triggers",
        "Marketing Releases",
        "Support for Multi-Organization Workflow",
        "Free 90-day trial",
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
                  Number of users ({p.userSelector.min}-{p.userSelector.max}):
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
            {p.id === 3 && (
              <div className="userSelector">
                <div className="userSelectorLabel">40+ users</div>
              </div>
            )}
            <ul className="planFeatures">
              {p.bullets.map((b, i) => (
                <li key={i} className="planFeature">
                {b.endsWith("*") ? (
                  <span className="tooltipWrapper">
                    {b}
                    <span className="tooltipText">Usually, enough to store more than 300,000 SBOMs</span>
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
