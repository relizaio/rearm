import { describe, expect, it } from 'vitest'
import {
    COMPARED_WITH_LABELS, baselineAcceptedText, bulkRejectResultLines, notPendingStatus, pendingBadge, proposalStatusText,
    safetyInstantLabel, sourceReleaseHref, sourceReleaseLabel
} from './safetyContext'

describe('safetyInstantLabel (u14)', () => {
    it.each([
        ['2026-11-20T23:30:00Z', '2026-11-20 23:30 UTC'],
        ['2026-01-05T04:07:09.123456789Z', '2026-01-05 04:07 UTC'],
        ['2026-11-21T01:30:00+02:00', '2026-11-20 23:30 UTC']
    ])('%s gives %s', (iso, expected) => {
        expect(safetyInstantLabel(iso)).toBe(expected)
    })

    it.each([[null], [undefined], ['garbage'], ['']])('%s gives ""', (iso) => {
        expect(safetyInstantLabel(iso as any)).toBe('')
    })
})

describe('sourceReleaseLabel and sourceReleaseHref (u9)', () => {
    const base = { releaseUuid: 'r1', sourceComponentName: 'pump', sourceReleaseVersion: '1.2.0' }
    it.each([
        [{ ...base, releaseUuid: null, sourceReleaseState: null }, '-', null],
        [{ ...base, sourceReleaseState: 'AVAILABLE' }, 'pump 1.2.0', '/release/show/r1'],
        [{ ...base, sourceReleaseState: 'ARCHIVED' }, 'pump 1.2.0 (archived)', '/release/show/r1'],
        [{ ...base, sourceComponentName: null, sourceReleaseState: 'DELETED' }, 'release no longer exists', null],
        [{ ...base, sourceComponentName: null, sourceReleaseState: 'NOT_VISIBLE' }, 'not visible to you', null],
        [{ ...base, releaseUuid: null, sourceReleaseState: 'NOT_VISIBLE' }, 'not visible to you', null]
    ])('%o', (row, text, href) => {
        expect(sourceReleaseLabel(row)).toBe(text)
        expect(sourceReleaseHref(row)).toBe(href)
    })
})

describe('accept dialog texts', () => {
    it('labels every comparison', () => {
        expect(Object.keys(COMPARED_WITH_LABELS))
            .toEqual(['SHOWN_ON_RELEASE', 'LATEST_FOR_PACKAGE', 'LATEST_ON_CHAIN', 'CARRIED_ON_RELEASE'])
        expect(COMPARED_WITH_LABELS.CARRIED_ON_RELEASE).toBe('carried from another version on this release')
    })

    it('says who accepted the current level', () => {
        const at = '2026-11-20T23:30:00Z'
        expect(baselineAcceptedText({ acceptedVia: 'MANUAL', acceptedByName: 'Dana', acceptedAt: at }, 'FULL'))
            .toBe('Dana on 2026-11-20 23:30 UTC')
        expect(baselineAcceptedText({ acceptedVia: 'MANUAL', acceptedByName: 'Dana', acceptedAt: at }, 'LEVEL_ONLY'))
            .toBe('not visible to you, on 2026-11-20 23:30 UTC')
    })

    it('reads the status of a not-pending refusal', () => {
        expect(notPendingStatus('Safety context proposal is not pending (was SUPERSEDED)')).toBe('SUPERSEDED')
        expect(notPendingStatus('Safety context entry not found')).toBeNull()
    })
})

describe('bulk reject result lines (u6, u7)', () => {
    const zero = { rejected: 0, alreadyDecided: 0, notPermitted: 0, notFound: 0, restored: 0 }
    it('every line, in order', () => {
        expect(bulkRejectResultLines({ rejected: 1, alreadyDecided: 2, notPermitted: 3, notFound: 4, restored: 5 })).toEqual([
            'Rejected: 1.', 'Already decided by someone else, left as they are: 2.',
            'Not permitted (needs Finding Analysis write at the level\'s scope), left pending: 3.', 'Not found: 4.',
            'Earlier pending levels this upload had replaced, back in review: 5.'])
    })
    it('a zero line is left out, except the first', () => {
        expect(bulkRejectResultLines({ ...zero, notFound: 1 })).toEqual(['Rejected: 0.', 'Not found: 1.'])
    })
    it('all zero: nothing to reject', () => {
        expect(bulkRejectResultLines(zero)).toEqual(['Nothing to reject: no pending levels from this document.'])
    })
})

describe('status and badge texts', () => {
    it('marks only an undone upload (u15)', () => {
        expect(proposalStatusText({ proposalStatus: 'REJECTED', rejectKind: 'UPLOAD_UNDONE' })).toBe('REJECTED (upload undone)')
        expect(proposalStatusText({ proposalStatus: 'REJECTED', rejectKind: 'REVIEWED' })).toBe('REJECTED')
        expect(proposalStatusText({ proposalStatus: 'PENDING' })).toBe('PENDING')
    })

    it('badge boundaries (u11)', () => {
        expect(pendingBadge(null)).toBeNull()
        expect(pendingBadge({ canRead: true, pending: 0 })).toBeNull()
        expect(pendingBadge({ canRead: false, pending: 3 })).toBeNull()
        expect(pendingBadge({ canRead: true, pending: 999, pendingDowngrade: 999 }))
            .toMatchObject({ pending: '999 pending', downgrades: '999 downgrades' })
        expect(pendingBadge({ canRead: true, pending: 1000, pendingDowngrade: 0 }))
            .toMatchObject({ pending: '999+ pending', downgrades: null })
    })
})
