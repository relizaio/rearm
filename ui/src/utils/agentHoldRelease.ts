// Releasing a hold (task 4c566d0d). A hold routing placed because a loop stopped -- no progress, or
// the cycle cap -- is released past that stop once, to the role routing would pick or to one the
// person names. Pure, so the specs need no store.

/**
 * A hold routing placed for no progress or the cycle cap (not the budget, not a person's hold). The
 * hold records its stop (task c0a2134c), which an escalation keeps while the holder changes; a
 * hold from before that is read by its holder and reason.
 */
export function isLoopStopHold (hold: any): boolean {
    if (!hold) return false
    if (hold.stop) return hold.stop === 'NO_PROGRESS' || hold.stop === 'CYCLE_CAP'
    if (hold.heldBy?.kind !== 'SYSTEM' || hold.heldBy?.name !== 'routing') return false
    const reason: string = hold.reason ?? ''
    return reason.startsWith('stopped by no progress') || reason.startsWith('stopped by cycle cap')
}

/**
 * Who may release a hold, in the banner's words (task c0a2134c): the first no-progress or cycle-cap
 * stop of its kind is the coordinator's to release once, and a person may release it too; an
 * OPERATOR hold is the operator's alone. Null where the banner says it another way.
 */
export function holdReleaseNote (hold: any): string | null {
    if (!hold || hold.kind === 'HUMAN_GATE' || hold.kind === 'QUESTION') return null
    if (hold.level === 'OPERATOR') return 'operator only'
    return isLoopStopHold(hold) ? 'coordinator may release once' : null
}

/** Whether a person gets the release controls: on an OPERATOR hold, and on a stop the coordinator may release. */
export function personMayRelease (hold: any): boolean {
    if (!hold || hold.kind === 'HUMAN_GATE' || hold.kind === 'QUESTION') return false
    return hold.level === 'OPERATOR' || isLoopStopHold(hold)
}

/** The roles a release may name: the board's active agent roles, by name, in board order. */
export function releaseRoleOptions (roles: any[] | null | undefined): { label: string, value: string }[] {
    return (roles ?? [])
        .filter((r: any) => r?.active !== false && r?.kind !== 'HUMAN' && r?.name)
        .slice()
        .sort((a: any, b: any) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
        .map((r: any) => ({ label: r.name, value: r.name }))
}

/** What a release sends: the note and, only when one is picked, the role. */
export function releasePayload (task: any, note: string | null | undefined, role: string | null | undefined):
    { task: any, note: string, role?: string } {
    const r = (role ?? '').trim()
    return r ? { task, note: note ?? '', role: r } : { task, note: note ?? '' }
}

/** The button's words: where the release sends the task. */
export function releaseLabel (stop: boolean, role: string | null | undefined): string {
    if (role) return `Release to ${role}`
    return stop ? 'Release past the stop' : 'Operator release'
}
