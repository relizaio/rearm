// @vitest-environment happy-dom
//
// Task RD4-7: a version of a round the same hop replaced by publishing the path again stays in the
// Documents list, greyed, with "replaced by v<n>"; the current version reads as before.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskDocuments from './TaskDocuments.vue'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

function release (uuid: string, version: string, extra: Record<string, any> = {}) {
    return { uuid, version, lifecycle: 'DRAFT', component: 'c1', createdDate: '2026-09-29T09:00:00Z',
        sourceCodeEntryDetails: null,
        document: { specification: 'DETAILED_DESIGN', path: 'impl/RD-1/notes-2.md', digest: uuid, mediaType: 'text/markdown',
            task: 't1', session: 's1', round: 2, elements: null, checks: null, findings: null, ...extra } }
}

describe('a replaced version in the task documents', () => {
    it('is greyed and names its replacement; the newest version is not', () => {
        const documents = [release('n2', '12'), release('n1', '11', { supersededBy: 'n2' })]
        const w = mount(TaskDocuments, { props: { task: { uuid: 't1', board: 'b1', status: 'ASSIGNED', documents } },
            shallow: false })
        const rows = w.findAll('.drow')
        expect(rows).toHaveLength(2)
        expect(rows[0].classes()).not.toContain('drow--superseded')
        expect(rows[0].text()).not.toContain('replaced')
        expect(rows[1].classes()).toContain('drow--superseded')
        expect(rows[1].text()).toContain('replaced by v12')
    })
})
