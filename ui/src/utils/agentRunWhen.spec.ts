import { describe, expect, it } from 'vitest'
import { conditionSkipLine, runWhenDraftOf, runWhenLabel, runWhenLocked, runWhenPatch } from './agentRunWhen'

// A role's runWhen condition in the UI (task TPF-156, T-109): the form's draft and patch, its lock rule, the chip text
// and the hop row's line.
const entry = () => ({
    role: 'mutant_coder', roleUuid: 'r1', reason: 'runWhen not met: no mutation tag', at: '2026-11-05T13:00:00Z',
    by: { kind: 'SYSTEM', uuid: null, name: 'routing' }, waives: [] as string[], live: true as boolean | undefined,
})

describe('runWhenDraftOf', () => {
    it('a role without a condition gives two empty lists', () => {
        expect(runWhenDraftOf({ name: 'x', runWhen: null })).toEqual({ runWhenAny: [], runWhenNone: [] })
        expect(runWhenDraftOf(null)).toEqual({ runWhenAny: [], runWhenNone: [] })
    })
    it('a condition gives copies: editing the draft leaves the role untouched', () => {
        const role = { name: 'x', runWhen: { taggedAny: ['mutation'], taggedNone: [] as string[] } }
        const draft = runWhenDraftOf(role)
        expect(draft).toEqual({ runWhenAny: ['mutation'], runWhenNone: [] })
        draft.runWhenAny.push('perf')
        draft.runWhenNone.push('hotfix')
        expect(role.runWhen).toEqual({ taggedAny: ['mutation'], taggedNone: [] })
    })
})

describe('runWhenPatch', () => {
    it('both lists empty: null, which clears', () => {
        expect(runWhenPatch({ runWhenAny: [], runWhenNone: [] })).toBeNull()
    })
    it('keys trimmed, lower-cased, repeats and blanks dropped, order kept', () => {
        expect(runWhenPatch({ runWhenAny: [' Mutation ', 'mutation', ''], runWhenNone: ['hotfix'] }))
            .toEqual({ taggedAny: ['mutation'], taggedNone: ['hotfix'] })
        expect(runWhenPatch({ runWhenAny: ['b', 'A', 'b'], runWhenNone: [] }))
            .toEqual({ taggedAny: ['b', 'a'], taggedNone: [] })
    })
    it('absent lists: null', () => {
        expect(runWhenPatch({ runWhenAny: undefined, runWhenNone: null })).toBeNull()
    })
    it('an empty side is sent empty, never omitted', () => {
        const patch = runWhenPatch({ runWhenAny: [], runWhenNone: ['x'] })
        expect(patch).toEqual({ taggedAny: [], taggedNone: ['x'] })
        expect(Object.keys(patch as object)).toEqual(['taggedAny', 'taggedNone'])
    })
})

describe('runWhenLocked', () => {
    it('open only for an OPTIONAL role without the coordinator checkpoint', () => {
        expect(runWhenLocked({ necessity: 'OPTIONAL', coordinatorCheckpoint: false })).toBe(false)
        expect(runWhenLocked({ necessity: 'REQUIRED' })).toBe(true)
        expect(runWhenLocked({ necessity: 'OPTIONAL', coordinatorCheckpoint: true })).toBe(true)
        expect(runWhenLocked(null)).toBe(true)
    })
})

describe('runWhenLabel', () => {
    it('null when there is no condition or it names no key', () => {
        expect(runWhenLabel(null)).toBeNull()
        expect(runWhenLabel({ taggedAny: [], taggedNone: [] })).toBeNull()
    })
    it('the describe text', () => {
        expect(runWhenLabel({ taggedAny: ['mutation'], taggedNone: [] })).toBe('tagged mutation')
        expect(runWhenLabel({ taggedAny: [], taggedNone: ['hotfix'] })).toBe('not tagged hotfix')
        expect(runWhenLabel({ taggedAny: ['nightly', 'soak'], taggedNone: ['hotfix'] }))
            .toBe('tagged nightly or soak and not tagged hotfix')
    })
})

describe('conditionSkipLine', () => {
    const base = 'mutant_coder passed over by routing: runWhen not met: no mutation tag'
    it('the live entry', () => {
        expect(conditionSkipLine(entry())).toBe(base)
    })
    it('waives', () => {
        expect(conditionSkipLine({ ...entry(), waives: ['BOARD_TEST_REPORT', 'ARCHITECTURE'] }))
            .toBe(base + '; waives BOARD_TEST_REPORT, ARCHITECTURE')
    })
    it('a null actor changes nothing', () => {
        expect(conditionSkipLine({ ...entry(), by: null, roleUuid: null })).toBe(base)
    })
    it('live false: the marker, last', () => {
        expect(conditionSkipLine({ ...entry(), live: false })).toBe(base + ' (since ran)')
        expect(conditionSkipLine({ ...entry(), live: false, waives: ['BOARD_TEST_REPORT'] }))
            .toBe(base + '; waives BOARD_TEST_REPORT (since ran)')
    })
    it('live absent: no marker', () => {
        const rec: any = entry()
        delete rec.live
        expect(conditionSkipLine(rec)).toBe(base)
    })
})
