// Presentation helpers for agent usage: token counts, derived cost, and the
// two honesty badges that go with them.
//
// Pure and dependency-free so the arithmetic can be tested without mounting a
// component. Every number here arrives from the server already summed; nothing
// in this file re-derives a total, because a UI that recomputes money from
// parts will eventually disagree with the server that priced it.

/** Server shape: UsageTotals / HopUsage. Fields are nullable throughout. */
export interface UsageTotals {
    inputTokens?: number | null
    outputTokens?: number | null
    cacheReadTokens?: number | null
    cacheWriteTokens?: number | null
    requests?: number | null
    turns?: number | null
    toolCalls?: number | null
    wallSeconds?: number | null
    reports?: number | null
    derivedCostMicros?: number | null
    priceVersions?: string[] | null
    costComplete?: boolean | null
    byModel?: UsageByModel[] | null
}

export interface UsageByModel {
    model?: string | null
    modelName?: string | null
    inputTokens?: number | null
    outputTokens?: number | null
    cacheReadTokens?: number | null
    cacheWriteTokens?: number | null
    requests?: number | null
    turns?: number | null
    derivedCostMicros?: number | null
}

/**
 * Total tokens across every class.
 *
 * Cache reads are included, and they dominate: on a long cached session they
 * run two orders of magnitude above uncached input. Showing an "input tokens"
 * figure alone makes a session look almost free, which is the opposite of what
 * the number is for.
 */
export function totalTokens (u?: UsageTotals | null): number {
    if (!u) return 0
    return (u.inputTokens ?? 0) + (u.outputTokens ?? 0) +
        (u.cacheReadTokens ?? 0) + (u.cacheWriteTokens ?? 0)
}

/** Compact token count: 1_234_567 -> "1.23M". */
export function formatTokens (n?: number | null): string {
    const v = n ?? 0
    if (v >= 1_000_000_000) return (v / 1_000_000_000).toFixed(2) + 'B'
    if (v >= 1_000_000) return (v / 1_000_000).toFixed(2) + 'M'
    if (v >= 1_000) return (v / 1_000).toFixed(1) + 'k'
    return String(v)
}

/**
 * Money, from USD micros.
 *
 * Returns null when the server derived no cost, and the caller must render that
 * as "no price" rather than as $0.00. The two mean opposite things: $0.00 is a
 * priced session that cost nothing, while null is an unpriced one whose cost is
 * unknown. Collapsing them would quietly report a catalogue with no pricing
 * entries as a fleet that runs for free.
 */
export function formatCostMicros (micros?: number | null): string | null {
    if (micros === null || micros === undefined) return null
    const dollars = micros / 1_000_000
    if (dollars > 0 && dollars < 0.01) return '<$0.01'
    return '$' + dollars.toFixed(dollars >= 100 ? 0 : 2)
}

/** What to show where a cost goes, including the null case. */
export function costLabel (u?: UsageTotals | null): string {
    const formatted = formatCostMicros(u?.derivedCostMicros)
    if (formatted === null) return 'no price'
    // costComplete false means SOME row priced and some did not, so the figure
    // is a floor rather than a total. Saying so inline is the difference
    // between an understatement and a lie.
    return u?.costComplete === false ? '≥ ' + formatted : formatted
}

export type CostConfidence = 'PRICED' | 'PARTIAL' | 'UNPRICED'

export function costConfidence (u?: UsageTotals | null): CostConfidence {
    if (u?.derivedCostMicros === null || u?.derivedCostMicros === undefined) return 'UNPRICED'
    return u?.costComplete === false ? 'PARTIAL' : 'PRICED'
}

/** Session usage completeness, as the server recorded it. */
export type Completeness = 'COMPLETE' | 'INCOMPLETE' | 'NONE' | 'UNKNOWN'

export interface Badge {
    label: string
    type: 'success' | 'warning' | 'error' | 'info' | 'default'
    tooltip: string
}

/**
 * The badges that qualify a usage figure.
 *
 * A number with no provenance invites more trust than it has earned, so
 * anything that makes these totals partial or suspect gets its own badge rather
 * than a footnote: incomplete reporting, an unpriced or part-priced total, and
 * a session whose observed model disagreed with what it declared.
 */
export function usageBadges (
    completeness?: string | null,
    modelMismatch?: boolean | null,
    totals?: UsageTotals | null
): Badge[] {
    const out: Badge[] = []

    switch (completeness) {
    case 'COMPLETE':
        break // The expected case earns no badge; a clean row should look clean.
    case 'INCOMPLETE':
        out.push({
            label: 'partial usage',
            type: 'warning',
            tooltip: 'The session was closed by the idle sweeper rather than by its agent, so its ' +
                'final usage reports may never have arrived. Treat these totals as a lower bound.',
        })
        break
    case 'NONE':
        out.push({
            label: 'no usage reported',
            type: 'default',
            tooltip: 'This session reported no usage at all — most often an agent running without ' +
                'the usage hooks installed.',
        })
        break
    default:
        break
    }

    if (modelMismatch) {
        out.push({
            label: 'model mismatch',
            type: 'warning',
            tooltip: 'The model observed in this session\'s usage reports is not the one it declared ' +
                'at registration. Cost is derived from what actually ran.',
        })
    }

    const confidence = costConfidence(totals)
    // Only worth a badge when there is usage to price; an empty session showing
    // "unpriced" is noise, not information.
    if ((totals?.reports ?? 0) > 0) {
        if (confidence === 'UNPRICED') {
            out.push({
                label: 'unpriced',
                type: 'info',
                tooltip: 'No pricing entry in this org\'s model catalogue covers these rows, so no ' +
                    'cost could be derived. Add pricing under Settings → Models.',
            })
        } else if (confidence === 'PARTIAL') {
            out.push({
                label: 'partly priced',
                type: 'info',
                tooltip: 'Some rows had no applicable pricing entry, so the cost shown is a lower bound.',
            })
        }
    }
    return out
}

/** Rows for the by-model breakdown, largest spender first. */
export function byModelRows (u?: UsageTotals | null): UsageByModel[] {
    const rows = (u?.byModel ?? []).filter(Boolean) as UsageByModel[]
    return [...rows].sort((a, b) => {
        // Cost first where both are priced, tokens otherwise: sorting an
        // unpriced fleet by a null cost would order it arbitrarily.
        const ac = a.derivedCostMicros, bc = b.derivedCostMicros
        if (ac != null && bc != null && ac !== bc) return bc - ac
        return totalTokens(b as UsageTotals) - totalTokens(a as UsageTotals)
    })
}

/**
 * What to show for a model in a breakdown.
 *
 * The rollup carries the display name beside the uuid; falling back to a shortened uuid rather than
 * the full one keeps an unnameable row (a model deleted out from under its usage) legible instead
 * of letting a 36-character id take over the column.
 */
export function modelDisplayName (row?: UsageByModel | null): string {
    if (row?.modelName) return row.modelName
    if (row?.model) return String(row.model).slice(0, 8) + '…'
    return '—'
}

/** Preset windows for the board and org period selectors. */
export const USAGE_PERIODS = [
    { label: 'Last 24 hours', hours: 24 },
    { label: 'Last 7 days', hours: 24 * 7 },
    { label: 'Last 30 days', hours: 24 * 30 },
    { label: 'Last 90 days', hours: 24 * 90 },
]

/**
 * Window bounds for a preset, as ISO strings.
 *
 * `now` is injectable so the caller's clock is testable; defaulting to
 * Date.now() inside the function would make every test of it time-dependent.
 */
export function periodRange (hours: number, now: number = Date.now()): { from: string, to: string } {
    return {
        from: new Date(now - hours * 3600 * 1000).toISOString(),
        to: new Date(now).toISOString(),
    }
}
