import { describe, expect, it } from 'vitest'
import { STALENESS_FIELDS, STALENESS_KEYS, stalenessDraftOf, stalenessPatch, stalenessProblem } from './agentStaleness'
import { disabledReason } from './agentTaskHints'
import { unassignPayload } from './agentTaskAdmin'

describe('agentStaleness', () => {
    it('reads a board block, or none, into a draft of every threshold', () => {
        expect(stalenessDraftOf(null)).toEqual({ roleUnstaffedMinutes: null, hopNoProgressMinutes: null,
            deliveryStuckMinutes: null, seatSilentMinutes: null, repeatMinutes: null, investigationOverdueMinutes: null })
        expect(stalenessDraftOf({ hopNoProgressMinutes: 90 }).hopNoProgressMinutes).toBe(90)
        expect(STALENESS_FIELDS.map(f => f.key)).toEqual([...STALENESS_KEYS])
    })

    it('refuses zero, negatives and fractions, not blanks', () => {
        expect(stalenessProblem({ roleUnstaffedMinutes: 60, repeatMinutes: null })).toBe('')
        expect(stalenessProblem({ seatSilentMinutes: 0 })).toBe('seatSilentMinutes must be at least 1 minute; leave it blank to turn it off')
        expect(stalenessProblem({ hopNoProgressMinutes: -5 })).not.toBe('')
        expect(stalenessProblem({ deliveryStuckMinutes: 1.5 })).not.toBe('')
    })

    it('sends the whole block when it changed, null when emptied, nothing when unchanged', () => {
        const board = { roleUnstaffedMinutes: 60, hopNoProgressMinutes: 90, deliveryStuckMinutes: null, seatSilentMinutes: null, repeatMinutes: null }
        expect(stalenessPatch(board, { ...board })).toBeUndefined()
        expect(stalenessPatch(board, { ...board, hopNoProgressMinutes: 120 })).toEqual({ roleUnstaffedMinutes: 60, hopNoProgressMinutes: 120 })
        expect(stalenessPatch(board, {})).toBeNull()
        expect(stalenessPatch(null, {})).toBeUndefined()
        expect(stalenessPatch(null, { seatSilentMinutes: 30 })).toEqual({ seatSilentMinutes: 30 })
    })
})

describe('unassigning', () => {
    const operator = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }
    it('is offered on an assigned task, to a person with BOARD_WRITE, with a reason', () => {
        const assigned = { status: 'ASSIGNED' }
        expect(disabledReason('unassign', assigned, operator, { unassignReason: 'the agent is gone' })).toBeNull()
        expect(disabledReason('unassign', assigned, operator, { unassignReason: ' ' })).toBe('say why it is unassigned')
        expect(disabledReason('unassign', { status: 'QUEUED' }, operator, { unassignReason: 'x' }))
            .toBe('only an assigned task can be unassigned')
        expect(disabledReason('unassign', assigned, { myPermissions: ['BOARD_READ'] }, { unassignReason: 'x' }))
            .toBe('unassigning needs BOARD_WRITE on this board')
    })

    it('sends the task and the trimmed reason, or nothing without one', () => {
        const task = { uuid: 't-1', status: 'ASSIGNED' }
        expect(unassignPayload(task, '  gone  ')).toEqual({ task, reason: 'gone' })
        expect(unassignPayload(task, '')).toBeNull()
        expect(unassignPayload({ uuid: 't-1', status: 'QUEUED' }, 'gone')).toBeNull()
    })
})
