import { describe, it, expect } from 'vitest'
import {
    buildMemberRolesPayload,
    validateTeamMembers,
} from './teamMembers'

const A = '11111111-1111-1111-1111-111111111111'
const B = '22222222-2222-2222-2222-222222222222'
const label = (u: string) => (u === A ? 'Alice' : u === B ? 'Bob' : u)

describe('buildMemberRolesPayload', () => {
    it('emits a role for a roster member', () => {
        expect(buildMemberRolesPayload({ [A]: { role: 'QA' } }, [A]))
            .toEqual([{ userRef: A, role: 'QA', customRole: null }])
    })

    it('drops a role for someone removed from the roster in this session', () => {
        // The backend rejects a role whose userRef is not on the post-merge
        // roster, and that rejection fails the WHOLE mutation -- so the editor
        // must never emit one.
        expect(buildMemberRolesPayload({ [A]: { role: 'QA' }, [B]: { role: 'TEAM_LEAD' } }, [A]))
            .toEqual([{ userRef: A, role: 'QA', customRole: null }])
    })

    it('omits a cleared dropdown rather than emitting a null role', () => {
        expect(buildMemberRolesPayload({ [A]: { role: null, customRole: 'stale' } }, [A])).toEqual([])
    })

    it('keeps the label only for CUSTOM and trims it', () => {
        expect(buildMemberRolesPayload({ [A]: { role: 'CUSTOM', customRole: '  Release Captain  ' } }, [A]))
            .toEqual([{ userRef: A, role: 'CUSTOM', customRole: 'Release Captain' }])
    })

    it('nulls a stale label left behind after switching away from CUSTOM', () => {
        expect(buildMemberRolesPayload({ [A]: { role: 'DEVELOPER', customRole: 'Release Captain' } }, [A]))
            .toEqual([{ userRef: A, role: 'DEVELOPER', customRole: null }])
    })
})

describe('validateTeamMembers', () => {
    it('passes a well-formed payload', () => {
        expect(validateTeamMembers([{ userRef: A, role: 'QA', customRole: null }], label)).toBeNull()
    })

    it('names the member whose CUSTOM role has no label', () => {
        const err = validateTeamMembers([{ userRef: A, role: 'CUSTOM', customRole: null }], label)
        expect(err).toContain('Alice')
    })

    it('passes an empty payload', () => {
        expect(validateTeamMembers([], label)).toBeNull()
    })
})
