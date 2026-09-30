// Formatting and small derivations shared by the task page, the task drawer and their section
// components (components/task/*). Kept here rather than in one component so the page and the
// drawer cannot drift apart on how a time, a duration or a task's label reads.
import { statusWord, triggerWord } from './agentWords'
import { investigationRequiredRoles, isInvestigation } from './agentInvestigation'

const pad = (n: number) => String(n).padStart(2, '0')

function localDate (d: Date): string {
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * The one date format on board, task, session and document surfaces (task RD2-23, sweep UI-16):
 * "2026-09-27 15:36", ISO date and local time, with the seconds only within a minute of now, where they
 * tell two moments apart. {@link tsFull} is the title attribute's full timestamp.
 */
export function ts (iso: string | null | undefined, now: number = Date.now()): string {
    if (!iso) return '—'
    const d = new Date(iso)
    if (isNaN(d.getTime())) return '—'
    const base = `${localDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    return Math.abs(now - d.getTime()) < 60_000 ? `${base}:${pad(d.getSeconds())}` : base
}

/** The full timestamp behind {@link ts}, for a title attribute: "2026-09-27 15:36:12 UTC-04:00". Empty when unknown. */
export function tsFull (iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    if (isNaN(d.getTime())) return ''
    const off = -d.getTimezoneOffset()
    const zone = `UTC${off < 0 ? '-' : '+'}${pad(Math.floor(Math.abs(off) / 60))}:${pad(Math.abs(off) % 60)}`
    return `${localDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())} ${zone}`
}

/** A date alone, as {@link ts} writes it: "2026-09-27". */
export function tsDate (iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '—' : localDate(d)
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

/**
 * A task as a chip: its key (RD-42) when it has one (board-documents.md D12), else its tracker
 * number, else its title cut to 20 characters.
 */
export function taskLabel (t: any): string {
    if (t?.key) return String(t.key)
    if (t?.externalRef?.includes('#')) return '#' + t.externalRef.split('#').pop()
    const title = t?.title ?? 'task'
    return title.length > 20 ? title.slice(0, 19) + '…' : title
}

/**
 * The reference a board card leads with (task 36d0549e): the task's key (RD-42), else its tracker
 * number, else the fallback the card uses for a task with neither.
 */
export function cardRef (t: any, fallback: string): string {
    if (t?.key) return String(t.key)
    if (t?.externalRef?.includes('#')) return '#' + t.externalRef.split('#').pop()
    return fallback
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
    // An investigation needs its investigating role and its reviewer, not the board's pipeline (task RD4-12).
    const required = isInvestigation(task) ? investigationRequiredRoles(task)
        : (roles ?? []).filter((r: any) => r.active && r.necessity === 'REQUIRED').map((r: any) => r.name as string)
    return required
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

/**
 * A status-history row's words (task RD2-23, sweep UI-50): the transition in words, and, when routing wrote
 * why, "routing: findings decided; back to designer" in place of the bare trigger. Rows without a note -- a
 * person's action, or routing before notes were kept -- read as before.
 */
export function statusRowWords (c: { from?: string | null, to?: string | null, trigger?: string | null,
    actor?: { kind?: string | null, name?: string | null } | null, note?: string | null }):
    { arrow: string, routing: string | null, trigger: string } {
    const byRouting = c.actor?.kind === 'SYSTEM' && c.actor?.name === 'routing'
    return {
        arrow: `${c.from ? statusWord(c.from) : '·'} → ${statusWord(c.to)}`,
        routing: byRouting && c.note ? `routing: ${c.note}` : null,
        trigger: triggerWord(c.trigger),
    }
}

/**
 * The required roles still to pass, as the line under the task's status (task RD2-23, sweep UI-32):
 * "Required sign-offs: reviewer ✗ · coder ✗". Null when none is missing. It sat under "Human review" as
 * chips, though it says nothing about human review.
 */
export function requiredSignOffsLine (missing: string[]): string | null {
    return missing.length ? `Required sign-offs: ${missing.map(m => `${m} ✗`).join(' · ')}` : null
}
