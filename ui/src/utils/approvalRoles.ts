import type { ApprovalRole } from './commonTypes'

/**
 * Resolve the approval roles of an approval requirement for display.
 *
 * The backend expands `allowedApprovalRoleIds` against the organization's
 * defined approval roles and silently drops ids that are not (or no longer)
 * defined there — e.g. after an org admin deletes a role that an approval
 * entry still references, or on imported data. In that case
 * `allowedApprovalRoleIdExpanded` comes back empty and dereferencing `[0]`
 * crashes the view.
 *
 * Fall back to the raw role ids as their own display name: approvals keep
 * working through such roles (the wire format and the backend's
 * authorization both use the raw id string), they just render without a
 * friendly display name.
 */
export function resolveApprovalRoles(ar: {
    allowedApprovalRoleIdExpanded?: ApprovalRole[] | null,
    allowedApprovalRoleIds?: string[] | null
}): ApprovalRole[] {
    if (ar.allowedApprovalRoleIdExpanded && ar.allowedApprovalRoleIdExpanded.length) {
        return ar.allowedApprovalRoleIdExpanded
    }
    return (ar.allowedApprovalRoleIds ?? []).map(id => ({ id, displayView: id }))
}
