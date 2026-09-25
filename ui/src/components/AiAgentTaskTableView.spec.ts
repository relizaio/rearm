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
