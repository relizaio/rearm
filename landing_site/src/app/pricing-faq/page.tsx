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
  answer: string[];
};

const faqs: FaqItem[] = [
  {
    question: "How is the number of users calculated?",
    answer: [
      "Each paid seat corresponds to one write user — someone who can create, modify, or manage releases, components, artifacts, and settings within ReARM.",
      "For every write user on your plan, we include one additional read-only user at no extra cost. Read-only users can view releases, artifacts, and dashboards but cannot make changes.",
      "For example, if you purchase a plan for 5 users, you get 5 write users and up to 5 read-only users (10 total).",
    ],
  },
  {
    question: "What is the difference in infrastructure between tiers?",
    answer: [
      "Starter — Your ReARM instance runs on a single node and artifact storage limited to 100GB (you will be offered to migrate to Standard tier if you exceed this limit). This is ideal for small teams getting started with supply chain evidence management.",
      "Standard — Your ReARM instance runs in a dedicated private VNet / VPC.",
      "Enterprise — In addition to the private VNet / VPC option, the Enterprise tier is the only plan where we support on-premise deployments, including fully air-gapped environments.",
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
