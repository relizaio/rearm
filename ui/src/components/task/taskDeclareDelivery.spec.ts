// @vitest-environment happy-dom
//
// Declaring a delivery from the task page (RD2-10): on a DELIVERING task, for who may operate the board,
// each PR not yet merged gets "Declare merge…" and "Mark abandoned…"; a board with no PRs gets
// "Declare delivery…". The forms check the commit before the server does and send agentTaskDeclareDelivery's
// variables.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import TaskPullRequests from './TaskPullRequests.vue'
import TaskActions from './TaskActions.vue'
import { fixtureRoles, richTask } from './taskFixtures'
import { declarationPayload, declarationDraftOf, commitProblem } from '@/utils/agentDelivery'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const open = 'https://github.com/acme/app/pull/7'
const merged = 'https://github.com/acme/app/pull/8'
const declared = 'https://github.com/acme/app/pull/9'

function delivering (over: Record<string, any> = {}) {
    return richTask({ status: 'DELIVERING', hold: null, prUrls: [open, merged, declared], pullRequests: [
        { url: open, state: null, registered: false },
        { url: merged, state: 'MERGED', registered: true },
        { url: declared, state: null, registered: false,
            declaration: { unit: declared, commit: 'abcdef1234', outcome: 'DELIVERED', by: { kind: 'USER', name: 'pavel' } } },
    ], ...over })
}

describe('the declare helpers', () => {
    it('check the commit as the server does', () => {
        expect(commitProblem('')).toBe('The merge commit is required')
        expect(commitProblem('abc12')).toBe('A commit is 7 to 40 hex characters')
        expect(commitProblem('xyz1234')).toBe('A commit is 7 to 40 hex characters')
        expect(commitProblem('abc1234')).toBeNull()
        expect(commitProblem('a'.repeat(40))).toBeNull()
        expect(commitProblem('a'.repeat(41))).toBe('A commit is 7 to 40 hex characters')
    })

    it('send a delivery with its commit, an abandonment with its reason', () => {
        const d = declarationDraftOf(open)
        expect(declarationPayload('t', d)).toBeNull()
        d.commit = ' ABC1234 '
        expect(declarationPayload('t', d)).toEqual({ task: 't', unit: open, commit: 'ABC1234', outcome: 'DELIVERED', note: null })
        const a = declarationDraftOf(open, 'ABANDONED')
        expect(declarationPayload('t', a)).toBeNull()
        a.note = 'superseded by #10'
        expect(declarationPayload('t', a)).toEqual({ task: 't', unit: open, commit: null, outcome: 'ABANDONED', note: 'superseded by #10' })
        expect(declarationPayload('t', { ...declarationDraftOf(null), commit: 'abc1234' })).toBeNull()
    })
})

describe('a PR row on a DELIVERING task', () => {
    const rowOf = (w: any, url: string) => w.findAll('.prrow').find((r: any) => r.find(`a[href="${url}"]`).exists())

    it('offers declare and abandon on a PR not yet merged, to who may operate the board only', () => {
        const w = mount(TaskPullRequests, { props: { task: delivering(), canOperate: true } })
        expect(rowOf(w, open).find('[data-testid="declare-merge"]').exists()).toBe(true)
        expect(rowOf(w, open).find('[data-testid="mark-abandoned"]').exists()).toBe(true)
        expect(rowOf(w, merged).find('[data-testid="declare-merge"]').exists()).toBe(false)
        // A declared PR shows who declared it at which commit, and needs nothing more.
        expect(rowOf(w, declared).find('[data-testid="declare-merge"]').exists()).toBe(false)
        expect(rowOf(w, declared).text()).toContain('declared by pavel at abcdef1')

        expect(mount(TaskPullRequests, { props: { task: delivering(), canOperate: false } })
            .find('[data-testid="declare-merge"]').exists()).toBe(false)
        expect(mount(TaskPullRequests, { props: { task: delivering({ status: 'COMPLETED' }), canOperate: true } })
            .find('[data-testid="declare-merge"]').exists()).toBe(false)
    })

    it('checks the commit, then sends the PR, the commit and the note', async () => {
        const task = delivering()
        const w = mount(TaskPullRequests, { props: { task, canOperate: true } })
        await rowOf(w, open).find('[data-testid="declare-merge"]').trigger('click')
        expect((w.find('[data-testid="declare-unit"] input').element as HTMLInputElement).value).toBe(open)
        await w.find('[data-testid="declare-commit"] input').setValue('zz')
        expect(w.find('[data-testid="declare-commit-error"]').text()).toBe('A commit is 7 to 40 hex characters')
        expect(w.find('[data-testid="declare-submit"]').attributes('disabled')).toBeDefined()
        await w.find('[data-testid="declare-commit"] input').setValue('0123abcd')
        await w.find('[data-testid="declare-note"] input').setValue('merged on the mirror')
        await w.find('[data-testid="declare-submit"]').trigger('click')
        expect(w.emitted('declare-delivery')?.[0]?.[0]).toEqual({ task, unit: open, commit: '0123abcd', outcome: 'DELIVERED',
            note: 'merged on the mirror' })
        expect(w.find('[data-declare]').exists()).toBe(false, 'the form closes')
    })

    it('marks a PR abandoned with a reason and no commit', async () => {
        const task = delivering()
        const w = mount(TaskPullRequests, { props: { task, canOperate: true } })
        await rowOf(w, open).find('[data-testid="mark-abandoned"]').trigger('click')
        expect(w.find('[data-testid="declare-commit"]').exists()).toBe(false)
        expect(w.find('[data-testid="declare-submit"]').attributes('disabled')).toBeDefined()
        await w.find('[data-testid="declare-note"] input').setValue('closed in favour of #10')
        await w.find('[data-testid="declare-submit"]').trigger('click')
        expect(w.emitted('declare-delivery')?.[0]?.[0]).toEqual({ task, unit: open, commit: null, outcome: 'ABANDONED',
            note: 'closed in favour of #10' })
    })
})

describe('a DELIVERING task on a board with no PRs', () => {
    const actions = (task: any) => mount(TaskActions, { props: { task, roles: fixtureRoles, board: {}, canReopen: true, admin: true } })

    it('offers Declare delivery with a free unit', async () => {
        const task = richTask({ status: 'DELIVERING', hold: null, prUrls: [] })
        const w = actions(task)
        await w.find('[data-testid="declare-delivery"]').trigger('click')
        await w.find('[data-testid="declare-delivery-unit"] input').setValue('release 1.4.0')
        await w.find('[data-testid="declare-delivery-commit"] input').setValue('fedcba9')
        await nextTick()
        await w.find('[data-testid="declare-delivery-submit"]').trigger('click')
        expect(w.emitted('declare-delivery')?.[0]?.[0]).toEqual({ task, unit: 'release 1.4.0', commit: 'fedcba9', outcome: 'DELIVERED', note: null })
    })

    it('is not offered where PRs are linked, or before DELIVERING', () => {
        expect(actions(richTask({ status: 'DELIVERING', hold: null, prUrls: [open] })).find('[data-testid="declare-delivery-row"]').exists())
            .toBe(false)
        expect(actions(richTask({ status: 'QUEUED', hold: null, prUrls: [] })).find('[data-testid="declare-delivery-row"]').exists())
            .toBe(false)
    })
})
