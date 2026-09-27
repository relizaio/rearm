// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TableView from './AiAgentTaskTableView.vue'

const stubs = { RouterLink: { props: ['to'], template: '<a class="rl" :href="to"><slot/></a>' } }

describe('AiAgentTaskTableView', () => {
    it('links each title to its task page; the row still opens the drawer', async () => {
        const tasks = [{ uuid: 't1', title: 'Task page per task', status: 'QUEUED', externalRef: 'github:o/r#42' }]
        const w = mount(TableView, { props: { tasks, agentNames: {} }, global: { stubs } })
        const link = w.find('a.rl')
        expect(link.attributes('href')).toBe('/aiAgentTask/t1')
        expect(link.text()).toBe('Task page per task')
        await link.trigger('click')
        expect(w.emitted('open')).toBeUndefined()
        await w.find('tbody tr').trigger('click')
        expect(w.emitted('open')?.[0]).toEqual([tasks[0]])
    })
})

// The Level column (RD2-1): sorted numerically with no level last, and the filter box takes "L2".
describe('AiAgentTaskTableView: level', () => {
    const tasks = [
        { uuid: 'a', key: 'RD2-1', title: 'deep', status: 'QUEUED', level: 3 },
        { uuid: 'b', key: 'RD2-2', title: 'none of its own', status: 'QUEUED', level: null },
        { uuid: 'c', key: 'RD2-3', title: 'shallow', status: 'QUEUED', level: 0 },
    ]

    it('shows each task\'s level, the board default for one without', () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board: { defaultTaskLevel: 1 } }, global: { stubs } })
        expect(w.findAll('[data-level]').map(e => e.text())).toEqual(['L3', 'L1', 'L0'])
    })

    it('sorts by level, no level last', async () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board: {} }, global: { stubs } })
        const order = () => w.findAll('tbody tr').map(r => r.text().includes('shallow') ? 'c' : r.text().includes('deep') ? 'a' : 'b')
        const header = () => w.findAll('th').find(th => th.text().includes('Level'))!
        await header().trigger('click')
        const first = order()
        await header().trigger('click')
        const second = order()
        // One click sorts one way and the next the other: 0, 3, then no level (or reversed).
        expect([first, second]).toEqual(expect.arrayContaining([['c', 'a', 'b'], ['b', 'a', 'c']]))
    })

    it('filters by "L2" or "level 2", and by text otherwise', async () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board: {} }, global: { stubs } })
        await w.find('input').setValue('L3')
        expect(w.findAll('tbody tr').map(r => r.text())).toHaveLength(1)
        expect(w.find('tbody').text()).toContain('deep')
        await w.find('input').setValue('level 0')
        expect(w.find('tbody').text()).toContain('shallow')
        await w.find('input').setValue('none of')
        expect(w.find('tbody').text()).toContain('none of its own')
    })
})

// Group and Tags columns (RD2-31): the filter box matches group and tag keys, and the group and tag
// filters the page keeps in the URL narrow the rows.
describe('AiAgentTaskTableView: groups and tags', () => {
    const board = { groups: [{ key: 'core-work', order: 1, status: 'OPEN' }, { key: 'ui-work', order: 2, status: 'OPEN' }] }
    const tasks = [
        { uuid: 'a', key: 'RD2-1', title: 'front', status: 'QUEUED', group: { key: 'ui-work' }, tags: [{ key: 'client-req' }] },
        { uuid: 'b', key: 'RD2-2', title: 'back', status: 'QUEUED', group: { key: 'core-work' }, tags: [] },
        { uuid: 'c', key: 'RD2-3', title: 'loose', status: 'QUEUED', group: null, tags: [{ key: 'urgent' }, { key: 'client-req' }] },
    ]
    const titles = (w: any) => w.findAll('tbody tr').map((r: any) => r.text().includes('front') ? 'a' : r.text().includes('back') ? 'b' : 'c')

    it('shows each task\'s group and tags', () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board }, global: { stubs } })
        expect(w.findAll('[data-group]').map(e => e.text())).toEqual(['ui-work', 'core-work'])
        expect(w.findAll('[data-tags]').map(e => e.attributes('data-tags'))).toEqual(['client-req', 'urgent,client-req'])
    })

    it('sorts groups in the board\'s order, ungrouped last', async () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board }, global: { stubs } })
        const header = () => w.findAll('th').find((th: any) => th.text().includes('Group'))!
        await header().trigger('click')
        const first = titles(w)
        await header().trigger('click')
        expect([first, titles(w)]).toEqual(expect.arrayContaining([['b', 'a', 'c'], ['c', 'a', 'b']]))
    })

    it('filters by group or tag text in the filter box', async () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board }, global: { stubs } })
        await w.find('input').setValue('core-work')
        expect(titles(w)).toEqual(['b'])
        await w.find('input').setValue('client')
        expect(titles(w)).toEqual(['a', 'c'])
    })

    it('narrows to the group and tag the page asks for', async () => {
        const w = mount(TableView, { props: { tasks, agentNames: {}, board, groupFilter: 'ui-work' }, global: { stubs } })
        expect(titles(w)).toEqual(['a'])
        await w.setProps({ groupFilter: '', tagFilter: 'client-req' })
        expect(titles(w)).toEqual(['c'], 'ungrouped and tagged')
        await w.setProps({ groupFilter: null, tagFilter: 'client-req' })
        expect(titles(w)).toEqual(['a', 'c'])
    })
})
