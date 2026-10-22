// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskStale from './TaskStale.vue'
import { staleTask } from './taskFixtures'

// The task page's stale block (task TPF-69, mutation round).
describe('TaskStale', () => {
    it('is headed Stale and carries the chip with the ALERT text as its title', () => {
        const w = mount(TaskStale, { props: { task: staleTask('assigned') } })
        expect(w.find('.dsec__h').text()).toBe('Stale')
        expect(w.find('.tstale').text()).toContain('stale: no progress')
        expect(w.find('.tstale').attributes('title')).toContain('hopNoProgress: ')
    })

    it('shows nothing for a task with no mark', () => {
        const w = mount(TaskStale, { props: { task: { stale: [] } } })
        expect(w.find('[data-testid="task-stale"]').exists()).toBe(false)
    })
})
