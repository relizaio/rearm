import { describe, it, expect } from 'vitest'
import { isOrgAdmin, reopenRoleOptions, reopenPayload } from './agentReopen'
import { reopenLine } from './agentTaskHints'

const ORG = 'org-1'
const roles = [
    { name: 'tester', orderIndex: 30, active: true },
    { name: 'architect', orderIndex: 10, active: true },
    { name: 'retired', orderIndex: 5, active: false },
    { name: 'coder', orderIndex: 20 }
]

describe('isOrgAdmin', () => {
    it('is an ORGANIZATION-scope ADMIN permission on this org', () => {
        expect(isOrgAdmin([{ org: ORG, scope: 'ORGANIZATION', type: 'ADMIN' }], ORG)).toBe(true)
        expect(isOrgAdmin([{ org: 'other', scope: 'ORGANIZATION', type: 'ADMIN' }], ORG)).toBe(false)
        expect(isOrgAdmin([{ org: ORG, scope: 'ORGANIZATION', type: 'READ_WRITE' }], ORG)).toBe(false)
        expect(isOrgAdmin([{ org: ORG, scope: 'COMPONENT', type: 'ADMIN' }], ORG)).toBe(false)
        expect(isOrgAdmin(undefined, ORG)).toBe(false)
    })
})

describe('reopenRoleOptions', () => {
    it('offers the active roles in board order on a completed task, to an admin', () => {
        expect(reopenRoleOptions({ status: 'COMPLETED' }, roles, true).map(o => o.value))
            .toEqual(['architect', 'coder', 'tester'])
    })

    it('offers the same on a DELIVERING task: its PR may be the thing that cannot land', () => {
        expect(reopenRoleOptions({ status: 'DELIVERING' }, roles, true).map(o => o.value))
            .toEqual(['architect', 'coder', 'tester'])
    })

    it('offers nothing on any other status, a cancelled task included, or to a non-admin', () => {
        for (const status of ['CANCELLED', 'QUEUED', 'ASSIGNED', 'ON_HOLD', 'AWAITING_COORDINATOR']) {
            expect(reopenRoleOptions({ status }, roles, true)).toEqual([])
        }
        expect(reopenRoleOptions({ status: 'COMPLETED' }, roles, false)).toEqual([])
    })
})

describe('reopenPayload', () => {
    it('sends the task, the role and the trimmed reason', () => {
        expect(reopenPayload({ uuid: 't-1' }, 'coder', '  PR conflicts  '))
            .toEqual({ taskUuid: 't-1', role: 'coder', reason: 'PR conflicts' })
    })

    it('is not ready without a role or a reason', () => {
        expect(reopenPayload({ uuid: 't-1' }, null, 'why')).toBeNull()
        expect(reopenPayload({ uuid: 't-1' }, 'coder', '   ')).toBeNull()
        expect(reopenPayload(null, 'coder', 'why')).toBeNull()
    })
})

// RD2-16: the reopen line says who reopened it, to which role and why, not only when.
describe('the reopen line', () => {
    const when = (at: string) => at.slice(0, 10)
    it('names the last reopen: when, the role, the person and the reason', () => {
        expect(reopenLine({ reopenCount: 2, reopenedAt: '2026-09-27T10:00:00Z', reopens: [
            { role: 'architect', at: '2026-09-20T10:00:00Z', reason: 'first', by: { kind: 'USER', name: 'a@x' } },
            { role: 'coder', at: '2026-09-27T10:00:00Z', reason: 'PR conflicts with main', by: { kind: 'USER', uuid: 'u1', name: 'pm@example.com' } },
        ] }, when)).toBe('Reopened 2× · last 2026-09-27 to coder by pm@example.com: PR conflicts with main')
    })

    it('says the board reopened it when a delivery could not land', () => {
        expect(reopenLine({ reopenCount: 1, reopens: [{ role: 'tester', at: '2026-09-27T11:00:00Z',
            reason: 'PR moved past the tested head', by: { kind: 'SYSTEM', uuid: null, name: 'delivery' } }] }, when))
            .toBe('Reopened 1× · last 2026-09-27 to tester by the board: PR moved past the tested head')
    })

    it('keeps the count and time for a read without reopens, and says nothing for none', () => {
        expect(reopenLine({ reopenCount: 1, reopenedAt: '2026-09-26T09:00:00Z' }, when)).toBe('Reopened 1× · last 2026-09-26')
        expect(reopenLine({ reopenCount: 0 }, when)).toBeNull()
    })
})

