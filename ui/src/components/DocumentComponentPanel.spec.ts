// @vitest-environment happy-dom
//
// A BOARD_DOCUMENT component's page (task 36d0549e): the board it belongs to, and its rounds.
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
            component: { uuid: 'c1', kind: 'BOARD_DOCUMENT', agentBoard: { uuid: 'bd1', name: 'ReARM Dogfood', taskPrefix: 'RD', readable: true } } } })
        await flushPromises()
        expect(dispatch).toHaveBeenCalledWith('fetchDocumentRounds', 'b1')
        expect(w.find('a.rl.documentComponent__board').exists()).toBe(true)
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
        const w = mount(Panel, { props: { orgUuid: 'o1', baseBranchUuid: null, component: { uuid: 'c1', kind: 'BOARD_DOCUMENT', agentBoard: null } } })
        await flushPromises()
        expect(w.find('.documentComponent__board').text()).toBe('Document component, no board')
        expect(w.find('a.rl.documentComponent__board').exists()).toBe(false)
    })

    // RD2-9: the component reads under its own permission (D18); its board only under the board's.
    it('names a board the person cannot open without linking to it', async () => {
        const w = mount(Panel, { props: { orgUuid: 'o1', baseBranchUuid: 'b1',
            component: { uuid: 'c1', kind: 'BOARD_DOCUMENT', agentBoard: { uuid: 'bd1', name: 'ReARM Dogfood', taskPrefix: 'RD', readable: false } } } })
        await flushPromises()
        expect(w.find('a.rl.documentComponent__board').exists()).toBe(false)
        expect(w.find('.documentComponent__board').text()).toBe('Belongs to board RD · ReARM Dogfood (board not visible to you)')
    })
})

// RD2-24: a normal Rounds heading, the state as a word, the task a link to its page, the date through ts().
describe('the BOARD_DOCUMENT page\'s rounds', () => {
    it('reads as the page speaks, and links each round to its task', async () => {
        const w = mount(Panel, { props: { orgUuid: 'o1', baseBranchUuid: 'b1',
            component: { uuid: 'c1', kind: 'BOARD_DOCUMENT', agentBoard: { uuid: 'bd1', name: 'ReARM Dogfood', taskPrefix: 'RD', readable: true } } } })
        await flushPromises()
        const heading = w.find('[data-testid="rounds-heading"]')
        expect(heading.element.tagName).toBe('DIV', 'a section heading, not the 9 px h6')
        expect(heading.classes()).toContain('dsec__h')
        const rows = w.findAll('.documentComponent__round')
        expect(rows[1].text()).toContain('assembled')
        expect(rows[1].text()).not.toContain('ASSEMBLED')
        const task = rows[0].find('[data-testid="round-task"]')
        expect(task.text()).toBe('RD-1')
    })
})

