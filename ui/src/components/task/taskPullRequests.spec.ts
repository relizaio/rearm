// @vitest-environment happy-dom
//
// The PR list shows the head that passed against the PR's head now (task 3b97ccfd).
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskPullRequests from './TaskPullRequests.vue'

describe('task pull requests', () => {
    it('flags a PR that moved past its tested head', () => {
        const w = mount(TaskPullRequests, { props: { task: {
            status: 'DELIVERING',
            prUrls: ['https://github.com/acme/app/pull/1', 'https://github.com/acme/app/pull/2'],
            testedHeads: [{ pr: 'https://github.com/acme/app/pull/1', head: 'aaaaaaa1' },
                { pr: 'https://github.com/acme/app/pull/2', head: 'aaaaaaa1' }],
            pullRequests: [
                { url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true, head: 'aaaaaaa1ffff' },
                { url: 'https://github.com/acme/app/pull/2', state: 'OPEN', registered: true, head: 'bbbbbbb2ffff' }
            ]
        } } })
        const heads = w.findAll('.prheads')
        expect(heads).toHaveLength(2)
        expect(heads[0].text()).toBe('tested aaaaaaa · the PR is at it')
        expect(heads[1].text()).toBe('tested aaaaaaa · now bbbbbbb: moved past the tested head')
    })

    it('shows no heads on a read without them', () => {
        const w = mount(TaskPullRequests, { props: { task: {
            prUrls: ['https://github.com/acme/app/pull/1'],
            pullRequests: [{ url: 'https://github.com/acme/app/pull/1', state: 'OPEN', registered: true }]
        } } })
        expect(w.find('.prheads').exists()).toBe(false)
    })
})
