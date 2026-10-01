// @vitest-environment happy-dom
//
// Controls shown to people who cannot act (RD2-6, d8e7bd7e §3.6): a BOARD_READ person sees no "File
// a review item", no "decide" and no element-check-report "Re-run" -- hidden, not disabled -- and still reads
// the review items and the report. A BOARD_WRITE person sees all three.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskReviewItems from './TaskReviewItems.vue'
import TaskDocuments from './TaskDocuments.vue'
import AiAgentElementCheckReport from '../AiAgentElementCheckReport.vue'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn().mockResolvedValue([]), getters: {} }) }))

const reader = { myPermissions: ['BOARD_READ'] }
const writer = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }

const task = {
    uuid: 't1', status: 'AWAITING_COORDINATOR',
    documents: [{ uuid: 'r1', lifecycle: 'READY_TO_SHIP', document: { specification: 'BOARD_REVIEW_ITEMS', round: 1,
        reviewItems: { kind: 'BOARD_REVIEW_ITEMS', verdict: 'REJECTED',
            reviewItems: [{ id: 'F-1', priority: 1, status: 'OPEN', title: 'fix the thing' }] } } }],
}

const decideButtons = (w: any) => w.findAll('button').filter((b: any) => b.text() === 'decide')

describe('review items for a reader and a writer', () => {
    it('a reader reads the review items but gets no decide and no File a review item', () => {
        const w = mount(TaskReviewItems, { props: { task, board: reader } as any })
        expect(w.find('.frow').exists()).toBe(true)
        expect(w.text()).toContain('fix the thing')
        expect(decideButtons(w)).toHaveLength(0)
        expect(w.text()).not.toContain('File a review item')
    })

    it('a board with no permissions served reads as a reader', () => {
        const w = mount(TaskReviewItems, { props: { task, board: { myPermissions: null } } as any })
        expect(decideButtons(w)).toHaveLength(0)
        expect(w.text()).not.toContain('File a review item')
    })

    it('a writer decides and files', () => {
        const w = mount(TaskReviewItems, { props: { task, board: writer } as any })
        expect(decideButtons(w)).toHaveLength(1)
        expect(w.text()).toContain('File a review item')
    })

    it('nobody decides on a task past the decidable states', () => {
        const w = mount(TaskReviewItems, { props: { task: { ...task, status: 'COMPLETED' }, board: writer } as any })
        expect(decideButtons(w)).toHaveLength(0)
    })
})

const elements = { grammarVersion: '1.1', digest: 'd1', elements: [{ id: 'REQ-1', family: 'REQ', title: 'A requirement' }], warnings: [] }
function release (uuid: string, specification: string, extra: Record<string, any> = {}) {
    return { uuid, version: '1', lifecycle: 'ASSEMBLED', component: 'c1', createdDate: '2026-09-25T09:00:00Z',
        document: { specification, path: `docs/${uuid}.md`, digest: uuid, mediaType: 'text/markdown', task: 't1',
            session: 's1', round: 1, elements: null, elementChecks: null, reviewItems: null, ...extra } }
}
const docs = [
    release('cr1', 'BOARD_ELEMENT_CHECK_REPORT', { elementChecks: { catalogueVersion: '2026-09.2', digest: 'x', scope: { checked: 'a1', releases: [] },
        results: [{ check: 'ids.unique', result: 'PASS', blocking: false }] } }),
    release('a1', 'ARCHITECTURE', { elements }),
]

describe('the check report for a reader and a writer', () => {
    it('shows Re-run only to who may re-run it, and the report to both', () => {
        const read = mount(AiAgentElementCheckReport, { props: { release: docs[1], documents: docs } as any })
        expect(read.find('.chk__line').exists()).toBe(true)
        expect(read.find('[data-testid="element-check-rerun"]').exists()).toBe(false)
        const write = mount(AiAgentElementCheckReport, { props: { release: docs[1], documents: docs, canRerun: true } as any })
        expect(write.find('[data-testid="element-check-rerun"]').exists()).toBe(true)
    })

    it('the documents section hands the flag to each report', () => {
        const w = mount(TaskDocuments, { props: { task: { uuid: 't1', status: 'ASSIGNED', documents: docs }, canRerun: true },
            shallow: true })
        expect(w.findComponent(AiAgentElementCheckReport).props('canRerun')).toBe(true)
        const r = mount(TaskDocuments, { props: { task: { uuid: 't1', status: 'ASSIGNED', documents: docs } }, shallow: true })
        expect(r.findComponent(AiAgentElementCheckReport).props('canRerun')).toBeFalsy()
    })
})
