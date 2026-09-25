import { describe, expect, it } from 'vitest'
import { budgetChip, dollarsToMicros, hopBudgetInput, microsToDollars, settingsPatch } from './agentBudget'

describe('agentBudget', () => {
    it('converts dollars and micros both ways', () => {
        expect(dollarsToMicros(5)).toBe(5_000_000)
        expect(dollarsToMicros(0.42)).toBe(420_000)
        expect(dollarsToMicros(1.0000004)).toBe(1_000_000)
        expect(dollarsToMicros(null)).toBeNull()
        expect(dollarsToMicros(undefined)).toBeNull()
        expect(microsToDollars(2_500_000)).toBe(2.5)
        expect(microsToDollars(null)).toBeNull()
    })

    it('colours spend against the budget at the soft-alert line and at 100%', () => {
        expect(budgetChip(1_000_000, null)).toEqual({ label: 'spent $1.00, no budget', type: 'default', percent: null })
        expect(budgetChip(1_000_000, 5_000_000)).toEqual({ label: 'spent $1.00 of $5.00 (20%)', type: 'success', percent: 20 })
        expect(budgetChip(4_000_000, 5_000_000).type).toBe('warning')
        expect(budgetChip(3_500_000, 5_000_000, 70).type).toBe('warning')
        expect(budgetChip(3_400_000, 5_000_000, 70).type).toBe('success')
        expect(budgetChip(5_000_000, 5_000_000)).toMatchObject({ type: 'error', percent: 100 })
        expect(budgetChip(7_000_000, 5_000_000)).toMatchObject({ type: 'error', percent: 140 })
        expect(budgetChip(null, 5_000_000).label).toBe('spent $0.00 of $5.00 (0%)')
        expect(budgetChip(1, 0).type).toBe('error')
    })

    it('sends only what changed, and an emptied setting as null', () => {
        const original = { budgetMicros: 5_000_000, softAlertPercent: 80, cycleCap: 3, noProgressRepeatsToStop: null,
            blockingPriority: null, completionPriority: 1 }
        expect(settingsPatch(original, { ...original })).toBeNull()
        expect(settingsPatch(original, { ...original, budgetMicros: 7_000_000 })).toEqual({ budgetMicros: 7_000_000 })
        expect(settingsPatch(original, { ...original, cycleCap: null, blockingPriority: 2 }))
            .toEqual({ cycleCap: null, blockingPriority: 2 })
        expect(settingsPatch(null, { budgetMicros: 1_000_000 })).toEqual({ budgetMicros: 1_000_000 },
        )
    })

    it('leaves a never-set allowance out, and removes an emptied one', () => {
        expect(hopBudgetInput(null, 1.5)).toBe(1_500_000)
        expect(hopBudgetInput(undefined, null)).toBeUndefined()
        expect(hopBudgetInput(null, null)).toBeUndefined()
        expect(hopBudgetInput(2_000_000, null)).toBeNull()
        expect(hopBudgetInput(2_000_000, 2)).toBe(2_000_000)
    })

    it('tells the drawer when the task budget field changed (6f1b348d)', async () => {
        const { budgetChanged } = await import('./agentBudget')
        expect(budgetChanged(null, null)).toBe(false)
        expect(budgetChanged(undefined, null)).toBe(false)
        expect(budgetChanged(2_500_000, 2.5)).toBe(false)
        expect(budgetChanged(2_500_000, 3)).toBe(true)
        expect(budgetChanged(2_500_000, null)).toBe(true)
        expect(budgetChanged(null, 1)).toBe(true)
    })
})
