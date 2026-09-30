// A task the coordinator seat parked for an operator decision (task RD4-17): the hold records where the answer
// returns the task, the card reads "awaiting the operator" with the question on hover, a release needs the
// answer, and acting on the task as the status it was parked from answers it.
import { describe, expect, it } from 'vitest'
import { actingAnswers, awaitingOperator, effectiveStatus, holdChip, operatorQuestions, parkedHop, releaseNeedsAnswer,
    returnsTo, seatParked } from './agentOperatorQuestion'
import { reopenRoleOptions } from './agentReopen'

const seat = { kind: 'SESSION', uuid: 's-seat', name: null }
const pat = { kind: 'USER', uuid: 'u1', name: 'Pat Operator' }
const QUESTION = 'CI is red on #401. Options: re-run, reopen to the coder. Recommend: re-run.'
const asked = { from: 'DELIVERING', to: 'ON_HOLD', at: '2026-09-30T10:00:00Z', trigger: 'HOLD', actor: seat,
    note: `awaiting the operator: ${QUESTION}` }
const seatHold = { level: 'OPERATOR', kind: 'MANUAL', reason: `awaiting the operator: ${QUESTION}`, heldBy: seat,
    heldAt: '2026-09-30T10:00:00Z', returnTo: 'DELIVERING' }

function held (over: Record<string, any> = {}) {
    return { uuid: 't16', status: 'ON_HOLD', hold: seatHold,
        statusHistory: [{ from: 'ASSIGNED', to: 'DELIVERING', trigger: 'DELIVER_WAIT', actor: seat, note: null }, asked],
        ...over }
}

describe('a task the seat parked for the operator', () => {
    it('is an OPERATOR MANUAL hold a session placed with a status to return to', () => {
        expect(seatParked(held())).toBe(true)
        expect(awaitingOperator(held())).toBe(true)
        expect(parkedHop(held()), 'not a hop\'s own hold: nobody was working it').toBe(false)
        expect(returnsTo(held())).toBe('delivering')
        expect(returnsTo(held({ hold: { ...seatHold, returnTo: 'AWAITING_COORDINATOR' } }))).toBe('awaiting coordinator')
    })

    it('is not any other hold', () => {
        expect(seatParked(held({ hold: { ...seatHold, returnTo: null } })), 'no returnTo').toBe(false)
        expect(seatParked(held({ hold: { ...seatHold, level: 'COORDINATOR' } })), 'the seat\'s own hold').toBe(false)
        expect(seatParked(held({ hold: { ...seatHold, heldBy: pat } })), 'a person\'s hold').toBe(false)
        expect(seatParked(held({ status: 'DELIVERING', hold: null })), 'released').toBe(false)
        expect(seatParked(null)).toBe(false)
    })

    it('needs the answer on release', () => {
        expect(releaseNeedsAnswer(held(), ' ')).toBe(true)
        expect(releaseNeedsAnswer(held(), 're-run it')).toBe(false)
    })

    it('reads as the status it was parked from for what may be done with it', () => {
        expect(effectiveStatus(held())).toBe('DELIVERING')
        expect(effectiveStatus({ status: 'ON_HOLD', hold: { ...seatHold, heldBy: pat } })).toBe('ON_HOLD')
        const roles = [{ name: 'coder', active: true, orderIndex: 1 }]
        expect(reopenRoleOptions(held(), roles, true).map(o => o.value), 'a person may reopen it, which answers it')
            .toEqual(['coder'])
        expect(reopenRoleOptions(held({ hold: { ...seatHold, returnTo: 'QUEUED' } }), roles, true)).toEqual([])
        expect(actingAnswers(held())).toBe(`Awaiting the operator: ${QUESTION} Acting here answers it: what you do is`
            + ' recorded as the answer, and the task returns to delivering.')
        expect(actingAnswers({ status: 'DELIVERING', hold: null })).toBeNull()
    })

    it('lists the question as the coordinator\'s, and the answer a person\'s action gave', () => {
        const acted = { from: 'ON_HOLD', to: 'DELIVERING', at: '2026-09-30T11:00:00Z', trigger: 'RELEASE_HOLD', actor: pat,
            note: 'attested by Pat Operator: https://github.com/acme/app/pull/401 delivered at 0123456' }
        const qs = operatorQuestions(held({ status: 'COMPLETED', hold: null,
            statusHistory: [...held().statusHistory, acted] }))
        expect(qs).toHaveLength(1)
        expect(qs[0].question).toBe(QUESTION)
        expect(qs[0].askedByCoordinator).toBe(true)
        expect(qs[0].answer).toBe('attested by Pat Operator: https://github.com/acme/app/pull/401 delivered at 0123456')
        expect(qs[0].answeredBy).toEqual(pat)
        // any other action of a person (architecture round 2): its row is the answer, then the action's own row
        const cancelled = operatorQuestions(held({ status: 'CANCELLED', hold: null, statusHistory: [...held().statusHistory,
            { ...acted, to: 'DELIVERING', note: 'cancelled by Pat Operator: a duplicate of RD4-3' },
            { from: 'DELIVERING', to: 'CANCELLED', at: '2026-09-30T11:00:00Z', trigger: 'CANCEL', actor: pat,
                note: 'a duplicate of RD4-3' }] }))
        expect(cancelled[0].answer).toBe('cancelled by Pat Operator: a duplicate of RD4-3')
        // a hop's own question stays the hop's
        const hop = operatorQuestions({ statusHistory: [{ ...asked, from: 'ASSIGNED' }] })
        expect(hop[0].askedByCoordinator).toBe(false)
        // the seat's COORDINATOR hold carries no question
        expect(operatorQuestions({ statusHistory: [{ ...asked, note: null }] })).toEqual([])
    })
})

describe('the kanban card\'s hold chip', () => {
    it('reads awaiting the operator with the question on hover, for the seat and for a hop', () => {
        expect(holdChip(held())).toEqual({ label: 'awaiting the operator', tooltip: QUESTION, operator: true })
        const hop = { status: 'ON_HOLD', hold: { ...seatHold, returnTo: undefined },
            statusHistory: [{ ...asked, from: 'ASSIGNED' }] }
        expect(holdChip(hop)?.label).toBe('awaiting the operator')
    })

    it('keeps the gate and a plain hold as they were', () => {
        expect(holdChip({ status: 'ON_HOLD', hold: { kind: 'HUMAN_GATE', reason: 'coder passed' } }))
            .toEqual({ label: '✋ human review', tooltip: 'coder passed', operator: false })
        expect(holdChip({ status: 'ON_HOLD', hold: { kind: 'MANUAL', level: 'COORDINATOR', reason: 'waiting on legal' } }))
            .toEqual({ label: 'on hold', tooltip: 'waiting on legal', operator: false })
        expect(holdChip({ status: 'DELIVERING', hold: null })).toBeNull()
    })
})
