import { describe, expect, it } from 'vitest'
import {
    attestationVariables,
    validateAttestation,
    type AttestationForm
} from './supportAttestationInput'

/** A form where nothing has been touched. */
const pristine = (): AttestationForm => ({
    levelOfSupport: null,
    justification: '',
    party: null,
    endOfGuaranteedSupportDate: null,
    endOfSupportDate: null,
    endOfLifeDate: null,
    supportNotes: '',
    reason: '',
    clearMilestones: [],
    clearJustification: false
})

describe('clearing is explicit, never accidental', () => {
    /**
     * An empty string is not "nothing to say" on this mutation -- it is the CLEAR signal.
     * The server reads a supplied-but-blank justification as an instruction to null the
     * stored basis (`request.justification() != null ? blankToNull(...) : existing`), and
     * its own error text offers it as such: "or an empty one to clear the previous basis".
     *
     * So the form must never emit '' by accident. Only an explicit clear does.
     */
    it('emits an empty justification ONLY for an explicit clear', () => {
        const f = pristine()
        expect('justification' in attestationVariables('c-1', f)).toBe(false)
        f.clearJustification = true
        f.reason = 'basis belonged to the previous claim'
        expect(attestationVariables('c-1', f).justification).toBe('')
    })

    /**
     * The case with NO SERVER BACKSTOP. The negative levels are protected -- a blank
     * justification on NO_LONGER_MAINTAINED or ABANDONED is rejected on the stored result.
     * ACTIVELY_MAINTAINED does not require a basis, so a blank justification is accepted and
     * SILENTLY CLEARS whatever basis was recorded before. Nothing server-side catches it;
     * the form is the only guard.
     */
    it('does not clear the basis when setting ACTIVELY_MAINTAINED with an untouched justification', () => {
        const f = pristine()
        f.levelOfSupport = 'ACTIVELY_MAINTAINED'
        const v = attestationVariables('c-1', f)
        expect(v.levelOfSupport).toBe('ACTIVELY_MAINTAINED')
        expect('justification' in v,
            'sending justification:"" here would silently wipe the previous basis, and'
            + ' ACTIVELY_MAINTAINED is the one level the server does not backstop')
            .toBe(false)
    })

    // Clearing carries a reason because the audit row is the only trace it happened. This
    // mirrors the server guard rather than duplicating its judgement.
    it('refuses a clear with no reason', () => {
        const f = pristine()
        f.clearJustification = true
        expect(validateAttestation(f)).toContain(
            'Clearing the justification needs a reason: the audit row is the only record that it happened.')
    })

    it('refuses a milestone clear with no reason', () => {
        const f = pristine()
        f.clearMilestones = ['END_OF_SUPPORT']
        expect(validateAttestation(f).length).toBeGreaterThan(0)
    })

    // Mirrors the server's stored-result invariant so the operator finds out before the
    // round trip, not after a rejected write.
    it.each(['NO_LONGER_MAINTAINED', 'ABANDONED'] as const)(
        'refuses %s without a justification', (level) => {
            const f = pristine()
            f.levelOfSupport = level
            expect(validateAttestation(f).some(e => e.includes('justification'))).toBe(true)
        })

    it('accepts a negative level once a justification is given', () => {
        const f = pristine()
        f.levelOfSupport = 'ABANDONED'
        f.justification = 'upstream archived the repository in 2024'
        expect(validateAttestation(f)).toEqual([])
    })

    it('accepts ACTIVELY_MAINTAINED with no justification', () => {
        const f = pristine()
        f.levelOfSupport = 'ACTIVELY_MAINTAINED'
        expect(validateAttestation(f)).toEqual([])
    })
})

describe('attestationVariables: PATCH semantics', () => {
    /**
     * THE contract. The backend treats an absent argument as "leave this alone" and a
     * supplied one as "set it". So a form that always sends every field would overwrite
     * another person's recorded level of support with null every time someone edited a date.
     * Untouched must mean OMITTED, not empty.
     */
    it('omits every untouched field rather than sending null or empty', () => {
        const v = attestationVariables('c-1', pristine())
        expect(Object.keys(v)).toEqual(['sbomComponentUuid'])
    })

    it('sends only the field that changed', () => {
        const f = pristine()
        f.endOfSupportDate = '2030-01-01'
        const v = attestationVariables('c-1', f)
        expect(v).toEqual({ sbomComponentUuid: 'c-1', endOfSupportDate: '2030-01-01' })
    })

    // Blank strings are the trap: an untouched text input is '' , not null, and '' is a
    // value the server would happily store, wiping a justification someone else wrote.
    it.each(['justification', 'supportNotes', 'reason'] as const)(
        'treats a blank %s as untouched, not as a value', (field) => {
            const f = pristine()
            ;(f as any)[field] = '   '
            expect(Object.keys(attestationVariables('c-1', f))).toEqual(['sbomComponentUuid'])
        })

    it('trims text it does send', () => {
        const f = pristine()
        f.justification = '  supplier confirmed  '
        expect(attestationVariables('c-1', f).justification).toBe('supplier confirmed')
    })

    /**
     * Clearing is an explicit act and must survive the omit-empties rule: an empty
     * clearMilestones array means "clear nothing", but a populated one is a real instruction
     * that happens to be about absence.
     */
    it('omits an empty clearMilestones but sends a populated one', () => {
        const f = pristine()
        expect('clearMilestones' in attestationVariables('c-1', f)).toBe(false)
        f.clearMilestones = ['END_OF_SUPPORT']
        expect(attestationVariables('c-1', f).clearMilestones).toEqual(['END_OF_SUPPORT'])
    })

    // "Assessed, nothing published": the operator asked the supplier and got no answer. A
    // justification alone IS a complete attestation -- it is the case the storage shape
    // exists for, and the fresh-row guard on the server accepts exactly this.
    it('accepts a justification with no level and no dates', () => {
        const f = pristine()
        f.justification = 'supplier declined to state a support level'
        expect(attestationVariables('c-1', f)).toEqual({
            sbomComponentUuid: 'c-1',
            justification: 'supplier declined to state a support level'
        })
    })

    it('sends level and party when chosen', () => {
        const f = pristine()
        f.levelOfSupport = 'NO_LONGER_MAINTAINED'
        f.party = 'SUPPLIER'
        f.justification = 'upstream archived the repository'
        const v = attestationVariables('c-1', f)
        expect(v.levelOfSupport).toBe('NO_LONGER_MAINTAINED')
        expect(v.supportParty).toBe('SUPPLIER')
    })

    // The wire name differs from the form field name; a rename on either side would
    // silently stop sending it, and the server would treat it as untouched.
    it('maps party to the supportParty argument the schema declares', () => {
        const f = pristine()
        f.party = 'MANUFACTURER'
        const v = attestationVariables('c-1', f)
        expect('supportParty' in v).toBe(true)
        expect('party' in v).toBe(false)
    })
})
