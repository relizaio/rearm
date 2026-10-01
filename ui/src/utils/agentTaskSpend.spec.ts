import { describe, expect, it } from 'vitest'
import { taskSpendLabel, taskSpendVisible, taskSpentMicros } from './agentUsage'

// What the board charges a task (task 02bfab7c): its rows plus its coordinator share.
describe('taskSpendLabel', () => {
    it('headlines the spend and says how much is the coordinator estimate', () => {
        expect(taskSpendLabel({ spentMicros: 420_000, coordinatorEstimateMicros: 50_000 }))
            .toEqual({ label: '$0.42 spent', title: '$0.42 spent, incl. $0.05 coordinator estimate' })
    })

    it('says when all of it is the task\'s own usage', () => {
        expect(taskSpendLabel({ spentMicros: 420_000, coordinatorEstimateMicros: null }).title)
            .toBe('$0.42 spent, all from its own usage')
    })

    it('says when the figure is a lower bound', () => {
        expect(taskSpendLabel({ spentMicros: 420_000, usage: { costComplete: false } as any }).title)
            .toBe('$0.42 spent, all from its own usage (a lower bound: some usage has no price)')
    })

    it('falls back to the rows from a server that does not serve spentMicros', () => {
        expect(taskSpentMicros({ usage: { derivedCostMicros: 300_000 } as any })).toBe(300_000)
        expect(taskSpentMicros({ spentMicros: 800_000, usage: { derivedCostMicros: 300_000 } as any })).toBe(800_000)
        expect(taskSpendLabel(null).label).toBe('$0.00 spent')
    })
})

describe('taskSpendVisible', () => {
    it('shows a coordinator share alone, or a budget, and nothing else', () => {
        expect(taskSpendVisible({ spentMicros: 50_000, usage: null })).toBe(true)
        expect(taskSpendVisible({ spentMicros: 0, budgetMicros: 1_000_000 })).toBe(true)
        expect(taskSpendVisible({ spentMicros: 0, budgetMicros: null })).toBe(false)
        expect(taskSpendVisible({})).toBe(false)
    })
})
