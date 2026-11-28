import { describe, it, expect } from 'vitest'
import {
    supportClaimsContradict, attestationClaimConflict, emptyAttestationForm, LEVEL_OF_SUPPORT_OPTIONS,
    SUPPORT_CLAIM_CONFLICT_WARNING, BULK_CLAIM_CONFLICT_ERROR, type AttestationForm
} from './supportAttestationInput'
import { validateBulkInput } from './useBulkAttest'

/** FDAW-161 tests 13 and 14: one contradicting pair, the single form's warning helper and the bulk refusal. */
const CODES = ['NO_COMMITTED_END_OF_SUPPORT', 'COMMUNITY_PROJECT', 'SUPPLIER_PUBLISHES_NO_DATE', 'OTHER', null]

function form (over: Partial<AttestationForm> = {}): AttestationForm {
    return { ...emptyAttestationForm(), ...over }
}

describe('supportClaimsContradict, the server table', () => {
    it('is true for actively maintained with community project only', () => {
        for (const level of [...LEVEL_OF_SUPPORT_OPTIONS.map(o => o.value), null]) {
            for (const code of CODES) {
                expect(supportClaimsContradict(level, code), `${level} + ${code}`)
                    .toBe(level === 'ACTIVELY_MAINTAINED' && code === 'COMMUNITY_PROJECT')
            }
        }
    })

    it('the texts are the design\'s, the bulk one the server\'s refusal word for word', () => {
        expect(SUPPORT_CLAIM_CONFLICT_WARNING).toBe('Actively maintained together with "community project, no support'
            + ' commitment" reads as a contradiction in the FDA addendum. Saved as you enter it, and listed for review'
            + ' before Finalize.')
        expect(BULK_CLAIM_CONFLICT_ERROR).toBe('A bulk entry cannot record the level actively maintained together'
            + ' with the end-of-support reason "community project, no support commitment": for each component one of'
            + ' them is likely untrue. Check each project and record the level one component at a time, or choose'
            + ' another reason.')
    })
})

describe('attestationClaimConflict, the effective values', () => {
    const storedUnknown = { endOfSupportUnknown: { code: 'COMMUNITY_PROJECT', text: '' } }
    const storedLevel = { attestedLevelOfSupport: 'ACTIVELY_MAINTAINED' }

    it('the form level with the stored unknown', () => {
        expect(attestationClaimConflict(form({ levelOfSupport: 'ACTIVELY_MAINTAINED' }), storedUnknown)).toBe(true)
    })

    it('the stored level with the form unknown', () => {
        expect(attestationClaimConflict(form({ endOfSupportMode: 'UNKNOWN', endOfSupportUnknownCode: 'COMMUNITY_PROJECT' }),
            storedLevel)).toBe(true)
    })

    it('a form that clears the stored unknown does not', () => {
        expect(attestationClaimConflict(form({ levelOfSupport: 'ACTIVELY_MAINTAINED', clearEndOfSupportUnknown: true }),
            storedUnknown)).toBe(false)
    })

    it('a form that changes the level to no longer maintained does not', () => {
        expect(attestationClaimConflict(form({ levelOfSupport: 'NO_LONGER_MAINTAINED' }),
            { ...storedLevel, ...storedUnknown })).toBe(false)
    })

    it('a form whose unknown is another reason does not, and nothing stored nothing chosen does not', () => {
        expect(attestationClaimConflict(form({ endOfSupportMode: 'UNKNOWN',
            endOfSupportUnknownCode: 'NO_COMMITTED_END_OF_SUPPORT' }), { ...storedLevel, ...storedUnknown })).toBe(false)
        expect(attestationClaimConflict(form(), null)).toBe(false)
    })
})

describe('validateBulkInput refuses the pair before the walk', () => {
    const base = { levelOfSupport: 'ACTIVELY_MAINTAINED' as const, endOfSupportMode: 'UNKNOWN' as const, reason: 'sweep' }

    it('the pair gives the server\'s refusal', () => {
        expect(validateBulkInput({ ...base, endOfSupportUnknownCode: 'COMMUNITY_PROJECT' })).toEqual([BULK_CLAIM_CONFLICT_ERROR])
    })

    it('another reason, or the reason without a level, does not', () => {
        expect(validateBulkInput({ ...base, endOfSupportUnknownCode: 'NO_COMMITTED_END_OF_SUPPORT' })).toEqual([])
        expect(validateBulkInput({ ...base, levelOfSupport: null, endOfSupportUnknownCode: 'COMMUNITY_PROJECT' })).toEqual([])
    })

    it('date mode with community project left in the select does not', () => {
        expect(validateBulkInput({ ...base, endOfSupportMode: 'DATE', endOfSupportUnknownCode: 'COMMUNITY_PROJECT' }))
            .not.toContain(BULK_CLAIM_CONFLICT_ERROR)
    })
})
