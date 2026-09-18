import { describe, expect, it } from 'vitest'
import { resolveApprovalRoles } from './approvalRoles'

describe('resolveApprovalRoles', () => {
    it('returns the expanded roles when present', () => {
        const expanded = [{ id: 'PM', displayView: 'Project Management' }]
        expect(resolveApprovalRoles({
            allowedApprovalRoleIdExpanded: expanded,
            allowedApprovalRoleIds: ['PM'],
        })).toEqual(expanded)
    })

    it('falls back to raw ids when expansion is empty (role undefined on the org)', () => {
        expect(resolveApprovalRoles({
            allowedApprovalRoleIdExpanded: [],
            allowedApprovalRoleIds: ['QA_UAT'],
        })).toEqual([{ id: 'QA_UAT', displayView: 'QA_UAT' }])
    })

    it('falls back when expansion is missing entirely', () => {
        expect(resolveApprovalRoles({
            allowedApprovalRoleIds: ['PM', 'QA_AUTO'],
        })).toEqual([
            { id: 'PM', displayView: 'PM' },
            { id: 'QA_AUTO', displayView: 'QA_AUTO' },
        ])
    })

    it('returns empty for a requirement with no roles at all', () => {
        expect(resolveApprovalRoles({})).toEqual([])
        expect(resolveApprovalRoles({
            allowedApprovalRoleIdExpanded: null,
            allowedApprovalRoleIds: null,
        })).toEqual([])
    })
})
