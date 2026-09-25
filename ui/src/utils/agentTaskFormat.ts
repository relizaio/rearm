// Formatting and small derivations shared by the task page, the task drawer and their section
// components (components/task/*). Kept here rather than in one component so the page and the
// drawer cannot drift apart on how a time, a duration or a task's label reads.

export function ts (iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '—' : d.toLocaleString('en-CA', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
    })
}

/** From one instant to another (or to now), coarsened: minutes, then hours, then days. */
export function dur (from: string | null | undefined, to: string | null | undefined): string {
    if (!from) return ''
    const a = new Date(from).getTime()
    const b = to ? new Date(to).getTime() : Date.now()
    if (isNaN(a) || isNaN(b) || b < a) return ''
    const mins = Math.round((b - a) / 60000)
    if (mins < 60) return `${mins}m`
    const h = Math.floor(mins / 60)
    return h < 48 ? `${h}h ${mins % 60}m` : `${Math.floor(h / 24)}d`
}

export function shortId (u: string | null | undefined): string {
    return u ? u.slice(0, 8) : ''
}

/** A task as a chip: its tracker number when it has one, else its title cut to 20 characters. */
export function taskLabel (t: any): string {
    if (t?.externalRef?.includes('#')) return '#' + t.externalRef.split('#').pop()
    const title = t?.title ?? 'task'
    return title.length > 20 ? title.slice(0, 19) + '…' : title
}

export function statusTone (s: string | null | undefined): 'success' | 'info' | 'error' | 'warning' | 'default' {
    if (s === 'COMPLETED') return 'success'
    if (s === 'DELIVERING') return 'info'
    if (s === 'ON_HOLD' || s === 'CANCELLED') return 'error'
    if (s === 'ASSIGNED') return 'warning'
    return 'default'
}

export function agentName (names: Record<string, string> | null | undefined, uuid: string | null | undefined): string {
    if (!uuid) return '—'
    return names?.[uuid] ?? shortId(uuid)
}

/**
 * The name of a role config, for the question stack.
 *
 * Frames carry role uuids because a name is not an identity -- the same reason the hop records
 * carry one. A human reading "coder asked designer" wants neither uuid, so this resolves from the
 * roles already loaded and falls back to a short uuid when a role has been removed.
 */
export function roleName (roles: any[] | null | undefined, uuid: string | null | undefined): string {
    if (!uuid) return ''
    const rc = (roles ?? []).find((r: any) => r.uuid === uuid)
    return rc?.name ?? uuid.slice(0, 8)
}

export function isTerminal (task: any): boolean {
    return task?.status === 'COMPLETED' || task?.status === 'CANCELLED'
}

/**
 * Active REQUIRED roles whose most recent sign-off on this task is not PASSED -- mirrors the
 * server-side completion gate so the gap is visible before "done". A parent task completes on its
 * children, and a terminal one has nothing left to gate.
 */
export function missingRequiredRoles (task: any, roles: any[] | null | undefined): string[] {
    if (!task || isTerminal(task) || task.childTasks?.length) return []
    return (roles ?? [])
        .filter((r: any) => r.active && r.necessity === 'REQUIRED')
        .map((r: any) => r.name as string)
        .filter((role: string) => {
            const last = [...(task.signOffs ?? [])].reverse()
                .find((s: any) => (s.role ?? '').toLowerCase() === role.toLowerCase())
            return !last || last.outcome !== 'PASSED'
        })
}

export type HopEntry = { kind: 'signoff' | 'return', rec: any, at: string | null }

/** Sign-offs and returns interleaved chronologically -- the task's hop log. */
export function hopHistory (task: any): HopEntry[] {
    if (!task) return []
    const rows: HopEntry[] = [
        ...(task.signOffs ?? []).map((rec: any) => ({ kind: 'signoff' as const, rec, at: rec.signedOffAt })),
        ...(task.returns ?? []).map((rec: any) => ({ kind: 'return' as const, rec, at: rec.returnedAt })),
    ]
    return rows.sort((a, b) => String(a.at ?? '').localeCompare(String(b.at ?? '')))
}

/** The route of a task's page. */
export function taskPagePath (uuid: string): string {
    return `/aiAgentTask/${uuid}`
}
