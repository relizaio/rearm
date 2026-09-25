// Budgets in the board and role forms, and budget against spend on the board (task 40f270be).
// People type dollars; the server stores USD micros. Kept here, with a spec, so the conversion and
// the chip's thresholds are tested rather than inlined in a component.
import { formatCostMicros } from './agentUsage'

/** Dollars as typed into a form, to micros; blank stays blank. Rounded to the micro. */
export function dollarsToMicros (dollars: number | null | undefined): number | null {
    if (dollars === null || dollars === undefined || Number.isNaN(dollars)) return null
    return Math.round(dollars * 1_000_000)
}

/** Micros for a form field, in dollars; blank stays blank. */
export function microsToDollars (micros: number | null | undefined): number | null {
    if (micros === null || micros === undefined) return null
    return micros / 1_000_000
}

export type BudgetChip = { label: string, type: 'default' | 'success' | 'warning' | 'error', percent: number | null }

/**
 * Spend against the board's budget: warning from the soft-alert line (80% unless the board sets
 * another), error at 100%. With no budget, just the spend.
 */
export function budgetChip (spentMicros: number | null | undefined, budgetMicros: number | null | undefined,
    softAlertPercent?: number | null): BudgetChip {
    const spent = formatCostMicros(spentMicros ?? 0) ?? '$0.00'
    if (budgetMicros === null || budgetMicros === undefined) {
        return { label: `spent ${spent}, no budget`, type: 'default', percent: null }
    }
    const budget = formatCostMicros(budgetMicros) ?? '$0.00'
    const percent = budgetMicros > 0 ? Math.round(((spentMicros ?? 0) / budgetMicros) * 100) : 100
    const soft = softAlertPercent ?? 80
    const type = percent >= 100 ? 'error' : percent >= soft ? 'warning' : 'success'
    return { label: `spent ${spent} of ${budget} (${percent}%)`, type, percent }
}

/** The board settings the form edits, in the order it shows them. */
export const BOARD_SETTING_KEYS = ['budgetMicros', 'softAlertPercent', 'cycleCap', 'noProgressRepeatsToStop',
    'blockingPriority', 'completionPriority', 'coordinatorStopRelease'] as const
export type BoardSettingKey = typeof BOARD_SETTING_KEYS[number]

/**
 * What the form sends as AgentBoardInput.settings: only what changed. A value the person emptied is
 * sent as null, which clears it; one left alone is left out, which keeps it. Null when nothing changed.
 */
export function settingsPatch (original: Partial<Record<BoardSettingKey, number | boolean | null>> | null | undefined,
    draft: Partial<Record<BoardSettingKey, number | boolean | null>>): Record<string, number | boolean | null> | null {
    const patch: Record<string, number | boolean | null> = {}
    for (const k of BOARD_SETTING_KEYS) {
        const before = original?.[k] ?? null
        const after = draft[k] ?? null
        if (before !== after) patch[k] = after
    }
    return Object.keys(patch).length ? patch : null
}

/**
 * hopBudgetMicros for the role input: the value when set, null when a set allowance was emptied
 * (the server removes it), undefined when it was never set and is still blank (left out).
 */
export function hopBudgetInput (originalMicros: number | null | undefined, draftDollars: number | null | undefined):
    number | null | undefined {
    const micros = dollarsToMicros(draftDollars)
    if (micros !== null) return micros
    return originalMicros !== null && originalMicros !== undefined ? null : undefined
}

/** Whether the drawer's budget field differs from the task's budget (task 6f1b348d); blank against none is no change. */
export function budgetChanged (budgetMicros: number | null | undefined, draftDollars: number | null | undefined): boolean {
    return (budgetMicros ?? null) !== dollarsToMicros(draftDollars)
}
