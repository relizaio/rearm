import { describe, it, expect } from 'vitest'
import { buildUserGroupUpdateInput } from './userGroupUpdateInput'

const GROUP = {
    uuid: 'g-1',
    name: 'Docs Team',
    description: 'writers',
    manualUsers: ['u-1'],
    status: 'ACTIVE',
    connectedSsoGroups: ['sso-docs'],
}
const ROLES = [{ userRef: 'u-1', role: 'QA', customRole: null }]
const LOADED = { known: true, channelsLoadFailed: false, channels: ['c-1'], memberRoles: ROLES }

describe('buildUserGroupUpdateInput', () => {
    it('never sends externalMembers, even with team data fully loaded', () => {
        // The editor for it was withdrawn, so the form does not know the stored
        // value. The backend REPLACES on a non-null list -- sending [] here
        // would delete every external contact on the team, silently, with no UI
        // left to notice or restore it.
        expect('externalMembers' in buildUserGroupUpdateInput(GROUP, [], LOADED)).toBe(false)
    })

    it('omits every team field until this group is loaded', () => {
        const input = buildUserGroupUpdateInput(GROUP, [], {
            known: false, channelsLoadFailed: false, channels: [], memberRoles: [],
        })
        expect('memberRoles' in input).toBe(false)
        expect('notificationChannels' in input).toBe(false)
    })

    it('omits channels when the channel list failed to load, but still saves roles', () => {
        // A picker built from a partial list must not be written over the real
        // one -- while a rename or role edit in the same modal still saves.
        const input = buildUserGroupUpdateInput(GROUP, [], { ...LOADED, channelsLoadFailed: true })
        expect('notificationChannels' in input).toBe(false)
        expect(input.memberRoles).toEqual(ROLES)
    })

    it('sends roles and channels once loaded', () => {
        const input = buildUserGroupUpdateInput(GROUP, [], LOADED)
        expect(input.memberRoles).toEqual(ROLES)
        expect(input.notificationChannels).toEqual(['c-1'])
    })

    it('carries the core fields through, defaulting the list ones', () => {
        const input = buildUserGroupUpdateInput({ uuid: 'g-2' }, [{ scope: 'ORGANIZATION' }], {
            known: false, channelsLoadFailed: false, channels: [], memberRoles: [],
        })
        expect(input.groupId).toBe('g-2')
        expect(input.manualUsers).toEqual([])
        expect(input.connectedSsoGroups).toEqual([])
        expect(input.permissions).toEqual([{ scope: 'ORGANIZATION' }])
    })
})
