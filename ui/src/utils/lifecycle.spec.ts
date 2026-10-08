import { describe, expect, it } from 'vitest'
import constants from './constants'
import { lifecycleLabel } from './lifecycle'

describe('lifecycleLabel', () => {
    it('shows general availability as "Shipped"', () => {
        expect(lifecycleLabel('GENERAL_AVAILABILITY')).toBe('Shipped')
    })

    it('uses the picker labels for every lifecycle', () => {
        for (const lo of constants.LifecycleOptions) {
            expect(lifecycleLabel(lo.key)).toBe(lo.label)
        }
        expect(lifecycleLabel('READY_TO_SHIP')).toBe('Ready to Ship')
    })

    it('passes an unknown value through and renders nothing for no value', () => {
        expect(lifecycleLabel('SOME_NEW_STAGE')).toBe('SOME_NEW_STAGE')
        expect(lifecycleLabel(null)).toBe('')
        expect(lifecycleLabel(undefined)).toBe('')
    })
})
