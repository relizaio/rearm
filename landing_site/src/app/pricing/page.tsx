import type { Metadata } from "next";
import PricingPlan from "@/components/PricingPlan";

export const metadata: Metadata = {
  title: "Pricing - ReARM by Reliza",
  description: "ReARM pricing plans: Community Edition, Pro (Startup/Standard), and Enterprise.",
};

export default function PricingPage() {
  return (
    <main className="container py-5">
      <section className="text-center py-5">
        <h1 className="display-5 fw-bold mb-3">Pricing</h1>
        <p className="mx-auto" style={{ maxWidth: 720, fontSize: "1.1rem" }}>
          One tool for your whole team needs. Try ReARM Pro free for 90 days. No credit card required.
        </p>
      </section>

      <section className="mb-5">
        <PricingPlan />
      </section>

      <section className="integration-getStarted text-center mb-5">
        <p className="mb-3 fw-semibold mb-4">Questions about product or pricing?</p>
        <h1 className="text-center mx-auto" style={{ maxWidth: "650px" }}>Book demo with us!</h1>
        <a href="https://calendly.com/pavel_reliza/demo" target="_blank" rel="noopener noreferrer" className="contactUs-btn_ContactUs fw-bold">
          Book Private Demo
        </a>
      </section>

      <section className="text-center mb-5">
        <h2 className="h4 fw-semibold mb-2">Frequently Asked Questions</h2>
        <p>Reach us at <a className="link-active" href="https://calendly.com/pavel_reliza/demo" target="_blank" rel="noopener noreferrer">Contact us</a></p>
      </section>
    </main>
  );
}
