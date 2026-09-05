import { describe, expect, it } from 'vitest'
import { useAttestationForm } from './useAttestationForm'
import { attestationVariables } from './supportAttestationInput'

const existing = (over: Record<string, unknown> = {}) => ({
    attestationState: 'ATTESTED',
    attestedLevelOfSupport: 'ACTIVELY_MAINTAINED',
    justification: 'checked the upstream release feed in March',
    supportParty: 'SUPPLIER',
    endOfGuaranteedSupportDate: null,
    endOfSupportDate: '2030-01-01',
    endOfLifeDate: null,
    supportNotes: '',
    ...over
})

describe('seeding', () => {
    it('opens with the stored attestation, so a save is a no-op until something changes', () => {
        const f = useAttestationForm()
        f.open(existing())
        expect(f.form.levelOfSupport).toBe('ACTIVELY_MAINTAINED')
        expect(f.form.party).toBe('SUPPLIER')
        expect(f.form.endOfSupportDate).toBe('2030-01-01')
        expect(f.dirty()).toBe(false)
    })

    it('opens empty for a component with no attestation', () => {
        const f = useAttestationForm()
        f.open(null)
        expect(f.form.levelOfSupport).toBeNull()
        expect(f.form.justification).toBe('')
        expect(f.isUnRetract()).toBe(false)
    })
})

describe('justification confirm-or-revise on a milestone change', () => {
    /**
     * The basis was written for the dates that were there when it was written. Changing a
     * date without revisiting it leaves a justification silently vouching for a claim it was
     * never about -- which is the substantiation record quietly going stale.
     */
    it('blocks a date change while an existing basis is neither confirmed nor revised', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfSupportDate = '2032-06-01'
        expect(f.needsJustificationDecision()).toBe(true)
        expect(f.canSubmit()).toBe(false)
    })

    it('is satisfied by confirming the existing basis still holds', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfSupportDate = '2032-06-01'
        f.confirmJustification()
        expect(f.needsJustificationDecision()).toBe(false)
        expect(f.canSubmit()).toBe(true)
    })

    it('is satisfied by revising the basis instead', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfSupportDate = '2032-06-01'
        f.form.justification = 'supplier published a new support window in September'
        expect(f.needsJustificationDecision()).toBe(false)
    })

    // No stored basis means nothing to go stale.
    it('does not ask when there was no basis to begin with', () => {
        const f = useAttestationForm()
        f.open(existing({ justification: null }))
        f.form.endOfSupportDate = '2032-06-01'
        expect(f.needsJustificationDecision()).toBe(false)
    })

    // Editing only the internal notes is not a claim change.
    it('does not ask when no milestone moved', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.supportNotes = 'chased the supplier again'
        expect(f.needsJustificationDecision()).toBe(false)
    })

    // Confirming means "still true for the NEW dates" -- it must not survive a further edit.
    it('re-asks if a date changes again after confirmation', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfSupportDate = '2032-06-01'
        f.confirmJustification()
        f.form.endOfLifeDate = '2035-01-01'
        expect(f.needsJustificationDecision()).toBe(true)
    })
})

describe('un-retracting a withdrawn attestation', () => {
    /**
     * Re-asserting a withdrawn claim is a reversal, and the audit row is the only record it
     * happened. Same argument the bulk path already enforces server-side.
     */
    it('requires a reason', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        expect(f.isUnRetract()).toBe(true)
        f.form.endOfSupportDate = '2031-01-01'
        f.confirmJustification()
        expect(f.canSubmit()).toBe(false)
        expect(f.errors().some(e => e.includes('reason'))).toBe(true)
        f.form.reason = 'supplier confirmed the original window in writing'
        expect(f.canSubmit()).toBe(true)
    })

    /**
     * The defect a browser probe caught that every other check missed: the audit row was
     * correct, the reason landed, the toast said saved -- and the component stayed
     * WITHDRAWN, because state is preserved when omitted.
     */
    it('sends state ATTESTED, or the re-assertion does not happen', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        f.form.reason = 'supplier confirmed the original window'
        expect(attestationVariables('c-1', f.form).state).toBe('ATTESTED')
    })

    /**
     * The most likely un-retract of all: the original claim was right and the withdrawal was
     * the mistake, so nothing needs editing. The form refused this until the pending state
     * change was counted as a change.
     */
    it('allows a pure un-retract with a reason and no other edit', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        f.form.reason = 'the withdrawal was filed against the wrong component'
        expect(f.dirty()).toBe(true)
        expect(f.canSubmit()).toBe(true)
    })

    it('does not touch state on an ordinary edit', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.supportNotes = 'note'
        expect('state' in attestationVariables('c-1', f.form)).toBe(false)
    })

    it('does not demand a reason for an ordinary edit', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.supportNotes = 'note'
        expect(f.errors()).toEqual([])
    })
})

describe('"assessed, nothing published"', () => {
    /**
     * The most complete attestation available when a supplier will not state a level. It
     * submits a justification ALONE -- no level, no dates, no party -- which is exactly the
     * justification-only path the server's fresh-row guard accepts.
     *
     * It is the near-opposite of clearing, which removes a basis. Keeping them apart in the
     * UI matters; keeping them apart in the data is what this asserts.
     */
    it('submits a justification and nothing else', () => {
        const f = useAttestationForm()
        f.open(null)
        f.assessedNothingPublished('supplier declined to state a support level')
        const v = attestationVariables('c-1', f.form)
        expect(v).toEqual({
            sbomComponentUuid: 'c-1',
            justification: 'supplier declined to state a support level'
        })
    })

    it('discards a level and dates already typed, rather than half-applying them', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.assessedNothingPublished('supplier declined to state a support level')
        expect(f.form.levelOfSupport).toBeNull()
        expect(f.form.endOfSupportDate).toBeNull()
        expect(f.form.party).toBeNull()
    })

    it('is not a clear', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.assessedNothingPublished('supplier declined to state a support level')
        expect(f.form.clearJustification).toBe(false)
        expect(attestationVariables('c-1', f.form).justification).not.toBe('')
    })
})

describe('submission gating', () => {
    it('refuses a save that would send nothing', () => {
        const f = useAttestationForm()
        f.open(null)
        expect(f.canSubmit()).toBe(false)
    })

    it('surfaces the server-mirrored guards', () => {
        const f = useAttestationForm()
        f.open(null)
        f.form.levelOfSupport = 'ABANDONED'
        expect(f.canSubmit()).toBe(false)
        f.form.justification = 'upstream archived the repository'
        expect(f.canSubmit()).toBe(true)
    })
})
