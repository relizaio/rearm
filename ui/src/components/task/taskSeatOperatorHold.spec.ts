// @vitest-environment happy-dom
//
// A task the coordinator seat parked for an operator decision (task RD4-17): the banner titles it Awaiting the
// operator, says the coordinator asks and where the answer returns the task, and releases only with the answer,
// to no role; the action sections say that acting answers it, and a parked delivery keeps its attest controls.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import TaskOperatorQuestions from './TaskOperatorQuestions.vue'
import TaskPullRequests from './TaskPullRequests.vue'
import TaskActions from './TaskActions.vue'
import TaskQuestions from './TaskQuestions.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const seat = { kind: 'SESSION', uuid: 's-seat', name: null }
const QUESTION = 'CI is red on #401. Options: re-run, reopen to the coder. Recommend: re-run.'
const pr = 'https://github.com/acme/app/pull/401'

function parked () {
    return richTask({ status: 'ON_HOLD', role: 'coder', assignment: null, questionStack: [], prUrls: [pr],
        pullRequests: [{ url: pr, state: null, registered: false }],
        hold: { level: 'OPERATOR', kind: 'MANUAL', gateRole: null, reason: `awaiting the operator: ${QUESTION}`,
            heldBy: seat, heldAt: '2026-09-30T10:00:00Z', returnTo: 'DELIVERING' },
        statusHistory: [{ from: 'ASSIGNED', to: 'DELIVERING', at: '2026-09-30T09:00:00Z', trigger: 'DELIVER_WAIT', actor: seat, note: null },
            { from: 'DELIVERING', to: 'ON_HOLD', at: '2026-09-30T10:00:00Z', trigger: 'HOLD', actor: seat,
                note: `awaiting the operator: ${QUESTION}` }] })
}

describe('a task the seat parked on the task page', () => {
    it('titles the banner, shows the question and who asked, and names no role on release', () => {
        const w = mount(TaskHeader, { props: { task: parked(), roles: fixtureRoles, canOperate: true } })
        expect(w.text()).toContain('Awaiting the operator')
        expect(w.text()).toContain(`awaiting the operator: ${QUESTION}`)
        expect(w.find('.holdmeta').text()).toContain('held by')
        const note = w.find('[data-testid="seat-parked"]').text()
        expect(note).toContain('The coordinator asks the operator')
        expect(note).toContain('the task returns to delivering')
        expect(note).toContain('Anything else you do on it (answering its questions, attesting, superseding, completing,'
            + ' reopening, cancelling, a new order or level) answers it too')
        expect(w.find('[data-testid="parked-hop"]').exists(), 'not a hop\'s question').toBe(false)
        expect(w.find('.relrole').exists(), 'the answer returns the task; nothing routes').toBe(false)
        expect(w.find('.relbtn').text()).toBe('Answer and release')
    })

    it('releases only with the answer, and sends it as the note', async () => {
        const w = mount(TaskHeader, { props: { task: parked(), roles: fixtureRoles, canOperate: true } })
        expect(w.find('.relbtn').attributes('disabled')).toBeDefined()
        ;(w.vm as any).releaseNote = 're-run it once'
        await w.vm.$nextTick()
        expect(w.find('.relbtn').attributes('disabled')).toBeUndefined()
        await w.find('.relbtn').trigger('click')
        const sent = w.emitted('operator-release')?.[0]?.[0] as any
        expect(sent).toMatchObject({ note: 're-run it once' })
        expect(sent).not.toHaveProperty('role')
    })

    it('lists the question as the coordinator\'s while it waits', () => {
        const w = mount(TaskOperatorQuestions, { props: { task: parked() } })
        expect(w.find('[data-testid="operator-question"]').text()).toBe(QUESTION)
        expect(w.find('[data-testid="operator-waiting"]').exists()).toBe(true)
        expect(w.find('.oq__meta').text()).toContain('asked by the coordinator')
    })

    it('keeps the attest controls of the delivery it parked, and says acting answers it', () => {
        const w = mount(TaskPullRequests, { props: { task: parked(), canOperate: true } })
        expect(w.find('[data-testid="attest-merge"]').exists(), 'a person may answer by attesting').toBe(true)
        expect(w.find('[data-testid="acting-answers"]').text()).toContain(`Awaiting the operator: ${QUESTION}`)
        const a = mount(TaskActions, { props: { task: parked(), roles: fixtureRoles, board: {}, canReopen: true, admin: true } })
        expect(a.find('[data-testid="acting-answers"]').text()).toContain('Acting here answers it')
        expect(a.find('[data-testid="reopen"]').exists(), 'a person may answer by reopening').toBe(true)
    })

    it('says beside the answer form that answering the questions answers the seat too', () => {
        // the tester's T-1 (run 1): the answer to the task's open questions is an action like any other
        const frame = { askingRole: null, answeringRole: null, questionsRelease: null, askedAt: '2026-09-30T09:30:00Z' }
        const t = parked()
        t.hold.returnTo = 'AWAITING_COORDINATOR'
        t.questionStack = [frame]
        t.openQuestions = [{ id: 'q1', title: 'Who writes the test plan?', status: 'OPEN', priority: 1 }]
        const w = mount(TaskQuestions, { props: { task: t, roles: fixtureRoles } })
        expect(w.find('[data-testid="acting-answers"]').text()).toContain('Acting here answers it')
        expect(w.find('[data-testid="acting-answers"]').text()).toContain('returns to awaiting coordinator')
        expect(w.find('.qans').text()).toContain('Answer and release')
        const plain = richTask({ status: 'AWAITING_COORDINATOR', hold: null, questionStack: [frame],
            openQuestions: [{ id: 'q1', title: 'Who writes the test plan?', status: 'OPEN', priority: 1 }] })
        const p = mount(TaskQuestions, { props: { task: plain, roles: fixtureRoles } })
        expect(p.find('.qans').exists()).toBe(true)
        expect(p.find('[data-testid="acting-answers"]').exists(), 'nobody parked it').toBe(false)
    })

    it('says nothing of the kind on a delivery nobody parked', () => {
        const t = richTask({ status: 'DELIVERING', hold: null, prUrls: [pr], pullRequests: [{ url: pr, state: null, registered: false }] })
        expect(mount(TaskPullRequests, { props: { task: t, canOperate: true } }).find('[data-testid="acting-answers"]').exists())
            .toBe(false)
    })
})
