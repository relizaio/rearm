import { describe, it, expect, vi } from 'vitest'
import {
    BULK_ARCHIVE_CHUNK, BranchArchiveResult, NO_UNDO_SENTENCE, chunk, confirmText, inSelectionCell, keepFailedSelected,
    pluralOf, rowClickAction, selectableBranch, selectableKeys, summarizeOutcomes, toggleKey, unsentAfter
} from './branchBulkArchive'

// TPF-78 test 4 (TPF-73 part 2, ARCHITECTURE round 2 3.1): the pure helpers behind "Archive selected".
const COMPONENT = { branch: 'branch', branchFirstUpper: 'Branch' }
const PRODUCT = { branch: 'feature set', branchFirstUpper: 'Feature Set' }
const STREAM = { branch: 'stream', branchFirstUpper: 'Stream' }

const COMPONENT_TAIL = 'Archived branches leave this list and cannot be restored from ReARM today; only a new release or version for a branch from CI (an API key), or a CycloneDX import naming it, brings it back (a release added by hand does not).'
const PRODUCT_TAIL = 'Archived feature sets leave this list and cannot be restored from ReARM today; only a new release or version for a feature set from CI (an API key) brings it back (a release added by hand does not).'
const STREAM_TAIL = 'Archived streams leave this list and cannot be restored from ReARM today; only a new release or version for a stream from CI (an API key) brings it back (a release added by hand does not).'

const uuids = (n: number): string[] => Array.from({ length: n }, (_, i) => `u${i}`)

describe('pluralOf', () => {
    it('pluralises the branch word', () => {
        expect(pluralOf({ branch: 'branch', branchFirstUpper: 'Branch' })).toBe('branches')
        expect(pluralOf({ branch: 'feature set', branchFirstUpper: 'Feature Set' })).toBe('feature sets')
        expect(pluralOf({ branch: 'stream', branchFirstUpper: 'Stream' })).toBe('streams')
    })
})

describe('selectableBranch and selectableKeys', () => {
    it('refuses only the base branch', () => {
        expect(selectableBranch({ type: 'BASE' })).toBe(false)
        for (const type of ['REGULAR', 'FEATURE', 'TAG', 'PULL_REQUEST', 'RELEASE', 'DEVELOP', 'HOTFIX']) {
            expect(selectableBranch({ type })).toBe(true)
        }
    })

    it('skips exactly the base row and keeps list order', () => {
        const rows = [{ uuid: 'a', type: 'REGULAR' }, { uuid: 'b', type: 'FEATURE' }, { uuid: 'm', type: 'BASE' }, { uuid: 'c', type: 'TAG' }, { uuid: 'd', type: 'HOTFIX' }]
        expect(selectableKeys(rows)).toEqual(['a', 'b', 'c', 'd'])
    })
})

describe('confirmText and NO_UNDO_SENTENCE', () => {
    it('names the count and the names, and ends with the component sentence', () => {
        const text = confirmText(['a', 'b', 'c'], COMPONENT)
        expect(text.startsWith('Archive 3 branches? "a", "b", "c".')).toBe(true)
        expect(text.endsWith(COMPONENT_TAIL)).toBe(true)
        expect(text).toBe('Archive 3 branches? "a", "b", "c". ' + COMPONENT_TAIL)
    })

    it('uses the singular for one name', () => {
        const text = confirmText(['a'], COMPONENT)
        expect(text.startsWith('Archive 1 branch? "a".')).toBe(true)
        expect(text.endsWith(COMPONENT_TAIL)).toBe(true)
    })

    it('lists the first ten names and counts the rest', () => {
        const names = Array.from({ length: 13 }, (_, i) => `n${i + 1}`)
        const text = confirmText(names, COMPONENT)
        const listed = names.slice(0, 10).map((n) => `"${n}"`).join(', ')
        expect(text).toBe(`Archive 13 branches? ${listed} and 3 more. ${COMPONENT_TAIL}`)
        expect(text).not.toContain('"n11"')
    })

    it('uses the product form for feature sets and any other label', () => {
        const product = confirmText(['a', 'b', 'c'], PRODUCT)
        expect(product.startsWith('Archive 3 feature sets?')).toBe(true)
        expect(product.endsWith(PRODUCT_TAIL)).toBe(true)
        const stream = confirmText(['a', 'b'], STREAM)
        expect(stream.startsWith('Archive 2 streams?')).toBe(true)
        expect(stream.endsWith(STREAM_TAIL)).toBe(true)
    })

    it('is the single source of the tail, and neither form offers an undo or a release by hand', () => {
        expect(NO_UNDO_SENTENCE(COMPONENT)).toBe(COMPONENT_TAIL)
        expect(NO_UNDO_SENTENCE(PRODUCT)).toBe(PRODUCT_TAIL)
        expect(NO_UNDO_SENTENCE(STREAM)).toBe(STREAM_TAIL)
        for (const words of [COMPONENT, PRODUCT, STREAM]) {
            expect(NO_UNDO_SENTENCE(words).toLowerCase()).not.toContain('unarchive')
            expect(NO_UNDO_SENTENCE(words)).not.toContain('release on a')
        }
    })
})

describe('summarizeOutcomes', () => {
    const rows = new Map([
        ['a1', { name: 'alpha' }], ['a2', { name: 'beta' }], ['b', { name: 'main' }], ['s', { name: 'stale' }],
        ['f', { name: 'locked' }], ['x', { name: 'broken' }]
    ])
    const mixed: BranchArchiveResult[] = [
        { branchUuid: 'a1', outcome: 'ARCHIVED', message: null },
        { branchUuid: 'a2', outcome: 'ARCHIVED', message: null },
        { branchUuid: 'b', outcome: 'SKIPPED_BASE', message: null },
        { branchUuid: 's', outcome: 'SKIPPED_ARCHIVED', message: null },
        { branchUuid: 'gone-uuid', outcome: 'NOT_FOUND', message: null },
        { branchUuid: 'f', outcome: 'FORBIDDEN', message: null },
        { branchUuid: 'x', outcome: 'FAILED', message: 'could not be archived; see server logs for this branch' }
    ]

    it('names every row under its group on a mixed run', () => {
        const s = summarizeOutcomes(mixed, rows, COMPONENT)
        expect(s.title).toBe('2 branches archived')
        expect(s.type).toBe('warning')
        expect(s.text).toContain('Archived: alpha, beta.')
        expect(s.text).toContain('Skipped (base branch): main.')
        expect(s.text).toContain('Skipped (already archived): stale.')
        expect(s.text).toContain('Not found: gone-uuid.')
        expect(s.text).toContain('Not permitted: locked.')
        expect(s.text).toContain('Failed: broken (could not be archived; see server logs for this branch).')
    })

    it('is a success with no skipped or failed sentence when everything was archived', () => {
        const s = summarizeOutcomes(mixed.slice(0, 2), rows, COMPONENT)
        expect(s.type).toBe('success')
        expect(s.text).toBe('Archived: alpha, beta.')
        expect(s.text).not.toMatch(/Skipped|Not found|Not permitted|Failed/)
    })

    it('is an error when nothing was archived', () => {
        const s = summarizeOutcomes([{ branchUuid: 'f', outcome: 'FORBIDDEN' }], rows, COMPONENT)
        expect(s.type).toBe('error')
        expect(s.title).toBe('0 branches archived')
        expect(s.text).toBe('Not permitted: locked.')
    })

    it('uses the singular and the product words', () => {
        expect(summarizeOutcomes(mixed.slice(0, 1), rows, COMPONENT).title).toBe('1 branch archived')
        expect(summarizeOutcomes(mixed.slice(0, 2), rows, PRODUCT).title).toBe('2 feature sets archived')
    })

    it('reports an unknown outcome under Failed with its raw value', () => {
        const s = summarizeOutcomes([{ branchUuid: 'a1', outcome: 'SOMETHING_NEW' as any }], rows, COMPONENT)
        expect(s.text).toBe('Failed: alpha (SOMETHING_NEW).')
        expect(s.type).toBe('error')
    })
})

describe('keepFailedSelected', () => {
    it('keeps only the not-archived rows still listed, in results order', () => {
        const results: BranchArchiveResult[] = [
            { branchUuid: 'f', outcome: 'FORBIDDEN' },
            { branchUuid: 'a', outcome: 'ARCHIVED' },
            { branchUuid: 'x', outcome: 'FAILED', message: 'm' },
            { branchUuid: 'b', outcome: 'SKIPPED_BASE' },
            { branchUuid: 's', outcome: 'SKIPPED_ARCHIVED' },
            { branchUuid: 'n', outcome: 'NOT_FOUND' },
            { branchUuid: 'gone', outcome: 'NOT_FOUND' }
        ]
        const refreshed = [{ uuid: 'n' }, { uuid: 'x' }, { uuid: 'b' }, { uuid: 'f' }]
        expect(keepFailedSelected(results, refreshed)).toEqual(['f', 'x', 'n'])
    })
})

describe('chunk and unsentAfter', () => {
    it('chunks at the server bound', () => {
        expect(BULK_ARCHIVE_CHUNK).toBe(200)
        const all = uuids(450)
        const batches = chunk(all)
        expect(batches.map((b) => b.length)).toEqual([200, 200, 50])
        expect(batches.flat()).toEqual(all)
        expect(chunk([])).toEqual([])
        expect(chunk(uuids(200))).toHaveLength(1)
    })

    it('keeps the failing batch and every later one', () => {
        const all = uuids(450)
        const batches = chunk(all)
        expect(unsentAfter(batches, 1)).toEqual(all.slice(200))
        expect(unsentAfter(batches, 0)).toEqual(all)
        expect(unsentAfter(batches, 3)).toEqual([])
        expect(unsentAfter(batches, 2)).toEqual(all.slice(400))
    })
})

// TPF-78 ARCHITECTURE round 3 3.1 and 3.7 (tester run 1 T-1): a row click in the selection mode.
const REGULAR = (uuid: string) => ({ uuid, type: 'REGULAR' })
const BASE = (uuid: string) => ({ uuid, type: 'BASE' })
const checkboxCell = () => ({ closest: (s: string) => s === '.n-data-table-td--selection' ? {} : null })
const plainCell = () => ({ closest: () => null })

describe('toggleKey', () => {
    it('appends an absent selectable row and removes a present one, keeping the order of the rest', () => {
        const keys = ['a', 'c']
        expect(toggleKey(keys, REGULAR('b'))).toEqual(['a', 'c', 'b'])
        expect(toggleKey(keys, REGULAR('a'))).toEqual(['c'])
        expect(keys).toEqual(['a', 'c'])
    })

    it('gives the same array back for the base row, present or not', () => {
        const keys = ['a', 'c']
        expect(toggleKey(keys, BASE('b'))).toBe(keys)
        const withBase = ['a', 'b']
        expect(toggleKey(withBase, BASE('b'))).toBe(withBase)
        expect(withBase).toEqual(['a', 'b'])
    })

    it('never mutates the keys', () => {
        const keys = Object.freeze(['a', 'c']) as unknown as string[]
        expect(toggleKey(keys, REGULAR('b'))).toEqual(['a', 'c', 'b'])
        expect(toggleKey(keys, REGULAR('c'))).toEqual(['a'])
        expect(keys).toEqual(['a', 'c'])
    })
})

describe('inSelectionCell', () => {
    it('is true inside naive-ui selection cell', () => {
        expect(inSelectionCell(checkboxCell())).toBe(true)
    })

    it('is false outside it, for null and for a target without closest', () => {
        expect(inSelectionCell(plainCell())).toBe(false)
        expect(inSelectionCell(null)).toBe(false)
        expect(inSelectionCell({})).toBe(false)
    })

    it('asks closest for exactly the selection cell class', () => {
        const closest = vi.fn(() => null)
        inSelectionCell({ closest })
        expect(closest).toHaveBeenCalledTimes(1)
        expect(closest).toHaveBeenCalledWith('.n-data-table-td--selection')
    })
})

describe('rowClickAction', () => {
    it('outside the mode the click selects as today, for any row and target, without reading the keys', () => {
        const keys = new Proxy([] as string[], { get: () => { throw new Error('keys read') } })
        expect(rowClickAction(false, REGULAR('a'), plainCell(), keys)).toEqual({ action: 'select' })
        expect(rowClickAction(false, BASE('m'), plainCell(), keys)).toEqual({ action: 'select' })
        expect(rowClickAction(false, REGULAR('a'), checkboxCell(), keys)).toEqual({ action: 'select' })
    })

    it('in the mode the checkbox click is ignored and does not navigate (no double toggle)', () => {
        expect(rowClickAction(true, REGULAR('a'), checkboxCell(), [])).toEqual({ action: 'ignore' })
        expect(rowClickAction(true, REGULAR('a'), checkboxCell(), ['a'])).toEqual({ action: 'ignore' })
    })

    it('in the mode a row click toggles and does not navigate', () => {
        expect(rowClickAction(true, REGULAR('b'), plainCell(), ['a'])).toEqual({ action: 'toggle', keys: ['a', 'b'] })
        expect(rowClickAction(true, REGULAR('a'), plainCell(), ['a', 'b'])).toEqual({ action: 'toggle', keys: ['b'] })
    })

    it('in the mode the base row is not toggled by a click', () => {
        expect(rowClickAction(true, BASE('m'), plainCell(), ['a'])).toEqual({ action: 'ignore' })
        expect(rowClickAction(true, BASE('m'), null, ['a'])).toEqual({ action: 'ignore' })
    })

    it('in the mode a TAG row toggles like a REGULAR row', () => {
        expect(rowClickAction(true, { uuid: 't', type: 'TAG' }, plainCell(), [])).toEqual({ action: 'toggle', keys: ['t'] })
        expect(rowClickAction(true, { uuid: 't', type: 'TAG' }, plainCell(), ['t'])).toEqual({ action: 'toggle', keys: [] })
    })
})
