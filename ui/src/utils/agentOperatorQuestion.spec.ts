// A hop parked for the operator (task RD4-5): the page reads the question and the answer from the status
// history, and a release of such a hold needs the answer.
import { describe, expect, it } from 'vitest'
import { answerOf, operatorQuestions, parkedHop, questionOf, releaseNeedsAnswer } from './agentOperatorQuestion'

const holder = { kind: 'SESSION', uuid: 's-arch', name: null }
const pat = { kind: 'USER', uuid: 'u1', name: 'Pat Operator' }
const asked = { from: 'ASSIGNED', to: 'ON_HOLD', at: '2026-09-29T10:00:00Z', trigger: 'HOLD', actor: holder,
    note: 'awaiting the operator: per-org or per-board?' }
const answered = { from: 'ON_HOLD', to: 'ASSIGNED', at: '2026-09-29T11:00:00Z', trigger: 'RELEASE_HOLD', actor: pat,
    note: 'released by Pat Operator: per-org, rotated yearly' }
const parkedHold = { level: 'OPERATOR', kind: 'MANUAL', reason: 'awaiting the operator: per-org or per-board?', heldBy: holder }

function task (over: Record<string, any>) {
    return { status: 'ON_HOLD', hold: parkedHold,
        statusHistory: [{ from: 'QUEUED', to: 'ASSIGNED', trigger: 'ASSIGN', actor: holder, note: null }, asked], ...over }
}

describe('parked hop', () => {
    it('is an OPERATOR MANUAL hold a session placed on the hop it was working', () => {
        expect(parkedHop(task({}))).toBe(true)
    })

    it('is not any other hold', () => {
        expect(parkedHop(task({ hold: { ...parkedHold, level: 'COORDINATOR' } })), 'the seat\'s level').toBe(false)
        expect(parkedHop(task({ hold: { ...parkedHold, kind: 'HUMAN_GATE' } })), 'a gate').toBe(false)
        expect(parkedHop(task({ hold: { ...parkedHold, heldBy: pat } })), 'a person\'s hold').toBe(false)
        // the coordinator escalated its own hold: OPERATOR, by a session, but not from a hop
        expect(parkedHop(task({ statusHistory: [{ ...asked, from: 'AWAITING_COORDINATOR' }] })), 'an escalation').toBe(false)
        expect(parkedHop(task({ status: 'ASSIGNED', hold: null })), 'no hold').toBe(false)
        expect(parkedHop(null)).toBe(false)
    })

    it('needs the answer on release, where another hold does not', () => {
        expect(releaseNeedsAnswer(task({}), '  ')).toBe(true)
        expect(releaseNeedsAnswer(task({}), 'per-org')).toBe(false)
        expect(releaseNeedsAnswer(task({ hold: { ...parkedHold, heldBy: pat } }), '')).toBe(false)
    })
})

describe('operator questions', () => {
    it('reads the question while the hop waits', () => {
        const qs = operatorQuestions(task({}))
        expect(qs).toHaveLength(1)
        expect(qs[0]).toMatchObject({ question: 'per-org or per-board?', askedBy: holder, answer: null, answeredBy: null })
    })

    it('pairs the question with the answer the release gave', () => {
        const qs = operatorQuestions(task({ status: 'ASSIGNED', hold: null,
            statusHistory: [asked, answered, { ...asked, at: '2026-09-29T12:00:00Z', note: 'awaiting the operator: which region?' }] }))
        expect(qs.map(q => [q.question, q.answer])).toEqual([
            ['per-org or per-board?', 'per-org, rotated yearly'],
            ['which region?', null],
        ])
        expect(qs[0]).toMatchObject({ answeredBy: pat, answeredAt: '2026-09-29T11:00:00Z', askedAt: '2026-09-29T10:00:00Z' })
    })

    it('leaves a question unanswered when the task left the hold another way', () => {
        const cancelled = { from: 'ON_HOLD', to: 'CANCELLED', trigger: 'CANCEL', actor: pat, note: 'not needed' }
        expect(operatorQuestions(task({ statusHistory: [asked, cancelled] }))[0].answer).toBeNull()
    })

    // A HOLD row from QUEUED by a session with the question is the seat's (RD4-17): see agentSeatOperatorHold.spec.ts.
    it('ignores holds that ask the operator nothing', () => {
        expect(operatorQuestions(task({ statusHistory: [{ ...asked, actor: pat },
            { ...asked, from: 'QUEUED', note: 'waiting on the tracker' }] }))).toEqual([])
    })

    it('strips the words before the question and the answer', () => {
        expect(questionOf('awaiting the operator: q?')).toBe('q?')
        expect(questionOf('q?')).toBe('q?')
        expect(answerOf(answered)).toBe('per-org, rotated yearly')
        expect(answerOf({ note: 'released by USER u1: yes: both', actor: { kind: 'USER', uuid: 'u1', name: null } })).toBe('yes: both')
    })
})
