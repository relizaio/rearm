import { describe, it, expect } from 'vitest'
import { strengthDraft, strengthInput, strengthSummary } from './roleStrength'

describe('roleStrength', () => {
    it('drafts an empty role as no floor, zero headroom and no overrides', () => {
        expect(strengthDraft(null)).toEqual({ requiredStrength: null, strengthHeadroom: 0, strengthCategory: null, modelStrengths: [] })
    })

    it('sends all four fields, so what the form shows is what is saved', () => {
        const input = strengthInput(strengthDraft({ requiredStrength: 3.5, strengthHeadroom: 0.5, strengthCategory: 'ARCHITECT',
            modelStrengths: [{ model: 'm1', strength: 4.25 }] }))
        expect(input).toEqual({ requiredStrength: 3.5, strengthHeadroom: 0.5, strengthCategory: 'ARCHITECT',
            modelStrengths: [{ model: 'm1', strength: 4.25 }] })
    })

    it('clears a floor by sending null, and drops the headroom with it', () => {
        expect(strengthInput({ requiredStrength: null, strengthHeadroom: 1, strengthCategory: null, modelStrengths: [] }))
            .toEqual({ requiredStrength: null, strengthHeadroom: 0, strengthCategory: null, modelStrengths: [] })
    })

    it('refuses an override row with no model or no strength rather than dropping it', () => {
        expect(() => strengthInput({ requiredStrength: null, strengthHeadroom: 0, strengthCategory: null,
            modelStrengths: [{ model: null, strength: 3 }] })).toThrow()
    })

    it('summarises for the role table', () => {
        expect(strengthSummary({ requiredStrength: 3.5, strengthHeadroom: 0.5, strengthCategory: 'QA',
            modelStrengths: [{ model: 'a', strength: 1 }] })).toBe('≥ 3.5 (+0.5) · QA · 1 override')
        expect(strengthSummary({})).toBe('—')
    })
})
