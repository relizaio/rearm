import { describe, expect, it } from 'vitest'
import { MIN_WIDTH, ticks, timelineWidth } from './agentTimelineAxis'

// RD2-12 (sweep UI-13): the timeline fills its panel and names its ticks distinctly, inside the drawing.
describe('the timeline axis', () => {
    it('is as wide as the panel, never under 760', () => {
        expect(timelineWidth(1100)).toBe(1100)
        expect(timelineWidth(500)).toBe(MIN_WIDTH)
        expect(timelineWidth(null)).toBe(MIN_WIDTH)
    })

    const x = (min: number, max: number, w: number) => (t: number) => ((t - min) / (max - min)) * (w - 10)

    it('names a three-minute span\'s ticks by the second, each distinct', () => {
        const min = Date.UTC(2026, 8, 27, 10, 0, 0)
        const max = min + 3 * 60_000
        const labels = ticks(min, max, x(min, max, 900), 4, 'UTC').map(t => t.label)
        expect(new Set(labels).size).toBe(5)
        expect(labels[0]).toBe('10:00:00')
        expect(labels[4]).toBe('10:03:00')
    })

    it('names a three-day span\'s ticks with the date, each distinct', () => {
        const min = Date.UTC(2026, 8, 25, 0, 0, 0)
        const max = min + 3 * 24 * 3_600_000
        const labels = ticks(min, max, x(min, max, 900), 4, 'UTC').map(t => t.label)
        expect(new Set(labels).size).toBe(5)
        expect(labels[0]).toContain('09-25')
        expect(labels[4]).toContain('09-28')
    })

    it('keeps the last label inside the drawing: it ends at its line', () => {
        const min = 0
        const max = 3_600_000
        const t = ticks(min, max, x(min, max, 900), 4, 'UTC')
        expect(t[4].anchor).toBe('end')
        expect(t[4].labelX).toBeLessThan(t[4].x)
        expect(t[0].anchor).toBe('start')
    })
})
