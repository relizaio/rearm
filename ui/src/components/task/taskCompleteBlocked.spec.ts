// @vitest-environment happy-dom
//
// task RD3-16 run 1 T-2: the Complete button is disabled whenever its hint says why, a delivery that will not
// land included, so the tooltip never explains a disabled state the button is not in.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskActions from './TaskActions.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const prBoard = { deliveryPolicy: { mode: 'PULL_REQUEST' } }
const abandoned = { url: 'https://github.com/relizaio/rearm-saas/pull/692', state: 'OPEN',
    declaration: { outcome: 'ABANDONED', note: 'superseded by #694' } }
const merged = { url: 'https://github.com/relizaio/rearm-saas/pull/694', state: 'MERGED' }

function completeButton (task: any) {
    const w = mount(TaskActions, { props: { task, roles: fixtureRoles, board: prBoard, canReopen: true, admin: true } })
    return w.find('[data-testid="complete-open"]')
}

describe('the Complete button', () => {
    it('is disabled when the delivery will not land', () => {
        const b = completeButton(richTask({ status: 'AWAITING_COORDINATOR', hold: null, pullRequests: [abandoned, merged] }))
        expect(b.attributes('disabled')).toBeDefined()
    })

    it('is enabled for a completable task whose delivery can land', () => {
        const b = completeButton(richTask({ status: 'AWAITING_COORDINATOR', hold: null, pullRequests: [merged] }))
        expect(b.attributes('disabled')).toBeUndefined()
    })

    it('is disabled for a status complete does not take', () => {
        const b = completeButton(richTask({ status: 'ASSIGNED', hold: null, pullRequests: [merged] }))
        expect(b.attributes('disabled')).toBeDefined()
    })
})
