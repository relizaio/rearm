import { describe, it, expect } from 'vitest'
import { buildUserGroupUpdateInput } from './userGroupUpdateInput'

const GROUP = {
    uuid: 'g-1',
    name: 'Docs',
    description: 'writers',
    manualUsers: ['u-1'],
    status: 'ACTIVE',
    connectedSsoGroups: ['sso-docs'],
}

describe('buildUserGroupUpdateInput', () => {
    it('carries the core fields through, defaulting the list ones', () => {
        const input = buildUserGroupUpdateInput({ uuid: 'g-2' }, [{ scope: 'ORGANIZATION' }])
        expect(input.groupId).toBe('g-2')
        expect(input.manualUsers).toEqual([])
        expect(input.connectedSsoGroups).toEqual([])
        expect(input.permissions).toEqual([{ scope: 'ORGANIZATION' }])
    })

    it('never sends a team field', () => {
        // These moved to Team. The schema no longer accepts them either, so this
        // is belt and braces -- but the payload is where a reintroduction would
        // start, and a group save silently clearing a team's channels is exactly
        // the failure this file was extracted to prevent.
        const input = buildUserGroupUpdateInput(GROUP, [])
        for (const dead of ['memberRoles', 'externalMembers', 'notificationChannels']) {
            expect(dead in input).toBe(false)
        }
    })
})
