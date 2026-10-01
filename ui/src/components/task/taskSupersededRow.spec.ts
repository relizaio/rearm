// @vitest-environment happy-dom
//
// A linked PR declared superseded by its replacement (task RD3-13): the chip says so, naming the successor; the
// task page keeps the row, struck through, and offers no declaration on it.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskPullRequests from './TaskPullRequests.vue'
import { richTask } from './taskFixtures'
import { declarable, declarationChip, prChips, prNumber } from '@/utils/agentDelivery'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const old = 'https://github.com/relizaio/rearm-saas/pull/692'
const replacement = 'https://github.com/relizaio/rearm-saas/pull/694'

function replaced (over: Record<string, any> = {}) {
    return richTask({ status: 'DELIVERING', hold: null, prUrls: [old, replacement], pullRequests: [
        { url: old, state: 'CLOSED', registered: true,
            declaration: { unit: old, outcome: 'SUPERSEDED', supersededBy: replacement, by: { kind: 'SESSION', name: 'coder' },
                note: 'trailer-less merge dropped' } },
        { url: replacement, state: 'OPEN', registered: true },
    ], ...over })
}

describe('a superseded PR', () => {
    it('is a superseded chip naming its successor, never declarable', () => {
        const chip = declarationChip(replaced().pullRequests[0])!
        expect(chip).toMatchObject({ state: 'superseded', type: 'default', superseded: true,
            title: 'superseded by #694, declared by coder: trailer-less merge dropped' })
        expect(declarable(chip)).toBe(false)
        expect(prChips(replaced()).map(c => c.state)).toEqual(['superseded', 'open'])
    })

    it('names a PR by its number, or its short form when it has none', () => {
        expect(prNumber(replacement)).toBe('#694')
        expect(prNumber(replacement + '/')).toBe('#694')
        expect(prNumber('https://gitlab.example.com/team/app/-/merge_requests/12')).toBe('#12')
        expect(prNumber('https://example.com/a/b/c/branch-x')).toBe('a/b/c/branch-x')
    })

    it('keeps its row on the task page, struck through, with no declare buttons', () => {
        const w = mount(TaskPullRequests, { props: { task: replaced(), canOperate: true } })
        const rows = w.findAll('.prrow')
        expect(rows.length).toBe(2)
        const link = rows[0].find('a')
        expect(link.classes()).toContain('struck')
        expect(rows[0].text()).toContain('superseded by #694')
        expect(rows[0].find('[data-testid="declare-merge"]').exists()).toBe(false)
        expect(rows[1].find('a').classes()).not.toContain('struck')
        expect(rows[1].find('[data-testid="declare-merge"]').exists()).toBe(true)
    })
})
