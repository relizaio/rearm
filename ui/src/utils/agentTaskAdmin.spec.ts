import { describe, expect, it } from 'vitest'
import { canForceClose, canPlaceHold, forceCloseNeedsBoards, holdPayload, roleFloor, strengthPlaceholder, strengthToSet } from './agentTaskAdmin'

const roles = [{ name: 'coder', requiredStrength: 3.5 }, { name: 'tester', requiredStrength: null }]

describe('task admin verbs', () => {
    it('offers a hold only to an admin, and only where the server places one', () => {
        for (const s of ['PENDING_INTAKE', 'QUEUED', 'AWAITING_COORDINATOR']) expect(canPlaceHold({ status: s }, true)).toBe(true)
        for (const s of ['ASSIGNED', 'ON_HOLD', 'DELIVERING', 'COMPLETED', 'CANCELLED']) expect(canPlaceHold({ status: s }, true)).toBe(false)
        expect(canPlaceHold({ status: 'QUEUED' }, false)).toBe(false)
    })

    it('needs a reason for a hold', () => {
        const t = { uuid: 't' }
        expect(holdPayload(t, '')).toBeNull()
        expect(holdPayload(t, '   ')).toBeNull()
        expect(holdPayload(t, null)).toBeNull()
        expect(holdPayload(t, ' waiting on legal ')).toEqual({ task: t, reason: 'waiting on legal' })
    })

    it('shows the role floor where the task has no strength of its own', () => {
        expect(roleFloor({ role: 'coder' }, roles)).toBe(3.5)
        expect(roleFloor({ role: 'tester' }, roles)).toBeNull()
        expect(roleFloor({ role: 'gone' }, roles)).toBeNull()
        expect(strengthPlaceholder({ role: 'coder' }, roles)).toBe('role floor 3.5')
        expect(strengthPlaceholder({ role: null }, roles)).toBe('none')
    })

    it('sends a strength only when it changes, rounded to two decimals', () => {
        expect(strengthToSet({ requiredStrength: null }, 4)).toBe(4)
        expect(strengthToSet({ requiredStrength: 4 }, 4)).toBeUndefined()
        expect(strengthToSet({ requiredStrength: 4 }, 4.004)).toBeUndefined()
        expect(strengthToSet({ requiredStrength: 4 }, 4.126)).toBe(4.13)
        expect(strengthToSet({ requiredStrength: null }, null)).toBeUndefined()
        expect(strengthToSet({ requiredStrength: null }, -1)).toBeUndefined()
        expect(strengthToSet({ requiredStrength: 2 }, 0)).toBe(0)
    })

    it('offers force-close to an admin on an open session only', () => {
        expect(canForceClose({ status: 'OPEN' }, true)).toBe(true)
        expect(canForceClose({ status: 'CLOSED' }, true)).toBe(false)
        expect(canForceClose({ status: 'OPEN' }, false)).toBe(false)
        expect(canForceClose(null, true)).toBe(false)
    })

    // RD2-5: the server's rule, BOARD_WRITE on a board the session worked, else the org admin.
    const writeA = { uuid: 'A', myPermissions: ['BOARD_READ', 'BOARD_AGENT', 'BOARD_WRITE'] }
    const readA = { uuid: 'A', myPermissions: ['BOARD_READ'] }
    const writeB = { uuid: 'B', myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }
    const workedA = { status: 'OPEN', boardsWorked: ['A'] }

    it('offers force-close on BOARD_WRITE over a board the session worked', () => {
        expect(canForceClose(workedA, true, [])).toBe(true)
        expect(canForceClose(workedA, false, [writeA])).toBe(true)
        expect(canForceClose(workedA, false, [readA]), 'BOARD_READ only').toBe(false)
        expect(canForceClose(workedA, false, [writeB]), 'BOARD_WRITE on a board it did not work').toBe(false)
        expect(canForceClose({ status: 'OPEN', boardsWorked: ['B', 'A'] }, false, [readA, writeB])).toBe(true)
        expect(canForceClose({ status: 'OPEN', boardsWorked: [] }, false, [writeA]), 'no board and not admin').toBe(false)
        expect(canForceClose({ status: 'OPEN' }, false, [writeA]), 'boardsWorked not served').toBe(false)
        expect(canForceClose({ ...workedA, status: 'CLOSED' }, false, [writeA])).toBe(false)
        expect(canForceClose({ ...workedA, status: 'CLOSED' }, true, [writeA])).toBe(false)
        expect(canForceClose(workedA, false, null)).toBe(false)
    })

    it('reads the boards only when they decide', () => {
        expect(forceCloseNeedsBoards(workedA, false)).toBe(true)
        expect(forceCloseNeedsBoards(workedA, true), 'the admin passes anyway').toBe(false)
        expect(forceCloseNeedsBoards({ ...workedA, status: 'CLOSED' }, false)).toBe(false)
        expect(forceCloseNeedsBoards({ status: 'OPEN', boardsWorked: [] }, false)).toBe(false)
        expect(forceCloseNeedsBoards(null, false)).toBe(false)
    })
})
