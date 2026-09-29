// @vitest-environment happy-dom
//
// The task page's PR row says how far a registered PR's base moved since the round (task RD4-2).
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskPullRequests from './TaskPullRequests.vue'

describe('task pull requests: base moved (task RD4-2)', () => {
    it('shows the line on a PR whose base moved, and none elsewhere', () => {
        const w = mount(TaskPullRequests, { props: { task: {
            status: 'AWAITING_COORDINATOR',
            prUrls: ['https://github.com/acme/app/pull/1', 'https://github.com/acme/app/pull/2'],
            pullRequests: [
                { url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true, baseMovedBy: 3 },
                { url: 'https://github.com/acme/app/pull/2', state: null, registered: false, baseMovedBy: null }
            ]
        } } })
        const lines = w.findAll('[data-testid="base-moved"]')
        expect(lines).toHaveLength(1)
        expect(lines[0].text()).toBe('base moved: 3 commits since your round')
    })
})
