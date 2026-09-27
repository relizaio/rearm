// @vitest-environment happy-dom
//
// The human-review box on a rejected gate (task RD2-25): Reject (send back) leads, and Approve waits
// until every blocking item has a decision, then confirms what it approves past.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const confirm = { emits: ['positive-click'], props: ['disabled'],
    template: '<div class="pc"><slot name="trigger"/><span class="pc__body"><slot/></span><button class="pc__yes" @click="$emit(\'positive-click\')"/></div>' }
const stubs = { NPopconfirm: confirm, Popconfirm: confirm }

function header (outcome: string, findings: any[], board: any = { blockingPriority: null }) {
    const task = richTask({
        status: 'ON_HOLD',
        hold: { level: 'OPERATOR', kind: 'HUMAN_GATE', gateRole: 'reviewer', reason: 'gate', heldAt: '2026-09-27T10:00:00Z' },
        signOffs: [{ role: 'reviewer', outcome, outputs: ['r2'], signedOffAt: '2026-09-27T09:00:00Z' }],
        documents: [{ uuid: 'r2', document: { specification: 'REVIEW_FINDINGS', findings: { findings } } }],
    })
    return mount(TaskHeader, { props: { task, roles: fixtureRoles, canOperate: true, board }, global: { stubs } })
}
const f1 = { id: 'F-1', priority: 1, status: 'OPEN', title: 'the cycle is not refused' }

describe('the rejected gate', () => {
    it('leads with Reject (send back) and holds Approve until F-1 is decided', async () => {
        const w = header('REJECTED', [f1])
        const buttons = w.findAll('button').filter(b => /Reject|Approve/.test(b.text()))
        expect(buttons.map(b => b.text())).toEqual(['Reject (send back)', 'Approve past the findings'])
        expect(w.find('.gate-approve').attributes('disabled')).toBeDefined()
        expect(w.find('[data-finding="F-1"]').text()).toContain('F-1 (P1)')

        await w.find('.gatefind__accept input').setValue(true)
        await w.find('.gatefind__reason input').setValue('we ship with it')
        expect(w.find('.gate-approve').attributes('disabled')).toBeUndefined()
        expect(w.find('.gate-confirm').text()).toBe('Approving past F-1 (P1): accepted')

        await w.find('.pc__yes').trigger('click')
        expect(w.emitted('human-review')?.[0]?.[0]).toMatchObject({ approve: true,
            findings: [{ action: 'ACCEPT', findingId: 'F-1', resolution: 'we ship with it' }] })
    })

    it('sends it back with no decisions', async () => {
        const w = header('REJECTED', [f1])
        await w.find('.gate-reject').trigger('click')
        const p: any = w.emitted('human-review')?.[0]?.[0]
        expect(p.approve).toBe(false)
        expect(p.findings).toBeUndefined()
    })

    it('approves bare when nothing blocks: below the line on a lax board, or no findings', async () => {
        const lax = header('REJECTED', [{ ...f1, priority: 3 }], { blockingPriority: 1 })
        expect(lax.find('[data-testid="gate-findings"]').exists()).toBe(false)
        expect(lax.find('.gate-approve').text()).toBe('Approve anyway')
        await lax.find('.gate-approve').trigger('click')
        expect(lax.emitted('human-review')?.[0]?.[0]).toMatchObject({ approve: true })
        expect(header('REJECTED', []).find('.gate-approve').text()).toBe('Approve anyway')
    })

    it('a passed gate reads as before', () => {
        const w = header('PASSED', [f1])
        const buttons = w.findAll('button').filter(b => /Reject|Approve/.test(b.text()))
        expect(buttons.map(b => b.text())).toEqual(['Approve reviewer pass', 'Reject'])
        expect(w.find('[data-testid="gate-findings"]').exists()).toBe(false)
    })
})
