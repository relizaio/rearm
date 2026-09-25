// The words a task card, the task drawer and the table view say about a task (task 562ac668),
// in one place so the three agree. Pure, so each rule has a spec.

const TERMINAL = ['COMPLETED', 'CANCELLED']
const HISTORY = ['AWAITING_COORDINATOR', 'ON_HOLD', 'DELIVERING', 'COMPLETED', 'CANCELLED']

export interface SubtaskProgress {
    done: number
    total: number
    /** the children not yet COMPLETED or CANCELLED, as found in the board's task list */
    open: any[]
}

/**
 * How far a split parent's children have got. COMPLETED and CANCELLED both count as done -- the
 * board completes the parent when neither kind is left open. A child missing from the list (not
 * loaded) counts as open, never as done.
 */
export function subtaskProgress (task: any, tasks: any[] | null | undefined): SubtaskProgress {
    const ids: string[] = task?.childTasks ?? []
    const byId = new Map((tasks ?? []).map((t: any) => [t.uuid, t]))
    const open: any[] = []
    let done = 0
    for (const id of ids) {
        const c = byId.get(id)
        if (c && TERMINAL.includes(c.status)) done++
        else open.push(c ?? { uuid: id, status: 'UNKNOWN' })
    }
    return { done, total: ids.length, open }
}

/** The card tag for a split parent, or null when the task has no children. */
export function subtaskTag (task: any, tasks: any[] | null | undefined):
    { text: string, type: 'warning' | 'success' | 'info' } | null {
    const p = subtaskProgress(task, tasks)
    if (p.total === 0) return null
    if (TERMINAL.includes(task?.status)) return { text: `${p.total} subtasks`, type: 'info' }
    if (p.done === p.total) return { text: 'subtasks done', type: 'success' }
    return { text: `waiting on subtasks · ${p.done} of ${p.total} done`, type: 'warning' }
}

export interface RoleTag {
    text: string
    /** current: the task is with this role now; history: it is the role of the last hop */
    kind: 'current' | 'history'
    tooltip: string | null
}

/**
 * The role tag. On QUEUED and ASSIGNED the role is where the task is; on every other status it is
 * the last hop's role, and says so, because the board -- not that role -- decides what happens next.
 */
export function roleTagFor (task: any): RoleTag | null {
    const role = task?.assignment?.role ?? task?.role
    if (!role || task?.status === 'PENDING_INTAKE') return null
    if (task.status === 'QUEUED') {
        return { text: `${role}${task.orderIndex != null ? ' · #' + task.orderIndex : ''}`, kind: 'current', tooltip: null }
    }
    if (task.status === 'ASSIGNED') return { text: role, kind: 'current', tooltip: null }
    if (HISTORY.includes(task.status)) {
        return { text: `last: ${role}`, kind: 'history', tooltip: 'The role of the last hop; the board decides the next one.' }
    }
    return { text: role, kind: 'current', tooltip: null }
}

/**
 * The tracker line under a task's title: its tracker ref; "draft (no tracker ref yet)" only on a
 * board with sources, where a missing ref is the intake state; nothing on a board without sources,
 * where no task ever has one and the title above already names it.
 */
export function refLabel (task: any, boardHasSources: boolean): string | null {
    if (task?.externalRef) return String(task.externalRef).replace(/^github:/, '')
    return boardHasSources ? 'draft (no tracker ref yet)' : null
}

/** The table's short ref: "#123", or "draft" on a board with sources, else a dash. */
export function shortRef (task: any, boardHasSources: boolean): string {
    if (task?.externalRef?.includes('#')) return '#' + String(task.externalRef).split('#').pop()
    if (task?.externalRef) return String(task.externalRef).replace(/^github:/, '')
    return boardHasSources ? 'draft' : '—'
}
