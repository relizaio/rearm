import { describe, it, expect } from 'vitest'
import { LATEST_VERSION_NOTE, checkedDay, groupLatestOf, groupLatestText, groupLatestTitle, hasLatestFields, latestFixCell, latestFixesText, latestOf } from './latestVersionDisplay'
import type { DetailedMetric } from './metrics'
import { LatestFixVerdict } from '@/constants/latestFixVerdict'

function row (partial: Partial<DetailedMetric> & Pick<DetailedMetric, 'type' | 'id'>): DetailedMetric {
    return { purl: 'pkg:npm/a@1.4.1', severity: 'HIGH', details: '-', location: '-', fingerprint: '-', ...partial } as DetailedMetric
}

const match = (latestVersion: string | null) => ({
    sbomComponentUuid: 'c', canonicalPurl: 'pkg:npm/a@1.4.1', missReason: null,
    latestVersion, latestVersionChecked: latestVersion ? '2026-09-29T06:00:00Z' : null
})

describe('the latest version of a component group', () => {
    it('counts the vulnerability rows the latest version fixes', () => {
        const latest = groupLatestOf([
            row({ type: 'Vulnerability', id: 'A', sbomMatch: match('1.5.0'), latestFix: LatestFixVerdict.FIXES }),
            row({ type: 'Vulnerability', id: 'B', sbomMatch: match('1.5.0'), latestFix: LatestFixVerdict.DOES_NOT_FIX }),
            row({ type: 'Vulnerability', id: 'C', sbomMatch: match('1.5.0'), latestFix: null }),
            row({ type: 'Violation', id: 'LICENSE' })
        ])
        expect(latest).toEqual({ version: '1.5.0', checked: '2026-09-29T06:00:00Z', fixes: 1, stillAffected: 1, total: 3 })
        expect(groupLatestText(latest!)).toBe('1.5.0 fixes 1 of 3')
        expect(groupLatestTitle(latest!)).toContain('Checked 2026-09-29')
    })

    it('shows the bare version when it fixes none, and nothing when none is known', () => {
        const none = groupLatestOf([row({ type: 'Vulnerability', id: 'A', sbomMatch: match('1.5.0'), latestFix: LatestFixVerdict.DOES_NOT_FIX })])
        expect(groupLatestText(none!)).toBe('1.5.0')
        expect(groupLatestOf([row({ type: 'Vulnerability', id: 'A', sbomMatch: match(null), latestFix: null })])).toBeNull()
        expect(groupLatestOf([row({ type: 'Vulnerability', id: 'A' })])).toBeNull()
    })

    it('knows whether the backend served the fields at all', () => {
        expect(hasLatestFields([row({ type: 'Vulnerability', id: 'A', latestFix: null })])).toBe(true)
        expect(hasLatestFields([row({ type: 'Vulnerability', id: 'A' })])).toBe(false)
    })

    it('counts a component\'s verdicts for the graph page', () => {
        const latest = latestOf('5.0.12', null, [LatestFixVerdict.FIXES, LatestFixVerdict.DOES_NOT_FIX, null, LatestFixVerdict.FIXES])
        expect(latestFixesText(latest)).toBe('(fixes 2 of 4 findings)')
        expect(latestFixesText(latestOf('5.0.12', null, [LatestFixVerdict.DOES_NOT_FIX]))).toBe('')
    })

    it('tells "fixes none" from "cannot tell"', () => {
        expect(groupLatestTitle({ version: '9.9.9', checked: null, fixes: 0, stillAffected: 2, total: 2 }))
            .toContain('still inside the affected ranges of 2')
        expect(groupLatestTitle({ version: '9.9.9', checked: null, fixes: 0, stillAffected: 0, total: 2 }))
            .toContain('no ranges')
        expect(latestFixCell(LatestFixVerdict.NOT_ABOVE_CURRENT).text).toBe('at or past latest')
        expect(latestFixCell(null).text).toBe('')
    })

    it('never words the latest version as end of support', () => {
        const texts = [LATEST_VERSION_NOTE,
            groupLatestTitle({ version: '9.9.9', checked: null, fixes: 0, stillAffected: 0, total: 2 }),
            ...Object.values(LatestFixVerdict).map(v => latestFixCell(v).title)].map(t => t.toLowerCase())
        for (const text of texts) {
            for (const word of ['end of support', 'end of life', 'eol', 'eos', 'outdated', 'unsupported']) {
                expect(text).not.toContain(word)
            }
        }
        expect(checkedDay(null)).toBe('')
    })
})
