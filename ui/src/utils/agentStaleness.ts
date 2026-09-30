// A board's staleness block (task RD3-4): minutes each, a blank threshold off, and the block replaced
// whole when it is saved. The sweep only ALERTs; a person releases a stalled assignment by hand.

export const STALENESS_KEYS = ['roleUnstaffedMinutes', 'hopNoProgressMinutes', 'deliveryStuckMinutes',
    'seatSilentMinutes', 'repeatMinutes', 'investigationOverdueMinutes'] as const
export type StalenessKey = typeof STALENESS_KEYS[number]
export type Staleness = Partial<Record<StalenessKey, number | null>>

/** What each threshold means, for the form's labels and tooltips. */
export const STALENESS_FIELDS: { key: StalenessKey, label: string, help: string }[] = [
    { key: 'roleUnstaffedMinutes', label: 'role unstaffed, min', help: 'A task queued for a role this long while no session of the role polled the board.' },
    { key: 'hopNoProgressMinutes', label: 'hop stalled, min', help: 'A task assigned this long with nothing from its holder: no document, sign-off, question or usage report.' },
    { key: 'deliveryStuckMinutes', label: 'delivery stuck, min', help: 'A task delivering this long with a linked PR not delivered.' },
    { key: 'seatSilentMinutes', label: 'seat silent, min', help: 'A task waiting on the coordinator this long while the seat is held.' },
    { key: 'repeatMinutes', label: 're-alert after, min', help: 'How long a standing breach stays quiet before it is alerted again; blank is 240.' },
    { key: 'investigationOverdueMinutes', label: 'investigation overdue, min', help: 'An investigation not completed this long after its deadline; 0 alerts as soon as the deadline passes.' }
]

/** Thresholds counted past a deadline rather than from a state (task RD4-12): 0 is a threshold, not off. */
const ZERO_ALLOWED: StalenessKey[] = ['investigationOverdueMinutes']

/** The form's draft: each threshold as a number or null. */
export function stalenessDraftOf (block: Staleness | null | undefined): Record<StalenessKey, number | null> {
    const out = {} as Record<StalenessKey, number | null>
    for (const k of STALENESS_KEYS) out[k] = block?.[k] ?? null
    return out
}

/**
 * Why the draft cannot be saved, or '' when it can: each threshold is a whole number of minutes, at least 1, or at
 * least 0 for the investigation deadline's grace.
 */
export function stalenessProblem (draft: Staleness): string {
    for (const k of STALENESS_KEYS) {
        const v = draft[k]
        const floor = ZERO_ALLOWED.includes(k) ? 0 : 1
        if (v != null && (!Number.isInteger(v) || v < floor)) {
            return floor === 0 ? `${k} cannot be negative; 0 alerts at the deadline, blank turns it off`
                : `${k} must be at least 1 minute; leave it blank to turn it off`
        }
    }
    return ''
}

/**
 * What the form sends as settings.staleness: undefined when nothing changed (left out, kept), null when
 * every threshold was emptied (the rules off), else the whole block.
 */
export function stalenessPatch (original: Staleness | null | undefined, draft: Staleness): Staleness | null | undefined {
    const before = stalenessDraftOf(original)
    const after = stalenessDraftOf(draft)
    if (STALENESS_KEYS.every(k => before[k] === after[k])) return undefined
    if (STALENESS_KEYS.every(k => after[k] == null)) return null
    const out: Staleness = {}
    for (const k of STALENESS_KEYS) if (after[k] != null) out[k] = after[k]
    return out
}
