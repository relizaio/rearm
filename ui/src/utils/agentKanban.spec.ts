import { describe, expect, it } from 'vitest'
import { BACK_LANES_HINT, CARD_TOOLTIP_STYLE, CARD_TOOLTIP_TEXT_STYLE, COL_MIN_WIDTH, columnFolded, columnHead, columnKey, columnsPastEdge,
    EMPTY_BOARD_HINT, LANE_CARDS, moreCardsLabel, moreLanesHint, shownCards, stripHint, stripMeasure } from './agentKanban'

// RD2-13 (sweep UI-21, UI-36): the kanban's columns at a laptop's width.
describe('the kanban columns', () => {
    it('fold an empty column until it is opened, never one with cards', () => {
        expect(columnFolded('awaiting', 0, new Set())).toBe(true)
        expect(columnFolded('awaiting', 0, new Set(['awaiting']))).toBe(false)
        expect(columnFolded('awaiting', 2, new Set())).toBe(false)
    })

    it('say their count in the head', () => {
        expect(columnHead('On hold', 2)).toBe('On hold · 2')
    })

    it('count the columns past the strip\'s edge and say so', () => {
        const cols = [0, 192, 384, 576, 768, 960, 1152].map(x => ({ offsetLeft: x, offsetWidth: 180 }))
        expect(columnsPastEdge(cols, 0, 1150)).toBe(1, 'only the seventh ends past 1150')
        expect(columnsPastEdge(cols, 0, 1100)).toBe(2, 'the sixth ends at 1140, past 1100 too')
        expect(columnsPastEdge(cols, 300, 1100)).toBe(0, 'scrolled, every column shows')
        expect(moreLanesHint(1)).toBe('1 more lane →')
        expect(moreLanesHint(3)).toBe('3 more lanes →')
        expect(moreLanesHint(0)).toBeNull()
    })

    it('fit six columns of the narrowest width, with their gaps, in a 1280 window\'s board area', () => {
        // 1280 less the side nav and the page's padding leaves about 1180 for the strip.
        expect(6 * COL_MIN_WIDTH + 5 * 12).toBeLessThanOrEqual(1180)
    })

    it('tell an empty board how tasks arrive', () => {
        expect(EMPTY_BOARD_HINT).toBe('No tasks yet. + New task, or agents register tasks via the coordinator.')
    })
})

// Tester run 1 T-1: the kanban opened after another tab mounts its strip once the tasks are there; the strip is
// measured when it mounts, not only when the tasks change.
describe('measuring a lane\'s strip', () => {
    const strip = (widths: number[], clientWidth: number) => ({ scrollLeft: 0, clientWidth,
        children: widths.map((w, i) => ({ offsetLeft: i * (w + 12), offsetWidth: w })) })

    it('measures a strip when it mounts, and says how many columns sit past its edge', () => {
        const queued: (() => void)[] = []
        const m = stripMeasure(fn => { queued.push(fn) }, { value: {} })
        m.boardEl('none', strip([180, 180, 180, 180, 180, 180, 180, 180], 1180))
        expect(queued).toHaveLength(1, 'a measure is due on mount')
        queued.forEach(fn => fn())
        expect(m.pastEdge.value).toEqual({ none: 2 })
    })

    it('does not re-measure for the same strip handed again, and forgets a strip that goes', () => {
        const queued: (() => void)[] = []
        const m = stripMeasure(fn => { queued.push(fn) }, { value: {} })
        const el = strip([180, 180], 1180)
        m.boardEl('none', el)
        m.boardEl('none', el)
        expect(queued).toHaveLength(1)
        m.boardEl('none', null)
        m.measure()
        expect(m.pastEdge.value).toEqual({})
    })
})

// The hint above a strip is a control: "N more lanes" scrolls the strip to its end, and once there with nothing past
// the edge, "back" scrolls it to its start.
describe('the strip hint', () => {
    it('offers the far end while columns sit past the edge, the start once scrolled there, else nothing', () => {
        expect(stripHint(2, false)).toEqual({ label: '2 more lanes \u2192', toward: 'end' })
        expect(stripHint(1, true)).toEqual({ label: '1 more lane \u2192', toward: 'end' })
        expect(stripHint(0, true)).toEqual({ label: BACK_LANES_HINT, toward: 'start' })
        expect(BACK_LANES_HINT).toBe('\u2190 back')
        expect(stripHint(0, false)).toBeNull()
    })

    const strip = (scrollLeft: number) => {
        const el: any = { scrollLeft, clientWidth: 1180, scrollWidth: 1716, children: [0, 1, 2, 3, 4, 5, 6, 7, 8]
            .map(i => ({ offsetLeft: i * 192, offsetWidth: 180 })) }
        el.scrollTo = (opts: { left: number, behavior: string }) => { el.calls = [...(el.calls ?? []), opts] }
        return el
    }

    it('scrolls a lane\'s strip smoothly to its far end, and back to its start', () => {
        const m = stripMeasure(fn => fn(), { value: {} })
        const el = strip(0)
        m.boardEl('none', el)
        m.scrollStrip('none', 'end')
        expect(el.calls).toEqual([{ left: 1716, behavior: 'smooth' }])
        m.scrollStrip('none', 'start')
        expect(el.calls[1]).toEqual({ left: 0, behavior: 'smooth' })
        m.scrollStrip('gone', 'end')
        expect(el.calls).toHaveLength(2, 'a lane without a strip scrolls nothing')
    })

    it('records whether each strip is scrolled off its start, so the hint turns to back at the end', () => {
        const scrolled = { value: {} as Record<string, boolean> }
        const m = stripMeasure(fn => fn(), { value: {} }, scrolled)
        const el = strip(0)
        m.boardEl('none', el)
        expect(m.pastEdge.value).toEqual({ none: 3 })
        expect(scrolled.value).toEqual({ none: false })
        expect(stripHint(m.pastEdge.value.none, scrolled.value.none)?.toward).toBe('end')
        el.scrollLeft = 536
        m.measure()
        expect(m.pastEdge.value).toEqual({ none: 0 })
        expect(scrolled.value).toEqual({ none: true })
        expect(stripHint(m.pastEdge.value.none, scrolled.value.none)?.toward).toBe('start')
    })
})

// Five cards a column until "Show N more" (the board load), and the card tooltips' bounds.
describe('the kanban columns: five cards each', () => {
    const cards = [1, 2, 3, 4, 5, 6, 7]

    it('cut a column to five until it is expanded', () => {
        expect(LANE_CARDS).toBe(5)
        expect(shownCards(cards, false)).toEqual([1, 2, 3, 4, 5])
        expect(shownCards(cards, true)).toEqual(cards)
        expect(shownCards([1, 2], false)).toEqual([1, 2])
    })

    it('offer Show N more, then Show fewer, and nothing on a column that fits', () => {
        expect(moreCardsLabel(7, false)).toBe('Show 2 more')
        expect(moreCardsLabel(7, true)).toBe('Show fewer')
        expect(moreCardsLabel(5, false)).toBeNull()
        expect(moreCardsLabel(5, true)).toBeNull()
        expect(moreCardsLabel(0, false)).toBeNull()
    })

    it('keep each column of each lane apart', () => {
        expect(columnKey('none', 'intake')).toBe('none|intake')
        expect(columnKey('g1', 'role:coder')).not.toBe(columnKey('g2', 'role:coder'))
    })

    it('bound a card tooltip to the screen, its text wrapping, keeping its breaks and scrolling', () => {
        expect(CARD_TOOLTIP_STYLE).toEqual({ maxWidth: 'min(560px, calc(100vw - 32px))' })
        for (const rule of ['white-space: pre-wrap', 'overflow-wrap: anywhere', 'word-break: break-word', 'max-height: 60vh',
            'overflow-y: auto', 'display: block']) {
            expect(CARD_TOOLTIP_TEXT_STYLE).toContain(rule)
        }
    })
})
