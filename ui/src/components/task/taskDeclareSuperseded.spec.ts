// @vitest-environment happy-dom
//
// Declaring a linked PR superseded from the task page (task RD3-18): a closed, unmerged row not yet settled
// offers "Declare superseded…" to a person with BOARD_WRITE; the picker lists the task's other linked PRs on
// the same repository; a reader sees the button disabled with the reason; it sends the user mutation's
// variables.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { NSelect } from 'naive-ui'
import TaskPullRequests from './TaskPullRequests.vue'
import { richTask } from './taskFixtures'
import { offersSupersede, prRepository, supersedeCandidates, supersedeDisabledReason, supersedePayload } from '@/utils/agentTaskAdmin'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const old = 'https://github.com/relizaio/rearm-saas/pull/692'
const replacement = 'https://github.com/relizaio/rearm-saas/pull/694'
const other = 'https://github.com/relizaio/rearm-saas/pull/700'
const elsewhere = 'https://github.com/relizaio/terraform-provider-rearm/pull/15'
const merged = 'https://github.com/relizaio/rearm-saas/pull/690'

function replaced (over: Record<string, any> = {}, extra: any[] = []) {
    return richTask({ status: 'AWAITING_COORDINATOR', hold: null, prUrls: [old, replacement, elsewhere, merged, ...extra.map(e => e.url)],
        pullRequests: [
            { url: old, state: 'CLOSED', registered: true },
            { url: replacement, state: 'OPEN', registered: true },
            { url: elsewhere, state: 'OPEN', registered: true },
            { url: merged, state: 'MERGED', registered: true },
            ...extra,
        ], ...over })
}

const row = (w: any, url: string) => w.findAll('.prrow').find((r: any) => r.find('a').attributes('href') === url)

describe('the helpers', () => {
    it('key a repository as the server does', () => {
        expect(prRepository('HTTPS://GitHub.com/relizaio/rearm-saas/pull/692/')).toBe('https://github.com/relizaio/rearm-saas')
        expect(prRepository(old)).toBe(prRepository(replacement))
        expect(prRepository(old)).not.toBe(prRepository(elsewhere))
        expect(prRepository(' ')).toBeNull()
    })

    it('offer the declaration on a closed row not settled, a PR declared abandoned included', () => {
        const t = replaced()
        expect(offersSupersede(t, old)).toBe(true)
        for (const u of [replacement, merged]) expect(offersSupersede(t, u)).toBe(false)
        const abandoned = replaced({}, [])
        abandoned.pullRequests[0] = { ...abandoned.pullRequests[0], declaration: { outcome: 'ABANDONED' } }
        expect(offersSupersede(abandoned, old)).toBe(true)
        for (const outcome of ['SUPERSEDED', 'DELIVERED']) {
            const settled = replaced()
            settled.pullRequests[0] = { ...settled.pullRequests[0], declaration: { outcome } }
            expect(offersSupersede(settled, old), outcome).toBe(false)
        }
    })

    it('list same-repository candidates only, and send nothing without one', () => {
        expect(supersedeCandidates(replaced(), old)).toEqual([replacement, merged])
        expect(supersedeDisabledReason(replaced(), old, false)).toBe('declaring a PR superseded needs BOARD_WRITE on this board')
        expect(supersedeDisabledReason(richTask({ prUrls: [old], pullRequests: [{ url: old, state: 'CLOSED' }] }), old, true))
            .toBe('link the PR that replaces it first, on the same repository')
        expect(supersedePayload(replaced(), old, elsewhere, '')).toBeNull()
        expect(supersedePayload(replaced(), old, replacement, '  dropped trailer-less merges ')).toMatchObject(
            { oldUrl: old, byUrl: replacement, note: 'dropped trailer-less merges' })
    })
})

describe('the task page row', () => {
    it('shows the button on the closed row only, and sends the mutation variables', async () => {
        const w = mount(TaskPullRequests, { props: { task: replaced(), canOperate: true } })
        expect(row(w, old).find('[data-testid="declare-superseded"]').exists()).toBe(true)
        for (const u of [replacement, merged, elsewhere]) {
            expect(row(w, u).find('[data-testid="declare-superseded"]').exists(), u).toBe(false)
        }
        await row(w, old).find('[data-testid="declare-superseded"]').trigger('click')
        await nextTick()
        const select = w.findComponent(NSelect)
        expect((select.props('options') as any[]).map(o => o.value)).toEqual([replacement, merged])
        select.vm.$emit('update:value', replacement)
        await nextTick()
        await w.find('[data-testid="supersede-note"] input').setValue('dropped trailer-less merges')
        await w.find('[data-testid="supersede-submit"]').trigger('click')
        expect(w.emitted('supersede')?.[0]?.[0]).toMatchObject({ oldUrl: old, byUrl: replacement, note: 'dropped trailer-less merges' })
    })

    it('is disabled with the reason for a person without BOARD_WRITE', () => {
        const w = mount(TaskPullRequests, { props: { task: replaced(), canOperate: false } })
        const b = row(w, old).find('[data-testid="declare-superseded"]')
        expect(b.attributes('disabled')).toBeDefined()
        expect(row(w, old).find('[data-hint]').attributes('data-hint')).toBe('declaring a PR superseded needs BOARD_WRITE on this board')
    })

    it('is absent on a superseded row and on a completed task', () => {
        const done = replaced()
        done.pullRequests[0] = { ...done.pullRequests[0], declaration: { outcome: 'SUPERSEDED', supersededBy: replacement } }
        expect(mount(TaskPullRequests, { props: { task: done, canOperate: true } }).find('[data-testid="declare-superseded"]').exists())
            .toBe(false)
        expect(mount(TaskPullRequests, { props: { task: replaced({ status: 'COMPLETED' }), canOperate: true } })
            .find('[data-testid="declare-superseded"]').exists()).toBe(false)
    })
})
