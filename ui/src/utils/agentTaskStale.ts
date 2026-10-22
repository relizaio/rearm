/**
 * A task's staleness marks (TPF-69): AgentTask.stale, computed live by the server from the board's staleness
 * block with the rules the sweep's ALERTs use. The words, the chip, the kanban's "stale only" filter and its
 * URL key all live here, so the card (a render function) is pinned by source text and the words by a spec.
 */
import { staleTitle } from './agentsView'

/** The words a rule reads as on the chip; an unknown rule prints its own name. */
export const STALE_RULE_WORDS: Record<string, string> = {
    hopNoProgress: 'no progress',
    deliveryStuck: 'delivery stuck',
    investigationOverdue: 'overdue',
}

/** A span as the ALERT text rounds it: minutes below two hours, whole hours from there. */
export function staleAgo (minutes: number | null | undefined): string {
    const n = Math.max(0, Math.floor(Number(minutes ?? 0)))
    return n < 120 ? `${n} min` : `${Math.floor(n / 60)} h`
}

export interface StaleChip {
    /** "stale: <words of the first mark> <span>", plus " +<n>" when more marks follow. */
    label: string
    /** Every mark's "rule: message" on its own line. */
    tooltip: string
    /** The rules, in the server's order. */
    rules: string[]
}

/** The chip of a stale task, or null when the task carries no mark (an older backend, a board without a block). */
export function staleChip (task: any): StaleChip | null {
    const marks: any[] = Array.isArray(task?.stale) ? task.stale.filter((m: any) => m) : []
    if (!marks.length) return null
    const first = marks[0]
    const words = STALE_RULE_WORDS[first.rule] ?? String(first.rule ?? '')
    const more = marks.length > 1 ? ` +${marks.length - 1}` : ''
    return {
        label: `stale: ${words} ${staleAgo(first.minutes)}${more}`,
        tooltip: staleTitle(marks) ?? '',
        rules: marks.map(m => String(m.rule ?? '')),
    }
}

/** Whether the task carries a staleness mark. */
export function isStale (task: any): boolean {
    return staleChip(task) !== null
}

/** The "stale only" filter: every task passes when it is off, only a marked task when it is on. */
export function passesStale (task: any, staleOnly: boolean): boolean {
    return !staleOnly || isStale(task)
}

/** The "stale only" filter the URL carries (?stale=1). */
export function staleFromQuery (query: Record<string, any> | null | undefined): boolean {
    return query?.stale === '1'
}

/** The query with the "stale only" filter set; off drops the key. */
export function withStaleQuery (query: Record<string, any> | null | undefined, on: boolean): Record<string, any> {
    const q: Record<string, any> = { ...(query ?? {}) }
    if (on) q.stale = '1'
    else delete q.stale
    return q
}
