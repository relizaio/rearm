import { describe, it, expect } from 'vitest'
import {
    documentFileUrl,
    documentLabel,
    documentVerdict,
    templateRows,
    findingLocation,
    findingsOf,
    completionBlockers,
    groupByPriority,
    latestRound,
    openFindingsOf,
    outputsOfHop,
    sortFindings,
    statusType,
    testCounts,
    verdictType,
} from './agentDocuments'

// Merges into the defaults rather than replacing them: a trailing `...over` would clobber the
// whole document object, so `release({ document: { round: null } })` would silently drop the
// specification too and every assertion about it would be testing the fixture, not the code.
const release = (over: any = {}) => {
    const { document: doc, sourceCodeEntryDetails: sce, ...rest } = over
    return {
        uuid: 'r-1',
        ...rest,
        document: {
            specification: 'REVIEW_FINDINGS',
            path: 'findings/1a2b3c4d/round-2.md',
            round: 2,
            findings: {
                kind: 'REVIEW_FINDINGS',
                verdict: 'REJECTED',
                findings: [
                    { id: 'F-3', priority: 1, status: 'OPEN', title: 'null deref', location: { path: 'a/B.java', line: 412 } },
                    { id: 'F-1', priority: 2, status: 'RESOLVED', title: 'missing index' },
                ],
            },
            ...doc,
        },
        sourceCodeEntryDetails: {
            commit: 'abc1234',
            vcsRepository: { uri: 'github.com/acme/docs' },
            ...sce,
        },
    }
}

describe('documentFileUrl', () => {
    it('builds a blob url for hosts whose shape we know', () => {
        expect(documentFileUrl(release()))
            .toBe('https://github.com/acme/docs/blob/abc1234/findings/1a2b3c4d/round-2.md')
    })

    it('uses each host its own way', () => {
        const at = (uri: string) => documentFileUrl(release({ sourceCodeEntryDetails: { commit: 'c1', vcsRepository: { uri } } }))
        expect(at('gitlab.com/acme/docs')).toContain('/-/blob/c1/')
        expect(at('bitbucket.org/acme/docs')).toContain('/src/c1/')
        expect(at('codeberg.org/acme/docs')).toContain('/src/commit/c1/')
    })

    it('returns null for a host it cannot construct a url for', () => {
        // A repository can live anywhere, and there is no general rule for turning one into a
        // browsable URL. A guessed link that 404s reads as "the document is missing", which is a
        // worse answer than showing the path and letting someone look it up.
        expect(documentFileUrl(release({
            sourceCodeEntryDetails: { commit: 'c1', vcsRepository: { uri: 'git.example.com/team/docs' } },
        }))).toBeNull()
    })

    it('returns null rather than a broken url when anything is missing', () => {
        expect(documentFileUrl(release({ sourceCodeEntryDetails: { commit: null, vcsRepository: { uri: 'github.com/a/b' } } }))).toBeNull()
        expect(documentFileUrl(release({ document: { path: null } }))).toBeNull()
        expect(documentFileUrl(null)).toBeNull()
    })
})

describe('findings', () => {
    it('reads the index and separates open from closed', () => {
        expect(findingsOf(release()).map(f => f.id)).toEqual(['F-3', 'F-1'])
        expect(openFindingsOf(release()).map(f => f.id)).toEqual(['F-3'])
    })

    it('is empty rather than null for an unindexed document', () => {
        expect(findingsOf(release({ document: { findings: null } }))).toEqual([])
        expect(openFindingsOf(null)).toEqual([])
    })

    it('sorts by priority then id, highest priority first', () => {
        const sorted = sortFindings([
            { id: 'F-9', priority: 2 }, { id: 'F-2', priority: 1 }, { id: 'F-1', priority: 2 },
        ])
        expect(sorted.map(f => f.id)).toEqual(['F-2', 'F-1', 'F-9'])
    })

    it('keeps a priority outside the org scale rather than hiding it', () => {
        // Lowering the level count is validated at publish only, so history legitimately carries
        // higher numbers. A reader that dropped them would hide real findings.
        const sorted = sortFindings([{ id: 'F-1', priority: 7 }, { id: 'F-2', priority: 1 }])
        expect(sorted.map(f => f.id)).toEqual(['F-2', 'F-1'])
    })

    it('groups by priority, most urgent first', () => {
        const groups = groupByPriority([
            { id: 'F-1', priority: 2 }, { id: 'F-2', priority: 1 }, { id: 'F-3', priority: 1 },
        ])
        expect(groups.map(g => g.priority)).toEqual([1, 2])
        expect(groups[0].findings.map(f => f.id)).toEqual(['F-2', 'F-3'])
    })

    it('does not mutate the array it was given', () => {
        const input = [{ id: 'F-9', priority: 2 }, { id: 'F-1', priority: 1 }]
        sortFindings(input)
        expect(input.map(f => f.id)).toEqual(['F-9', 'F-1'])
    })
})

describe('display helpers', () => {
    it('labels a document by type and round', () => {
        expect(documentLabel(release())).toBe('review findings · round 2')
        expect(documentLabel(release({ document: { round: null } }))).toBe('review findings')
        expect(documentLabel(null)).toBe('—')
    })

    it('reads the verdict and test counts', () => {
        expect(documentVerdict(release())).toBe('REJECTED')
        expect(testCounts(release())).toBeNull()
        const report = release({ document: { findings: { counts: { passed: 412, failed: 2, skipped: 8 } } } })
        expect(testCounts(report)).toEqual({ passed: 412, failed: 2, skipped: 8 })
    })

    it('renders a location for code and for a document reference', () => {
        expect(findingLocation({ location: { path: 'a/B.java', line: 412 } })).toBe('a/B.java:412')
        expect(findingLocation({ location: { path: 'a/B.java' } })).toBe('a/B.java')
        expect(findingLocation({ location: { ref: 'REQ-14' } })).toBe('REQ-14')
        expect(findingLocation({})).toBe('')
    })

    it('colours status and verdict so open and rejected read as problems', () => {
        expect(statusType('OPEN')).toBe('error')
        expect(statusType('RESOLVED')).toBe('success')
        expect(statusType('ACCEPTED')).toBe('warning')
        expect(statusType('WITHDRAWN')).toBe('default')
        expect(statusType('SOMETHING_NEW')).toBe('default')
        expect(verdictType('PASSED')).toBe('success')
        expect(verdictType('REJECTED')).toBe('error')
    })
})

describe('outputsOfHop', () => {
    it('resolves a hop\'s uuids against the task\'s documents', () => {
        const docs = [release(), { uuid: 'r-2', document: { specification: 'TEST_REPORT' } }]
        expect(outputsOfHop(['r-2'], docs).map(d => d.uuid)).toEqual(['r-2'])
    })

    it('skips an id the task no longer lists instead of rendering a blank row', () => {
        expect(outputsOfHop(['r-1', 'gone'], [release()]).map(d => d.uuid)).toEqual(['r-1'])
    })

    it('is empty for a hop that produced nothing', () => {
        expect(outputsOfHop([], [release()])).toEqual([])
        expect(outputsOfHop(null, null)).toEqual([])
    })
})

describe('templateRows', () => {
    const effective = {
        REVIEW_FINDINGS: 'findings/{task}/round-{round}.md',
        TEST_REPORT: 'tests/{task}/run-{round}.md',
        QUESTIONS: 'questions/{task}/round-{round}.md',
        DETAILED_DESIGN: 'docs/{type}/{task}/round-{round}.md',
        GLOSSARY: 'docs/{type}/{component}.md',
    }
    it('lists the index types and every type an active role produces, with the server placeholder', () => {
        const rows = templateRows([
            { name: 'coder', active: true, producesOutputs: [{ specification: 'DETAILED_DESIGN', scope: 'TASK' }] },
            { name: 'old', active: false, producesOutputs: [{ specification: 'GLOSSARY', scope: 'COMPONENT' }] },
            { name: 'tester', producesOutputs: [{ specification: 'TEST_REPORT', scope: 'TASK' }] },
        ], effective)
        expect(rows.map(r => r.spec)).toEqual(['REVIEW_FINDINGS', 'TEST_REPORT', 'QUESTIONS', 'DETAILED_DESIGN'])
        expect(rows.find(r => r.spec === 'DETAILED_DESIGN')?.placeholder).toBe('docs/{type}/{task}/round-{round}.md')
    })
    it('lists only the index types for a board with no roles yet, and says the default when none is known', () => {
        const rows = templateRows(null, null)
        expect(rows.map(r => r.spec)).toEqual(['REVIEW_FINDINGS', 'TEST_REPORT', 'QUESTIONS'])
        expect(rows[0].placeholder).toBe('the default for its scope')
    })
})

describe('latestRound', () => {
    const doc = (uuid: string, spec: string, lifecycle: string, findings: any[] = []) => ({
        uuid, lifecycle, document: { specification: spec, findings: { kind: spec, findings } },
    })

    it('takes the newest settled round of the type, the list being newest first', () => {
        const docs = [
            doc('pending', 'REVIEW_FINDINGS', 'PENDING'),
            doc('tr', 'TEST_REPORT', 'ASSEMBLED'),
            doc('new', 'REVIEW_FINDINGS', 'ASSEMBLED'),
            doc('old', 'REVIEW_FINDINGS', 'ASSEMBLED'),
        ]
        expect(latestRound(docs, 'REVIEW_FINDINGS')?.uuid).toBe('new')
        expect(latestRound(docs, 'TEST_REPORT')?.uuid).toBe('tr')
        expect(latestRound(docs, 'QUESTIONS')).toBeNull()
    })

    it('counts a draft as a round: an agent round is a draft until its hop signs off', () => {
        expect(latestRound([doc('d', 'REVIEW_FINDINGS', 'DRAFT')], 'REVIEW_FINDINGS')?.uuid).toBe('d')
    })
})

describe('completionBlockers', () => {
    const docs = [
        { uuid: 'r', lifecycle: 'ASSEMBLED', document: { specification: 'REVIEW_FINDINGS', findings: { findings: [
            { id: 'F-2', priority: 2, status: 'OPEN' },
            { id: 'F-1', priority: 1, status: 'OPEN' },
            { id: 'F-3', priority: 1, status: 'ACCEPTED' },
        ] } } },
        { uuid: 't', lifecycle: 'ASSEMBLED', document: { specification: 'TEST_REPORT', findings: { findings: [
            { id: 'T-1', priority: 3, status: 'OPEN' },
        ] } } },
    ]

    it('lists open items at or above the completion priority, highest first', () => {
        expect(completionBlockers(docs, 1).map(b => b.finding.id)).toEqual(['F-1'])
        expect(completionBlockers(docs, 2).map(b => b.finding.id)).toEqual(['F-1', 'F-2'])
    })

    it('counts every open item when the board sets no threshold, and names the index', () => {
        const all = completionBlockers(docs, null)
        expect(all.map(b => b.finding.id)).toEqual(['F-1', 'F-2', 'T-1'])
        expect(all[2].specification).toBe('TEST_REPORT')
    })
})

describe('documentLifecycleLabel', () => {
    it('says what a board document lifecycle means', async () => {
        const { documentLifecycleLabel } = await import('./agentDocuments')
        expect(documentLifecycleLabel({ lifecycle: 'DRAFT' } as any)).toEqual({ label: 'draft', type: 'default' })
        expect(documentLifecycleLabel({ lifecycle: 'ASSEMBLED' } as any)).toEqual({ label: 'handed over', type: 'info' })
        expect(documentLifecycleLabel({ lifecycle: 'READY_TO_SHIP' } as any)).toEqual({ label: 'reviewed', type: 'success' })
        expect(documentLifecycleLabel({ lifecycle: 'GENERAL_AVAILABILITY' } as any)?.label).toBe('general availability')
        expect(documentLifecycleLabel({} as any)).toBeNull()
        expect(documentLifecycleLabel(null)).toBeNull()
    })
})
