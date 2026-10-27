import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { staleAgo, staleChip } from './agentTaskStale'

const hop = { rule: 'hopNoProgress', since: '2026-10-20T10:00:00Z', minutes: 70, message: 'RD3-5 stalled: assigned to coder (session 1a2b3c4d) for 70 min with no progress reported' }

// The edges of a task's staleness chip that the main spec's round numbers do not reach (task TPF-69, mutation round).
describe('agentTaskStale edges', () => {
    it('shows whole hours rounded down from two hours on, as the ALERT text does', () => {
        expect(staleAgo(150)).toBe('2 h')
        expect(staleAgo(179)).toBe('2 h')
        expect(staleAgo(180)).toBe('3 h')
    })

    it('shows a negative span as no time at all', () => {
        expect(staleAgo(-5)).toBe('0 min')
    })

    it('skips an empty entry in the marks', () => {
        const c = staleChip({ stale: [null, hop, undefined] })
        expect(c?.label).toBe('stale: no progress 70 min')
        expect(c?.rules).toEqual(['hopNoProgress'])
        expect(staleChip({ stale: [null] })).toBeNull()
    })

    it('asks the server for the marks on every task read of a board and of one task', () => {
        const store = readFileSync(fileURLToPath(new URL('../store.ts', import.meta.url)), 'utf8')
        const start = store.indexOf('const AGENT_TASK_SELECTION = `')
        const selection = store.slice(start, store.indexOf('\n`', start))
        expect(start).toBeGreaterThan(-1)
        expect(selection).toContain('\n    stale { rule since minutes message }\n')
        // both reads that serve the board's cards and the task page splice this selection
        expect(store.split('${AGENT_TASK_SELECTION}').length - 1).toBeGreaterThanOrEqual(2)
    })
})
