// @vitest-environment happy-dom
//
// Unlinking a PR that should never have counted, from the task page (task t20261010-033523-18839): every linked
// PR of a live task offers "Unlink..." to a person with BOARD_WRITE; it is disabled, in the server's words, for a
// merged or delivered PR, for the replacement of a superseded one, on a task parked for the operator, and for the
// last PR a DELIVERING task has to deliver; taking out an open PR a DELIVERING task waits on says it posts an
// ALERT. The "Unlinked" list shows who took each PR off and why, and the relink stamp; each chip carries its
// delivery unit (task t20261010-033522-23606). The page's own task is t20261010-033525-24393.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import TaskPullRequests from './TaskPullRequests.vue'
import { richTask } from './taskFixtures'
import { offersUnlink, unlinkDisabledReason, unlinkPayload, unlinkWarning } from '@/utils/agentTaskAdmin'
import { prChips, unitLine } from '@/utils/agentDelivery'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const a = 'https://github.com/relizaio/rearm-saas/pull/799'
const b = 'https://github.com/relizaio/rearm-saas/pull/800'
const m = 'https://github.com/relizaio/rearm-saas/pull/790'

/** A superseded by B, B open, M merged; DELIVERING with B's head tested. */
function pair (over: Record<string, any> = {}) {
    return richTask({ key: 'RS-7', status: 'DELIVERING', hold: null, prUrls: [a, b, m],
        pullRequests: [
            { url: a, state: 'CLOSED', registered: true, unit: 'SUPERSEDED',
                declaration: { unit: a, outcome: 'SUPERSEDED', supersededBy: b, by: { kind: 'SESSION', name: 'coder' } } },
            { url: b, state: 'OPEN', registered: true, unit: 'WAITING' },
            { url: m, state: 'MERGED', registered: true, unit: 'DELIVERED' },
        ],
        testedHeads: [{ pr: b, head: 'a3d02e0e4f00' }], ...over })
}

const row = (w: any, url: string) => w.findAll('.prrow').find((r: any) => r.find('a').attributes('href') === url)

describe('the unlink helpers', () => {
    it('offer every linked PR of a live task, none of a terminal one', () => {
        for (const u of [a, b, m]) expect(offersUnlink(pair(), u), u).toBe(true)
        expect(offersUnlink(pair(), 'https://github.com/relizaio/rearm-saas/pull/1')).toBe(false)
        for (const status of ['COMPLETED', 'CANCELLED']) expect(offersUnlink(pair({ status }), a), status).toBe(false)
    })

    it('give the server reasons, in its order', () => {
        expect(unlinkDisabledReason(pair(), a, false)).toBe('unlinking a PR needs BOARD_WRITE on this board')
        expect(unlinkDisabledReason(pair(), a, true)).toBeNull()
        expect(unlinkDisabledReason(pair(), m, true)).toBe(`${m} merged; a delivered PR stays linked`)
        const declared = pair()
        declared.pullRequests[1] = { ...declared.pullRequests[1], declaration: { outcome: 'DELIVERED' }, unit: 'DELIVERED' }
        expect(unlinkDisabledReason(declared, b, true)).toBe(`${b} is declared delivered; a delivered PR stays linked`)
        expect(unlinkDisabledReason(pair(), b, true)).toBe(`${b} is the replacement of ${a} (declared superseded by it); unlink ${a}`
            + ' first, or supersede it by another PR')
        const parked = pair({ status: 'ON_HOLD', hold: { level: 'OPERATOR', kind: 'MANUAL', returnTo: 'DELIVERING',
            heldBy: { kind: 'SESSION', uuid: 's9', name: 'seat' }, reason: 'awaiting the operator: merge?' } })
        expect(unlinkDisabledReason(parked, a, true)).toBe('This task is parked, awaiting the operator; release the hold first, then unlink')
    })

    it('refuse the last PR a DELIVERING task has to deliver, and only on DELIVERING with units read', () => {
        const only = richTask({ key: 'RS-8', status: 'DELIVERING', hold: null, prUrls: [b],
            pullRequests: [{ url: b, state: 'OPEN', registered: true, unit: 'WAITING' }] })
        expect(unlinkDisabledReason(only, b, true)).toBe(`Unlinking ${b} would leave this task with no PR to deliver; reopen it to`
            + ' the role that redoes the work, supersede it (task supersedepr), or cancel it')
        expect(unlinkDisabledReason({ ...only, status: 'AWAITING_COORDINATOR' }, b, true)).toBeNull()
        expect(unlinkDisabledReason({ ...only, pullRequests: [{ url: b, state: 'OPEN', registered: true }] }, b, true)).toBeNull()
        // A PR spelled with a trailing slash is the same PR.
        expect(unlinkDisabledReason(pair(), m + '/', true)).toBe(`${m}/ merged; a delivered PR stays linked`)
        // A superseded and a merged PR elsewhere: the merged one still delivers, so the open one may go.
        const p = pair()
        p.pullRequests[0] = { ...p.pullRequests[0], declaration: null, unit: 'BLOCKED' }
        expect(unlinkDisabledReason(p, b, true)).toBeNull()
    })

    it('warn before an open PR a DELIVERING task waits on goes, with the tested head when one is named', () => {
        expect(unlinkWarning(pair(), b)).toBe('relizaio/rearm-saas/pull/800 is open and delivery waits on it: unlinking it posts'
            + ' an ALERT, and the tested head a3d02e0 no longer counts')
        expect(unlinkWarning(pair({ testedHeads: [] }), b)).toBe('relizaio/rearm-saas/pull/800 is open and delivery waits on it:'
            + ' unlinking it posts an ALERT')
        expect(unlinkWarning(pair(), a)).toBeNull()
        // Before DELIVERING the page cannot tell whether a pass is live: the server's ALERT says it.
        expect(unlinkWarning(pair({ status: 'AWAITING_COORDINATOR' }), b)).toBeNull()
    })

    it('send the note trimmed, none when blank', () => {
        expect(unlinkPayload(pair(), a, '  linked by mistake ')).toMatchObject({ prUrl: a, note: 'linked by mistake' })
        expect(unlinkPayload(pair(), a, '  ')).toMatchObject({ prUrl: a, note: null })
    })

    it('say how delivery counts each PR', () => {
        expect(prChips(pair()).map(c => c.unit)).toEqual([unitLine('SUPERSEDED'), unitLine('WAITING'), unitLine('DELIVERED')])
        expect(unitLine(undefined)).toBeUndefined()
    })
})

describe('the task page', () => {
    it('unlinks a superseded PR with a note', async () => {
        const w = mount(TaskPullRequests, { props: { task: pair(), canOperate: true } })
        await row(w, a).find('[data-testid="unlink-pr"]').trigger('click')
        await nextTick()
        expect(w.find('[data-testid="unlink-warning"]').exists()).toBe(false)
        await w.find('[data-testid="unlink-note"] input').setValue('the superseded half of the pair')
        await w.find('[data-testid="unlink-submit"]').trigger('click')
        expect(w.emitted('unlink')?.[0]?.[0]).toMatchObject({ prUrl: a, note: 'the superseded half of the pair' })
    })

    it('disables the replacement and the merged PR with the reason', () => {
        const w = mount(TaskPullRequests, { props: { task: pair(), canOperate: true } })
        expect(row(w, b).find('[data-testid="unlink-pr"]').attributes('disabled')).toBeDefined()
        expect(row(w, b).find('[data-hint]').attributes('data-hint')).toContain('is the replacement of')
        expect(row(w, m).find('[data-testid="unlink-pr"]').attributes('disabled')).toBeDefined()
        expect(mount(TaskPullRequests, { props: { task: pair({ status: 'COMPLETED' }), canOperate: true } })
            .find('[data-testid="unlink-pr"]').exists()).toBe(false)
    })

    it('warns before a person takes out an open PR a pass names', async () => {
        const t = pair()
        t.pullRequests[0] = { ...t.pullRequests[0], declaration: null, unit: 'BLOCKED' }
        const w = mount(TaskPullRequests, { props: { task: t, canOperate: true } })
        await row(w, b).find('[data-testid="unlink-pr"]').trigger('click')
        await nextTick()
        expect(w.find('[data-testid="unlink-warning"]').text()).toContain('posts an ALERT, and the tested head a3d02e0 no longer counts')
    })

    it('lists the unlinked PRs with who, when, why and the relink stamp, even with none linked', () => {
        const t = richTask({ status: 'AWAITING_COORDINATOR', hold: null, prUrls: [], pullRequests: [], unlinkedPrs: [
            { url: a, by: { kind: 'USER', uuid: 'u1', name: 'Pat' }, at: '2026-10-10T06:00:00Z', note: 'linked by mistake',
                relinkedBy: null, relinkedAt: null },
            { url: b, by: { kind: 'SESSION', uuid: 's1', name: 'coder' }, at: '2026-10-10T06:05:00Z', note: null,
                relinkedBy: 'coder-agent', relinkedAt: '2026-10-10T07:00:00Z' },
        ] })
        const rows = mount(TaskPullRequests, { props: { task: t, canOperate: true } }).findAll('[data-testid="unlinked-pr"]')
        expect(rows.length).toBe(2)
        expect(rows[0].text()).toContain('unlinked by Pat')
        expect(rows[0].text()).toContain(': linked by mistake')
        expect(rows[0].text()).not.toContain('re-linked')
        expect(rows[1].text()).toContain('re-linked by coder-agent')
    })

    it('puts the delivery unit on the state chip', () => {
        const w = mount(TaskPullRequests, { props: { task: pair(), canOperate: true } })
        expect(row(w, b).find('[data-testid="pr-state"]').attributes('title')).toBe('delivery: waiting on it to merge')
    })
})
