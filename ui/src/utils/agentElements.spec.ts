import { describe, expect, it } from 'vitest'
import type { DocumentRelease } from '@/utils/agentDocuments'
import {
    documentDefining, elementsOf, findingElement, findingsByElement, historyRows, linkCounts, linksIn,
    linksOut, taskElementsOf, warningsOf,
} from '@/utils/agentElements'

const repo = { commit: 'abc1234', vcsRepository: { uri: 'github.com/acme/docs' } }

function doc (uuid: string, spec: string, round: number, elements: any[], extra: any = {}): DocumentRelease {
    return {
        uuid,
        lifecycle: extra.lifecycle ?? 'DRAFT',
        sourceCodeEntryDetails: repo,
        document: { specification: spec, round, path: `docs/${spec.toLowerCase()}.md`, elements: { elements, warnings: extra.warnings ?? [] }, findings: extra.findings ?? null },
    }
}

const design2 = doc('d2', 'ARCHITECTURE', 2, [
    { id: 'REQ-1', family: 'requirement', title: 'Refuse a cycle', line: 3 },
    { id: 'REQ-2', family: 'requirement', title: 'Name the tasks', parent: 'REQ-1', line: 8 },
], { warnings: [{ code: 'UNRESOLVED_TARGET', elementId: 'REQ-2', message: 'x' }] })
const design1 = doc('d1', 'ARCHITECTURE', 1, [{ id: 'REQ-1', title: 'old', line: 3 }])
const note = doc('n1', 'DETAILED_DESIGN', 1, [
    { id: 'FN-1', traces: [{ verb: 'satisfies', target: 'REQ-1' }], assumes: ['ADR-7'], line: 2 },
    { id: 'FN-2', parent: 'REQ-2', line: 9 },
])
const review = doc('r1', 'REVIEW_FINDINGS', 1, [], {
    findings: {
        findings: [
            { id: 'F-2', priority: 2, status: 'OPEN', title: 'vague', location: { element: 'REQ-2', path: 'docs/architecture.md', line: 8 } },
            { id: 'F-1', priority: 1, status: 'OPEN', title: 'wrong', location: { element: 'REQ-2' } },
            { id: 'F-3', priority: 1, status: 'RESOLVED', title: 'done', location: { element: 'REQ-1' } },
            { id: 'F-4', priority: 3, status: 'OPEN', title: 'code', location: { path: 'a.java', line: 3 } },
        ],
    },
})
// newest first, as the server returns them
const documents = [review, note, design2, design1]

describe('the elements of a task', () => {
    it('reads a document index, stamped with where each element came from', () => {
        const els = elementsOf(design2)
        expect(els.map(e => e.id)).toEqual(['REQ-1', 'REQ-2'])
        expect(els[1]).toMatchObject({ release: 'd2', specification: 'ARCHITECTURE', parent: 'REQ-1' })
        expect(elementsOf(review)).toEqual([])
        expect(elementsOf(null)).toEqual([])
    })

    it('takes the newest round of each document, as the server does', () => {
        const els = taskElementsOf(documents)
        expect(els.map(e => `${e.id}@${e.release}`)).toEqual(['FN-1@n1', 'FN-2@n1', 'REQ-1@d2', 'REQ-2@d2'])
        const cancelled = doc('d3', 'ARCHITECTURE', 3, [{ id: 'REQ-9' }], { lifecycle: 'CANCELLED' })
        expect(taskElementsOf([cancelled, ...documents]).some(e => e.id === 'REQ-9')).toBe(false)
    })

    it('finds the warnings about one element', () => {
        expect(warningsOf(design2, 'REQ-2')).toHaveLength(1)
        expect(warningsOf(design2, 'REQ-1')).toHaveLength(0)
    })
})

describe('links', () => {
    const els = taskElementsOf(documents)

    it('goes out along parent, traces and assumptions, with the verb as the kind', () => {
        const fn1 = els.find(e => e.id === 'FN-1')!
        expect(linksOut(fn1)).toEqual([
            { from: 'FN-1', to: 'REQ-1', kind: 'satisfies' },
            { from: 'FN-1', to: 'ADR-7', kind: 'assumes' },
        ])
    })

    it('comes in from whoever points at an element', () => {
        expect(linksIn(els, 'REQ-1')).toEqual([
            { from: 'FN-1', to: 'REQ-1', kind: 'satisfies' },
            { from: 'REQ-2', to: 'REQ-1', kind: 'parent' },
        ])
    })

    it('counts in and out per element, including targets no document defines', () => {
        const c = linkCounts(els)
        expect(c.get('REQ-1')).toEqual({ in: 2, out: 0 })
        expect(c.get('REQ-2')).toEqual({ in: 1, out: 1 })
        expect(c.get('FN-1')).toEqual({ in: 0, out: 2 })
        expect(c.get('ADR-7')).toEqual({ in: 1, out: 0 })
    })
})

describe('findings about an element', () => {
    it('are the open ones of the newest rounds, most urgent first', () => {
        const by = findingsByElement(documents)
        expect(by.get('REQ-2')?.map(f => f.id)).toEqual(['F-1', 'F-2'])
        expect(by.has('REQ-1')).toBe(false)
        expect([...by.keys()]).toEqual(['REQ-2'])
    })

    it('reads the element a finding names', () => {
        expect(findingElement({ location: { element: 'REQ-2' } })).toBe('REQ-2')
        expect(findingElement({ location: { path: 'a.java' } })).toBeNull()
        expect(findingElement(null)).toBeNull()
    })
})

describe('history', () => {
    it('marks the first round, then changed or same, each linked at the element line', () => {
        const rows = historyRows([
            { release: 'd1', round: 1, line: 3, changed: false },
            { release: 'd2', round: 2, line: 5, changed: true },
            { release: 'gone', round: 3, line: 5, changed: false },
        ], documents)
        expect(rows.map(r => r.mark)).toEqual(['first', 'changed', 'same'])
        expect(rows[1].url).toBe('https://github.com/acme/docs/blob/abc1234/docs/architecture.md#L5')
        expect(rows[2].url).toBeNull()
        expect(historyRows(null, documents)).toEqual([])
    })

    it('finds the document that defines an element now', () => {
        expect(documentDefining(documents, 'REQ-1')?.uuid).toBe('d2')
        expect(documentDefining(documents, 'REQ-77')).toBeNull()
    })
})
