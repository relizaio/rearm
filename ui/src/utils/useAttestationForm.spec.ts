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
    supportMilestones: [
        { milestoneType: 'END_OF_SUPPORT', date: '2030-01-01', notes: 'vendor advisory of 3 March' }
    ],
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
        f.form.justification = 'rechecked the upstream feed'
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
        f.form.justification = 'rechecked the upstream feed'
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
    it('refuses a save after emptying the seeded justification', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.justification = ''
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

describe('notes are per milestone', () => {
    /**
     * The server reads supportNotes only inside its staged-milestone loop and writes it onto
     * the milestones being staged; SupportMilestoneFact.notes is documented as "the text
     * that justified [the date]... often the only evidence of what the assessor checked".
     * The flat supportNotes field is just the END_OF_SUPPORT milestone's notes under another
     * name. So the form follows the model instead of fighting it.
     */
    it('seeds each milestone note from its own milestone', () => {
        const f = useAttestationForm()
        f.open(existing())
        expect(f.form.milestoneNotes.END_OF_SUPPORT).toBe('vendor advisory of 3 March')
        expect(f.form.milestoneNotes.END_OF_LIFE).toBe('')
    })

    /**
     * A notes-only edit sends that milestone's OWN date alongside the text, unchanged,
     * because the server writes notes only onto milestones it is staging. That re-stamps
     * the milestone's lastAssessed, and that is correct rather than a breach of the diff
     * rule: revising what you checked IS a re-assessment of that milestone. The rule stays
     * "never send a date the operator did not touch" -- editing its notes counts as
     * touching it.
     */
    it('sends the milestone date with its notes, and nothing else', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.milestoneNotes.END_OF_SUPPORT = 'vendor reconfirmed by email on 3 Sep'
        expect(f.variableSets('c-1')).toEqual([{
            sbomComponentUuid: 'c-1',
            endOfSupportDate: '2030-01-01',
            supportNotes: 'vendor reconfirmed by email on 3 Sep'
        }])
    })

    /**
     * One supportNotes argument per call, so two milestones' notes in a single call would
     * land on both and cross-contaminate the evidence for each date. Serialised instead:
     * one call per edited milestone, each its own audit revision.
     */
    it('serialises rather than cross-contaminating two milestones', () => {
        const f = useAttestationForm()
        f.open(existing({
            supportMilestones: [
                { milestoneType: 'END_OF_SUPPORT', date: '2030-01-01', notes: 'a' },
                { milestoneType: 'END_OF_LIFE', date: '2031-01-01', notes: 'b' }
            ],
            endOfLifeDate: '2031-01-01'
        }))
        f.form.milestoneNotes.END_OF_SUPPORT = 'a2'
        f.form.milestoneNotes.END_OF_LIFE = 'b2'
        const sets = f.variableSets('c-1')
        expect(sets.length).toBe(2)
        expect(sets.map(x => x.supportNotes)).toEqual(['a2', 'b2'])
        expect(sets[0].endOfSupportDate).toBe('2030-01-01')
        expect(sets[1].endOfLifeDate).toBe('2031-01-01')
        expect('supportNotes' in sets[0] && 'endOfLifeDate' in sets[0]).toBe(false)
    })

    // The server has nowhere to put a note for a milestone that does not exist.
    it('refuses notes on a milestone with no date', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.milestoneNotes.END_OF_LIFE = 'orphan note'
        expect(f.canSubmit()).toBe(false)
        expect(f.errors().some(e => e.includes('set the date first'))).toBe(true)
    })

    it('accepts notes on a milestone whose date is set in the same save', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.endOfLifeDate = '2035-01-01'
        f.form.milestoneNotes.END_OF_LIFE = 'supplier end-of-sale notice'
        f.confirmJustification()
        expect(f.errors()).toEqual([])
    })

    it('carries the reason onto every write so each audit revision explains itself', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        f.form.reason = 'withdrawal was filed in error'
        f.form.milestoneNotes.END_OF_SUPPORT = 'reconfirmed'
        const sets = f.variableSets('c-1')
        expect(sets.length).toBeGreaterThan(1)
        for (const set of sets) expect(set.reason).toBe('withdrawal was filed in error')
    })
})

describe('a partial save leaves only the remainder pending', () => {
    /**
     * Writes are serialised, so call 1 of 2 can land and call 2 throw. Reporting that as a
     * plain failure makes the operator resubmit BOTH, and the milestone that already landed
     * gets written again -- re-stamping its lastAssessed for an assessment that happened
     * once, so the record claims two re-assessments where there was one.
     */
    it('drops the completed write and keeps the failed one pending', () => {
        const f = useAttestationForm()
        f.open(existing({
            supportMilestones: [
                { milestoneType: 'END_OF_SUPPORT', date: '2030-01-01', notes: 'a' },
                { milestoneType: 'END_OF_LIFE', date: '2031-01-01', notes: 'b' }
            ],
            endOfLifeDate: '2031-01-01'
        }))
        f.form.milestoneNotes.END_OF_SUPPORT = 'a2'
        f.form.milestoneNotes.END_OF_LIFE = 'b2'
        const sets = f.variableSets('c-1')
        expect(sets.length).toBe(2)

        // First landed, second threw.
        f.markSaved([sets[0]])

        const remaining = f.variableSets('c-1')
        expect(remaining.length).toBe(1)
        expect(remaining[0].supportNotes).toBe('b2')
        expect(remaining[0].endOfLifeDate).toBe('2031-01-01')
    })

    it('reports nothing pending once every write has landed', () => {
        const f = useAttestationForm()
        f.open(existing())
        f.form.milestoneNotes.END_OF_SUPPORT = 'reconfirmed'
        const sets = f.variableSets('c-1')
        f.markSaved(sets)
        expect(f.variableSets('c-1')).toEqual([])
        expect(f.canSubmit()).toBe(false)
    })

    // A landed un-retract must not be re-sent either.
    it('clears the pending un-retract once it has landed', () => {
        const f = useAttestationForm()
        f.open(existing({ attestationState: 'WITHDRAWN' }))
        f.form.reason = 'withdrawal was filed in error'
        const sets = f.variableSets('c-1')
        f.markSaved(sets)
        expect(f.form.state).toBeNull()
        expect(f.variableSets('c-1')).toEqual([])
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
