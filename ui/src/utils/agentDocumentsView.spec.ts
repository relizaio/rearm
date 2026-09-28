import { describe, it, expect } from 'vitest'
import {
    SOFTWARE_KINDS,
    documentBoardBanner,
    documentRoundRows,
    documentSeriesRows,
    isDocumentComponent,
    splitDocumentDependencies,
} from './agentDocumentsView'
import { cardRef } from './agentTaskFormat'

// Documents inside the board (task 36d0549e).
describe('documents view', () => {
    it('the lists ask for the software kinds only', () => {
        expect(SOFTWARE_KINDS).toEqual(['GENERIC', 'HELM'])
        expect(SOFTWARE_KINDS).not.toContain('DOCUMENT')
        expect(isDocumentComponent({ kind: 'DOCUMENT' })).toBe(true)
        expect(isDocumentComponent({ kind: 'GENERIC' })).toBe(false)
        expect(isDocumentComponent(null)).toBe(false)
    })

    it('a branch groups its document dependencies apart', () => {
        const deps = [
            { component: { uuid: 'a', kind: 'GENERIC' } },
            { component: { uuid: 'd', kind: 'DOCUMENT' } },
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
                latestRound: { round: 2, version: '5', lifecycle: 'ASSEMBLED' }, roundsCount: 5, openFindings: null, checkVerdict: 'PASS' },
            { specification: 'REVIEW_FINDINGS', component: { uuid: 'c2', name: 'rd-review_findings' },
                latestRound: null, roundsCount: 0, openFindings: 3, checkVerdict: null },
        ])
        expect(rows.map(r => r.label)).toEqual(['architecture', 'review findings'])
        expect(rows[0]).toMatchObject({ componentName: 'rd-architecture', latest: 'round 2 · 5', roundsCount: 5, checkVerdict: 'PASS' })
        expect(rows[1]).toMatchObject({ latest: '—', openFindings: 3 })
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
