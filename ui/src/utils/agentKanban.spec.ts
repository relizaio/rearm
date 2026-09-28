import { describe, expect, it } from 'vitest'
import { COL_MIN_WIDTH, columnFolded, columnHead, columnsPastEdge, EMPTY_BOARD_HINT, moreLanesHint } from './agentKanban'

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
