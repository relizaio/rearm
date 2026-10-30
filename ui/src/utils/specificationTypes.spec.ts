import { describe, expect, it } from 'vitest'
import constants from './constants'

// The specification identifier picker (CreateComponent, ComponentView): a broken entry renders a blank
// option and saves a value the server refuses, since it enforces SpecificationType on write.
describe('specification types', () => {
    it('are {label, value} options with unique values', () => {
        for (const o of constants.SpecificationTypes) {
            expect(Object.keys(o).sort()).toEqual(['label', 'value'])
        }
        const values = constants.SpecificationTypes.map(o => o.value)
        expect(new Set(values).size).toBe(values.length)
    })
    it('offer a threat model', () => {
        expect(constants.SpecificationTypes).toContainEqual({ label: 'Threat model', value: 'THREAT_MODEL' })
    })
})
