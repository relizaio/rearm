import { describe, it, expect } from 'vitest'
import { validateInputTrigger } from './triggerValidation'

const rule = (celExpression: string) => ({ name: 'r', celExpression, outputEvents: ['a1'] })

describe('validateInputTrigger condition checks', () => {
    it('a missing condition belongs under the condition field', () => {
        expect(validateInputTrigger(rule(''))).toEqual({ valid: false, error: 'Condition is required.', field: 'celExpression' })
    })

    it('a trivially-true condition typed in CEL mode is refused, under the condition field', () => {
        const r = validateInputTrigger(rule('(release.branchType == "BASE" && true)'))
        expect(r.valid).toBe(false)
        expect(r.field).toBe('celExpression')
        expect(r.error).toContain('always true')
    })

    it('a complete condition passes', () => {
        expect(validateInputTrigger(rule('release.branchType == "BASE" && release.anyApproved'))).toEqual({ valid: true })
    })
})
