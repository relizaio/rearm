import React from "react";
import type { Metadata } from "next";
import Link from "next/link";

const baseUrl = (process.env.NEXT_PUBLIC_BASE_URL ?? "https://rearmhq.com").replace(/\/$/, "");

export const metadata: Metadata = {
  title: "Pricing FAQ - ReARM by Reliza",
  description: "Frequently asked questions about ReARM pricing, infrastructure, and plans.",
  alternates: { canonical: `${baseUrl}/pricing-faq/` },
  openGraph: {
    title: "Pricing FAQ - ReARM by Reliza",
    description: "Frequently asked questions about ReARM pricing, infrastructure, and plans.",
    url: `${baseUrl}/pricing-faq/`,
    type: "website",
    siteName: "ReARM - Release-Level Supply Chain Evidence Platform by Reliza",
  },
};

type FaqItem = {
  question: string;
  answer: React.ReactNode[];
};

const faqs: FaqItem[] = [
  {
    question: "How is the number of users calculated?",
    answer: [
      "Each paid seat corresponds to one write user — someone who can create, modify, or manage releases, components, artifacts, and settings within ReARM.",
      "For ReARM Pro Standard and Enterprise plans, for every 2 write users we include one additional read-only user at no extra cost. Read-only users can view releases, artifacts, and dashboards but cannot make changes.",
      "For example, on Standard or Enterprise, if you purchase a plan for 20 write users, you get up to 10 additional read-only users (30 total).",
    ],
  },
  {
    question: "What is the difference in infrastructure between tiers?",
    answer: [
      "Starter — Your ReARM instance runs on a single node and artifact storage limited to 100GB (you will be offered to migrate to Standard tier if you exceed this limit). This is ideal for small teams getting started with supply chain evidence management.",
      "Standard — Your ReARM instance runs in a dedicated private VNet / VPC or self-hosted in a non-air-gapped environment.",
      "Enterprise — In addition to the private VNet / VPC and self-hosted options, the Enterprise tier is the only plan where we support fully air-gapped environments.",
      "For self-hosted deployments under Standard and Enterprise tiers, Reliza provides support for the deployment and maintenance of the ReARM instance, including setting up of regular backups and monitoring.",
    ],
  },
  {
    question: "Is the infrastructure shared with other customers?",
    answer: [
      "No. Regardless of which tier you choose, you always get your own dedicated instance of ReARM.",
    ],
  },
  {
    question: "Why should I choose ReARM Pro if I don't need the additional features?",
    answer: [
      "Even if you only need the core SBOM/xBOM storage and retrieval functionality available in ReARM CE, ReARM Pro can still be the more economical choice.",
      "We believe that the price we offer for ReARM Pro is less than what you would typically pay for your own infrastructure plus ongoing maintenance, monitoring, backups, and upgrades.",
      "On top of that, you get premium support with guaranteed response times, a managed Dependency-Track instance, and automatic upgrades - all handled by Reliza.",
      "ReARM Pro also includes the ability to arrange custom commercial terms.",
      <>See <Link href="/comparisons/#rearm-pro-vs-rearm-ce">ReARM Pro vs ReARM CE comparison</Link> for details.</>,
    ],
  },
  {
    question: "Is it possible to upgrade from ReARM CE to ReARM Pro or vice versa?",
    answer: [
      "Yes, the data schema is fully compatible between ReARM editions. You can upgrade or downgrade at any time subject to any applicable contract terms.",
      "When you downgrade from ReARM Pro to ReARM CE, you lose access to any ReARM Pro features but all your data remains intact.",
      "When you upgrade from ReARM CE to ReARM Pro, you gain access to all ReARM Pro features right away.",
    ],
  },
];

export default function PricingFaqPage() {
  return (
    <main className="pricingFaqContainer">
      <div className="pricingFaqHeader">
        <h1 className="pricingFaqTitle">Pricing FAQ</h1>
        <p className="pricingFaqSubtitle">Common questions about ReARM plans, pricing, and infrastructure</p>
      </div>

      <div className="pricingFaqList">
        {faqs.map((faq, i) => (
          <div key={i} className="pricingFaqItem">
            <h2 className="pricingFaqQuestion">{faq.question}</h2>
            {faq.answer.map((paragraph, j) => (
              <p key={j} className="pricingFaqAnswer">{paragraph}</p>
            ))}
          </div>
        ))}
      </div>

      <div className="pricingFaqBack">
        <Link href="/#homePagePricing" className="pricingFaqBackLink">← Back to Pricing</Link>
      </div>
    </main>
  );
}
