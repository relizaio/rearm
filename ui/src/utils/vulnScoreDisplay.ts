// Display rules for vulnerability score lists (VulnerabilityRecordDetails.scores
// and each source's scores). Pure functions, so the ordering and number
// formatting are unit-tested without mounting the panel.

import type { RiskSummary, VulnScore, VulnScoreType, VulnSubScore, VulnSubScoreType } from './vulnerabilityRecordService'

// Newest CVSS first, then EPSS, then OWASP. The backend returns lists in
// VulnScoreType declaration order (CVSS_V2 first). A type this build does not
// know yet sorts last and shows its raw name.
export const SCORE_TYPE_ORDER: VulnScoreType[] = ['CVSS_V4', 'CVSS_V3', 'CVSS_V2', 'EPSS', 'OWASP_RR']

const SCORE_TYPE_LABELS: Record<VulnScoreType, string> = {
    CVSS_V4: 'CVSS v4',
    CVSS_V3: 'CVSS v3',
    CVSS_V2: 'CVSS v2',
    EPSS: 'EPSS',
    OWASP_RR: 'OWASP Risk Rating'
}

const SUB_SCORE_LABELS: Record<VulnSubScoreType, string> = {
    IMPACT: 'Impact',
    EXPLOITABILITY: 'Exploitability',
    PERCENTILE: 'Percentile',
    LIKELIHOOD: 'Likelihood',
    TECHNICAL_IMPACT: 'Technical impact',
    BUSINESS_IMPACT: 'Business impact'
}

export function orderScores (scores: VulnScore[] | undefined | null): VulnScore[] {
    const rank = (t: VulnScoreType) => {
        const i = SCORE_TYPE_ORDER.indexOf(t)
        return i < 0 ? SCORE_TYPE_ORDER.length : i
    }
    return [...(scores || [])].sort((a, b) => rank(a.type) - rank(b.type))
}

export function scoreTypeLabel (type: VulnScoreType): string {
    return SCORE_TYPE_LABELS[type] || type
}

// EPSS numbers are fractions (0..1). As percentages they stay readable at
// both ends: 0.00043 is "0.04%", and a 0.9962 percentile is "99.62%" rather
// than rounding to "1.00".
function percent (fraction: number): string {
    return `${(fraction * 100).toFixed(2)}%`
}

/** Hover text of a score ReARM computed from the published vector. */
export const COMPUTED_FROM_VECTOR_TITLE = 'The source published only the vector; ReARM computed the score from it'

/** Upstream published only the vector and ReARM computed the score. */
export function isComputedFromVector (sc: VulnScore): boolean {
    return sc.scoreSource === 'COMPUTED_FROM_VECTOR'
}

export function formatPrimaryScore (sc: VulnScore): string {
    if (sc.score == null) return '-'
    return sc.type === 'EPSS' ? percent(sc.score) : sc.score.toFixed(1)
}

function formatSubScore (sub: VulnSubScore): string {
    const value = sub.type === 'PERCENTILE' ? percent(sub.value) : sub.value.toFixed(1)
    return `${SUB_SCORE_LABELS[sub.type] || sub.type} ${value}`
}

/** "Impact 3.6 / Exploitability 3.9", "Percentile 66.51%", ... */
export function formatSubScores (sc: VulnScore): string {
    return (sc.subScores || []).map(formatSubScore).join(' / ')
}

/** One-line summary of a source's scores: "CVSS v3 5.9, EPSS 2.62%". Entries without a primary score are skipped. */
export function summarizeScores (scores: VulnScore[] | undefined | null): string {
    return orderScores(scores)
        .filter(sc => sc.score != null)
        .map(sc => `${scoreTypeLabel(sc.type)} ${formatPrimaryScore(sc)}`)
        .join(', ')
}

// Headline CVSS precedence, the same rule as the backend's VulnScore.topCvss:
// the newest CVSS version that has a score.
const HEADLINE_CVSS_ORDER: VulnScoreType[] = ['CVSS_V4', 'CVSS_V3', 'CVSS_V2']

/** The headline CVSS entry of a score list, or null when no CVSS entry has a score. */
export function topCvssOf (scores: VulnScore[] | undefined | null): VulnScore | null {
    for (const type of HEADLINE_CVSS_ORDER) {
        const sc = (scores || []).find(s => s.type === type && s.score != null)
        if (sc) return sc
    }
    return null
}

/** Score fields a findings row carries when its query selected them. */
export interface ScoredFinding {
    scores?: VulnScore[] | null
    topScore?: VulnScore | null
    epss?: VulnScore | null
}

/** A finding row's headline CVSS: the backend's topScore, else derived from its score list. */
export function rowTopScore (row: ScoredFinding): VulnScore | null {
    return row.topScore ?? topCvssOf(row.scores)
}

/** A finding row's EPSS entry, or null. */
export function rowEpss (row: ScoredFinding): VulnScore | null {
    return row.epss ?? (row.scores || []).find(s => s.type === 'EPSS' && s.score != null) ?? null
}

// The entry with the highest score among the rows' picks; the first row wins a tie.
function highest (rows: ScoredFinding[], pick: (row: ScoredFinding) => VulnScore | null): VulnScore | null {
    let best: VulnScore | null = null
    for (const row of rows) {
        const sc = pick(row)
        if (sc?.score != null && (best?.score == null || sc.score > best.score)) best = sc
    }
    return best
}

/** The highest headline CVSS over a group of findings. */
export function worstScoreOf (rows: ScoredFinding[]): VulnScore | null {
    return highest(rows, rowTopScore)
}

/** The highest EPSS over a group of findings. */
export function maxEpssOf (rows: ScoredFinding[]): VulnScore | null {
    return highest(rows, rowEpss)
}

/** Whether any row carries a score list, i.e. its query selected the score fields and the backend served them. */
export function hasScoreFields (rows: ScoredFinding[]): boolean {
    return rows.some(row => Array.isArray(row.scores))
}

/** Sort key for a score column: the number, or -1 when there is none, so a descending sort puts unscored rows last. */
export function scoreSortValue (sc: VulnScore | null): number {
    return sc?.score ?? -1
}

export type CvssBand = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE'

/** CVSS qualitative band of a score, the backend's bands: 9.0 / 7.0 / 4.0 / 0.1. */
export function cvssBandOf (score: number): CvssBand {
    if (score >= 9.0) return 'CRITICAL'
    if (score >= 7.0) return 'HIGH'
    if (score >= 4.0) return 'MEDIUM'
    if (score >= 0.1) return 'LOW'
    return 'NONE'
}

/** Text of a release header pill: the label shown and its hover title. */
export interface RiskPill {
    label: string
    title: string
}

/** "CVSS 9.8": the release's highest headline CVSS, or null when none is scored. */
export function cvssPillOf (summary: RiskSummary | null | undefined): RiskPill | null {
    if (summary?.maxCvss == null) return null
    const score = summary.maxCvss.toFixed(1)
    const type = summary.maxCvssType ? scoreTypeLabel(summary.maxCvssType) : 'CVSS'
    return {
        label: `CVSS ${score}`,
        title: `Highest ${type} score ${score}${summary.maxCvssVulnId ? ` (${summary.maxCvssVulnId})` : ''}; `
            + `${summary.scoredFindings ?? 0} of ${summary.totalFindings ?? 0} open findings scored. Click to list findings by score.`
    }
}

/** "EPSS 92.00%": the release's highest exploit probability, or null when none. */
export function epssPillOf (summary: RiskSummary | null | undefined): RiskPill | null {
    if (summary?.maxEpss == null) return null
    const probability = percent(summary.maxEpss)
    return {
        label: `EPSS ${probability}`,
        title: `Highest exploit probability (EPSS) ${probability}${summary.maxEpssVulnId ? ` (${summary.maxEpssVulnId})` : ''}; `
            + `${summary.epssAtLeastTenPercent ?? 0} open findings at 10% or more. Click to list findings by EPSS.`
    }
}
