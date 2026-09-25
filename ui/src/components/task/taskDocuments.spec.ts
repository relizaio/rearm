// @vitest-environment happy-dom
//
// The Documents section after #406 (elements.md §7): a CHECK_REPORT round is not a row of its own but
// is read under the document it is about, so the list hides it and each document with elements gets
// the check report view, which is handed every round so it can find the newest report for it.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AiAgentCheckReport from '../AiAgentCheckReport.vue'
import TaskDocuments from './TaskDocuments.vue'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const elements = { grammarVersion: '1.1', digest: 'd1', elements: [{ id: 'REQ-1', family: 'REQ', title: 'A requirement' }], warnings: [] }

function release (uuid: string, specification: string, round: number, extra: Record<string, any> = {}) {
    return { uuid, version: `${round}`, lifecycle: 'ASSEMBLED', component: 'c1', createdDate: '2026-09-25T09:00:00Z',
        sourceCodeEntryDetails: null,
        document: { specification, path: `docs/${uuid}.md`, digest: uuid, mediaType: 'text/markdown', task: 't1',
            session: 's1', round, elements: null, checks: null, findings: null, ...extra } }
}

const documents = [
    release('cr1', 'CHECK_REPORT', 1, { checks: { catalogueVersion: '2026-09.2', digest: 'x',
        scope: { checked: 'a1', releases: [] }, results: [] } }),
    release('a1', 'ARCHITECTURE', 1, { elements }),
    release('n1', 'DETAILED_DESIGN', 1),
]

describe('task documents', () => {
    it('lists every document but the check report rounds, and shows a report under each with elements', () => {
        const w = mount(TaskDocuments, { props: { task: { uuid: 't1', board: 'b1', status: 'ASSIGNED', documents } },
            shallow: true })
        const rows = w.findAll('.drow__label')
        expect(rows).toHaveLength(2)
        expect(w.findAll('.drow__path--plain').map(c => c.text())).toEqual(['docs/a1.md', 'docs/n1.md'])
        const reports = w.findAllComponents(AiAgentCheckReport)
        expect(reports).toHaveLength(1)
        expect(reports[0].props('release').uuid).toBe('a1')
        expect(reports[0].props('documents')).toHaveLength(3)
    })
})
