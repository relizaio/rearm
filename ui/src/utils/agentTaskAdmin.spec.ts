import { describe, expect, it } from 'vitest'
import { canForceClose, canPlaceHold, holdPayload, roleFloor, strengthPlaceholder, strengthToSet } from './agentTaskAdmin'

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
})
