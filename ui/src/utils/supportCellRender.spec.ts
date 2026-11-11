// @vitest-environment happy-dom
//
// FDAW-68 (architecture-2 row 10, REQ-2): the release SBOM list's Support column, mounted for
// each branch of supportCellOf. Rows 1-3 (supportCell.spec.ts) pin the decisions; this pins
// that the column renders them: a renderer that dashed a withdrawn row, or printed the level
// only beside a date, would pass any source grep and bring FDAW-65's failure back.
import { describe, it, expect } from 'vitest'
import { h } from 'vue'
import { mount } from '@vue/test-utils'
import { NTag } from 'naive-ui'
import { renderSupportCell } from './supportCellRender'

const base = {
    attestationState: null, supportStatus: 'UNKNOWN', supportSource: null, attestedLevelOfSupportText: null,
    endOfSupportDate: null, endOfSupportUnknown: null
}
const row = (over: Record<string, unknown>): any => ({ ...base, ...over })
const stub = () => h('span', { 'data-testid': 'risk-stub' }, 'risk')
const render = (c: any, risk: any) => mount({ render: () => renderSupportCell(c, risk) })
const tags = (w: any): string[] => w.findAllComponents(NTag).map((t: any) => t.text())
const has = (w: any, id: string): boolean => w.find('[data-testid="' + id + '"]').exists()
const text = (w: any, id: string): string => w.find('[data-testid="' + id + '"]').text()

describe('renderSupportCell', () => {
    it('(a) no record: the dash, no tag, and the risk line when there is one', () => {
        const c = row({ supportStatus: 'WITHIN_SUPPORT_WINDOW', endOfSupportDate: '2030-01-01',
            supportSource: 'MANUAL', attestedLevelOfSupportText: 'actively maintained' })
        const w = render(c, null)
        expect(text(w, 'support-dash')).toBe('\u2014')
        expect(tags(w)).toEqual([])
        for (const id of ['support-level', 'support-eos', 'eos-unknown', 'risk-stub']) expect(has(w, id)).toBe(false)
        expect(has(render(c, stub()), 'risk-stub')).toBe(true)
    })

    it('(b) withdrawn: the Withdrawn tag and the risk line only', () => {
        const w = render(row({ attestationState: 'WITHDRAWN', endOfSupportDate: '2025-12-31',
            attestedLevelOfSupportText: 'actively maintained',
            endOfSupportUnknown: { code: 'NO_COMMITTED_END_OF_SUPPORT', bulkAct: 'act-1' } }), stub())
        expect(tags(w)).toEqual(['Withdrawn'])
        for (const id of ['support-dash', 'support-level', 'support-eos', 'eos-unknown', 'eos-unknown-bulk']) {
            expect(has(w, id)).toBe(false)
        }
        expect(w.text()).not.toContain('EOS')
        expect(w.text()).not.toContain('actively maintained')
        expect(has(w, 'risk-stub')).toBe(true)
    })

    it('(c) undated ATTESTED with a level (the FDAW-65 row): tag and level', () => {
        const w = render(row({ attestationState: 'ATTESTED', attestedLevelOfSupportText: 'actively maintained' }), null)
        expect(tags(w)).toEqual(['Unknown'])
        expect(text(w, 'support-level')).toBe('actively maintained')
        for (const id of ['support-eos', 'eos-unknown', 'support-dash', 'risk-stub']) expect(has(w, id)).toBe(false)
    })

    it('(d) a justified unknown: the label, and the bulk tag only for a bulk one', () => {
        const c = (bulkAct: string | null) => row({ attestationState: 'ATTESTED',
            attestedLevelOfSupportText: 'actively maintained',
            endOfSupportUnknown: { code: 'NO_COMMITTED_END_OF_SUPPORT', bulkAct } })
        const bulk = render(c('act-1'), null)
        expect(text(bulk, 'eos-unknown')).toBe('not available (No committed end of support)')
        expect(tags(bulk)).toEqual(['Unknown', 'bulk'])
        expect(has(bulk, 'eos-unknown-bulk')).toBe(true)
        expect(text(bulk, 'support-level')).toBe('actively maintained')
        const single = render(c(null), null)
        expect(text(single, 'eos-unknown')).toBe('not available (No committed end of support)')
        expect(tags(single)).toEqual(['Unknown'])
        expect(has(single, 'eos-unknown-bulk')).toBe(false)
    })

    it('(e) dated: tag, level, EOS date and the risk line', () => {
        const w = render(row({ attestationState: 'ATTESTED', supportStatus: 'WITHIN_SUPPORT_WINDOW',
            endOfSupportDate: '2030-01-01', attestedLevelOfSupportText: 'actively maintained' }), stub())
        expect(text(w, 'support-eos')).toBe('EOS 2030-01-01')
        expect(text(w, 'support-level')).toBe('actively maintained')
        expect(tags(w)).toEqual(['Within support window'])
        expect(has(w, 'eos-unknown')).toBe(false)
        expect(has(w, 'risk-stub')).toBe(true)
    })

    it('(f) live with no level, no date and no unknown: the tag alone, not the dash', () => {
        const w = render(row({ attestationState: 'ATTESTED', endOfLifeDate: '2031-01-01' }), null)
        expect(tags(w)).toEqual(['Unknown'])
        for (const id of ['support-level', 'support-eos', 'eos-unknown', 'support-dash']) expect(has(w, id)).toBe(false)
    })
})
