// The timeline at the panel's width (task RD2-12, sweep UI-13): it fills the panel it sits in, never narrower than
// MIN_WIDTH, and its tick labels are distinct for the span shown -- seconds under ten minutes, minutes under a
// day, the date beyond -- and stay inside the drawing. Pure, so the rules are testable without a layout.

export const MIN_WIDTH = 760

/** The drawing's width for a panel this wide: the panel's, never under MIN_WIDTH. */
export function timelineWidth (panelWidth: number | null | undefined): number {
    return panelWidth && panelWidth > MIN_WIDTH ? Math.floor(panelWidth) : MIN_WIDTH
}

const MINUTE = 60_000
const DAY = 24 * 60 * MINUTE

/** How a tick names its time, for a span this long. */
export function tickFormat (spanMs: number): Intl.DateTimeFormatOptions {
    if (spanMs < 10 * MINUTE) return { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }
    if (spanMs < DAY) return { hour: '2-digit', minute: '2-digit', hour12: false }
    return { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }
}

export interface Tick {
    /** Where the tick's line is. */
    x: number
    label: string
    /** Where the label starts, and which way it runs: the last one ends at the line, inside the drawing. */
    labelX: number
    anchor: 'start' | 'end'
}

/** n + 1 ticks over [min, max], placed by xOf, named for the span, the last label kept inside the drawing. */
export function ticks (min: number, max: number, xOf: (t: number) => number, n = 4, timeZone?: string): Tick[] {
    const span = max - min
    const fmt = new Intl.DateTimeFormat('en-CA', { ...tickFormat(span), ...(timeZone ? { timeZone } : {}) })
    return Array.from({ length: n + 1 }, (_, i) => {
        const t = min + (span * i) / n
        const x = xOf(t)
        const last = i === n
        return { x, label: fmt.format(new Date(t)), labelX: last ? x - 4 : x + 4, anchor: last ? 'end' : 'start' }
    })
}
