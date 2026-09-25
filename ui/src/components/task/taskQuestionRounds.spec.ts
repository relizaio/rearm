// @vitest-environment happy-dom
//
// Questions say who asked, in which round and about what, and a QUESTIONS round says open or
// answered rather than the asking hop's REJECTED (gaps §1.27).
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskDocuments from './TaskDocuments.vue'
import TaskOpenQuestions from './TaskOpenQuestions.vue'
import TaskQuestions from './TaskQuestions.vue'
import TaskSummary from './TaskSummary.vue'
import { fixtureFinding, fixtureRoles, questionsRound, questionsTask, richDocuments } from './taskFixtures'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const stubs = { RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }

/** The coder's two questions, answered by architecture round 2 in the board's unwind round. */
function answeredTask () {
    const a2 = { ...richDocuments().find(d => d.uuid === 'a1')!, uuid: 'a2' } as any
    a2.document = { ...a2.document, round: 2, path: 'design/t1/architecture-2.md' }
    const asked = questionsRound('q-rel', 1, [fixtureFinding('Q-1', 2, 'OPEN', 'which branch?'),
        fixtureFinding('Q-2', 2, 'OPEN', 'which port?')])
    const unwound = questionsRound('q-2', 2, [fixtureFinding('Q-1', 2, 'RESOLVED', 'which branch?', { resolvedBy: 'a2' }),
        fixtureFinding('Q-2', 2, 'RESOLVED', 'which port?', { resolvedBy: 'a2' })])
    return questionsTask({ status: 'ASSIGNED', questionStack: [], openQuestions: [],
        signOffs: [{ role: 'coder', roleUuid: 'rc-coder', outputs: ['q-rel'], outcome: 'REJECTED' }],
        documents: [unwound, a2, asked, ...richDocuments()] })
}

describe('question rounds on the task page', () => {
    it('titles the open questions with who asked, the round and what about, and says who it waits on', () => {
        // Answering happens elsewhere (ASSIGNED): this section shows the questions.
        const open = questionsTask({ status: 'ASSIGNED',
            questionStack: [{ askingRole: 'rc-coder', questionsRelease: 'q-rel', answeringRole: 'rc-arch', askedAt: null }] })
        const w = mount(TaskOpenQuestions, { props: { task: open, roles: fixtureRoles } })
        expect(w.find('.dsec__h').text()).toBe('Questions from coder · round 1 · about ARCHITECTURE round 1 · open (1)')
        expect(w.find('.oq__sub').text()).toBe('waiting on architect')

        const nobody = mount(TaskOpenQuestions, { props: { task: questionsTask({ status: 'ASSIGNED' }), roles: fixtureRoles } })
        expect(nobody.find('.oq__sub').text()).toBe('with the coordinator to name a role')
    })

    it('shows a QUESTIONS round as open or answered, never REJECTED, with what answered it', () => {
        const open = mount(TaskDocuments, { props: { task: questionsTask() }, global: { stubs } })
        const openRow = open.findAll('.drow').find(r => r.text().includes('questions-1.md'))!
        expect(openRow.find('.drow__qstate').text()).toBe('open (1)')
        expect(openRow.text()).not.toContain('REJECTED')

        const answered = mount(TaskDocuments, { props: { task: answeredTask() }, global: { stubs } })
        const rows = answered.findAll('.drow').filter(r => r.text().includes('questions-'))
        expect(rows).toHaveLength(2)
        for (const r of rows) {
            expect(r.find('.drow__qstate').text()).toBe('answered')
            expect(r.find('.drow__answered').text()).toBe('answered by ARCHITECTURE round 2')
            expect(r.text()).not.toContain('REJECTED')
        }
        // The other indexed types keep their verdict.
        expect(answered.text()).toContain('REJECTED')
    })

    it('says a person answered, and a withdrawn round names nothing', () => {
        const asked = questionsRound('q-rel', 1, [fixtureFinding('Q-1', 2, 'OPEN', 'which branch?'),
            fixtureFinding('Q-2', 2, 'OPEN', 'which port?')])
        // The person's answer round, as the server writes it: its items point at the round itself.
        const answer = questionsRound('q-2', 2, [
            fixtureFinding('Q-1', 2, 'RESOLVED', 'which branch?', { resolution: 'main', resolvedBy: 'q-2' }),
            fixtureFinding('Q-2', 2, 'WITHDRAWN', 'which port?', { resolution: 'not needed', resolvedBy: 'q-2' })])
        const task = questionsTask({ questionStack: [], openQuestions: [], documents: [answer, asked, ...richDocuments()] })
        const w = mount(TaskDocuments, { props: { task }, global: { stubs } })
        const rows = w.findAll('.drow').filter(r => r.text().includes('questions-'))
        for (const r of rows) {
            expect(r.find('.drow__qstate').text()).toBe('answered')
            expect(r.findAll('.drow__answered').map(a => a.text())).toEqual(['answered by a person in questions round 2'])
            expect(r.text()).not.toContain('answered by QUESTIONS')
        }

        const allWithdrawn = questionsRound('q-3', 3, [fixtureFinding('Q-9', 2, 'WITHDRAWN', 'moot', { resolution: 'moot', resolvedBy: 'q-3' })])
        const w2 = mount(TaskDocuments, { props: { task: questionsTask({ documents: [allWithdrawn, ...richDocuments()] }) },
            global: { stubs } })
        const row = w2.findAll('.drow').find(r => r.text().includes('questions-3.md'))!
        expect(row.find('.drow__qstate').text()).toBe('withdrawn')
        expect(row.find('.drow__answered').exists()).toBe(false)
    })

    it('says which round each waiting-on row is and what it is about', () => {
        const w = mount(TaskQuestions, { props: { task: questionsTask(), roles: fixtureRoles }, global: { stubs } })
        expect(w.find('.qstack__row').text()).toContain('coder asked nobody yet · questions round 1 · about ARCHITECTURE round 1')
    })

    it('puts the open questions on one line in the preview', () => {
        const w = mount(TaskSummary, { props: { task: questionsTask(), tasks: [], roles: fixtureRoles, agentNames: {} },
            global: { stubs } })
        expect(w.text()).toContain('1 open question from coder (round 1, about ARCHITECTURE round 1)')
    })
})
