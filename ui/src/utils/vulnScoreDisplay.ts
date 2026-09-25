// Display rules for vulnerability score lists (VulnerabilityRecordDetails.scores
// and each source's scores). Pure functions, so the ordering and number
// formatting are unit-tested without mounting the panel.

import type { VulnScore, VulnScoreType, VulnSubScore, VulnSubScoreType } from './vulnerabilityRecordService'

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
