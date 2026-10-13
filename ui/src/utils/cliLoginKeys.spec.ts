import { describe, expect, it } from 'vitest'
import { boundAgentsLabel, isBoundToAgent, keysToOffer, permissionSummary } from './cliLoginKeys'

const key = (uuid: string, extra: any = {}) => ({ uuid, status: 'ACTIVE', type: 'FREEFORM', ...extra })

describe('keys the CLI login offers', () => {
    const keys = [
        key('free'),
        key('bound', { boundAgents: [{ uuid: 'a1', name: 'builder' }] }),
        key('personal', { type: 'USER', boundAgents: [] }),
        key('revoked', { status: 'REVOKED' }),
        key('org-key', { type: 'ORGANIZATION_RW' }),
    ]

    it('offers unbound keys by default, in order', () => {
        expect(keysToOffer(keys, false).map(k => k.uuid)).toEqual(['free', 'personal'])
    })

    it('adds the bound keys when asked', () => {
        expect(keysToOffer(keys, true).map(k => k.uuid)).toEqual(['free', 'bound', 'personal'])
    })

    it('never offers an inactive key or a key type a session cannot act as', () => {
        const all = keysToOffer(keys, true).map(k => k.uuid)
        expect(all).not.toContain('revoked')
        expect(all).not.toContain('org-key')
    })

    it('treats a missing or empty boundAgents as unbound', () => {
        expect(isBoundToAgent(key('x'))).toBe(false)
        expect(isBoundToAgent(key('x', { boundAgents: null }))).toBe(false)
        expect(isBoundToAgent(key('x', { boundAgents: [] }))).toBe(false)
        expect(keysToOffer(null as any, false)).toEqual([])
    })

    it('names the agents of a bound key', () => {
        expect(boundAgentsLabel(key('b', { boundAgents: [{ uuid: 'a1', name: 'builder' }, { uuid: 'a2', name: 'reviewer' }] })))
            .toBe('bound to builder, reviewer')
        expect(boundAgentsLabel(key('b', { boundAgents: [{ uuid: 'a3' }] }))).toBe('bound to a3')
        expect(boundAgentsLabel(key('free'))).toBe('')
    })
})

describe('permission summary of a key', () => {
    const perms = (...types: string[]) => ({ permissions: { permissions: types.map(type => ({ scope: 'ORGANIZATION', type })) } })

    it('says so when the key grants nothing', () => {
        expect(permissionSummary(perms())).toBe('no permissions')
        expect(permissionSummary({})).toBe('no permissions')
    })

    it('names the one permission a key grants', () => {
        expect(permissionSummary(perms('READ_WRITE'))).toBe('1 permission, READ_WRITE')
    })

    it('counts several and names the highest, an unknown type ranking below NONE', () => {
        expect(permissionSummary(perms('READ_ONLY', 'ADMIN', 'NONE'))).toBe('3 permissions, highest ADMIN')
        expect(permissionSummary(perms('SOMETHING_NEW', 'NONE'))).toBe('2 permissions, highest NONE')
    })
})
