// @vitest-environment happy-dom
//
// A PR linked to a task the coordinator seat parked for the operator (task RD4-19) is preparation for the decision,
// never its answer: the banner lists it under the question, and the "Asked of the operator" section beside the
// question while it waits and beside the answer once given. None on a parked task nothing was linked to.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskHeader from './TaskHeader.vue'
import TaskOperatorQuestions from './TaskOperatorQuestions.vue'
import { fixtureRoles, richTask } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const seat = { kind: 'SESSION', uuid: 's-seat', name: null }
const person = { kind: 'USER', uuid: 'u-1', name: 'Pat' }
const QUESTION = 'PR #401 is closed unmerged. Options: supersede it, reopen to the coder. Recommend: supersede.'
const old = 'https://github.com/acme/app/pull/401'
const replacement = 'https://github.com/acme/app/pull/402'
const link = { prUrl: replacement, by: 'Claude Code (agent 2ffebe1a)', at: '2026-10-01T10:05:00Z' }
const LINE = `since parked: PR ${replacement} linked by Claude Code (agent 2ffebe1a) at`

const asked = { from: 'DELIVERING', to: 'ON_HOLD', at: '2026-10-01T10:00:00Z', trigger: 'HOLD', actor: seat,
    note: `awaiting the operator: ${QUESTION}`, linked: null }

function parked (linked: any[] | null) {
    return richTask({ status: 'ON_HOLD', role: 'coder', assignment: null, questionStack: [], prUrls: [old, replacement],
        hold: { level: 'OPERATOR', kind: 'MANUAL', gateRole: null, reason: `awaiting the operator: ${QUESTION}`,
            heldBy: seat, heldAt: '2026-10-01T10:00:00Z', returnTo: 'DELIVERING', linked },
        statusHistory: [{ from: 'ASSIGNED', to: 'DELIVERING', at: '2026-10-01T09:00:00Z', trigger: 'DELIVER_WAIT', actor: seat,
            note: null, linked: null }, asked] })
}

function answered () {
    return richTask({ status: 'DELIVERING', role: 'coder', assignment: null, questionStack: [], prUrls: [old, replacement],
        hold: null,
        statusHistory: [asked, { from: 'ON_HOLD', to: 'DELIVERING', at: '2026-10-01T10:10:00Z', trigger: 'LIFT_HOLD',
            actor: person, note: `declared superseded by Pat: ${old}, replaced by ${replacement}: replaced`, linked: [link] }] })
}

describe('a PR linked to a task the seat parked', () => {
    it('is listed in the banner under the question, one line per link', () => {
        const w = mount(TaskHeader, { props: { task: parked([link]), roles: fixtureRoles, canOperate: true } })
        const lines = w.findAll('[data-testid="parked-link"]')
        expect(lines.length).toBe(1)
        expect(lines[0].text()).toContain(LINE)
        expect(w.text()).toContain(`awaiting the operator: ${QUESTION}`)
        expect(w.find('.holdmeta').text(), 'held by stays the first line under the question').toContain('held by')
        expect(w.find('.relbtn').text(), 'still a decision for a person').toBe('Answer and lift')
    })

    it('shows nothing in the banner when nothing was linked since parking', () => {
        for (const linked of [null, []]) {
            const w = mount(TaskHeader, { props: { task: parked(linked), roles: fixtureRoles, canOperate: true } })
            expect(w.find('[data-testid="parked-link"]').exists()).toBe(false)
        }
    })

    it('is listed beside the question while it waits, in the Asked of the operator section', () => {
        const w = mount(TaskOperatorQuestions, { props: { task: parked([link]) } })
        expect(w.find('[data-testid="operator-waiting"]').exists()).toBe(true)
        expect(w.find('[data-testid="operator-question-link"]').text()).toContain(LINE)
        const none = mount(TaskOperatorQuestions, { props: { task: parked(null) } })
        expect(none.find('[data-testid="operator-question-link"]').exists()).toBe(false)
    })

    it('stays beside the answer once given, read from the row that lifted the hold', () => {
        const w = mount(TaskOperatorQuestions, { props: { task: answered() } })
        expect(w.find('[data-testid="operator-answer"]').text())
            .toBe(`declared superseded by Pat: ${old}, replaced by ${replacement}: replaced`)
        const lines = w.findAll('[data-testid="operator-question-link"]')
        expect(lines.length).toBe(1)
        expect(lines[0].text()).toContain(LINE)
    })
})
