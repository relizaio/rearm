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

/** What an empty board says under its headers. */
export const EMPTY_BOARD_HINT = 'No tasks yet. + New task, or agents register tasks via the coordinator.'

export interface StripMeasure {
    /** The lane's strip as Vue hands it to a :ref callback: set when it mounts, null when it goes. */
    boardEl (key: string, el: any): void
    /** Re-measure every strip: on scroll, on resize, after the tasks change. */
    measure (): void
    /** Per lane, how many columns end past the strip's edge. */
    pastEdge: { value: Record<string, number> }
}

/**
 * Each lane's strip, and how many of its columns sit past its edge. A strip is measured when it mounts
 * (RD2-13 run 1, T-1): opened on another tab first, the kanban's strip mounts after the tasks arrived, so a
 * watch on the tasks alone measured nothing.
 */
export function stripMeasure (after: (fn: () => void) => void, pastEdge: { value: Record<string, number> }): StripMeasure {
    const els = new Map<string, HTMLElement>()
    function measure () {
        const out: Record<string, number> = {}
        for (const [key, el] of els) out[key] = columnsPastEdge(Array.from(el.children) as any[], el.scrollLeft, el.clientWidth)
        pastEdge.value = out
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
    return { boardEl, measure, pastEdge }
}
