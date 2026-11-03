import { describe, expect, it, vi } from 'vitest'

// TPF-124 (architecture 4.4, test rows 15 and 22): the combined-permission line builder shared by the
// Users tab's eye and the Free Form Keys eye. The graphql client connects on import (commonFunctions
// imports it, and it reads window.location through keycloak.ts); nothing here calls it.
// commonFunctions itself stays real, so translatePermissionName yields the real strings.
vi.mock('@/utils/graphql', () => ({ default: {} }))
const { combinedPermissionLines, sourcedPermissionLines } = await import('./combinedPermissionLines')

const names = {
    orgUuid: 'o1',
    orgName: () => 'Acme' as string | undefined,
    perspectiveName: (u: string) => u === 'p1' ? 'Core' : undefined,
    boardName: (u: string) => u === 'b1' ? 'Fixes' : undefined,
    component: (u: string) => u === 'c1' ? { name: 'api', type: 'COMPONENT' } : u === 'c2' ? { name: 'Shop', type: 'PRODUCT' } : undefined,
}
const row = (scope: string, object: string, type: string, extra: Record<string, any> = {}) => ({ org: 'o1', scope, object, type, ...extra })

describe('combinedPermissionLines (row 15 a, row 22)', () => {
    it('renders the rows exactly as the inline builder of the Users tab did', () => {
        // Literal strings: what the inline builder of OrgSettings (before TPF-124) produced for the
        // same inputs, never computed through the util under test.
        const rows = [
            row('ORGANIZATION', 'o1', 'READ_ONLY'),
            row('ORGANIZATION', 'o2', 'ADMIN'),
            row('PERSPECTIVE', 'p1', 'READ_WRITE'),
            row('PERSPECTIVE', 'p9', 'READ_ONLY'),
            row('BOARD', 'b1', 'READ_ONLY'),
            row('BOARD', 'b9', 'READ_ONLY'),
            row('COMPONENT', 'c1', 'READ_WRITE'),
            row('COMPONENT', 'c2', 'READ_ONLY'),
            row('COMPONENT', 'c9', 'ESSENTIAL_READ'),
        ]
        expect(combinedPermissionLines(rows, names)).toEqual([
            'ORGANIZATION: Acme - Read Only',
            'ORGANIZATION: o2 - Administrator',
            'PERSPECTIVE: Core - Read & Write',
            'PERSPECTIVE: p9 - Read Only',
            'BOARD: Fixes - Read Only',
            'BOARD: b9 - Read Only',
            'COMPONENT: api - Read & Write',
            'PRODUCT: Shop - Read Only',
            'COMPONENT: c9 - Essential Read',
        ])
    })

    it('falls back to the org uuid when the org name is unknown', () => {
        expect(combinedPermissionLines([row('ORGANIZATION', 'o1', 'READ_ONLY')], { ...names, orgName: () => undefined }))
            .toEqual(['ORGANIZATION: o1 - Read Only'])
    })

    it('gives no lines for empty and null input', () => {
        expect(combinedPermissionLines([], names)).toEqual([])
        expect(combinedPermissionLines(null, names)).toEqual([])
        expect(combinedPermissionLines(undefined, names)).toEqual([])
    })
})

describe('sourcedPermissionLines (row 15 b)', () => {
    it('adds (own) for an own source at the row level', () => {
        const lines = sourcedPermissionLines([row('ORGANIZATION', 'o1', 'READ_ONLY', { sources: [{ kind: 'OWN', type: 'READ_ONLY' }] })], names)
        expect(lines).toEqual(['ORGANIZATION: Acme - Read Only (own)'])
    })

    it('names a group source', () => {
        const lines = sourcedPermissionLines([row('BOARD', 'b1', 'READ_ONLY', {
            sources: [{ kind: 'GROUP', groupUuid: 'g1', groupName: 'ci', type: 'READ_ONLY' }],
        })], names)
        expect(lines).toEqual(['BOARD: Fixes - Read Only (group "ci")'])
    })

    it('adds a source level only when it differs from the row level, in the received order', () => {
        const lines = sourcedPermissionLines([row('COMPONENT', 'c1', 'READ_WRITE', {
            sources: [
                { kind: 'OWN', groupUuid: null, groupName: null, type: 'READ_ONLY' },
                { kind: 'GROUP', groupUuid: 'g1', groupName: 'ci', type: 'READ_WRITE' },
            ],
        })], names)
        expect(lines).toEqual(['COMPONENT: api - Read & Write (own Read Only, group "ci")'])
        const reversed = sourcedPermissionLines([row('COMPONENT', 'c1', 'READ_WRITE', {
            sources: [
                { kind: 'GROUP', groupUuid: 'g1', groupName: 'ci', type: 'READ_WRITE' },
                { kind: 'OWN', type: 'READ_ONLY' },
            ],
        })], names)
        expect(reversed).toEqual(['COMPONENT: api - Read & Write (group "ci", own Read Only)'])
    })

    it('shows the group uuid when the name is missing', () => {
        const lines = sourcedPermissionLines([row('ORGANIZATION', 'o1', 'READ_ONLY', {
            sources: [{ kind: 'GROUP', groupUuid: 'g1', groupName: null, type: 'READ_ONLY' }],
        })], names)
        expect(lines).toEqual(['ORGANIZATION: Acme - Read Only (group "g1")'])
    })

    it('has no suffix for a row without sources', () => {
        expect(sourcedPermissionLines([row('ORGANIZATION', 'o1', 'READ_ONLY', { sources: [] })], names))
            .toEqual(['ORGANIZATION: Acme - Read Only'])
        expect(sourcedPermissionLines([row('ORGANIZATION', 'o1', 'READ_ONLY')], names))
            .toEqual(['ORGANIZATION: Acme - Read Only'])
        expect(sourcedPermissionLines(null, names)).toEqual([])
    })
})
