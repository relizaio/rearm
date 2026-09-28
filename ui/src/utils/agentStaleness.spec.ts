import { describe, expect, it } from 'vitest'
import { STALENESS_FIELDS, STALENESS_KEYS, stalenessDraftOf, stalenessPatch, stalenessProblem } from './agentStaleness'
import { disabledReason } from './agentTaskHints'
import { releasePayload } from './agentTaskAdmin'

describe('agentStaleness', () => {
    it('reads a board block, or none, into a draft of every threshold', () => {
        expect(stalenessDraftOf(null)).toEqual({ roleUnstaffedMinutes: null, hopNoProgressMinutes: null,
            deliveryStuckMinutes: null, seatSilentMinutes: null, repeatMinutes: null })
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

describe('releasing an assignment', () => {
    const operator = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }
    it('is offered on an assigned task, to a person with BOARD_WRITE, with a reason', () => {
        const assigned = { status: 'ASSIGNED' }
        expect(disabledReason('release', assigned, operator, { releaseReason: 'the agent is gone' })).toBeNull()
        expect(disabledReason('release', assigned, operator, { releaseReason: ' ' })).toBe('say why the assignment is released')
        expect(disabledReason('release', { status: 'QUEUED' }, operator, { releaseReason: 'x' }))
            .toBe('only an assigned task has an assignment to release')
        expect(disabledReason('release', assigned, { myPermissions: ['BOARD_READ'] }, { releaseReason: 'x' }))
            .toBe('releasing an assignment needs BOARD_WRITE on this board')
    })

    it('sends the task and the trimmed reason, or nothing without one', () => {
        const task = { uuid: 't-1', status: 'ASSIGNED' }
        expect(releasePayload(task, '  gone  ')).toEqual({ task, reason: 'gone' })
        expect(releasePayload(task, '')).toBeNull()
        expect(releasePayload({ uuid: 't-1', status: 'QUEUED' }, 'gone')).toBeNull()
    })
})
