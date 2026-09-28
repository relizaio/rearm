import { beforeEach, describe, expect, it, vi } from 'vitest'

// task RD3-16: complete refuses out loud. The toast gives the server's reason, and the button says why before
// the click when the task's delivery will not land.

const dispatch = vi.fn()
const success = vi.fn()
const error = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch }) }))
vi.mock('naive-ui', () => ({ useNotification: () => ({ success, error }) }))

const { useAgentTaskActions } = await import('./agentTaskActions')
const { blockedDelivery, disabledReason } = await import('./agentTaskHints')

const REASON = 'Task RD3-4 passed, but its delivery will not land: https://github.com/relizaio/rearm-saas/pull/692'
    + ' attested abandoned (superseded by #694). Reopen it to the role that must redo the work, or link the PR that replaces it.'

describe('the Complete action', () => {
    beforeEach(() => { dispatch.mockReset(); success.mockReset(); error.mockReset() })

    it('toasts the server reason when complete is refused', async () => {
        dispatch.mockRejectedValue(new Error(REASON))
        const after = vi.fn(async () => {})
        await useAgentTaskActions(after).completeTask({ task: { uuid: 't1' }, note: 'ship', skipRequiredRoles: false })
        expect(error).toHaveBeenCalledWith({ content: `Could not complete: ${REASON}`, duration: 8000 })
        expect(success).not.toHaveBeenCalled()
        expect(after).not.toHaveBeenCalled()
    })

    it('says a task waiting on its delivery passed, not that it completed', async () => {
        dispatch.mockResolvedValue({ uuid: 't1', status: 'DELIVERING' })
        await useAgentTaskActions(async () => {}).completeTask({ task: { uuid: 't1' }, note: '', skipRequiredRoles: false })
        expect(success).toHaveBeenCalledWith({ content: 'Passed: waiting for its delivery', duration: 3000 })
        dispatch.mockResolvedValue({ uuid: 't1', status: 'COMPLETED' })
        await useAgentTaskActions(async () => {}).completeTask({ task: { uuid: 't1' }, note: '', skipRequiredRoles: false })
        expect(success).toHaveBeenLastCalledWith({ content: 'Task completed', duration: 3000 })
    })
})

describe('why Complete is disabled', () => {
    const prBoard = { deliveryPolicy: { mode: 'PULL_REQUEST' } }
    const abandoned = { url: 'https://github.com/relizaio/rearm-saas/pull/692', state: 'OPEN',
        attestation: { outcome: 'ABANDONED', note: 'superseded by #694' } }
    const closed = { url: 'https://github.com/relizaio/rearm-saas/pull/700', state: 'CLOSED' }
    const merged = { url: 'https://github.com/relizaio/rearm-saas/pull/694', state: 'MERGED' }

    it('names each unit that will not land, the newest attestation settling a PR', () => {
        const task = { status: 'AWAITING_COORDINATOR', pullRequests: [abandoned, closed, merged] }
        expect(blockedDelivery(task, prBoard)).toEqual(['relizaio/rearm-saas/pull/692 attested abandoned',
            'relizaio/rearm-saas/pull/700 closed without merging'])
        expect(disabledReason('complete', task, prBoard)).toMatch(/^its delivery will not land: .*attested abandoned; .*closed without merging; reopen it, or have the role that pushes code link the PR that replaces it and declare this one superseded \(task supersedepr\)$/)
    })

    it('a closed PR attested delivered does not block', () => {
        const task = { status: 'AWAITING_COORDINATOR',
            pullRequests: [{ ...closed, attestation: { outcome: 'DELIVERED' } }, merged] }
        expect(blockedDelivery(task, prBoard)).toEqual([])
        expect(disabledReason('complete', task, prBoard)).toBeNull()
    })

    it('on a board without PRs, the newest attestation of the task decides', () => {
        const none = { deliveryPolicy: { mode: 'NONE', attest: true } }
        const task = { status: 'AWAITING_COORDINATOR', deliveries: [{ outcome: 'DELIVERED' }, { outcome: 'ABANDONED' }] }
        expect(disabledReason('complete', task, none)).toBe('its delivery will not land: its delivery attested abandoned;'
            + ' reopen it')
        expect(disabledReason('complete', { ...task, deliveries: [{ outcome: 'ABANDONED' }, { outcome: 'DELIVERED' }] }, none))
            .toBeNull()
    })

    it('the status rule still comes first', () => {
        expect(disabledReason('complete', { status: 'ASSIGNED', pullRequests: [closed] }, prBoard))
            .toBe('assigned to a session; release or force-close it first')
    })

    it('offers the supersede for a PR attested abandoned too (RD3-13 architecture-2)', () => {
        expect(disabledReason('complete', { status: 'AWAITING_COORDINATOR', pullRequests: [abandoned] }, prBoard))
            .toBe('its delivery will not land: relizaio/rearm-saas/pull/692 attested abandoned; reopen it, or have the role'
                + ' that pushes code link the PR that replaces it and declare this one superseded (task supersedepr)')
    })
})
