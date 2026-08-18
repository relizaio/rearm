---
title: "ReARM CE at Black Hat India 2026"
date: "2026-08-18"
---

We are excited to announce that ReARM CE has been selected for the **Arsenal** at [Black Hat India 2026](https://www.blackhat-india.com/arsenal-schedule#rearm-ce-answer-the-2-am-where-is-this-cve-shipping-question-across-your-whole-portfolio-60007) in Bengaluru!

Rhythm Garg, who leads core engineering for ReARM at Reliza, will demo **ReARM CE: answer the 2 AM "where is this CVE shipping?" question across your whole portfolio** live on **Thursday, October 29, 12:00 PM at Arsenal Station 2 in the Black Hat Business Hall**, as part of the **Vulnerability Assessment** track.

Three years into the SBOM mandate era, every build emits a CycloneDX or SPDX document - and almost nobody can query them. When a critical CVE drops, most teams still answer "is this dependency anywhere in our supply chain, and in which releases?" by grepping a bucket of JSON and asking around on Slack. Coverage without queryability is compliance theater. ReARM CE turns an accumulating pile of SBOMs, xBOMs, and security findings into a queryable, auditable evidence store, and at this Arsenal station attendees will drive it hands-on across three things tooling consistently can't do:

- **Portfolio forensics** - paste a PURL and get every affected release across a multi-component portfolio in seconds, including products affected only through bundling; batch a real multi-package advisory; answer "what did we ship to customer X in March?"
- **VEX as a workflow** - import a deliberately contradictory multi-statement CycloneDX VEX and watch it accept, stage, and reject statement by statement through a trust gate, then export your own triage downstream.
- **Posture over time + agent observability** - diff a release's security posture between two points in time, and inspect ReARM CE's records of what autonomous agents did in the pipeline.

Everything demoed is fully open source (AGPL-3.0), self-hostable, and reproducible against a public portfolio - you can manually install [ReARM CE](https://github.com/relizaio/rearm) and run every demo yourself.

If you are attending Black Hat India 2026 (October 29-30, Sheraton Grand at Brigade Gateway, Bengaluru), stop by Arsenal Station 2 on October 29 - Rhythm would love to show you how ReARM turns SBOM compliance artifacts into a queryable, auditable release governance platform.
