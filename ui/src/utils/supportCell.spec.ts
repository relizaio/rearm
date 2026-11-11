import { describe, it, expect, vi, afterEach } from 'vitest'
import { supportCellOf } from './supportCell'

/**
 * FDAW-68 (architecture-2 rows 1-3, REQ-1): the Support cell is decided by whether a support
 * record exists (attestationState), never by the END_OF_SUPPORT milestone's source. FDAW-65
 * found undated attested rows rendered as a dash because the list gated on that field.
 * Every expectation is a literal written here, never a production constant.
 */
const base = {
    attestationState: null, supportStatus: 'UNKNOWN', supportSource: null, attestedLevelOfSupportText: null,
    endOfSupportDate: null, endOfSupportUnknown: null
}
const row = (over: Record<string, unknown>): any => ({ ...base, ...over })

afterEach(() => { vi.restoreAllMocks() })

describe('no support record: none (row 1)', () => {
    it('is none for a component with no attestationState', () => {
        expect(supportCellOf(row({}))).toEqual({ kind: 'none' })
        expect(supportCellOf(undefined)).toEqual({ kind: 'none' })
        expect(supportCellOf(null)).toEqual({ kind: 'none' })
        expect(supportCellOf(row({ attestationState: '' }))).toEqual({ kind: 'none' })
        expect(supportCellOf(row({ attestationState: undefined }))).toEqual({ kind: 'none' })
    })

    it('does not gate on supportSource', () => {
        expect(supportCellOf(row({ supportSource: 'MANUAL', endOfSupportDate: '2030-01-01',
            supportStatus: 'WITHIN_SUPPORT_WINDOW' }))).toEqual({ kind: 'none' })
    })
})

describe('a live record (row 2)', () => {
    it('dated manual: tag, level and date', () => {
        expect(supportCellOf(row({ attestationState: 'ATTESTED', supportSource: 'MANUAL',
            supportStatus: 'WITHIN_SUPPORT_WINDOW', endOfSupportDate: '2030-01-01',
            attestedLevelOfSupportText: 'actively maintained' }))).toEqual({
            kind: 'live', tag: { type: 'default', label: 'Within support window' }, level: 'actively maintained',
            eosDate: '2030-01-01', unknownLabel: null, unknownBulk: false
        })
    })

    it('undated manual level (the FDAW-65 row): live with the level, no date', () => {
        expect(supportCellOf(row({ attestationState: 'ATTESTED', attestedLevelOfSupportText: 'actively maintained' })))
            .toEqual({ kind: 'live', tag: { type: 'default', label: 'Unknown' }, level: 'actively maintained',
                eosDate: null, unknownLabel: null, unknownBulk: false })
    })

    it('end-of-life date only: live, no level', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', endOfLifeDate: '2031-01-01' }))
        expect(cell.kind).toBe('live')
        expect(cell.tag.label).toBe('Unknown')
        expect(cell.level).toBeNull()
    })

    it('justification only: live, no level', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', justification: 'upstream publishes nothing' }))
        expect(cell.kind).toBe('live')
        expect(cell.level).toBeNull()
    })

    it('an FDAW-20 RULE record is live with its level', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', supportSource: 'RULE',
            assessmentSource: 'RULE', attestedLevelOfSupportText: 'actively maintained' }))
        expect(cell.kind).toBe('live')
        expect(cell.level).toBe('actively maintained')
    })

    it('keeps the fail-loud tag for an unrecognised status', () => {
        vi.spyOn(console, 'error').mockImplementation(() => {})
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', supportStatus: 'SOMETHING_NEW' }))
        expect(cell.tag.label).toBe('Unrecognised status')
    })

    it('renders an unknown state as live, not as no record', () => {
        expect(supportCellOf(row({ attestationState: 'SOMETHING_NEW' })).kind).toBe('live')
    })
})

describe('justified unknowns and withdrawn records (row 3)', () => {
    const unknown = (code: string, bulkAct: string | null) => ({ code, bulkAct })

    it('single justified unknown: label, not bulk, level kept', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', attestedLevelOfSupportText: 'actively maintained',
            endOfSupportUnknown: unknown('NO_COMMITTED_END_OF_SUPPORT', null) }))
        expect(cell.kind).toBe('live')
        expect(cell.level).toBe('actively maintained')
        expect(cell.unknownLabel).toBe('No committed end of support')
        expect(cell.unknownBulk).toBe(false)
    })

    it('bulk justified unknown (the FDAW-65 row after the sweep)', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', attestedLevelOfSupportText: 'actively maintained',
            endOfSupportUnknown: unknown('NO_COMMITTED_END_OF_SUPPORT', 'act-1') }))
        expect(cell.unknownLabel).toBe('No committed end of support')
        expect(cell.unknownBulk).toBe(true)
    })

    it('an unknown without a level', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED',
            endOfSupportUnknown: unknown('COMMUNITY_PROJECT', 'act-2') }))
        expect(cell.level).toBeNull()
        expect(cell.unknownLabel).toBe('Community project, no support commitment')
        expect(cell.unknownBulk).toBe(true)
    })

    it('a code this build does not know shows the raw code', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED',
            endOfSupportUnknown: unknown('FUTURE_CODE', null) }))
        expect(cell.unknownLabel).toBe('FUTURE_CODE')
    })

    it('the date wins over an unknown', () => {
        const cell: any = supportCellOf(row({ attestationState: 'ATTESTED', endOfSupportDate: '2030-01-01',
            endOfSupportUnknown: unknown('NO_COMMITTED_END_OF_SUPPORT', 'act-1') }))
        expect(cell.eosDate).toBe('2030-01-01')
        expect(cell.unknownLabel).toBeNull()
        expect(cell.unknownBulk).toBe(false)
    })

    it('a withdrawn record is the Withdrawn tag and nothing else', () => {
        const withdrawn = { kind: 'withdrawn', tag: { type: 'default', label: 'Withdrawn' } }
        expect(supportCellOf(row({ attestationState: 'WITHDRAWN', supportStatus: 'UNKNOWN', endOfSupportDate: '2025-12-31',
            attestedLevelOfSupportText: 'actively maintained', endOfSupportUnknown: null }))).toEqual(withdrawn)
        expect(supportCellOf(row({ attestationState: 'WITHDRAWN', attestedLevelOfSupportText: 'actively maintained',
            endOfSupportUnknown: unknown('NO_COMMITTED_END_OF_SUPPORT', 'act-1') }))).toEqual(withdrawn)
    })
})
