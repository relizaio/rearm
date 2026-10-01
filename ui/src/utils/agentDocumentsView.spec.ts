import { describe, it, expect } from 'vitest'
import {
    SOFTWARE_KINDS, recentReleasesKinds, documentBoardBanner, documentRoundRows, documentSeriesRows, isDocumentComponent, splitDocumentDependencies, elementCheckVerdictOf, documentRoundView, latestLabel,
} from './agentDocumentsView'
import { lifecycleWord } from './agentWords'
import { cardRef } from './agentTaskFormat'

// Documents inside the board (task 36d0549e).
describe('documents view', () => {
    it('the lists ask for the software kinds only', () => {
        expect(SOFTWARE_KINDS).toEqual(['GENERIC', 'HELM'])
        expect(SOFTWARE_KINDS).not.toContain('BOARD_DOCUMENT')
        expect(isDocumentComponent({ kind: 'BOARD_DOCUMENT' })).toBe(true)
        expect(isDocumentComponent({ kind: 'GENERIC' })).toBe(false)
        expect(isDocumentComponent(null)).toBe(false)
    })

    it('a branch groups its document dependencies apart', () => {
        const deps = [
            { component: { uuid: 'a', kind: 'GENERIC' } },
            { component: { uuid: 'd', kind: 'BOARD_DOCUMENT' } },
            { component: { uuid: 'h', kind: 'HELM' } },
            { component: null },
        ]
        const { software, documents } = splitDocumentDependencies(deps)
        expect(documents.map(d => d.component?.uuid)).toEqual(['d'])
        expect(software).toHaveLength(3)
        expect(splitDocumentDependencies(null)).toEqual({ software: [], documents: [] })
    })

    it('the banner names the board with its prefix, or says there is none', () => {
        expect(documentBoardBanner({ uuid: 'b1', name: 'ReARM Dogfood', taskPrefix: 'RD', readable: true }))
            .toEqual({ text: 'Belongs to board RD · ReARM Dogfood', board: 'b1' })
        expect(documentBoardBanner({ uuid: 'b1', name: 'Plain', readable: true }).text).toBe('Belongs to board Plain')
        expect(documentBoardBanner(null)).toEqual({ text: 'Document component, no board', board: null })
    })

    it('rounds list newest first with the task by its key', () => {
        const rows = documentRoundRows([
            { uuid: 'r1', version: '1', lifecycle: 'ASSEMBLED', createdDate: '2026-09-01T00:00:00Z',
                document: { round: 1, path: 'design/RD-1/architecture-1.md', task: 't1' } },
            { uuid: 'r2', version: '2', lifecycle: 'DRAFT', createdDate: '2026-09-02T00:00:00Z',
                document: { round: 2, path: 'design/RD-1/architecture-2.md', task: 't1' } },
            { uuid: 'x', version: '3' },
        ], { t1: 'RD-1' })
        expect(rows.map(r => r.uuid)).toEqual(['r2', 'r1'])
        expect(rows[0]).toMatchObject({ round: 2, version: '2', lifecycle: 'DRAFT', task: 'RD-1' })
        expect(documentRoundRows([{ uuid: 'r', document: { task: '1234567890ab' } }])[0].task).toBe('12345678')
    })

    it('the Documents section has a row per series, and none for an empty board', () => {
        const rows = documentSeriesRows([
            { specification: 'ARCHITECTURE', component: { uuid: 'c1', name: 'rd-architecture' },
                latestRound: { round: 2, version: '5', lifecycle: 'ASSEMBLED' }, roundsCount: 5, openReviewItems: null, elementCheckVerdict: 'PASS' },
            { specification: 'BOARD_REVIEW_ITEMS', component: { uuid: 'c2', name: 'rd-board-review-items' },
                latestRound: null, roundsCount: 0, openReviewItems: 3, elementCheckVerdict: null },
        ])
        expect(rows.map(r => r.label)).toEqual(['architecture', 'review items'])
        expect(rows[0]).toMatchObject({ componentName: 'rd-architecture', latest: 'round 2 · v5', roundsCount: 5, elementCheckVerdict: 'PASS' })  // the version labelled (RD2-24)
        expect(rows[1]).toMatchObject({ latest: '—', openReviewItems: 3 })
        expect(documentSeriesRows([])).toEqual([])
        expect(documentSeriesRows(null)).toEqual([])
    })

    it('cards lead with the key', () => {
        expect(cardRef({ key: 'RD-7', externalRef: 'github:a/b#42' }, 'draft')).toBe('RD-7')
        expect(cardRef({ externalRef: 'github:a/b#42' }, 'draft')).toBe('#42')
        expect(cardRef({ title: 'x' }, 'draft')).toBe('draft')
    })
})

// RD2-9: a document component reads under its own permission, its board under the board's.
describe('the banner for a person who cannot open the board', () => {
    it('keeps the board name and links only when the board is readable', () => {
        expect(documentBoardBanner({ uuid: 'b1', name: 'ReARM Dogfood', taskPrefix: 'RD', readable: false }))
            .toEqual({ text: 'Belongs to board RD · ReARM Dogfood (board not visible to you)', board: null })
        expect(documentBoardBanner({ uuid: 'b1', name: 'Plain', readable: null }).board).toBeNull()
        expect(documentBoardBanner({ uuid: 'b1', name: 'Plain', readable: true }).board).toBe('b1')
    })
})

// RD2-24: the Documents tab labels the version, counts the checks as the task page does, and reads the verdict by
// one rule from those counts; a round's release page shows the round.
describe('document surfaces', () => {
    it('labels the latest round\'s version', () => {
        expect(latestLabel({ round: 1, version: '0' })).toBe('round 1 · v0')
        expect(latestLabel({ round: 3, version: null })).toBe('round 3')
        expect(latestLabel(null)).toBe('—')
    })

    it('reads the verdict by one rule: FAIL on a blocking fail, WARN on any other fail, PASS otherwise', () => {
        expect(elementCheckVerdictOf({ pass: 7, fail: 1, skip: 2, blockingFailed: 1 })).toBe('FAIL')
        expect(elementCheckVerdictOf({ pass: 7, fail: 1, skip: 2, blockingFailed: 0 })).toBe('WARN')
        expect(elementCheckVerdictOf({ pass: 7, fail: 0, skip: 2, blockingFailed: 0 })).toBe('PASS')
    })

    it('shows the task page\'s summary on the Documents tab, the verdict from the counts', () => {
        const [row] = documentSeriesRows([{ specification: 'ARCHITECTURE', component: { uuid: 'c1' }, roundsCount: 1,
            latestRound: { round: 1, version: '0', lifecycle: 'ASSEMBLED' }, elementCheckVerdict: 'WARN',
            elementCheckCounts: { pass: 7, fail: 1, skip: 2, blockingFailed: 1 } }])
        expect(row.elementChecks).toBe('7 pass · 1 fail · 2 skip')
        expect(row.elementCheckVerdict).toBe('FAIL', 'the rule over the counts, never a WARN beside a blocking fail')
        expect(row.lifecycle).toBe('assembled')
        const [old] = documentSeriesRows([{ specification: 'ARCHITECTURE', component: { uuid: 'c1' }, roundsCount: 1,
            latestRound: null, elementCheckVerdict: 'PASS' }])
        expect(old.elementChecks).toBe('PASS', 'a server without counts: the verdict alone')
    })

    it('words a lifecycle, from the one table in agentWords (RD2-23)', () => {
        expect(lifecycleWord('READY_TO_SHIP')).toBe('ready to ship')
        expect(lifecycleWord(null)).toBe('')
    })

    it('describes a round for its release page', () => {
        const release = { uuid: 'r1', document: { specification: 'BOARD_REVIEW_ITEMS', round: 2, path: 'review/RD-1/r2.md', task: 't1',
            reviewItems: { reviewItems: [{ id: 'F-1', status: 'OPEN' }, { id: 'F-2', status: 'RESOLVED' }] },
            elements: { elements: [{ id: 'E1' }, { id: 'E2' }, { id: 'E3' }] } } }
        expect(documentRoundView(release, { key: 'RD-1', board: 'b1' }, 'o1', { pass: 2, fail: 0, skip: 1, blockingFailed: 0 }))
            .toEqual({ specification: 'review items', round: 2, path: 'review/RD-1/r2.md', taskLabel: 'RD-1',
                taskPath: '/aiAgentTask/t1', boardPath: '/aiAgentsOfOrg/o1?tab=boards&board=b1',
                reviewItems: '1 open · 2 items', elementChecks: { line: '2 pass · 0 fail · 1 skip', verdict: 'PASS' }, elementsCount: 3 })
        const bare = documentRoundView({ document: { specification: 'ARCHITECTURE', round: 1, path: 'a.md', task: 't1234567890' } },
            null, 'o1', null)
        expect(bare).toMatchObject({ taskLabel: 't1234567', boardPath: null, reviewItems: null, elementChecks: null, elementsCount: null })
        expect(documentRoundView({ uuid: 'r' }, null, 'o1', null)).toBeNull()
    })
})


// RD4-10: the home page's most-recent-releases widget lists software only.
describe('the most-recent-releases kinds', () => {
    it('are the software kinds, every kind but BOARD_DOCUMENT, unless the caller wants the rounds', () => {
        expect(recentReleasesKinds(false)).toBe(SOFTWARE_KINDS)
        expect(recentReleasesKinds(false)).toEqual(['GENERIC', 'HELM'])
        expect(recentReleasesKinds(false)).not.toContain('BOARD_DOCUMENT')
        expect(recentReleasesKinds(true)).toBeNull()
    })
})
