// @vitest-environment happy-dom
//
// Questions readable by everyone, answers kept on the task page (RD2-7, sweep UI-04, UI-05): the open
// question shows to a reader while it waits on a person -- asked by whom, about what, waiting on whom,
// since when -- with the answer form only for who may answer; and once answered, the question and its
// answer stay listed, with what answered it and when.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskOpenQuestions from './TaskOpenQuestions.vue'
import TaskQuestions from './TaskQuestions.vue'
import TaskAnsweredQuestions from './TaskAnsweredQuestions.vue'
import { fixtureFinding, fixtureRoles, questionsRound, questionsTask, richDocuments } from './taskFixtures'
import { answeredQuestions, questionRounds } from '@/utils/agentQuestionRounds'

vi.mock('vuex', () => ({ useStore: () => ({ dispatch: vi.fn(), getters: {} }) }))

const stubs = { RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }

/** Held for a person's answer: the question frame with nobody to answer it, the round open. */
function waitingOnAPerson () {
    return questionsTask({ status: 'ON_HOLD',
        questionStack: [{ askingRole: 'rc-coder', questionsRelease: 'q-rel', answeringRole: null, askedAt: '2026-09-21T10:00:00Z' }] })
}

describe('an open question, for a reader and for who answers it', () => {
    it('a reader sees the question, who asked it, what it waits on and since when, and no form', () => {
        const w = mount(TaskOpenQuestions, { props: { task: waitingOnAPerson(), roles: fixtureRoles } })
        expect(w.find('[data-testid="open-questions"]').exists()).toBe(true, 'shown while a person could answer, too')
        expect(w.find('.dsec__h').text()).toBe('Questions from coder · round 1 · about architecture round 1 · open (1)')
        expect(w.find('.oq__sub').text()).toBe('with the coordinator to name a role')
        expect(w.find('[data-testid="open-questions-since"]').text()).toMatch(/^since /)
        expect(w.text()).toContain('Which branch does the page link to?')
        expect(w.find('textarea').exists()).toBe(false)
    })

    it('who may answer gets the form beside it', () => {
        const w = mount(TaskQuestions, { props: { task: waitingOnAPerson(), roles: fixtureRoles }, global: { stubs } })
        expect(w.find('textarea').exists()).toBe(true)
        expect(w.text()).toContain('Answer')
    })
})

/** Asked in round 1; a person answered Q-1 in round 2, withdrew Q-2; architecture round 2 answered Q-3. */
function answeredTask () {
    const a2 = { ...richDocuments().find(d => d.uuid === 'a1')!, uuid: 'a2', createdDate: '2026-09-25T10:00:00Z' } as any
    a2.document = { ...a2.document, round: 2, path: 'design/t1/architecture-2.md' }
    const asked = questionsRound('q-rel', 1, [fixtureFinding('Q-1', 2, 'OPEN', 'which branch?'),
        fixtureFinding('Q-2', 2, 'OPEN', 'which port?'), fixtureFinding('Q-3', 2, 'OPEN', 'which schema?')])
    const answered = questionsRound('q-2', 2, [
        fixtureFinding('Q-1', 2, 'RESOLVED', 'which branch?', { resolvedBy: 'q-2', resolution: 'main' }),
        fixtureFinding('Q-2', 2, 'WITHDRAWN', 'which port?', { resolution: 'no port: it is a CLI' }),
        fixtureFinding('Q-3', 2, 'RESOLVED', 'which schema?', { resolvedBy: 'a2', resolution: 'the v2 one, see §3' })])
    return questionsTask({ status: 'ASSIGNED', questionStack: [], openQuestions: [],
        signOffs: [{ role: 'coder', roleUuid: 'rc-coder', outputs: ['q-rel'], outcome: 'REJECTED' }],
        documents: [answered, a2, asked, ...richDocuments()] })
}

describe('answered questions', () => {
    it('lists each question once, under the round that asked it, with the answer, what answered it and when', () => {
        const qs = answeredQuestions(answeredTask(), fixtureRoles)
        expect(qs.map(q => q.id)).toEqual(['Q-1', 'Q-2', 'Q-3'])
        expect(qs.every(q => q.askedIn.release === 'q-rel')).toBe(true)
        expect(qs[0]).toMatchObject({ answer: 'main', withdrawn: false, answeredBy: { person: true, round: 2 } })
        expect(qs[1]).toMatchObject({ answer: 'no port: it is a CLI', withdrawn: true, answeredBy: null })
        expect(qs[2]).toMatchObject({ answer: 'the v2 one, see §3', answeredBy: { specification: 'ARCHITECTURE', round: 2, release: 'a2' },
            answeredAt: '2026-09-25T10:00:00Z' })

        const w = mount(TaskAnsweredQuestions, { props: { task: answeredTask(), roles: fixtureRoles } })
        expect(w.find('.dsec__h').text()).toBe('Answered questions (3)')
        const q1 = w.find('[data-id="Q-1"]')
        expect(q1.text()).toContain('which branch?')
        expect(q1.find('.aq__a').text()).toBe('main')
        expect(q1.find('.aq__meta').text()).toContain('asked by coder · questions round 1 · about architecture round 1')
        expect(q1.find('.aq__meta').text()).toContain('answered by a person in questions round 2')
        const q2 = w.find('[data-id="Q-2"]')
        expect(q2.find('[data-testid="answered-withdrawn"]').exists()).toBe(true)
        expect(q2.find('.aq__meta').text()).toContain('does not apply')
        expect(w.find('[data-id="Q-3"] .aq__meta').text()).toContain('answered by architecture round 2')
    })

    it('shows nothing while every question is still open', () => {
        const w = mount(TaskAnsweredQuestions, { props: { task: waitingOnAPerson(), roles: fixtureRoles } })
        expect(w.find('[data-testid="answered-questions"]').exists()).toBe(false)
    })

    it('shows five and folds the rest behind show all', async () => {
        const items = Array.from({ length: 7 }, (_, i) => fixtureFinding(`Q-${i + 1}`, 2, 'OPEN', `question ${i + 1}`))
        const asked = questionsRound('q-rel', 1, items)
        const closed = questionsRound('q-2', 2, items.map((f: any) => ({ ...f, status: 'RESOLVED', resolvedBy: 'q-2', resolution: 'yes' })))
        const task = questionsTask({ status: 'ASSIGNED', questionStack: [], openQuestions: [], documents: [closed, asked, ...richDocuments()] })
        const w = mount(TaskAnsweredQuestions, { props: { task, roles: fixtureRoles } })
        expect(w.findAll('.aq')).toHaveLength(5)
        await w.find('[data-testid="answered-toggle"]').trigger('click')
        expect(w.findAll('.aq')).toHaveLength(7)
        expect(w.find('[data-testid="answered-toggle"]').text()).toBe('show fewer')
    })
})

// Tester run 1 T-2: q1 asked, a person answered it, then q1 asked again. Items were matched by id across the
// task, so round 1 read as the re-ask (open) and the answer round was skipped as seen: the list was empty.
describe('a question asked again after its answer', () => {
    function reasked () {
        const asked = questionsRound('q-rel', 1, [fixtureFinding('q1', 2, 'OPEN', 'which branch?')])
        const answered = questionsRound('q-2', 2, [
            fixtureFinding('q1', 2, 'RESOLVED', 'which branch?', { resolvedBy: 'q-2', resolution: 'main' })])
        const again = questionsRound('q-3', 3, [fixtureFinding('q1', 2, 'OPEN', 'which branch, for the docs?')])
        ;(answered as any).createdDate = '2026-09-26T10:00:00Z'
        return questionsTask({ status: 'ON_HOLD', openQuestions: [fixtureFinding('q1', 2, 'OPEN', 'which branch, for the docs?')],
            questionStack: [{ askingRole: 'rc-coder', questionsRelease: 'q-3', answeringRole: null, askedAt: '2026-09-27T10:00:00Z' }],
            signOffs: [{ role: 'coder', roleUuid: 'rc-coder', outputs: ['q-rel'], outcome: 'REJECTED' },
                { role: 'coder', roleUuid: 'rc-coder', outputs: ['q-3'], outcome: 'REJECTED' }],
            documents: [again, answered, asked, ...richDocuments()] })
    }

    it('keeps the first answer under the round that asked it, and the re-ask open', () => {
        const qs = answeredQuestions(reasked(), fixtureRoles)
        expect(qs.map(q => [q.id, q.askedIn.round, q.answer])).toEqual([['q1', 1, 'main']])
        expect(qs[0].answeredBy).toEqual({ person: true, round: 2 })
        expect(qs[0].answeredAt).toBe('2026-09-26T10:00:00Z')
        const w = mount(TaskAnsweredQuestions, { props: { task: reasked(), roles: fixtureRoles } })
        expect(w.text()).toContain('which branch?')
        expect(w.text()).toContain('main')
    })

    it('lists the question twice once the re-ask is answered too, each under the round that asked it', () => {
        const task = reasked()
        const fourth = questionsRound('q-4', 4, [fixtureFinding('q1', 2, 'RESOLVED', 'which branch, for the docs?',
            { resolvedBy: 'q-4', resolution: 'docs-main' })])
        task.documents = [fourth, ...task.documents]
        const qs = answeredQuestions(task, fixtureRoles)
        expect(qs.map(q => [q.id, q.askedIn.round, q.answer])).toEqual([['q1', 3, 'docs-main'], ['q1', 1, 'main']])
    })

    it('reads round 1 as answered and the re-asking round as open', () => {
        const rounds = questionRounds(reasked(), fixtureRoles)
        expect(rounds.map(r => [r.round, r.state])).toEqual([[3, 'open'], [2, 'answered'], [1, 'answered']])
        expect(rounds[2].answeredBy).toEqual([{ person: true, round: 2 }])
        const w = mount(TaskOpenQuestions, { props: { task: reasked(), roles: fixtureRoles } })
        expect(w.find('.dsec__h').text()).toContain('round 3')
        expect(w.text()).toContain('which branch, for the docs?')
    })
})

