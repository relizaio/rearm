// @vitest-environment happy-dom
//
// A DOCUMENT component's page (task 36d0549e): the board it belongs to, and its rounds.
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Panel from './DocumentComponentPanel.vue'

const dispatch = vi.fn(async () => ({
    releases: [
        { uuid: 'r1', version: '1', lifecycle: 'ASSEMBLED', createdDate: '2026-09-01T00:00:00Z',
            document: { round: 1, path: 'design/RD-1/architecture-1.md', task: 't1' } },
        { uuid: 'r2', version: '2', lifecycle: 'DRAFT', createdDate: '2026-09-02T00:00:00Z',
            document: { round: 2, path: 'design/RD-1/architecture-2.md', task: 't1' } },
    ],
    taskKeys: { t1: 'RD-1' },
}))
vi.mock('vuex', () => ({ useStore: () => ({ dispatch }) }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }), RouterLink: { props: ['to'], template: '<a class="rl"><slot/></a>' } }))

describe('DocumentComponentPanel', () => {
    it('names the board with its prefix and lists the rounds newest first', async () => {
        const w = mount(Panel, { props: { orgUuid: 'o1', baseBranchUuid: 'b1',
            component: { uuid: 'c1', kind: 'DOCUMENT', agentBoard: { uuid: 'bd1', name: 'ReARM Dogfood', taskPrefix: 'RD' } } } })
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchDocumentRounds', 'b1')
        expect(w.find('.documentComponent__board').text()).toBe('Belongs to board RD · ReARM Dogfood')
        expect(w.find('.documentComponent__title').text()).toBe('Rounds')
        const rows = w.findAll('.documentComponent__round')
        expect(rows).toHaveLength(2)
        expect(rows[0].text()).toContain('architecture-2.md')
        expect(rows[0].text()).toContain('RD-1')
        await rows[0].trigger('click')
        expect(push).toHaveBeenCalledWith({ name: 'ReleaseView', params: { uuid: 'r2' } })
    })

    it('says a document nobody owns has no board', async () => {
        const w = mount(Panel, { props: { orgUuid: 'o1', baseBranchUuid: null, component: { uuid: 'c1', kind: 'DOCUMENT', agentBoard: null } } })
        await flushPromises()
        expect(w.find('.documentComponent__board').text()).toBe('Document component, no board')
        expect(w.find('a.rl').exists()).toBe(false)
    })
})
