import { reactive, ref, type Ref } from 'vue'
import {
    emptyAttestationForm,
    validateAttestation,
    isEmptyAttestation,
    attestationVariables,
    MILESTONE_FIELD_TO_TYPE,
    MILESTONE_TYPE_TO_FIELD,
    type AttestationForm,
    type LevelOfSupport,
    type SupportParty
} from './supportAttestationInput'

/** The stored attestation a component already carries, as the list/detail query returns it. */
export interface ExistingAttestation {
    attestationState?: string | null
    attestedLevelOfSupport?: LevelOfSupport | null
    justification?: string | null
    supportParty?: SupportParty | null
    endOfGuaranteedSupportDate?: string | null
    endOfSupportDate?: string | null
    endOfLifeDate?: string | null
    supportNotes?: string | null
    supportMilestones?: Array<{ milestoneType: string, date?: string | null, notes?: string | null }>
}

const MILESTONE_FIELDS = [
    'endOfGuaranteedSupportDate', 'endOfSupportDate', 'endOfLifeDate'
] as const

/**
 * The attestation form's state machine, outside the SFC so it can be tested.
 *
 * Everything here is a rule about WHEN a save is allowed, and those rules are the substance
 * of the form -- the markup is just inputs. Keeping them in the component would put them
 * beyond reach of a test, which on this feature has already meant shipping a screen that
 * rendered nothing.
 */
export function useAttestationForm () {
    const form = reactive(emptyAttestationForm()) as AttestationForm
    // REFS, not plain closure variables. needsJustificationDecision() reads both, and the
    // template reads it through errors()/canSubmit(); with plain `let`, clicking "It still
    // holds" mutated nothing Vue tracks, so the warning stayed on screen and Save stayed
    // disabled until the operator happened to touch another field. The two sibling
    // composables on this track keep every value a consumer reads in a ref for this reason,
    // and use plain `let` only for things nothing renders.
    const seededRef: Ref<ExistingAttestation | null> = ref(null)
    const confirmedForDates = ref('')

    function snapshotDates (): string {
        return MILESTONE_FIELDS.map(f => form[f] ?? '').join('|')
    }

    function open (existing: ExistingAttestation | null): void {
        Object.assign(form, emptyAttestationForm())
        seededRef.value = existing
        confirmedForDates.value = ''
        if (!existing) return
        form.levelOfSupport = existing.attestedLevelOfSupport ?? null
        form.justification = existing.justification ?? ''
        form.party = existing.supportParty ?? null
        form.endOfGuaranteedSupportDate = existing.endOfGuaranteedSupportDate ?? null
        form.endOfSupportDate = existing.endOfSupportDate ?? null
        form.endOfLifeDate = existing.endOfLifeDate ?? null
        // Seeded from each milestone's OWN notes, not from the flat supportNotes field --
        // that field is only the END_OF_SUPPORT milestone's notes under another name.
        for (const m of existing.supportMilestones ?? []) {
            if (m.milestoneType in form.milestoneNotes) {
                (form.milestoneNotes as any)[m.milestoneType] = m.notes ?? ''
            }
        }
        // Re-asserting is the POINT of opening a withdrawn attestation, and it does not
        // happen by itself: state is preserved when omitted, so saving without this leaves
        // the row withdrawn and still uncounted while every other field updates.
        form.state = existing.attestationState === 'WITHDRAWN' ? 'ATTESTED' : null
    }

    /** Re-asserting a withdrawn claim is a reversal; the audit row is its only trace. */
    function isUnRetract (): boolean {
        return seededRef.value?.attestationState === 'WITHDRAWN'
    }

    function milestoneChanged (): boolean {
        if (!seededRef.value) return MILESTONE_FIELDS.some(f => !!form[f])
        return MILESTONE_FIELDS.some(f => (form[f] ?? null) !== (seededRef.value?.[f] ?? null))
    }

    function justificationRevised (): boolean {
        return (form.justification ?? '').trim() !== (seededRef.value?.justification ?? '').trim()
    }

    /**
     * A stored basis was written for the dates that were there at the time. Move a date and
     * it silently vouches for a claim it was never about -- the substantiation record going
     * stale without anyone deciding it should.
     *
     * Confirming is scoped to the dates confirmed FOR, so a further edit re-asks: "still
     * true" is a statement about a specific claim, not a permanent dismissal.
     */
    function needsJustificationDecision (): boolean {
        const basis = seededRef.value?.justification
        if (!basis || !basis.trim()) return false
        if (!milestoneChanged()) return false
        if (justificationRevised()) return false
        return confirmedForDates.value !== snapshotDates()
    }

    function confirmJustification (): void {
        confirmedForDates.value = snapshotDates()
    }

    /**
     * "Assessed, nothing published": a justification ALONE, which is a complete attestation
     * when a supplier will not state a level, and exactly the path the server's fresh-row
     * guard accepts.
     *
     * Resets the rest rather than layering on top, so a half-typed level cannot ride along
     * into a submission that is meant to say only "we asked, and this is what we found".
     */
    /**
     * True when the card can honour its own promise.
     *
     * It says "your basis alone -- no level, no dates". Under PATCH, blanking those in the
     * form OMITS them, and omit means PRESERVE -- so on a row that already carries a level,
     * the card would leave that level published against a justification written to mean the
     * opposite. There is no way to unset a level at all: the mutation has no argument for it.
     * So the card is offered only where it can tell the truth.
     */
    function canUseNothingPublished (): boolean {
        const seeded = seededRef.value
        if (!seeded) return true
        return !seeded.attestedLevelOfSupport
            && !seeded.endOfGuaranteedSupportDate
            && !seeded.endOfSupportDate
            && !seeded.endOfLifeDate
    }

    function assessedNothingPublished (justification: string): void {
        // state and reason survive the reset. state carries a PENDING UN-RETRACT, which is
        // set at open() precisely because omitting it means preserve -- dropping it here made
        // this card silently cancel the re-assertion, leaving the row withdrawn and
        // uncounted behind a success toast. That is the same defect this feature already
        // shipped once. reason survives because the guards that demand one still apply.
        const state = form.state
        const reason = form.reason
        const notes = { ...form.milestoneNotes }
        Object.assign(form, emptyAttestationForm())
        form.justification = justification
        form.state = state
        form.reason = reason
        // Milestone notes are restored, not blanked. Blanking them reads as an EDIT to each
        // milestone's notes -- against dates this same reset just cleared -- so the form
        // demanded a date for a note the operator never touched.
        form.milestoneNotes = notes
    }

    /**
     * Fold completed writes into the baseline, so they stop counting as pending edits.
     *
     * Needed because a save can be PARTIAL: the writes are serialised, so call 1 of 2 can
     * land and call 2 throw. Without this the form still shows both edits as outstanding,
     * the operator resubmits, and milestone 1 is written a second time -- re-stamping its
     * lastAssessed for an assessment that happened once. The record would then claim two
     * re-assessments where there was one.
     */
    function markSaved (sets: Array<Record<string, unknown>>): void {
        const seeded = (seededRef.value ?? {}) as any
        for (const set of sets) {
            for (const [type, field] of Object.entries(MILESTONE_TYPE_TO_FIELD)) {
                if (set[field] !== undefined && set.supportNotes !== undefined) {
                    const list = (seeded.supportMilestones ?? []).slice()
                    const at = list.findIndex((m: any) => m.milestoneType === type)
                    const row = { milestoneType: type, date: set[field] as string,
                        notes: set.supportNotes as string }
                    if (at >= 0) list[at] = { ...list[at], ...row }
                    else list.push(row)
                    seeded.supportMilestones = list
                }
            }
            if (set.levelOfSupport !== undefined) seeded.attestedLevelOfSupport = set.levelOfSupport
            if (set.supportParty !== undefined) seeded.supportParty = set.supportParty
            if (set.justification !== undefined) seeded.justification = set.justification
            for (const field of Object.values(MILESTONE_TYPE_TO_FIELD)) {
                if (set[field] !== undefined && set.supportNotes === undefined) {
                    seeded[field] = set[field]
                }
            }
            if (set.state !== undefined) seeded.attestationState = set.state
        }
        seededRef.value = { ...seeded }
        // Re-seed ONLY the milestones that landed. Re-seeding all of them would overwrite a
        // still-pending edit on another milestone with its stored value -- silently
        // discarding the very change the operator is about to retry.
        for (const set of sets) {
            for (const [type, field] of Object.entries(MILESTONE_TYPE_TO_FIELD)) {
                if (set[field] !== undefined && set.supportNotes !== undefined
                        && type in form.milestoneNotes) {
                    (form.milestoneNotes as any)[type] = set.supportNotes as string
                }
            }
        }
        if ((seededRef.value as any).attestationState !== 'WITHDRAWN') form.state = null
    }

    function errors (): string[] {
        const out = validateAttestation(form)
        if (isUnRetract() && !form.reason.trim()) {
            out.push('This attestation was withdrawn. Re-asserting it needs a reason: the'
                + ' audit row is the only record that the withdrawal was reversed.')
        }
        // Set-and-clear is only a contradiction when the operator has staged a NEW value.
        // A seeded date sitting in the box alongside a clear is the ordinary case -- that is
        // what clearing a recorded date looks like -- and the variables builder drops it.
        for (const [field, type] of Object.entries(MILESTONE_FIELD_TO_TYPE)) {
            const staged = (form as any)[field] as string | null
            const stored = (seededRef.value as any)?.[field] ?? null
            if (form.clearMilestones.includes(type) && staged && staged !== stored) {
                out.push(`You are both setting and clearing ${type.replace(/_/g, ' ').toLowerCase()}.`
                    + ' Pick one: clear it, or enter the new date.')
            }
        }
        for (const type of editedMilestoneNotes()) {
            const field = MILESTONE_TYPE_TO_FIELD[type as keyof typeof MILESTONE_TYPE_TO_FIELD]
            if (!(form as any)[field]) {
                out.push(`Notes for ${type.replace(/_/g, ' ').toLowerCase()} need a date --`
                    + ' the note is stored against the date, so set the date first.')
            }
        }
        if (needsJustificationDecision()) {
            out.push('A date changed. Confirm the recorded basis still holds, or revise it --'
                + ' it was written for the previous dates.')
        }
        return out
    }

    function dirty (): boolean {
        const seeded = seededRef.value
        if (!seeded) return !isEmptyAttestation(form)
        // Re-asserting a withdrawn attestation IS the change, even when no field differs.
        // Without this the form refuses a pure un-retract -- reason supplied, nothing else
        // edited -- which is the most likely un-retract there is: the original claim was
        // right and the withdrawal was the mistake.
        if (form.state === 'ATTESTED') return true
        return milestoneChanged() || justificationRevised()
            || (form.levelOfSupport ?? null) !== (seeded.attestedLevelOfSupport ?? null)
            || (form.party ?? null) !== (seeded.supportParty ?? null)
            || editedMilestoneNotes().length > 0
            || form.clearJustification || form.clearMilestones.length > 0
    }

    /**
     * Gated on what this save would actually SEND, diffed against the seed.
     *
     * It used to use isEmptyAttestation, which builds variables with NO baseline and so
     * answers "does the form contain any text" -- true for any seeded row. Emptying a box
     * therefore enabled Save, the diff then omitted the emptied field, and the mutation went
     * out as the component id alone: a green "Saved. Support attestation recorded." for a
     * write that changed nothing. On a form whose purpose is a defensible record, a success
     * message for a no-op is worse than an error.
     */
    function sendsAnything (): boolean {
        return variableSets('x').length > 0
    }

    function canSubmit (): boolean {
        return dirty() && errors().length === 0 && sendsAnything()
    }

    function storedNote (type: string): string {
        const m = (seededRef.value?.supportMilestones ?? [])
            .find(x => x.milestoneType === type)
        return (m?.notes ?? '').trim()
    }

    /** Milestone types whose notes the operator has edited. */
    function editedMilestoneNotes (): string[] {
        return Object.keys(form.milestoneNotes)
            .filter(t => (form.milestoneNotes as any)[t].trim() !== storedNote(t))
    }

    /** Variables for this save, diffed against what the form was seeded with. */
    function variables (sbomComponentUuid: string): Record<string, unknown> {
        return attestationVariables(sbomComponentUuid, form, seededRef.value as any)
    }

    /**
     * Every write this save needs, in order.
     *
     * SERIALISED because the mutation takes ONE supportNotes argument: notes for two
     * milestones in a single call would land on both, cross-contaminating the evidence for
     * each date. One call per edited milestone instead, each its own audit revision, which
     * is the honest shape rather than a clever one.
     *
     * A notes write carries that milestone's own date UNCHANGED, because the server only
     * writes notes onto milestones it is staging. That re-stamps the milestone's
     * lastAssessed, which is correct: revising what you checked is a re-assessment of that
     * milestone. The diff rule is "never send a date the operator did not touch" -- editing
     * its notes counts as touching it.
     */
    function variableSets (sbomComponentUuid: string): Array<Record<string, unknown>> {
        const sets: Array<Record<string, unknown>> = []
        const base = variables(sbomComponentUuid)
        // A reason on its own is not a change. It annotates one, so a base set carrying
        // nothing but the id and a reason would write an audit revision explaining an edit
        // that never happened.
        const substantive = Object.keys(base).filter(k => k !== 'sbomComponentUuid' && k !== 'reason')
        if (substantive.length > 0) sets.push(base)
        const reason = form.reason.trim()
        for (const type of editedMilestoneNotes()) {
            const field = MILESTONE_TYPE_TO_FIELD[type as keyof typeof MILESTONE_TYPE_TO_FIELD]
            const date = (form as any)[field]
            if (!date) continue
            const set: Record<string, unknown> = {
                sbomComponentUuid,
                [field]: date,
                supportNotes: (form.milestoneNotes as any)[type].trim()
            }
            // Carried onto every write so each audit revision explains itself rather than
            // leaving the follow-up rows unexplained.
            if (reason) set.reason = reason
            sets.push(set)
        }
        return sets
    }

    return {
        form,
        variables,
        sendsAnything,
        variableSets,
        editedMilestoneNotes,
        markSaved,
        canUseNothingPublished,
        open,
        isUnRetract,
        milestoneChanged,
        needsJustificationDecision,
        confirmJustification,
        assessedNothingPublished,
        errors,
        dirty,
        canSubmit
    }
}
