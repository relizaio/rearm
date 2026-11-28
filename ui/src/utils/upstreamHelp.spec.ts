// The explanations behind the info icons of the upstream pages, and the byte count in megabytes: one
// explanation per run counter, in the order and with the keys of UpstreamRunCounters in the Pro schema.
import { describe, expect, it } from 'vitest'
import { isObjectType } from 'graphql'
import {
    DEPTH_HELP, LINES_COUNT_HELP, ONE_OFF_HELP, ON_DRIFT_HELP, RELEASE_LINE_HELP, RUN_COUNTERS, TEI_SOURCE_HELP, UPDATE_STATE_HELP,
    VERSION_LINE_HELP, VERSION_LINES_AT_DEPTH_HELP, bytesExactly, counterValueOf, megabytesOf,
} from './upstreamHelp'
import { itemUpdateStateLabel } from './upstreamLabels'
import { proSchema } from './schemaDriftSupport'

describe('the run counters', () => {
    it('have a label and an explanation each, and no key twice', () => {
        expect(RUN_COUNTERS.length).toBe(16)
        expect(new Set(RUN_COUNTERS.map(c => c.key)).size).toBe(RUN_COUNTERS.length)
        for (const c of RUN_COUNTERS) {
            expect(c.label.trim(), c.key).not.toBe('')
            expect(c.help.trim(), c.key).not.toBe('')
        }
    })

    it.runIf(proSchema)('are exactly the fields of UpstreamRunCounters in the Pro schema', () => {
        const t = proSchema!.getType('UpstreamRunCounters')
        expect(t && isObjectType(t)).toBe(true)
        expect(RUN_COUNTERS.map(c => c.key).sort()).toEqual(Object.keys((t as any).getFields()).sort())
    })

    it('show the bytes in megabytes and every other count as a number, 0 when absent', () => {
        expect(counterValueOf('bytes', { bytes: 15681734 })).toBe('15.0 MB')
        expect(counterValueOf('bytes', { bytes: 1294967296 })).toBe('1,235.0 MB')
        expect(counterValueOf('filesStored', { filesStored: 17 })).toBe('17')
        expect(counterValueOf('held', {})).toBe('0')
        expect(counterValueOf('bytes', null)).toBe('0 MB')
    })
})

describe('megabytesOf and bytesExactly', () => {
    it('write megabytes with thousands separators and one decimal', () => {
        expect(megabytesOf(0)).toBe('0 MB')
        expect(megabytesOf(null)).toBe('0 MB')
        expect(megabytesOf(Number.NaN)).toBe('0 MB')
        expect(megabytesOf(-5)).toBe('0 MB')
        expect(megabytesOf(2048)).toBe('< 0.1 MB')
        expect(megabytesOf(104858)).toBe('0.1 MB')
        expect(megabytesOf(1024 * 1024)).toBe('1.0 MB')
        expect(megabytesOf(12345 * 1024 * 1024)).toBe('12,345.0 MB')
    })

    it('write the exact count with thousands separators', () => {
        expect(bytesExactly(15681734)).toBe('15,681,734 bytes')
        expect(bytesExactly(0)).toBe('0 bytes')
        expect(bytesExactly(undefined)).toBe('0 bytes')
    })
})

describe('the explanations', () => {
    it('are not empty and say what they explain', () => {
        for (const text of [VERSION_LINE_HELP, DEPTH_HELP, LINES_COUNT_HELP, RELEASE_LINE_HELP, ON_DRIFT_HELP, ONE_OFF_HELP, TEI_SOURCE_HELP]) {
            expect(text.trim()).not.toBe('')
        }
        expect(VERSION_LINE_HELP).toContain('version line')
        expect(DEPTH_HELP).toContain('leading version numbers')
        expect(VERSION_LINES_AT_DEPTH_HELP).toBe(`${VERSION_LINE_HELP} ${DEPTH_HELP}`)
        expect(ON_DRIFT_HELP).toContain('Stop')
        expect(ON_DRIFT_HELP).toContain('Adapt')
        expect(TEI_SOURCE_HELP).toContain('Any source that matches')
    })

    it('cover every update state', () => {
        expect(Object.keys(UPDATE_STATE_HELP).sort()).toEqual(Object.keys(itemUpdateStateLabel).sort())
    })
})
