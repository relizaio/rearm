// @vitest-environment happy-dom
//
// RD2-12 (sweep UI-13): the timeline follows its panel's width, never under 760, with distinct tick labels.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Timeline from './AiAgentTaskTimelineView.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const at = (m: number) => new Date(Date.UTC(2026, 8, 27, 10, m, 0)).toISOString()
const tasks = [{ uuid: 't1', key: 'RD-1', title: 'one', returns: [],
    signOffs: [{ role: 'coder', agent: 'a1', session: 's1', outcome: 'PASSED', assignedAt: at(0), signedOffAt: at(3) }] }]

describe('the timeline\'s width', () => {
    it('follows the panel, and never draws under 760', async () => {
        const w = mount(Timeline, { props: { tasks, agentNames: {} } })
        expect(w.find('svg').attributes('width')).toBe('760', 'a panel it cannot measure: the least width')
        ;(w.vm as any).panelWidth = 1180
        await w.vm.$nextTick()
        expect(w.find('svg').attributes('width')).toBe('1180')
        ;(w.vm as any).panelWidth = 600
        await w.vm.$nextTick()
        expect(w.find('svg').attributes('width')).toBe('760')
    })

    it('names its ticks distinctly, the last one ending inside the drawing', () => {
        const w = mount(Timeline, { props: { tasks, agentNames: {} } })
        const labels = w.findAll('text[data-tick]')
        expect(labels).toHaveLength(5)
        expect(new Set(labels.map(l => l.text())).size).toBe(5)
        expect(labels[4].attributes('text-anchor')).toBe('end')
    })
})
