// The Usage tab's breakdown (task RD2-8): the server's AgentBoard.spendBreakdown, whose parts add up
// to the window's total by construction. What is worked out here is only how it reads: the "By role"
// rows with the coordinator seat and the unattributed rest as rows of their own, the session rows,
// and whether there is anything to show at all.

export interface SpendRow {
    key: string
    label: string
    kind: 'role' | 'coordinator' | 'unattributed'
    costMicros: number
    closedHops: number | null
    openHops: number | null
    tokens: any | null
}

/**
 * "By role" as the tab shows it: each role, then the coordinator seat's line and the rows no hop
 * owns -- each only when it spent -- so the column adds up to the total above it.
 */
export function roleRows (b: any): SpendRow[] {
    if (!b) return []
    const out: SpendRow[] = (b.byRole ?? []).map((r: any) => ({
        key: `role:${r.role}`, label: r.role ?? '(unnamed role)', kind: 'role' as const, costMicros: r.costMicros ?? 0,
        closedHops: r.closedHops ?? 0, openHops: r.openHops ?? 0, tokens: r.tokens ?? null,
    }))
    if ((b.coordinatorEstimateMicros ?? 0) > 0) {
        out.push({ key: 'coordinator', label: 'coordinator estimate', kind: 'coordinator', costMicros: b.coordinatorEstimateMicros,
            closedHops: null, openHops: null, tokens: null })
    }
    if ((b.unattributedMicros ?? 0) > 0) {
        out.push({ key: 'unattributed', label: 'unattributed', kind: 'unattributed', costMicros: b.unattributedMicros,
            closedHops: null, openHops: null, tokens: null })
    }
    return out
}

/** "3 closed · 1 open", or "" for the lines that are not a role's. */
export function hopsLabel (r: SpendRow): string {
    if (r.kind !== 'role') return ''
    const parts: string[] = []
    if (r.closedHops) parts.push(`${r.closedHops} closed`)
    if (r.openHops) parts.push(`${r.openHops} open`)
    return parts.join(' · ') || '—'
}

/** Whether the window has any usage at all: the "no usage" texts show only then. */
export function breakdownEmpty (b: any): boolean {
    if (!b) return true
    return (b.byRole ?? []).length === 0 && (b.bySession ?? []).length === 0
        && !(b.coordinatorEstimateMicros ?? 0) && !(b.unattributedMicros ?? 0) && !(b.totalMicros ?? 0)
}

/** The parts' sum; equal to totalMicros by construction on the server. */
export function partsSum (b: any): number {
    return roleRows(b).reduce((n, r) => n + r.costMicros, 0)
}

/** The note shown when some usage had no price. */
export const LOWER_BOUND_NOTE = 'Some usage has no price; totals are a lower bound.'
