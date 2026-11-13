import { beforeEach, describe, expect, it, vi } from 'vitest'
import graphqlQueries from './graphqlQueries'
import { teaEntryRows } from './teaPublication'
import {
    loadTeaWithdrawn, resetTeaWithdrawn, teaMarkWithdrawn, teaWithdrawnNotice, teaWithdrawnText,
    TEA_WITHDRAWN_LABELS, TEA_WITHDRAWN_UNKNOWN,
} from './teaWithdrawn'
import { expectSwept } from './__fixtures__/fdaSweep'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))

// FDAW-80 (ARCHITECTURE r2 3.7 part 3, row 14 (a) to (d)): the release page's withdrawn TEA revisions.

const API = 'https://rearm.example.com/tea/11111111-1111-1111-1111-111111111111'
const W1 = { teaUuid: 't-snap', revision: 1, reason: 'VDR_SNAPSHOT' }
const W2 = { teaUuid: 't-snap2', revision: 3, reason: 'VDR_SNAPSHOT' }
const entry = (teaUuid: string, revision: number) => ({ teaUuid, revision, origin: 'REAL', name: teaUuid + '.json' })
const V1 = { version: 1, entries: [entry('t-lic', 1), entry('t-snap', 1), entry('t-snap2', 3)] }
const V2 = { version: 2, entries: [entry('t-lic', 1), entry('t-snap', 2)] }
const L1 = '1 artifact revision this release published is withdrawn: TEA no longer serves it.'
const L2 = '2 artifact revisions this release published are withdrawn: TEA no longer serves them.'
const Y = ' A VDR snapshot stored by a release action is never served on TEA.'

describe('the mark on an entry', () => {
    it('marks exactly the revision the list names, keeping every other member and the order', () => {
        const rows = teaEntryRows(V1, API)
        const marked = teaMarkWithdrawn(rows, [W1])
        expect(marked.map(r => [r.key, r.withdrawn])).toEqual([
            ['t-lic:1', ''], ['t-snap:1', 'Withdrawn: VDR snapshot, not served on TEA'], ['t-snap2:3', ''],
        ])
        expect(marked.map(({ withdrawn, ...rest }) => rest)).toEqual(rows)
        expect(teaMarkWithdrawn(teaEntryRows(V2, null), [W1]).map(r => r.withdrawn)).toEqual(['', ''])
        expect(teaMarkWithdrawn(rows, null).map(r => r.withdrawn)).toEqual(['', '', ''])
        expect(teaMarkWithdrawn(null, [W1])).toEqual([])
    })

    it('prints a reason through its label, an unknown one in lower case, none as not served', () => {
        expect(TEA_WITHDRAWN_LABELS).toEqual({ VDR_SNAPSHOT: 'VDR snapshot, not served on TEA' })
        expect([teaWithdrawnText('VDR_SNAPSHOT'), teaWithdrawnText('SOME_NEW_REASON'), teaWithdrawnText(null)]).toEqual([
            'Withdrawn: VDR snapshot, not served on TEA', 'Withdrawn: some new reason', 'Withdrawn: not served on TEA',
        ])
    })
})

describe('the notice', () => {
    it('counts distinct revisions and says whether the latest collection version still lists them', () => {
        const notices = [
            teaWithdrawnNotice([V1], [W1]),
            teaWithdrawnNotice([V2, V1], [W1]),
            teaWithdrawnNotice([V1], [W1, W2, { ...W1 }]),
            teaWithdrawnNotice([V1, { version: 2, entries: [entry('t-snap2', 3)] }], [W1, W2]),
            teaWithdrawnNotice([V1, V2], [W1, W2]),
            teaWithdrawnNotice([V1], [{ ...W1, reason: 'SOME_NEW_REASON' }]),
        ]
        expect(notices).toEqual([
            L1 + Y + ' The latest collection version still lists it; the next publish of this release writes a version without it.',
            L1 + Y + ' Only earlier collection versions list it.',
            L2 + Y + ' The latest collection version still lists them; the next publish of this release writes a version without them.',
            L2 + Y + ' The latest collection version still lists 1 of them; the next publish of this release writes a version without it.',
            L2 + Y + ' Only earlier collection versions list them.',
            L1 + ' The latest collection version still lists it; the next publish of this release writes a version without it.',
        ])
        expect(teaWithdrawnNotice([V1], [])).toBeNull()
        expect(teaWithdrawnNotice([V1], null)).toBeNull()
        expect(teaWithdrawnNotice(null, undefined)).toBeNull()
    })

    it('names no button, no act and no id, and holds no enum value', () => {
        expect(TEA_WITHDRAWN_UNKNOWN).toBe('Whether TEA still serves every artifact revision listed here could not be checked. '
            + 'Reload the page to check again.')
        const texts = [teaWithdrawnNotice([V1], [W1]), teaWithdrawnNotice([V1, V2], [W1, W2]), TEA_WITHDRAWN_UNKNOWN,
            teaWithdrawnText('VDR_SNAPSHOT'), teaWithdrawnText('SOME_NEW_REASON')].join('\n')
        const forbidden = /Re-publish|Publish|t-snap/
        expect('Re-publish t-snap').toMatch(forbidden)
        expect(texts).not.toMatch(forbidden)
        expectSwept(texts)
    })
})

describe('the read', () => {
    beforeEach(resetTeaWithdrawn)

    it('answers the served list, with one no-cache query of its own document', async () => {
        const query = vi.fn().mockResolvedValue({ data: { releaseTeaPublicationView: { release: 'r1', withdrawn: [W1] } } })
        expect(await loadTeaWithdrawn({ query }, 'r1')).toEqual({ withdrawn: [W1], failed: false })
        expect(query.mock.calls).toEqual([[{ query: graphqlQueries.ReleaseTeaWithdrawnGql, variables: { release: 'r1' }, fetchPolicy: 'no-cache' }]])
        const empty = vi.fn().mockResolvedValue({ data: { releaseTeaPublicationView: { release: 'r1', withdrawn: [] } } })
        expect(await loadTeaWithdrawn({ query: empty }, 'r1')).toEqual({ withdrawn: [], failed: false })
    })

    it('a schema-drift rejection is no failure, and is not asked again', async () => {
        const query = vi.fn().mockRejectedValue(new Error("Validation error (FieldUndefined@[releaseTeaPublicationView/withdrawn]) : Field 'withdrawn' in type 'TeaPublishPreview' is undefined"))
        expect([await loadTeaWithdrawn({ query }, 'r1'), await loadTeaWithdrawn({ query }, 'r2')]).toEqual([
            { withdrawn: null, failed: false }, { withdrawn: null, failed: false },
        ])
        expect(query).toHaveBeenCalledTimes(1)
    })

    it('any other failure, or an answer without a list, is a failure, asked again each time', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        const query = vi.fn().mockRejectedValue(new Error('Failed to fetch'))
        expect([await loadTeaWithdrawn({ query }, 'r1'), await loadTeaWithdrawn({ query }, 'r2')]).toEqual([
            { withdrawn: null, failed: true }, { withdrawn: null, failed: true },
        ])
        expect(query).toHaveBeenCalledTimes(2)
        const none = vi.fn().mockResolvedValue({ data: { releaseTeaPublicationView: null } })
        expect(await loadTeaWithdrawn({ query: none }, 'r1')).toEqual({ withdrawn: null, failed: true })
        warn.mockRestore()
    })
})
