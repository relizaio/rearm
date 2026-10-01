// The approval entry the create form builds. It used to offer only role checkboxes and turned each
// ticked role into its own requirement of one approval, so a requirement of two approvals, or
// "any 2 of these 3 roles", could only be made through the API.

export interface RequirementDraft {
    roles: string[]
    requiredNumberOfApprovals: number | null
    permittedNumberOfDisapprovals: number | null
}

export interface ApprovalRequirementInput {
    allowedApprovalRoleIds: string[]
    requiredNumberOfApprovals: number
    permittedNumberOfDisapprovals: number
}

export interface ApprovalEntryInput {
    org: string
    approvalName: string
    approvalRequirements: ApprovalRequirementInput[]
}

export function emptyRequirement(): RequirementDraft {
    return { roles: [], requiredNumberOfApprovals: 1, permittedNumberOfDisapprovals: 0 }
}

/** What is wrong with the form, one line per problem; empty when it can be submitted. */
export function approvalEntryIssues(name: string, requirements: RequirementDraft[]): string[] {
    const issues: string[] = []
    if (!name || !name.trim()) issues.push('The approval entry needs a name')
    if (!requirements.length) issues.push('Add at least one requirement')
    requirements.forEach((r, i) => {
        const where = requirements.length > 1 ? `Requirement ${i + 1}: ` : ''
        if (!r.roles.length) issues.push(`${where}pick at least one approval role`)
        if (!Number.isInteger(r.requiredNumberOfApprovals) || (r.requiredNumberOfApprovals as number) < 1) {
            issues.push(`${where}approvals needed must be a whole number of at least 1`)
        }
        if (!Number.isInteger(r.permittedNumberOfDisapprovals) || (r.permittedNumberOfDisapprovals as number) < 0) {
            issues.push(`${where}disapprovals tolerated must be a whole number of at least 0`)
        }
    })
    return issues
}

/** The GraphQL input for a form with no issues. */
export function toApprovalEntryInput(org: string, name: string, requirements: RequirementDraft[]): ApprovalEntryInput {
    return {
        org,
        approvalName: name.trim(),
        approvalRequirements: requirements.map(r => ({
            allowedApprovalRoleIds: [...r.roles],
            requiredNumberOfApprovals: r.requiredNumberOfApprovals as number,
            permittedNumberOfDisapprovals: r.permittedNumberOfDisapprovals as number
        }))
    }
}
