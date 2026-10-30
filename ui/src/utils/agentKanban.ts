// The kanban's columns at a laptop's width (task RD2-13, sweep UI-21, UI-36): each column says how many cards it
// holds, an empty one folds to its header until opened, and a strip that still overflows says how many columns
// sit past the edge. Pure, so the rules are testable without a layout.

/** The narrowest a column with cards gets. Six at 1280, with the gaps, fit without scrolling. */
export const COL_MIN_WIDTH = 180

/** Whether a column folds to its header: it holds no cards and the person has not opened it. */
export function columnFolded (key: string, count: number, opened: ReadonlySet<string>): boolean {
    return count === 0 && !opened.has(key)
}

/** A column header's words: its name and its count, "On hold · 2". */
export function columnHead (label: string, count: number): string {
    return `${label} · ${count}`
}

interface Box { offsetLeft: number, offsetWidth: number }

/** How many columns end past the strip's visible right edge. */
export function columnsPastEdge (columns: Box[], scrollLeft: number, clientWidth: number): number {
    const edge = scrollLeft + clientWidth
    return columns.filter(c => c.offsetLeft + c.offsetWidth > edge + 1).length
}

/** "2 more lanes →", or null when every column shows. */
export function moreLanesHint (past: number): string | null {
    return past > 0 ? `${past} more lane${past === 1 ? '' : 's'} →` : null
}

/** The hint once the strip is scrolled to its end: it takes the strip back to its first column. */
export const BACK_LANES_HINT = '← back'

/** Where a strip hint scrolls its strip when clicked. */
export type StripDirection = 'end' | 'start'

export interface StripHint { label: string, toward: StripDirection }

/**
 * A lane's hint above its strip: "2 more lanes ->" while columns sit past the right edge, which scrolls to the end;
 * "<- back" once the strip is scrolled off its start with nothing past the edge, which scrolls back; else none.
 */
export function stripHint (past: number, scrolledFromStart: boolean): StripHint | null {
    const more = moreLanesHint(past)
    if (more) return { label: more, toward: 'end' }
    return scrolledFromStart ? { label: BACK_LANES_HINT, toward: 'start' } : null
}

/** What an empty board says under its headers. */
export const EMPTY_BOARD_HINT = 'No tasks yet. + New task, or agents register tasks via the coordinator.'

export interface StripMeasure {
    /** The lane's strip as Vue hands it to a :ref callback: set when it mounts, null when it goes. */
    boardEl (key: string, el: any): void
    /** Re-measure every strip: on scroll, on resize, after the tasks change. */
    measure (): void
    /** Per lane, how many columns end past the strip's edge. */
    pastEdge: { value: Record<string, number> }
    /** Per lane, whether the strip is scrolled away from its start. */
    scrolled: { value: Record<string, boolean> }
    /** Scroll a lane's strip smoothly to its far end or back to its start; its scroll event re-measures it. */
    scrollStrip (key: string, toward: StripDirection): void
}

/**
 * Each lane's strip, and how many of its columns sit past its edge. A strip is measured when it mounts
 * (RD2-13 run 1, T-1): opened on another tab first, the kanban's strip mounts after the tasks arrived, so a
 * watch on the tasks alone measured nothing.
 */
export function stripMeasure (after: (fn: () => void) => void, pastEdge: { value: Record<string, number> },
    scrolled: { value: Record<string, boolean> } = { value: {} }): StripMeasure {
    const els = new Map<string, HTMLElement>()
    function measure () {
        const out: Record<string, number> = {}
        const off: Record<string, boolean> = {}
        for (const [key, el] of els) {
            out[key] = columnsPastEdge(Array.from(el.children) as any[], el.scrollLeft, el.clientWidth)
            off[key] = el.scrollLeft > 0
        }
        pastEdge.value = out
        scrolled.value = off
    }
    function scrollStrip (key: string, toward: StripDirection) {
        const el = els.get(key)
        if (!el) return
        el.scrollTo({ left: toward === 'end' ? el.scrollWidth : 0, behavior: 'smooth' })
    }
    function boardEl (key: string, el: any) {
        if (!el) {
            els.delete(key)
            return
        }
        const fresh = els.get(key) !== el
        els.set(key, el as HTMLElement)
        if (fresh) after(measure)
    }
    return { boardEl, measure, pastEdge, scrolled, scrollStrip }
}

/** How many cards a column shows before its "Show N more". */
export const LANE_CARDS = 5

/** A column's cards as shown: the first LANE_CARDS until the person expands it, then every one. */
export function shownCards<T> (cards: T[], expanded: boolean, limit = LANE_CARDS): T[] {
    return expanded || cards.length <= limit ? cards : cards.slice(0, limit)
}

/** The column's toggle: "Show 3 more" while it is cut, "Show fewer" once expanded, null when every card fits. */
export function moreCardsLabel (count: number, expanded: boolean, limit = LANE_CARDS): string | null {
    if (count <= limit) return null
    return expanded ? 'Show fewer' : `Show ${count - limit} more`
}

/** The key a column's expansion is kept under: one per column of each lane, so lanes expand apart. */
export function columnKey (lane: string, column: string): string {
    return `${lane}|${column}`
}

/**
 * A card tooltip's bounds: never wider than the screen, the width other wide tooltips take (InstanceView). The
 * text inside wraps a long word or URL, keeps the line breaks it was written with, and scrolls past 60% of the
 * screen's height rather than running off it.
 */
export const CARD_TOOLTIP_STYLE = { maxWidth: 'min(560px, calc(100vw - 32px))' }
export const CARD_TOOLTIP_TEXT_STYLE = 'white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; '
    + 'max-height: 60vh; overflow-y: auto; display: block'
