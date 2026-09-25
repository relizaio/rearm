import { describe, it, expect } from 'vitest'
import { isOrgAdmin, reopenRoleOptions, reopenPayload } from './agentReopen'

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
