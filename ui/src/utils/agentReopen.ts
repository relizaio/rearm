// Reopening a completed task to a role (task 56116a77): who is offered the control, which roles it
// offers, and what it sends. Pure, so the drawer's rules are testable without mounting it.

/** An org admin: the server's check for agentTaskReopen, mirrored so the control is not offered in vain. */
export function isOrgAdmin (permissions: any[] | null | undefined, orgUuid: string | null | undefined): boolean {
    return (permissions ?? []).some((p: any) => p?.org === orgUuid && p?.scope === 'ORGANIZATION' && p?.type === 'ADMIN')
}

/**
 * The roles a task can be reopened to: the board's active roles in board order, and only for a
 * COMPLETED or DELIVERING task seen by an admin. A cancelled task is never reopened -- it is
 * registered again.
 */
export function reopenRoleOptions (task: any, roles: any[] | null | undefined,
    canReopen: boolean): { label: string, value: string }[] {
    if (!canReopen || !['COMPLETED', 'DELIVERING'].includes(task?.status)) return []
    return (roles ?? [])
        .filter((r: any) => r?.active !== false && r?.name)
        .slice()
        .sort((a: any, b: any) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
        .map((r: any) => ({ label: r.name, value: r.name }))
}

/** The mutation's variables, or null until a role is chosen and a reason given. */
export function reopenPayload (task: any, role: string | null | undefined,
    reason: string | null | undefined): { taskUuid: string, role: string, reason: string } | null {
    const why = (reason ?? '').trim()
    if (!task?.uuid || !role || !why) return null
    return { taskUuid: task.uuid, role, reason: why }
}
