import { describe, it, expect } from 'vitest'
import { approvalEntryIssues, emptyRequirement, toApprovalEntryInput } from './approvalEntryInput'

describe('approval entry form', () => {
    it('builds a multi-approver, multi-role requirement -- unreachable from the UI before', () => {
        const input = toApprovalEntryInput('org1', '  Release sign-off ', [
            { roles: ['QA', 'SEC', 'OPS'], requiredNumberOfApprovals: 2, permittedNumberOfDisapprovals: 1 }
        ])
        expect(input).toEqual({
            org: 'org1',
            approvalName: 'Release sign-off',
            approvalRequirements: [{ allowedApprovalRoleIds: ['QA', 'SEC', 'OPS'], requiredNumberOfApprovals: 2, permittedNumberOfDisapprovals: 1 }]
        })
    })

    it('starts with one requirement of one approval and no tolerated disapproval', () => {
        expect(emptyRequirement()).toEqual({ roles: [], requiredNumberOfApprovals: 1, permittedNumberOfDisapprovals: 0 })
    })

    it('names what is missing', () => {
        expect(approvalEntryIssues('', [emptyRequirement()])).toEqual([
            'The approval entry needs a name',
            'pick at least one approval role'
        ])
        expect(approvalEntryIssues('x', [])).toEqual(['Add at least one requirement'])
        expect(approvalEntryIssues('x', [
            { roles: ['QA'], requiredNumberOfApprovals: 0, permittedNumberOfDisapprovals: 0 },
            { roles: ['SEC'], requiredNumberOfApprovals: 1, permittedNumberOfDisapprovals: null }
        ])).toEqual([
            'Requirement 1: approvals needed must be a whole number of at least 1',
            'Requirement 2: disapprovals tolerated must be a whole number of at least 0'
        ])
        expect(approvalEntryIssues('x', [{ roles: ['QA'], requiredNumberOfApprovals: 2, permittedNumberOfDisapprovals: 0 }])).toEqual([])
    })
})
