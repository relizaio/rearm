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

describe('variables are diffed against what was seeded', () => {
    /**
     * A seeded form holds the stored dates. Re-sending them re-stamps each milestone's
     * lastAssessed, asserting a fresh assessment nobody made -- the record quietly ageing
     * itself forward. Only what actually changed goes on the wire.
     */
    it('sends nothing but the id when nothing changed', () => {
        const f = useAttestationForm()
        f.open(existing())
        expect(Object.keys(f.variables('c-1'))).toEqual(['sbomComponentUuid'])
    })

    it('sends only the field that moved', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfLifeDate = '2035-01-01'
        f.confirmJustification()
        expect(f.variables('c-1')).toEqual({
            sbomComponentUuid: 'c-1', endOfLifeDate: '2035-01-01'
        })
    })

    /**
     * The failure the browser probe hit: the seeded date and a clear of the same milestone
     * went out together, and the server refused the pair outright -- "cannot set and clear
     * the same milestone in one call". A clear could therefore never succeed from a seeded
     * form.
     */
    it('never sets a milestone it is also clearing', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.clearMilestones = ['END_OF_SUPPORT']
        f.form.reason = 'the date belonged to the superseded claim'
        const v = f.variables('c-1')
        expect('endOfSupportDate' in v).toBe(false)
        expect(v.clearMilestones).toEqual(['END_OF_SUPPORT'])
    })

    it('still sends an unchanged level when re-asserting, since state travels with it', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        f.form.reason = 'withdrawal was filed in error'
        expect(f.variables('c-1').state).toBe('ATTESTED')
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
        // Deliberately NOT a notes-only edit: notes are stored per-milestone, so that is
        // blocked on its own. Revising the basis is the ordinary no-reason-needed change.
        f.form.justification = 'rechecked the upstream release feed in September'
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

describe('a save that would send nothing is refused', () => {
    /**
     * The failure this replaced: emptying a seeded box enabled Save, the baseline diff then
     * omitted the emptied field, and the mutation went out as the component id alone --
     * behind a green "Saved. Support attestation recorded." A success message for a write
     * that changed nothing is worse than an error on a form meant to produce a defensible
     * record.
     */
    it.each(['justification', 'supportNotes'] as const)(
        'refuses a save after emptying the seeded %s', (field) => {
            const f = useAttestationForm()
            f.open(existing())
            ;(f.form as any)[field] = ''
            expect(f.sendsAnything()).toBe(false)
            expect(f.canSubmit()).toBe(false)
        })

    it('refuses a save after blanking a seeded date', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfSupportDate = null
        expect(f.canSubmit()).toBe(false)
    })

    it('still allows a save that genuinely changes something', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.justification = 'rechecked the upstream release feed in September'
        expect(f.canSubmit()).toBe(true)
    })
})

describe('"assessed, nothing published" does not cancel a pending un-retract', () => {
    /**
     * It resets the form, and state carries the PENDING UN-RETRACT set at open(). Dropping
     * it left the row withdrawn and uncounted behind a success toast -- the same defect this
     * feature shipped once already, reached by a different route.
     */
    it('keeps state ATTESTED and the reason', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        f.form.reason = 'withdrawal was filed against the wrong component'
        f.assessedNothingPublished('supplier declined to state a support level')
        expect(f.form.state).toBe('ATTESTED')
        expect(f.form.reason).toBe('withdrawal was filed against the wrong component')
        expect(f.variables('c-1').state).toBe('ATTESTED')
        expect(f.canSubmit()).toBe(true)
    })
})

describe('contradictions and affordance honesty', () => {
    // Clearing a date that is already stored is the ordinary case, not a contradiction.
    it('allows clearing a seeded milestone', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.clearMilestones = ['END_OF_SUPPORT']
        f.form.reason = 'the date belonged to the superseded claim'
        expect(f.errors()).toEqual([])
        expect(f.canSubmit()).toBe(true)
    })

    /**
     * Typing a replacement date while also ticking its clear is a contradiction the server
     * refuses outright. The builder silently preferred the clear, so the operator's new date
     * evaporated with no warning.
     */
    it('refuses setting and clearing the same milestone', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfSupportDate = '2032-06-01'
        f.confirmJustification()
        f.form.clearMilestones = ['END_OF_SUPPORT']
        f.form.reason = 'r'
        expect(f.errors().some(e => e.includes('setting and clearing'))).toBe(true)
        expect(f.canSubmit()).toBe(false)
    })

    /**
     * The card promises "no level, no dates". Under PATCH, blanking those omits them, and
     * omit means preserve -- so on a row carrying a level it would leave that level
     * published against a justification written to mean the opposite. There is no way to
     * unset a level at all, so the card is only offered where it can tell the truth.
     */
    it('is unavailable when the row already carries a level or dates', () => {
        const f = useAttestationForm()
        f.open(existing())
        expect(f.canUseNothingPublished()).toBe(false)
    })

    it('is available on a fresh row and on a justification-only row', () => {
        const fresh = useAttestationForm()
        fresh.open(null)
        expect(fresh.canUseNothingPublished()).toBe(true)
        const basisOnly = useAttestationForm()
        basisOnly.open(existing({
            attestedLevelOfSupport: null, endOfSupportDate: null,
            endOfGuaranteedSupportDate: null, endOfLifeDate: null
        }))
        expect(basisOnly.canUseNothingPublished()).toBe(true)
    })
})

describe('internal notes are per-milestone server-side', () => {
    /**
     * The server reads supportNotes only inside its staged-milestone loop, and SupportData
     * has no record-level notes field. A notes-only save therefore reported success and
     * stored nothing -- and the backend's own comment calls that text "often the only
     * evidence of what the assessor checked".
     *
     * Blocked rather than silently dropped. Whether notes should become record-level or the
     * form should present them per-milestone is a design decision, not something to paper
     * over here.
     */
    it('refuses a notes-only edit instead of discarding it', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.supportNotes = 'called the vendor again on 3 Sep'
        expect(f.canSubmit()).toBe(false)
        expect(f.errors().some(e => e.includes('stored against a date'))).toBe(true)
    })

    it('allows notes alongside a date change, which is where they land', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.supportNotes = 'vendor confirmed by email'
        f.form.endOfSupportDate = '2032-01-01'
        f.confirmJustification()
        expect(f.errors()).toEqual([])
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
