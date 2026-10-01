// @vitest-environment happy-dom
//
// The human-review box on a rejected gate (task RD2-25): Reject (send back) leads, and Accept waits
// until every blocking item has a decision, then confirms what it accepts past.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const confirm = { emits: ['positive-click'], props: ['disabled'],
    template: '<div class="pc"><slot name="trigger"/><span class="pc__body"><slot/></span><button class="pc__yes" @click="$emit(\'positive-click\')"/></div>' }
const stubs = { NPopconfirm: confirm, Popconfirm: confirm }

function header (outcome: string, reviewItems: any[], board: any = { blockingPriority: null }) {
    const task = richTask({
        status: 'ON_HOLD',
        hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'gate', heldAt: '2026-09-27T10:00:00Z' },
        signOffs: [{ role: 'reviewer', outcome, outputs: ['r2'], signedOffAt: '2026-09-27T09:00:00Z' }],
        documents: [{ uuid: 'r2', document: { specification: 'BOARD_REVIEW_ITEMS', reviewItems: { reviewItems } } }],
    })
    return mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: true, board }, global: { stubs } })
}
const f1 = { id: 'F-1', priority: 1, status: 'OPEN', title: 'the cycle is not refused' }

describe('the rejected gate', () => {
    it('leads with Reject (send back) and holds Accept until F-1 is decided', async () => {
        const w = header('REJECTED', [f1])
        const buttons = w.findAll('button').filter(b => /Reject|Accept/.test(b.text()))
        expect(buttons.map(b => b.text())).toEqual(['Reject (send back)', 'Accept past the review items'])
        expect(w.find('.gate-accept').attributes('disabled')).toBeDefined()
        expect(w.find('[data-review-item="F-1"]').text()).toContain('F-1 (P1)')

        await w.find('.gateitem__accept input').setValue(true)
        await w.find('.gateitem__reason input').setValue('we ship with it')
        expect(w.find('.gate-accept').attributes('disabled')).toBeUndefined()
        expect(w.find('.gate-confirm').text()).toBe('Accepting past F-1 (P1): accepted')

        await w.find('.pc__yes').trigger('click')
        expect(w.emitted('human-review')?.[0]?.[0]).toMatchObject({ accept: true,
            reviewItems: [{ action: 'ACCEPT', reviewItemId: 'F-1', resolution: 'we ship with it' }] })
    })

    it('sends it back with no decisions', async () => {
        const w = header('REJECTED', [f1])
        await w.find('.gate-reject').trigger('click')
        const p: any = w.emitted('human-review')?.[0]?.[0]
        expect(p.accept).toBe(false)
        expect(p.reviewItems).toBeUndefined()
    })

    it('accepts bare when nothing blocks: below the line on a lax board, or no review items', async () => {
        const lax = header('REJECTED', [{ ...f1, priority: 3 }], { blockingPriority: 1 })
        expect(lax.find('[data-testid="gate-review-items"]').exists()).toBe(false)
        expect(lax.find('.gate-accept').text()).toBe('Accept anyway')
        await lax.find('.gate-accept').trigger('click')
        expect(lax.emitted('human-review')?.[0]?.[0]).toMatchObject({ accept: true })
        expect(header('REJECTED', []).find('.gate-accept').text()).toBe('Accept anyway')
    })

    it('a passed gate reads as before', () => {
        const w = header('PASSED', [f1])
        const buttons = w.findAll('button').filter(b => /Reject|Accept/.test(b.text()))
        expect(buttons.map(b => b.text())).toEqual(['Accept reviewer pass', 'Reject'])
        expect(w.find('[data-testid="gate-review-items"]').exists()).toBe(false)
    })
})
