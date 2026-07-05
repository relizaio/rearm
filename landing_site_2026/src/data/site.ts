// Central site data: navigation, links, and the design's page data
// (extracted verbatim from the claude-design handoff canvas).
import design from './design.json';

export const links = {
  privateDemo: 'https://calendly.com/pavel-reliza/30min',
  publicDemo: 'https://demo.rearmhq.com',
  docs: 'https://docs.rearmhq.com',
  discord: 'https://discord.gg/UTxjBf9juQ',
  github: 'https://github.com/relizaio/rearm',
  datasheet: 'https://d7ge14utcyki8.cloudfront.net/ReARM_Product_Info_Datasheet_v6_2026-04-16.pdf',
  // TODO: comparisons not yet ported to the 2026 site; absolute link to current site.
  comparisons: 'https://rearmhq.com/comparisons',
};

export const productNav = [
  { label: 'SBOM/xBOM Management', href: '/product/sbom-xbom-management/' },
  { label: 'Findings Aggregation', href: '/product/findings-aggregation/' },
  { label: 'Release Policies', href: '/product/release-policies/' },
  { label: 'AI Governance', href: '/product/ai-governance/' },
];

export const useCaseNav = [
  { label: 'Medical Devices', href: '/use-cases/medical-devices/' },
  { label: 'Field Digital Twin', href: '/use-cases/field-digital-twin/' },
  { label: 'Deployment Governance', href: '/use-cases/deployment-governance/' },
  { label: 'EU CRA Compliance', href: '/use-cases/eu-cra/' },
];

// page-name -> href used by capability cards / question rows / related links
export const pageHrefs: Record<string, string> = {
  'SBOM/xBOM Management': '/product/sbom-xbom-management/',
  'Findings Aggregation': '/product/findings-aggregation/',
  'Release Policies': '/product/release-policies/',
  'AI Governance': '/product/ai-governance/',
  'Medical Devices': '/use-cases/medical-devices/',
  'Field Digital Twin': '/use-cases/field-digital-twin/',
  'Deployment Governance': '/use-cases/deployment-governance/',
  'EU CRA': '/use-cases/eu-cra/',
  'EU CRA Compliance': '/use-cases/eu-cra/',
  Pricing: '/pricing/',
};

export type ChainLevel = { name: string; c1: string; c2: string; on: boolean };
export type PageBlock = { num: string; title: string; body: string; callout?: string; shot: string };
export type ProdPage = {
  id: string; path: string; label: string; h1: string; problem: string;
  chain: ChainLevel[]; hasRelated: boolean; related: string[]; blocks: PageBlock[];
};
export type UcStep = { num: string; title: string; body: string; cap: string };
export type UcPage = {
  id: string; path: string; label: string; persona: string; h1: string; pain: string;
  a1: string; a2: string; stripLabel: string; strip: string[]; evidence: string;
  publicDemo: boolean; shot: string | false; note: string | false; steps: UcStep[];
};

export const levels = design.levels as string[];
export const grads = design.grads as [string, string][];
export const caps = design.caps as { name: string; line: string; shot: string; link: string }[];
export const useCasePanels = design.useCasePanels as { q: string; name: string; c1: string; c2: string }[];
export const questions = design.questions as { q: string; page: string }[];
export const findBlocks = design.findBlocks as PageBlock[];
export const prodPages = design.prodPages as unknown as ProdPage[];
export const ucPages = design.ucPages as unknown as UcPage[];
export const tiers = design.tiers as {
  name: string; price: string; unit: string; border: string; bg: string;
  popular: boolean; cta: string; features: string[];
}[];

// full chain (all levels lit) for home + findings bands
export const fullChain: ChainLevel[] = levels.map((name, i) => ({
  name, c1: grads[i][0], c2: grads[i][1], on: true,
}));
