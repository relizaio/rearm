import { describe, expect, it } from 'vitest'
import type { DocumentRelease } from '@/utils/agentDocuments'
import {
    describeCheck, isStale, latestReportFor, offencesByElement, reportOf, resultType, summarise, summaryLine,
} from '@/utils/agentChecks'

function report (uuid: string, checked: string, round: number, results: any[], scope: any[] = [], lifecycle = 'ASSEMBLED'): DocumentRelease {
    return {
        uuid,
        lifecycle,
        document: {
            specification: 'CHECK_REPORT', round,
            checks: { catalogueVersion: '2026-09.2', scope: { checked, releases: scope }, results },
        } as any,
    }
}

function doc (uuid: string, spec: string, digest: string | null): DocumentRelease {
    return { uuid, lifecycle: 'DRAFT', document: { specification: spec, elements: digest ? { digest } : null } as any }
}

const results = [
    { check: 'ids.family', result: 'PASS', blocking: false },
    { check: 'trace.parent_exists', result: 'FAIL', blocking: true, offences: [
        { elementId: 'REQ-12', message: 'REQ-12 → REQ-4 not found' },
        { elementId: 'REQ-13', message: 'REQ-13 → REQ-5 not found' },
        { elementId: 'REQ-12', message: 'again' },
    ] },
    { check: 'tests.no_orphans', result: 'FAIL', blocking: false, offences: [{ elementId: 'T-1', message: 'x' }] },
    { check: 'speculative.inputs_recorded', result: 'SKIP', blocking: false, reason: 'no assignment' },
    { check: 'coverage.l2-verified', result: 'PASS', blocking: true, reason: 'select: …; require: …' },
]

describe('the report of a document', () => {
    const r2 = report('c2', 'a1', 2, results)
    const r1 = report('c1', 'a1', 1, [])
    const other = report('c3', 'b1', 1, [])
    const cancelled = report('c4', 'a1', 3, [], [], 'CANCELLED')
    const documents = [cancelled, r2, other, r1]

    it('is the newest settled CHECK_REPORT round naming it', () => {
        expect(latestReportFor(documents, 'a1')?.uuid).toBe('c2')
        expect(latestReportFor(documents, 'b1')?.uuid).toBe('c3')
        expect(latestReportFor(documents, 'zz')).toBeNull()
        expect(latestReportFor(documents, null)).toBeNull()
        expect(reportOf(r2)?.results).toHaveLength(5)
        expect(reportOf(doc('a1', 'ARCHITECTURE', 'x'))).toBeNull()
    })

    it('summarises the results and names what blocks', () => {
        const s = summarise(reportOf(r2))
        expect(s).toEqual({ pass: 2, fail: 2, skip: 1, blockingFailed: ['trace.parent_exists'] })
        expect(summaryLine(s)).toBe('2 pass · 2 fail · 1 skip')
        expect(summarise(null)).toEqual({ pass: 0, fail: 0, skip: 0, blockingFailed: [] })
    })

    it('groups offences by element, in reported order', () => {
        expect(offencesByElement(results[1])).toEqual([
            { elementId: 'REQ-12', messages: ['REQ-12 → REQ-4 not found', 'again'] },
            { elementId: 'REQ-13', messages: ['REQ-13 → REQ-5 not found'] },
        ])
        expect(offencesByElement(null)).toEqual([])
    })

    it('colours results and describes checks from the catalogue, a gate by its pattern', () => {
        expect(resultType('PASS')).toBe('success')
        expect(resultType('FAIL')).toBe('error')
        expect(resultType('SKIP')).toBe('default')
        const catalogue = [{ name: 'ids.family', description: 'prefixes are families' },
            { name: 'coverage.<gate>', description: 'the board gate' }]
        expect(describeCheck(catalogue, 'ids.family')).toBe('prefixes are families')
        expect(describeCheck(catalogue, 'coverage.l2-verified')).toBe('the board gate')
        expect(describeCheck(catalogue, 'unknown')).toBe('')
    })
})

describe('staleness', () => {
    const scope = [
        { release: 'a1', specification: 'ARCHITECTURE', elementsDigest: 'd-a1' },
        { release: 'q1', specification: 'REQUIREMENTS', elementsDigest: 'd-q1' },
        { release: 'x1', specification: 'DATA_MODEL', elementsDigest: 'd-x1' },
    ]
    const rep = reportOf(report('c1', 'a1', 1, [], scope))

    it('is fresh while the task has the same inputs', () => {
        expect(isStale(rep, [doc('a1', 'ARCHITECTURE', 'd-a1'), doc('q1', 'REQUIREMENTS', 'd-q1')])).toBe(false)
    })

    it('is stale when a newer input of a scoped specification has another index', () => {
        expect(isStale(rep, [doc('q2', 'REQUIREMENTS', 'd-q2'), doc('a1', 'ARCHITECTURE', 'd-a1'), doc('q1', 'REQUIREMENTS', 'd-q1')])).toBe(true)
    })

    it('does not count the document itself, or an input the task does not carry', () => {
        expect(isStale(rep, [doc('a2', 'ARCHITECTURE', 'd-a2'), doc('q1', 'REQUIREMENTS', 'd-q1')])).toBe(false)
        expect(isStale(rep, [])).toBe(false)
        expect(isStale(null, [])).toBe(false)
    })
})
