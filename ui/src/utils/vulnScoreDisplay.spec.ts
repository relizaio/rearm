import { describe, it, expect } from 'vitest'
import {
    orderScores, formatPrimaryScore, formatSubScores, summarizeScores, scoreTypeLabel, isComputedFromVector,
    topCvssOf, rowTopScore, rowEpss, worstScoreOf, maxEpssOf, hasScoreFields, scoreSortValue,
    cvssBandOf, cvssPillOf, epssPillOf
} from './vulnScoreDisplay'
import type { VulnScore } from './vulnerabilityRecordService'

function score (partial: Partial<VulnScore> & Pick<VulnScore, 'type'>): VulnScore {
    return { subScores: [], ...partial }
}

describe('vulnerability score display', () => {
    it('orders newest CVSS first, then EPSS, then OWASP, unknown types last', () => {
        const ordered = orderScores([
            score({ type: 'CVSS_V2', score: 5 }),
            score({ type: 'OWASP_RR' }),
            score({ type: 'FUTURE_TYPE' as any, score: 1 }),
            score({ type: 'EPSS', score: 0.1 }),
            score({ type: 'CVSS_V4', score: 8.7 }),
            score({ type: 'CVSS_V3', score: 7.5 })
        ])
        expect(ordered.map(s => s.type)).toEqual(['CVSS_V4', 'CVSS_V3', 'CVSS_V2', 'EPSS', 'OWASP_RR', 'FUTURE_TYPE'])
        expect(scoreTypeLabel('FUTURE_TYPE' as any)).toBe('FUTURE_TYPE')
        expect(orderScores(undefined)).toEqual([])
    })

    it('formats CVSS to one decimal and EPSS as a percentage', () => {
        expect(formatPrimaryScore(score({ type: 'CVSS_V3', score: 7.5 }))).toBe('7.5')
        expect(formatPrimaryScore(score({ type: 'EPSS', score: 0.00043 }))).toBe('0.04%')
        expect(formatPrimaryScore(score({ type: 'OWASP_RR' }))).toBe('-')
    })

    it('formats sub-scores in the order given, percentile as a percentage', () => {
        expect(formatSubScores(score({ type: 'CVSS_V3', score: 7.5,
            subScores: [{ type: 'IMPACT', value: 3.6 }, { type: 'EXPLOITABILITY', value: 3.9 }] })))
            .toBe('Impact 3.6 / Exploitability 3.9')
        // 0.9962 must not round up to a meaningless "1.00".
        expect(formatSubScores(score({ type: 'EPSS', score: 0.5, subScores: [{ type: 'PERCENTILE', value: 0.9962 }] })))
            .toBe('Percentile 99.62%')
        expect(formatSubScores(score({ type: 'OWASP_RR', subScores: [
            { type: 'LIKELIHOOD', value: 1.2 }, { type: 'TECHNICAL_IMPACT', value: 2 }, { type: 'BUSINESS_IMPACT', value: 3.25 }] })))
            .toBe('Likelihood 1.2 / Technical impact 2.0 / Business impact 3.3')
    })

    it('flags only scores computed from the vector', () => {
        expect(isComputedFromVector(score({ type: 'CVSS_V3', score: 7.4, scoreSource: 'COMPUTED_FROM_VECTOR' }))).toBe(true)
        expect(isComputedFromVector(score({ type: 'CVSS_V3', score: 7.4, scoreSource: 'UPSTREAM' }))).toBe(false)
        expect(isComputedFromVector(score({ type: 'CVSS_V3', score: 7.4 }))).toBe(false)
    })

    it('summarizes a source by its primary scores', () => {
        expect(summarizeScores([
            score({ type: 'EPSS', score: 0.02617 }),
            score({ type: 'CVSS_V3', score: 5.9 }),
            score({ type: 'OWASP_RR', subScores: [{ type: 'LIKELIHOOD', value: 1 }] })
        ])).toBe('CVSS v3 5.9, EPSS 2.62%')
        expect(summarizeScores([score({ type: 'CVSS_V3', vector: 'CVSS:3.1/AV:N' })])).toBe('')
    })
})

describe('headline scores of a finding and a group', () => {
    const v2 = score({ type: 'CVSS_V2', score: 10 })
    const v3 = score({ type: 'CVSS_V3', score: 7.5 })
    const v4 = score({ type: 'CVSS_V4', score: 6.1 })
    const epss = (p: number) => score({ type: 'EPSS', score: p, subScores: [{ type: 'PERCENTILE', value: 0.9 }] })

    it('picks CVSS v4, else v3, else v2, skipping an entry without a score', () => {
        expect(topCvssOf([v2, v3, v4])).toBe(v4)
        expect(topCvssOf([v2, v3, score({ type: 'CVSS_V4', vector: 'CVSS:4.0/AV:N' })])).toBe(v3)
        expect(topCvssOf([v2, epss(0.5)])).toBe(v2)
        expect(topCvssOf([epss(0.5)])).toBeNull()
        expect(topCvssOf(undefined)).toBeNull()
    })

    it('prefers the backend topScore and EPSS, deriving them from the list only when absent', () => {
        expect(rowTopScore({ topScore: v3, scores: [v4] })).toBe(v3)
        expect(rowTopScore({ scores: [v2, v4] })).toBe(v4)
        expect(rowTopScore({})).toBeNull()
        const e = epss(0.2)
        expect(rowEpss({ epss: e })).toBe(e)
        expect(rowEpss({ scores: [v3, e] })).toBe(e)
        expect(rowEpss({ scores: [v3] })).toBeNull()
    })

    it('takes the highest score and EPSS over a group, the first row on a tie', () => {
        const tie = score({ type: 'CVSS_V3', score: 7.5 })
        const rows = [{ topScore: v4 }, { topScore: v3 }, { topScore: tie }, { topScore: null }, { epss: epss(0.3) }, { epss: epss(0.7) }]
        expect(worstScoreOf(rows)).toBe(v3)
        expect(maxEpssOf(rows)?.score).toBe(0.7)
        expect(worstScoreOf([{}, { topScore: null }])).toBeNull()
        expect(maxEpssOf([])).toBeNull()
    })

    it('knows rows carry scores only when the list is there', () => {
        expect(hasScoreFields([{}, { scores: [] }])).toBe(true)
        expect(hasScoreFields([{}, { topScore: null }])).toBe(false)
        expect(hasScoreFields([])).toBe(false)
    })

    it('sorts an unscored row below any score, including 0.0', () => {
        expect(scoreSortValue(null)).toBeLessThan(scoreSortValue(score({ type: 'CVSS_V3', score: 0 })))
        expect(scoreSortValue(v3)).toBe(7.5)
    })
})

describe('release header pills', () => {
    it('bands a CVSS score at the qualitative boundaries', () => {
        expect([9.0, 8.9, 7.0, 6.9, 4.0, 3.9, 0.1, 0.0].map(cvssBandOf))
            .toEqual(['CRITICAL', 'HIGH', 'HIGH', 'MEDIUM', 'MEDIUM', 'LOW', 'LOW', 'NONE'])
    })

    it('labels the highest CVSS with its version, finding and coverage', () => {
        const pill = cvssPillOf({ maxCvss: 9.8, maxCvssType: 'CVSS_V3', maxCvssVulnId: 'CVE-2021-44228', scoredFindings: 40, totalFindings: 52 })
        expect(pill?.label).toBe('CVSS 9.8')
        expect(pill?.title).toContain('CVSS v3')
        expect(pill?.title).toContain('CVE-2021-44228')
        expect(pill?.title).toContain('40 of 52 open findings scored')
    })

    it('labels the highest EPSS as a percentage with the count at 10% or more', () => {
        const pill = epssPillOf({ maxEpss: 0.92, maxEpssVulnId: 'CVE-2021-44228', epssAtLeastTenPercent: 3 })
        expect(pill?.label).toBe('EPSS 92.00%')
        expect(pill?.title).toContain('3 open findings at 10% or more')
    })

    it('shows no pill without a summary or without a scored finding', () => {
        expect(cvssPillOf(null)).toBeNull()
        expect(cvssPillOf({ maxCvss: null, totalFindings: 3, scoredFindings: 0 })).toBeNull()
        expect(epssPillOf(undefined)).toBeNull()
        expect(epssPillOf({ maxEpss: null })).toBeNull()
    })
})
