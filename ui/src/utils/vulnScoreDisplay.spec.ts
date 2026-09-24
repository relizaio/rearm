import { describe, it, expect } from 'vitest'
import { orderScores, formatPrimaryScore, formatSubScores, summarizeScores, scoreTypeLabel, isComputedFromVector } from './vulnScoreDisplay'
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
