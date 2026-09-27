// @vitest-environment happy-dom
//
// The PERT with task groups (RD2-31): a box per group along the top, an edge for each dependency
// between groups, and a dotted line from a gated task up to each group it waits on.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Pert from './AiAgentTaskPertView.vue'
import { groupColour } from '@/utils/agentTaskGroups'

const board = {
    groups: [
        { key: 'core-work', order: 1, dependsOn: [], status: 'OPEN', progress: { total: 1, done: 0, open: 1 } },
        { key: 'ui-work', order: 2, dependsOn: ['core-work'], status: 'OPEN', progress: { total: 1, done: 0, open: 1 } },
        { key: 'old-work', order: 3, dependsOn: [], status: 'CLOSED', progress: { total: 0, done: 0, open: 0 } },
    ],
}
const tasks = [
    { uuid: 'a', key: 'RD-1', title: 'core', status: 'QUEUED', group: { key: 'core-work' }, waitingOnGroups: [] },
    { uuid: 'b', key: 'RD-2', title: 'ui', status: 'QUEUED', group: { key: 'ui-work' }, waitingOnGroups: ['core-work'] },
    { uuid: 'c', key: 'RD-3', title: 'loose', status: 'QUEUED', group: null },
]

describe('AiAgentTaskPertView: groups', () => {
    it('draws a box per group, an edge per dependency and a dotted gate to each gated task', () => {
        const w = mount(Pert, { props: { tasks, board } })
        expect(w.findAll('[data-testid="pert-group"]').map(g => g.attributes('data-key'))).toEqual(['core-work', 'ui-work', 'old-work'])
        expect(w.findAll('[data-testid="pert-group"]')[0].text()).toContain('0 of 1 done')
        expect(w.findAll('[data-testid="pert-group-edge"]')).toHaveLength(1)
        expect(w.findAll('[data-testid="pert-gate-edge"]').map(e => e.attributes('data-gate'))).toEqual(['core-work>b'])
        expect(w.find('.pert-group--closed').attributes('data-key')).toBe('old-work')
        // Each grouped task carries its group's colour on its edge.
        const stripes = w.findAll('.pert-node__group').map(s => s.attributes('style'))
        expect(stripes).toHaveLength(2)
        expect(stripes[0]).toContain(`fill: ${groupColour('core-work')}`)
    })

    it('draws no group band on a board without groups', () => {
        const w = mount(Pert, { props: { tasks: tasks.map(t => ({ ...t, group: null, waitingOnGroups: [] })), board: {} } })
        expect(w.find('[data-testid="pert-group"]').exists()).toBe(false)
        expect(w.find('[data-testid="pert-gate-edge"]').exists()).toBe(false)
        expect(w.findAll('.pert-node')).toHaveLength(3)
    })
})

