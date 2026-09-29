// @vitest-environment happy-dom
//
// A hop parked for the operator (task RD4-5): the banner says whose question it is and releases with the
// answer, which it requires, to no role; the task page shows the question and, once released, the answer.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import TaskOperatorQuestions from './TaskOperatorQuestions.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const holder = { kind: 'SESSION', uuid: 's-arch', name: null }
const pat = { kind: 'USER', uuid: 'u1', name: 'Pat Operator' }
const asked = { from: 'ASSIGNED', to: 'ON_HOLD', at: '2026-09-29T10:00:00Z', trigger: 'HOLD', actor: holder,
    note: 'awaiting the operator: per-org or per-board?' }
const answered = { from: 'ON_HOLD', to: 'ASSIGNED', at: '2026-09-29T11:00:00Z', trigger: 'RELEASE_HOLD', actor: pat,
    note: 'released by Pat Operator: per-org, rotated yearly' }

function parked () {
    return richTask({ status: 'ON_HOLD', role: 'architect', questionStack: [],
        assignment: { session: 's-arch', agent: 'a1', role: 'architect', assignedAt: '2026-09-29T09:00:00Z' },
        hold: { level: 'OPERATOR', kind: 'MANUAL', gateRole: null, reason: 'awaiting the operator: per-org or per-board?',
            heldBy: holder, heldAt: '2026-09-29T10:00:00Z' },
        statusHistory: [{ from: 'QUEUED', to: 'ASSIGNED', at: '2026-09-29T09:00:00Z', trigger: 'ASSIGN', actor: holder, note: null }, asked] })
}

describe('a parked hop on the task page', () => {
    it('titles the banner, shows the question, and names no role on release', () => {
        const w = mount(TaskHeader, { props: { task: parked(), roles: fixtureRoles, canOperate: true } })
        expect(w.text()).toContain('Awaiting the operator')
        expect(w.text()).toContain('awaiting the operator: per-org or per-board?')
        expect(w.find('[data-testid="parked-hop"]').text()).toContain('The architect hop working this task asks the operator')
        expect(w.find('.relrole').exists(), 'the hop resumes; nothing routes').toBe(false)
        expect(w.find('.relbtn').text()).toBe('Answer and release')
    })

    it('releases only with the answer, and sends it as the note', async () => {
        const w = mount(TaskHeader, { props: { task: parked(), roles: fixtureRoles, canOperate: true } })
        expect(w.find('.relbtn').attributes('disabled')).toBeDefined()
        ;(w.vm as any).releaseNote = 'per-org, rotated yearly'
        await w.vm.$nextTick()
        expect(w.find('.relbtn').attributes('disabled')).toBeUndefined()
        await w.find('.relbtn').trigger('click')
        const sent = w.emitted('operator-release')?.[0]?.[0] as any
        expect(sent).toMatchObject({ note: 'per-org, rotated yearly' })
        expect(sent).not.toHaveProperty('role')
    })

    it('keeps a person\'s own operator hold as it was', () => {
        const t = parked()
        t.hold = { ...t.hold, heldBy: pat, reason: 'waiting on legal' }
        const w = mount(TaskHeader, { props: { task: t, roles: fixtureRoles, canOperate: true } })
        expect(w.find('.relrole').exists()).toBe(true)
        expect(w.find('.relbtn').text()).toBe('Operator release')
        expect(w.find('.relbtn').attributes('disabled')).toBeUndefined()
    })

    it('shows the question while it waits, and the answer after the release', () => {
        const waiting = mount(TaskOperatorQuestions, { props: { task: parked() } })
        expect(waiting.find('[data-testid="operator-question"]').text()).toBe('per-org or per-board?')
        expect(waiting.find('[data-testid="operator-waiting"]').exists()).toBe(true)

        const t = parked()
        t.status = 'ASSIGNED'
        t.hold = null
        t.statusHistory = [...t.statusHistory, answered]
        const done = mount(TaskOperatorQuestions, { props: { task: t } })
        expect(done.find('[data-testid="operator-question"]').text()).toBe('per-org or per-board?')
        expect(done.find('[data-testid="operator-answer"]').text()).toBe('per-org, rotated yearly')
        expect(done.find('.oq__meta').text()).toContain('answered by')
    })

    it('shows nothing on a task no hop parked', () => {
        expect(mount(TaskOperatorQuestions, { props: { task: richTask({}) } }).find('[data-testid="operator-questions"]')
            .exists()).toBe(false)
    })
})
