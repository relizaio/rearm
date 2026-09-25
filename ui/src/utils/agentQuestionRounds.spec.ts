import { describe, expect, it } from 'vitest'
import { aboutLabel, answeredByLabel, latestQuestionRound, questionRoundLabel, questionRoundOf, questionRounds,
    questionStateLabel, questionStateType } from './agentQuestionRounds'

const roles = [{ uuid: 'rc-coder', name: 'coder' }, { uuid: 'rc-arch', name: 'architect' }]

function item (id: string, status: string, extra: Record<string, any> = {}) {
    return { id, priority: 2, status, title: `question ${id}`, resolvedBy: null, resolution: null, ...extra }
}

function doc (uuid: string, spec: string, round: number, items: any[] | null = null, about: any = null) {
    return { uuid, lifecycle: 'DRAFT', document: { specification: spec, round,
        findings: items ? { kind: spec, round, verdict: 'REJECTED', findings: items, about } : null } }
}

const ABOUT_A1 = { specification: 'ARCHITECTURE', release: 'a1' }
const a1 = doc('a1', 'ARCHITECTURE', 1)
const a2 = doc('a2', 'ARCHITECTURE', 2)

describe('question rounds', () => {
    it('takes the asker and the answering role from the frame while it is open', () => {
        const task = { documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN'), item('Q-2', 'OPEN')], ABOUT_A1), a1],
            questionStack: [{ askingRole: 'rc-coder', questionsRelease: 'q1', answeringRole: 'rc-arch' }], signOffs: [] }
        const r = latestQuestionRound(task, roles)!
        expect(r.askedBy?.roleName).toBe('coder')
        expect(r.waitingOn?.roleName).toBe('architect')
        expect(r.withCoordinator).toBe(false)
        expect(questionRoundLabel(r)).toBe('Questions from coder · round 1 · about ARCHITECTURE round 1')
        expect(questionStateLabel(r)).toBe('open (2)')
        expect(questionStateType(r)).toBe('warning')
    })

    it('is with the coordinator when the open frame names nobody to answer', () => {
        const task = { documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')], ABOUT_A1), a1],
            questionStack: [{ askingRole: 'rc-coder', questionsRelease: 'q1', answeringRole: null }] }
        const r = latestQuestionRound(task, roles)!
        expect(r.waitingOn).toBeNull()
        expect(r.withCoordinator).toBe(true)
    })

    it('falls back to the sign-off that published the round, then to the round before, then to "a role"', () => {
        const signOffs = [{ role: 'coder', roleUuid: 'rc-coder', outputs: ['q1'] }]
        const q1 = doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')], ABOUT_A1)
        // A board-cut round: the same items, no frame, no sign-off.
        const q2 = doc('q2', 'QUESTIONS', 2, [item('Q-1', 'RESOLVED', { resolvedBy: 'a2' })], ABOUT_A1)
        const [second, first] = questionRounds({ documents: [q2, a2, q1, a1], questionStack: [], signOffs }, roles)
        expect(first.askedBy?.roleName).toBe('coder')
        expect(second.askedBy?.roleName).toBe('coder')
        expect(questionRoundLabel(second)).toBe('Questions from coder · round 2 · about ARCHITECTURE round 1')

        const nobody = latestQuestionRound({ documents: [q1, a1], questionStack: [], signOffs: [] }, roles)!
        expect(nobody.askedBy).toBeNull()
        expect(questionRoundLabel(nobody)).toBe('Questions from a role · round 1 · about ARCHITECTURE round 1')
    })

    it('names a role no longer on the board by what its sign-off recorded', () => {
        const task = { documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')])],
            signOffs: [{ role: 'old coder', roleUuid: 'rc-gone', outputs: ['q1'] }] }
        expect(latestQuestionRound(task, roles)!.askedBy?.roleName).toBe('old coder')
        const legacy = { documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')])],
            signOffs: [{ role: 'coder', roleUuid: null, outputs: ['q1'] }] }
        expect(latestQuestionRound(legacy, roles)!.askedBy).toEqual({ roleUuid: null, roleName: 'coder' })
    })

    it('says what a round is about, with the round when the task has that document', () => {
        const unknown = latestQuestionRound({ documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')],
            { specification: 'ARCHITECTURE', release: 'elsewhere' })] }, roles)!
        expect(aboutLabel(unknown)).toBe('about ARCHITECTURE')
        const none = latestQuestionRound({ documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')])] }, roles)!
        expect(aboutLabel(none)).toBe('')
        expect(questionRoundLabel(none)).toBe('Questions from a role · round 1')
    })

    it('is open, answered or withdrawn, and a mix of answered and withdrawn is answered', () => {
        const state = (items: any[]) => latestQuestionRound({ documents: [doc('q', 'QUESTIONS', 1, items)] }, roles)!
        expect(state([item('Q-1', 'OPEN'), item('Q-2', 'RESOLVED', { resolution: 'x' })]).state).toBe('open')
        expect(state([item('Q-1', 'RESOLVED', { resolution: 'x' })]).state).toBe('answered')
        const w = state([item('Q-1', 'WITHDRAWN', { resolution: 'n/a' })])
        expect([w.state, questionStateLabel(w), questionStateType(w)]).toEqual(['withdrawn', 'withdrawn', 'default'])
        const mixed = state([item('Q-1', 'RESOLVED', { resolution: 'x' }), item('Q-2', 'WITHDRAWN', { resolution: 'n/a' })])
        expect(mixed.state).toBe('answered')
        expect(mixed.counts).toEqual({ open: 0, answered: 1, withdrawn: 1 })
        expect(questionStateType(mixed)).toBe('success')
    })

    it('reads the asking round as a later round left its items, and names what answered them once', () => {
        const q1 = doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN'), item('Q-2', 'OPEN')], ABOUT_A1)
        const q2 = doc('q2', 'QUESTIONS', 2, [item('Q-1', 'RESOLVED', { resolvedBy: 'a2' }),
            item('Q-2', 'WITHDRAWN', { resolvedBy: 'a2' })], ABOUT_A1)
        const [second, first] = questionRounds({ documents: [q2, a2, q1, a1] }, roles)
        for (const r of [first, second]) {
            expect(r.state).toBe('answered')
            expect(r.answeredBy).toEqual([{ specification: 'ARCHITECTURE', round: 2, release: 'a2' }])
            expect(answeredByLabel(r.answeredBy[0])).toBe('answered by ARCHITECTURE round 2')
        }
        expect(first.waitingOn).toBeNull()
    })

    it('says a person answered, in their answer round, as the server stamps it', () => {
        // A person's answer is a QUESTIONS round whose closed items point at that round itself
        // (stampSelfPointer), not at a document that answered them.
        const q1 = doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN'), item('Q-2', 'OPEN')], ABOUT_A1)
        const q3 = doc('q3', 'QUESTIONS', 3, [item('Q-1', 'RESOLVED', { resolution: 'use main', resolvedBy: 'q3' }),
            item('Q-2', 'RESOLVED', { resolution: 'yes', resolvedBy: 'q3' })], ABOUT_A1)
        const [answer, first] = questionRounds({ documents: [q3, q1, a1] }, roles)
        for (const r of [first, answer]) {
            expect(r.answeredBy).toEqual([{ person: true, round: 3 }])
            expect(answeredByLabel(r.answeredBy[0])).toBe('answered by a person in questions round 3')
        }
    })

    it('reads an older row with no resolvedBy as a person, in the round that closed it', () => {
        const q1 = doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')], ABOUT_A1)
        const q3 = doc('q3', 'QUESTIONS', 3, [item('Q-1', 'RESOLVED', { resolution: 'use main' })], ABOUT_A1)
        const [, first] = questionRounds({ documents: [q3, q1, a1] }, roles)
        expect(first.answeredBy).toEqual([{ person: true, round: 3 }])
        expect(answeredByLabel({ person: true, round: null })).toBe('answered by a person')
        expect(answeredByLabel({ specification: null, round: null, release: 'x' })).toBe('answered by a document')
    })

    it('names nothing for withdrawn questions: a withdrawn round, and the withdrawn part of a mix', () => {
        const withdrawn = latestQuestionRound({ documents: [doc('q2', 'QUESTIONS', 2,
            [item('Q-1', 'WITHDRAWN', { resolution: 'not needed', resolvedBy: 'q2' })], ABOUT_A1), a1] }, roles)!
        expect(withdrawn.state).toBe('withdrawn')
        expect(withdrawn.answeredBy).toEqual([])
        const mixed = latestQuestionRound({ documents: [doc('q2', 'QUESTIONS', 2,
            [item('Q-1', 'RESOLVED', { resolvedBy: 'a2' }), item('Q-2', 'WITHDRAWN', { resolvedBy: 'q2', resolution: 'n/a' })],
            ABOUT_A1), a2, a1] }, roles)!
        expect(mixed.state).toBe('answered')
        expect(mixed.answeredBy).toEqual([{ specification: 'ARCHITECTURE', round: 2, release: 'a2' }])
    })

    it('finds the round a frame points at, and nothing for none', () => {
        const task = { documents: [doc('q1', 'QUESTIONS', 1, [item('Q-1', 'OPEN')], ABOUT_A1), a1] }
        expect(questionRoundOf(task, roles, 'q1')?.round).toBe(1)
        expect(questionRoundOf(task, roles, null)).toBeNull()
        expect(questionRounds({ documents: [a1] }, roles)).toEqual([])
        expect(latestQuestionRound(null, roles)).toBeNull()
    })
})
