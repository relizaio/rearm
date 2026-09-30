import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'

/**
 * Wiring guard for the approvals UI gaps: a sole admin could not give themselves an approval role,
 * approval requirements beyond "one role, one approval" were API-only, and the release approval
 * matrix locked a role for everyone after the first vote. Logic lives in approvalMatrix.spec.ts and
 * approvalEntryInput.spec.ts; this checks the components use it.
 */
function source (file: string): string {
    return readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
        .replace(/<!--[\s\S]*?-->/g, '')
}

describe('OrgSettings own row', () => {
    const code = source('./OrgSettings.vue')

    it('can be edited but not removed, with the organization-wide level locked', () => {
        expect(code).toMatch(/if \(row\.uuid === myUser\.value\.uuid\) \{\s*els = \[editIcon\]/)
        expect(code).toContain(':lock-org-type="selectedUser.uuid === myUser.uuid"')
        expect(code).toContain(':show-admin-approvals="true"')
    })
})

describe('ScopedPermissions', () => {
    const code = source('./ScopedPermissions.vue')

    it('offers approval roles on an ADMIN permission only when asked to', () => {
        expect(code).toContain("(orgPermission.type !== 'ADMIN' || showAdminApprovals)")
    })

    it('locks the organization-wide level when told to', () => {
        expect(code).toContain('(lockOrgType && pt !== orgPermission.type)')
    })
})

describe('CreateApprovalEntry', () => {
    const code = source('./CreateApprovalEntry.vue')

    it('builds its input from the requirement rows, not one hardcoded requirement per role', () => {
        expect(code).toContain('toApprovalEntryInput(')
        expect(code).not.toMatch(/requiredNumberOfApprovals:\s*1,/)
    })
})

describe('ReleaseView approval matrix', () => {
    const code = source('./ReleaseView.vue')

    it('fills and locks cells from this user\'s own votes', () => {
        expect(code).toContain('return myVotes(approvalEvents, myUser?.uuid)')
        expect(code).toContain('isLockedByOwnVote(entryRequirementRoles.value[row.uuid] || []')
        expect(code).not.toContain('isDisabled = (givenApprovals.value[row.uuid][aid]?.length > 0)')
    })

    it('shows a column for every role of a requirement, not just the first', () => {
        expect(code).not.toContain('resolveApprovalRoles(ar)[0]')
    })
})
