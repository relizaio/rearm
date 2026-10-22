// @vitest-environment happy-dom
//
// TPF-89: every instruction, note and reason a person writes to the board on the task page is a growing
// textarea, and a multi-line value reaches the emitted payload with its newlines (only the ends trimmed
// where the helper trims). One describe per family; the rigs are those of the specs each sits beside.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { nextTick } from 'vue'
import { NSelect } from 'naive-ui'
import TaskHeader from './TaskHeader.vue'
import TaskActions from './TaskActions.vue'
import TaskReviewItems from './TaskReviewItems.vue'
import TaskPullRequests from './TaskPullRequests.vue'
import GrowingTextInput from './GrowingTextInput.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const confirm = { emits: ['positive-click'], props: ['disabled'],
    template: '<div class="pc"><slot name="trigger"/><span class="pc__body"><slot/></span><button class="pc__yes" @click="$emit(\'positive-click\')"/></div>' }
const modal = { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }
const stubs = { NPopconfirm: confirm, Popconfirm: confirm }

const two = 'first line\nsecond line'
const padded = '  ' + two + '  '

const button = (w: any, text: string) => w.findAll('button').find((b: any) => b.text() === text)

describe('TaskHeader: lift note, gate review, sign-off note', () => {
    it('lifts a manual hold with a two-line note, and shows the hold reason (row 5)', async () => {
        const hold = { level: 'OPERATOR', kind: 'MANUAL', reason: 'waiting on legal\nand on the vendor',
            heldBy: { kind: 'USER', name: 'pavel' }, heldAt: '2026-09-25T10:00:00Z' }
        const w = mount(TaskHeader, { props: { task: richTask({ status: 'ON_HOLD', hold }), roles: fixtureRoles, canOperate: true } })
        expect(w.find('[data-testid="lift-note"] textarea').exists()).toBe(true)
        expect(w.find('[data-testid="lift-note"] input').exists()).toBe(false)
        expect(w.find('[data-testid="hold-reason-text"]').text()).toBe(hold.reason)
        await w.find('[data-testid="lift-note"] textarea').setValue(two)
        await w.find('.relbtn').trigger('click')
        expect((w.emitted('lift-hold')?.[0]?.[0] as any).note).toBe(two)
    })

    it('sends the gate review note and the item reason with their newlines (row 6)', async () => {
        const task = richTask({
            status: 'ON_HOLD',
            hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'gate', heldAt: '2026-09-27T10:00:00Z' },
            signOffs: [{ role: 'reviewer', outcome: 'REJECTED', outputs: ['r2'], signedOffAt: '2026-09-27T09:00:00Z' }],
            documents: [{ uuid: 'r2', document: { specification: 'BOARD_REVIEW_ITEMS',
                reviewItems: { reviewItems: [{ id: 'F-1', priority: 1, status: 'OPEN', title: 'the cycle is not refused' }] } } }],
        })
        const w = mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: true, board: { blockingPriority: null } },
            global: { stubs } })
        await w.find('[data-testid="gate-review-note"] textarea').setValue(two)
        await w.find('.gateitem__accept input').setValue(true)
        expect(w.find('.gateitem__reason textarea').exists()).toBe(true)
        await w.find('[data-testid="gate-item-reason"] textarea').setValue(padded)
        await w.find('.pc__yes').trigger('click')
        const p: any = w.emitted('human-review')?.[0]?.[0]
        expect(p.note).toBe(two)
        expect(p.reviewItems[0].resolution).toBe(two)
    })

    it('signs off a human stage with a two-line note (row 7)', async () => {
        const w = mount(TaskHeader, { props: { task: richTask({ status: 'QUEUED', role: 'signoff', hold: null }),
            roles: fixtureRoles, canOperate: true } })
        await w.find('[data-testid="signoff-note"] textarea').setValue(two)
        await button(w, 'Sign off PASSED').trigger('click')
        const p: any = w.emitted('human-signoff')?.[0]?.[0]
        expect(p).toEqual({ task: p.task, outcome: 'PASSED', note: two })
        expect(p.task.uuid).toBe('t1')
    })
})

describe('TaskActions: hold, unassign, cancel, reopen, declare delivery, complete', () => {
    const actions = (task: any, board: any = {}) => mount(TaskActions, {
        props: { task, roles: fixtureRoles, board, canReopen: true, admin: true }, global: { stubs: { ...stubs, NModal: modal, Modal: modal } } })

    it('places a hold and unassigns with two-line reasons (row 8)', async () => {
        const held = actions(richTask({ status: 'AWAITING_COORDINATOR', hold: null }))
        await held.find('[data-testid="hold-reason"] textarea').setValue(padded)
        await held.find('[data-testid="hold-place"]').trigger('click')
        expect((held.emitted('operator-hold')?.[0]?.[0] as any).reason).toBe(two)

        const assigned = actions(richTask({ status: 'ASSIGNED', hold: null }), { myPermissions: ['BOARD_WRITE'] })
        await assigned.find('[data-testid="unassign-reason"] textarea').setValue(two)
        await assigned.find('[data-testid="unassign"]').trigger('click')
        expect((assigned.emitted('unassign')?.[0]?.[0] as any).reason).toBe(two)
        expect((assigned.find('[data-testid="unassign-reason"] textarea').element as HTMLTextAreaElement).value).toBe('')
    })

    it('cancels and reopens with two-line notes (row 9)', async () => {
        const live = actions(richTask({ status: 'AWAITING_COORDINATOR', hold: null }))
        expect(live.findAll('.pc__yes')).toHaveLength(1)
        await live.find('[data-testid="cancel-note"] textarea').setValue(two)
        await live.find('.pc__yes').trigger('click')
        expect((live.emitted('cancel')?.[0]?.[0] as any).note).toBe(two)

        const task = richTask({ status: 'COMPLETED', hold: null })
        const done = actions(task)
        expect(done.findAll('.pc__yes')).toHaveLength(1)
        ;(done.vm as any).reopenRole = 'coder'
        await nextTick()
        await done.find('[data-testid="reopen-reason"] textarea').setValue(two)
        await done.find('.pc__yes').trigger('click')
        expect(done.emitted('reopen')?.[0]?.[0]).toEqual({ task, role: 'coder', reason: two })
    })

    it('declares a delivery with a two-line note (row 10)', async () => {
        const w = actions(richTask({ status: 'DELIVERING', hold: null, prUrls: [] }))
        await w.find('[data-testid="declare-delivery"]').trigger('click')
        await w.find('[data-testid="declare-delivery-unit"] input').setValue('release 1.4.0')
        await w.find('[data-testid="declare-delivery-commit"] input').setValue('fedcba9')
        await w.find('[data-testid="declare-delivery-note"] textarea').setValue(two)
        await nextTick()
        await w.find('[data-testid="declare-delivery-submit"]').trigger('click')
        expect((w.emitted('declare-delivery')?.[0]?.[0] as any).note).toBe(two)
    })

    it('decides a blocking review item in the complete dialog, with no prop warning (row 11)', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        try {
            const task = richTask({ status: 'AWAITING_COORDINATOR', hold: null })
            const w = actions(task, { completionPriority: 2 })
            ;(w.vm as any).showComplete = true
            await nextTick()
            expect(w.find('.modal').text()).toContain('F-4')
            expect(warn.mock.calls.filter(c => String(c[0]).includes('Invalid prop'))).toEqual([])
            await w.find('[data-testid="blocker-words"] textarea').setValue(two)
            const accept = w.find('.modal').findAll('button').find((b: any) => b.text() === 'Accept the risk')!
            await accept.trigger('click')
            expect(w.emitted('decide')?.[0]?.[0]).toEqual({ task, specification: 'BOARD_REVIEW_ITEMS',
                decisions: [{ action: 'ACCEPT', reviewItemId: 'F-4', resolution: two }] })
        } finally {
            warn.mockRestore()
        }
    })
})

describe('TaskReviewItems: the decision words', () => {
    it('accepts a review item with a two-line resolution (row 12)', async () => {
        const w = mount(TaskReviewItems, { props: { task: richTask(), board: { myPermissions: ['BOARD_WRITE'] } } as any })
        const f4 = w.findAll('.frow').find((r: any) => r.find('.frow__id').text() === 'F-4')!
        await f4.findAll('button').find((b: any) => b.text() === 'decide')!.trigger('click')
        await w.find('[data-testid="decision-words"] textarea').setValue(two)
        await button(w, 'Accept the risk').trigger('click')
        expect((w.emitted('decide')?.[0]?.[0] as any).decisions[0]).toEqual({ action: 'ACCEPT', reviewItemId: 'F-4', resolution: two })
        expect(w.find('[data-testid="decision-words"]').exists()).toBe(false)
    })
})

describe('TaskPullRequests: unlink, supersede and declare notes', () => {
    const row = (w: any, url: string) => w.findAll('.prrow').find((r: any) => r.find('a').attributes('href') === url)

    it('unlinks with a two-line note, keeping the cap and the counter (row 13)', async () => {
        const a = 'https://github.com/relizaio/rearm-saas/pull/799'
        const b = 'https://github.com/relizaio/rearm-saas/pull/800'
        const m = 'https://github.com/relizaio/rearm-saas/pull/790'
        const task = richTask({ key: 'RS-7', status: 'DELIVERING', hold: null, prUrls: [a, b, m],
            pullRequests: [
                { url: a, state: 'CLOSED', registered: true, unit: 'SUPERSEDED',
                    declaration: { unit: a, outcome: 'SUPERSEDED', supersededBy: b, by: { kind: 'SESSION', name: 'coder' } } },
                { url: b, state: 'OPEN', registered: true, unit: 'WAITING' },
                { url: m, state: 'MERGED', registered: true, unit: 'DELIVERED' },
            ] })
        const w = mount(TaskPullRequests, { props: { task, canOperate: true } })
        await row(w, a).find('[data-testid="unlink-pr"]').trigger('click')
        await nextTick()
        expect(w.find('[data-testid="unlink-note"] textarea').attributes('maxlength')).toBe('4000')
        expect(w.findComponent(GrowingTextInput).props('showCount')).toBe(true)
        await w.find('[data-testid="unlink-note"] textarea').setValue(two)
        await w.find('[data-testid="unlink-submit"]').trigger('click')
        expect((w.emitted('unlink')?.[0]?.[0] as any).note).toBe(two)
    })

    it('declares superseded, merged and abandoned with two-line notes (row 14)', async () => {
        const old = 'https://github.com/relizaio/rearm-saas/pull/692'
        const replacement = 'https://github.com/relizaio/rearm-saas/pull/694'
        const replaced = richTask({ status: 'AWAITING_COORDINATOR', hold: null, prUrls: [old, replacement],
            pullRequests: [
                { url: old, state: 'CLOSED', registered: true },
                { url: replacement, state: 'OPEN', registered: true },
            ] })
        const s = mount(TaskPullRequests, { props: { task: replaced, canOperate: true } })
        await row(s, old).find('[data-testid="declare-superseded"]').trigger('click')
        await nextTick()
        s.findComponent(NSelect).vm.$emit('update:value', replacement)
        await nextTick()
        await s.find('[data-testid="supersede-note"] textarea').setValue(two)
        await s.find('[data-testid="supersede-submit"]').trigger('click')
        expect((s.emitted('supersede')?.[0]?.[0] as any).note).toBe(two)

        const open = 'https://github.com/acme/app/pull/7'
        const delivering = () => richTask({ status: 'DELIVERING', hold: null, prUrls: [open],
            pullRequests: [{ url: open, state: null, registered: false }] })
        const d = mount(TaskPullRequests, { props: { task: delivering(), canOperate: true } })
        await row(d, open).find('[data-testid="declare-merge"]').trigger('click')
        await d.find('[data-testid="declare-commit"] input').setValue('0123abcd')
        await d.find('[data-testid="declare-note"] textarea').setValue(two)
        await d.find('[data-testid="declare-submit"]').trigger('click')
        expect((d.emitted('declare-delivery')?.[0]?.[0] as any).note).toBe(two)

        const ab = mount(TaskPullRequests, { props: { task: delivering(), canOperate: true } })
        await row(ab, open).find('[data-testid="mark-abandoned"]').trigger('click')
        await ab.find('[data-testid="declare-note"] textarea').setValue(two)
        await ab.find('[data-testid="declare-submit"]').trigger('click')
        expect(ab.emitted('declare-delivery')?.[0]?.[0]).toMatchObject({ outcome: 'ABANDONED', note: two })
    })
})

const source = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')

describe('the sources: render sites and converted fields', () => {
    it('render stored line breaks where the notes are shown (row 18)', () => {
        const header = source('./TaskHeader.vue')
        expect(header).toContain('<div class="holdreason" data-testid="hold-reason-text">{{ task.hold.reason }}</div>')
        expect(header).toMatch(/@use '\.\/taskSections';[\s\S]*\.holdreason \{ white-space: pre-wrap; overflow-wrap: anywhere; \}/)
        expect(source('../AiAgentSessionView.vue')).toContain('.close-attribution { white-space: pre-wrap')
        const panel = source('../AiAgentBoardsPanel.vue')
        const passages = panel.slice(panel.indexOf('p.t.signOffs.map('), panel.indexOf('p.t.signOffs.map(') + 600)
        expect(passages).toContain("default: () => h('span', { style: 'white-space: pre-wrap' },")
    })

    it('every converted field is a growing-text-input and none is a one-line n-input (row 19)', () => {
        const converted: Record<string, string[]> = {
            './TaskHeader.vue': ['reviewNote', 'decisionOf(f.id).reason', 'liftNote', 'reviewNote'],
            './TaskActions.vue': ['deliveryDraft.note', 'holdReason', 'unassignReason', 'cancelNote', 'reopenReason',
                "blockerWords[b.reviewItem.id ?? '']"],
            './TaskReviewItems.vue': ['decisionWords'],
            './TaskPullRequests.vue': ['unlinks[c.url].note', 'supersedes[c.url].note', 'drafts[c.url].note'],
            '../AiAgentSessionView.vue': ['closeReasonInput'],
        }
        let sites = 0
        for (const [file, targets] of Object.entries(converted)) {
            const tags = [...source(file).matchAll(/<(n-input|growing-text-input)\s[^<]*?v-model:value="([^"]*)"/g)]
                .map(m => ({ tag: m[1], target: m[2] }))
            for (const t of new Set(targets)) {
                expect(tags.filter(x => x.tag === 'n-input' && x.target === t), `${file} ${t}`).toEqual([])
                expect(tags.filter(x => x.tag === 'growing-text-input' && x.target === t).length, `${file} ${t}`)
                    .toBe(targets.filter(x => x === t).length)
            }
            sites += tags.filter(x => x.tag === 'growing-text-input').length
        }
        expect(sites).toBe(15)
    })
})
