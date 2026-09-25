// An org admin's verbs on a task and a session that the API had and the UI did not (task
// 6fdc5a37): a task's required strength, an operator hold, and force-closing a session. Which
// apply, and what each sends; pure, so the specs need no store.

/** The statuses the server places a hold from; ASSIGNED and later are refused (placeHold). */
export const HOLDABLE_STATUSES = ['PENDING_INTAKE', 'QUEUED', 'AWAITING_COORDINATOR']

export function canPlaceHold (task: any, admin: boolean): boolean {
    return admin && HOLDABLE_STATUSES.includes(task?.status)
}

/** The hold to place, or null while there is no reason: the server refuses a blank one. */
export function holdPayload (task: any, reason: string | null | undefined): { task: any, reason: string } | null {
    const r = (reason ?? '').trim()
    return r ? { task, reason: r } : null
}

/** The strength floor of the role the task is with, when that role has one. */
export function roleFloor (task: any, roles: any[] | null | undefined): number | null {
    const rc = (roles ?? []).find((r: any) => r?.name === task?.role)
    return typeof rc?.requiredStrength === 'number' ? rc.requiredStrength : null
}

/** What the strength box shows when the task has none of its own. */
export function strengthPlaceholder (task: any, roles: any[] | null | undefined): string {
    const floor = roleFloor(task, roles)
    return floor == null ? 'none' : `role floor ${floor}`
}

/**
 * The strength to send for a draft, or undefined when there is nothing to send: no draft, a
 * negative one, or the value the task already has. Rounded to the server's two decimals.
 */
export function strengthToSet (task: any, draft: number | null | undefined): number | undefined {
    if (draft == null || !Number.isFinite(draft) || draft < 0) return undefined
    const v = Math.round(draft * 100) / 100
    return v === task?.requiredStrength ? undefined : v
}

/** An admin may force-close an OPEN session: its tasks go back to the coordinator. */
export function canForceClose (session: any, admin: boolean): boolean {
    return admin && session?.status === 'OPEN'
}
