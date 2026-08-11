---
title: "ReARM Meets ReArm: Release Governance for the €800B Rearmament"
date: "2026-07-23"
---

ReARM stands for Reliza's Artifact and Release Management. Then Europe announced
[ReArm Europe](https://commission.europa.eu/topics/defence/future-european-defence_en)
- an up-to-€800-billion plan, since rebranded Readiness 2030 - and handed us a
naming coincidence. But behind it sits a genuinely structural fit between what
this rearmament wave demands from suppliers and what a release governance
platform does.

## The landscape: SAFE, and Canada inside it

The flagship funding instrument of ReArm Europe is
[SAFE (Security Action for Europe)](https://defence-industry-space.ec.europa.eu/eu-defence-industry/safe-security-action-europe_en)
- a €150 billion loan instrument for joint defense procurement, in force since
May 2025. And in a development that matters to us personally as an
Ottawa-based company: on February 14, 2026, Canada [signed an agreement to
participate in SAFE](https://www.international.gc.ca/world-monde/international_relations-relations_internationales/eu-ue/agreement-accord.aspx?lang=eng),
becoming the first non-European country inside the EU's defense-financing
architecture. Canadian content can now comprise up to **80% of the total
value** of a procurement under SAFE (with at least 20% remaining EU,
EEA-EFTA, or Ukraine content) - against the 35% cap that applies to other
third countries.

So the two "rearms" we sit between are literal: Europe's, and the
Canadian-industrial one now plugged into it.

## Component origin rules are a BOM problem

Here is the substantive part. [Council Regulation (EU)
2025/1106](https://eur-lex.europa.eu/eli/reg/2025/1106/oj), which establishes
SAFE, carries strict eligibility requirements: contractors and subcontractors
must generally be established in the EU, EEA-EFTA states, or Ukraine, must
not be controlled by non-eligible third countries, and components originating
elsewhere are capped at 35% of the estimated cost. And this is not a SAFE
quirk - it is the standard template of the EU's entire defense-industrial
stack. [EDIRPA](https://eur-lex.europa.eu/eli/reg/2023/2418/oj) introduced
the same 65/35 origin rule for subsidized common procurement in 2023, and
[EDIP](https://eur-lex.europa.eu/eli/reg/2025/2643/oj), in force since
December 2025, repeats it and adds a further condition with direct software
relevance: the *design authority* - whoever controls the design of the
product - must generally sit in the EU, and no component may be sourced in
breach of EU security interests.

Every prime bidding into any of these programs therefore has to answer, for
every product, a question that sounds very familiar: **what is this thing
made of, who controls each part, and where does it come from?** That is a
Bill of Materials problem - hardware and software at once. It is structurally
the same question the US just asked its own defense base [in last week's
Executive Order on indentured
BOMs](/news/2026-07-22-us-eo-dod-dow-ibom-defense-supply-chains/): trace
components to their origin, attach supplier identity, prove it per release,
and keep proving it as the product evolves. Two different jurisdictions, two
different policy motivations, one identical evidence requirement landing on
suppliers.

The same honest caveat we gave for the US order applies here: no platform can
derive origin data your suppliers never declared - collecting it is a
supply-chain exercise. What a release governance platform does is make
declared origin and supplier data queryable across your whole portfolio,
versioned per release, and show you exactly which nodes still lack it before
a prime's questionnaire does.

## EDIP makes supply chain visibility a standing obligation

Origin caps are conditions you meet to win funding. EDIP goes further: its
security-of-supply chapter turns supply chain visibility into a standing
legal regime. The European Commission is now mandated to map, track, and
monitor defense supply chains, production capacity, and key market actors,
supported by a new Defence Security of Supply Board. Suppliers of
"crisis-relevant products" - defined to include not just defense goods but
their **components, raw materials, and critical services** - must notify
authorities when they detect supply disruptions. In a declared supply-crisis
state, the Commission can demand supply chain information directly from
companies, with fines of up to €300,000 for false information, and can issue
priority orders backed by penalty payments. These obligations cascade:
subcontractors and component suppliers qualify regardless of whether they
produce defense products directly.

Software is squarely inside this. Cyber and critical infrastructure
protection are explicit SAFE spending categories, and modern defense
platforms are software-defined regardless of category. The legal plumbing
for flow-downs is not even new - the EU's [defense procurement
directive](https://eur-lex.europa.eu/eli/dir/2009/81/oj) has long allowed
contracting authorities to demand security-of-supply commitments and
subcontractor disclosure. The new programs give primes €150 billion worth of
reasons to actually use it, and EDIP gives regulators the power to ask
directly. Horizontal EU regulation stacks on top: the [Cyber Resilience
Act](https://eur-lex.europa.eu/eli/reg/2024/2847/oj) keeps dual-use products
- most of what software SMEs actually sell into defense supply chains -
fully in scope, and the [Conflict Minerals
Regulation](https://eur-lex.europa.eu/eli/reg/2017/821/oj) already imposes
due-diligence duties on importers of tin, tantalum, tungsten, and gold - an
uncanny mirror of the raw-materials list in the US order.

Answering a mapping demand, a disruption notification, or an origin
questionnaire all draws on the same artifact: a complete, current,
per-release BOM.

## The SME wave

Both rearmaments are explicitly trying to pull small and mid-size companies
into defense supply chains - the EU tracks SME participation in national
plans, and Canada's defence industrial strategy pushes the same direction.
That means thousands of software-heavy companies entering defense work for
the first time, most with no compliance tooling beyond a folder of PDFs.

For that cohort, our advice is the same as it was for the US order: start
with the per-release BOM practice, because every downstream requirement -
origin caps, mapping demands, vetting questionnaires - draws on it.
[ReARM](https://rearmhq.com) stores SBOMs, HBOMs, and other xBOMs per
release, carries supplier and origin data on components, diffs releases over
time, and keeps compliance evidence attached to the exact releases it covers.
[ReARM CE](https://github.com/relizaio/rearm) is fully open source (AGPL-3.0)
and self-hostable, and ReARM Pro can be deployed entirely inside your own
environment, including air-gapped networks - which is where defense supply
chain data tends to want to live.

One expectation-setting note: defense procurement moves slowly, and SAFE
money flows to primes first. The reason to act now is not that a contract
lands next quarter - it is that primes de-risk *their* eligibility by pushing
BOM and origin requirements down to suppliers early, in questionnaires and
subcontract terms, long before any regulation compels you directly.

If your products might end up anywhere in Europe's or Canada's rearmament
supply chains, we would love to help - [reach
out](https://calendly.com/pavel-reliza/30min) or deploy [ReARM
CE](https://github.com/relizaio/rearm) today.
