import { reactive } from 'vue'
import {
    emptyAttestationForm,
    validateAttestation,
    isEmptyAttestation,
    attestationVariables,
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
    let seeded: ExistingAttestation | null = null
    let confirmedForDates = ''

    function snapshotDates (): string {
        return MILESTONE_FIELDS.map(f => form[f] ?? '').join('|')
    }

    function open (existing: ExistingAttestation | null): void {
        Object.assign(form, emptyAttestationForm())
        seeded = existing
        confirmedForDates = ''
        if (!existing) return
        form.levelOfSupport = existing.attestedLevelOfSupport ?? null
        form.justification = existing.justification ?? ''
        form.party = existing.supportParty ?? null
        form.endOfGuaranteedSupportDate = existing.endOfGuaranteedSupportDate ?? null
        form.endOfSupportDate = existing.endOfSupportDate ?? null
        form.endOfLifeDate = existing.endOfLifeDate ?? null
        form.supportNotes = existing.supportNotes ?? ''
        // Re-asserting is the POINT of opening a withdrawn attestation, and it does not
        // happen by itself: state is preserved when omitted, so saving without this leaves
        // the row withdrawn and still uncounted while every other field updates.
        form.state = existing.attestationState === 'WITHDRAWN' ? 'ATTESTED' : null
    }

    /** Re-asserting a withdrawn claim is a reversal; the audit row is its only trace. */
    function isUnRetract (): boolean {
        return seeded?.attestationState === 'WITHDRAWN'
    }

    function milestoneChanged (): boolean {
        if (!seeded) return MILESTONE_FIELDS.some(f => !!form[f])
        return MILESTONE_FIELDS.some(f => (form[f] ?? null) !== (seeded?.[f] ?? null))
    }

    function justificationRevised (): boolean {
        return (form.justification ?? '').trim() !== (seeded?.justification ?? '').trim()
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
        if (!seeded?.justification || !seeded.justification.trim()) return false
        if (!milestoneChanged()) return false
        if (justificationRevised()) return false
        return confirmedForDates !== snapshotDates()
    }

    function confirmJustification (): void {
        confirmedForDates = snapshotDates()
    }

    /**
     * "Assessed, nothing published": a justification ALONE, which is a complete attestation
     * when a supplier will not state a level, and exactly the path the server's fresh-row
     * guard accepts.
     *
     * Resets the rest rather than layering on top, so a half-typed level cannot ride along
     * into a submission that is meant to say only "we asked, and this is what we found".
     */
    function assessedNothingPublished (justification: string): void {
        const notes = form.supportNotes
        Object.assign(form, emptyAttestationForm())
        form.justification = justification
        form.supportNotes = notes
    }

    function errors (): string[] {
        const out = validateAttestation(form)
        if (isUnRetract() && !form.reason.trim()) {
            out.push('This attestation was withdrawn. Re-asserting it needs a reason: the'
                + ' audit row is the only record that the withdrawal was reversed.')
        }
        if (needsJustificationDecision()) {
            out.push('A date changed. Confirm the recorded basis still holds, or revise it --'
                + ' it was written for the previous dates.')
        }
        return out
    }

    function dirty (): boolean {
        if (!seeded) return !isEmptyAttestation(form)
        // Re-asserting a withdrawn attestation IS the change, even when no field differs.
        // Without this the form refuses a pure un-retract -- reason supplied, nothing else
        // edited -- which is the most likely un-retract there is: the original claim was
        // right and the withdrawal was the mistake.
        if (form.state === 'ATTESTED') return true
        return milestoneChanged() || justificationRevised()
            || (form.levelOfSupport ?? null) !== (seeded.attestedLevelOfSupport ?? null)
            || (form.party ?? null) !== (seeded.supportParty ?? null)
            || (form.supportNotes ?? '').trim() !== (seeded.supportNotes ?? '').trim()
            || form.clearJustification || form.clearMilestones.length > 0
    }

    function canSubmit (): boolean {
        return dirty() && errors().length === 0 && !isEmptyAttestation(form)
    }

    /** Variables for this save, diffed against what the form was seeded with. */
    function variables (sbomComponentUuid: string): Record<string, unknown> {
        return attestationVariables(sbomComponentUuid, form, seeded as any)
    }

    return {
        form,
        variables,
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
