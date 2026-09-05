// Form state -> setSbomComponentSupport variables, honouring the mutation's PATCH contract.

import type { SupportAttestationFilter } from './sbomComponentsQuery'

export type LevelOfSupport = 'ACTIVELY_MAINTAINED' | 'NO_LONGER_MAINTAINED' | 'ABANDONED'
export type SupportParty = 'MANUFACTURER' | 'SUPPLIER' | 'THIRD_PARTY'
export type SupportMilestoneType =
    'END_OF_GUARANTEED_SUPPORT' | 'END_OF_SUPPORT' | 'END_OF_LIFE'

export interface AttestationForm {
    levelOfSupport: LevelOfSupport | null
    justification: string
    party: SupportParty | null
    endOfGuaranteedSupportDate: string | null
    endOfSupportDate: string | null
    endOfLifeDate: string | null
    supportNotes: string
    reason: string
    clearMilestones: SupportMilestoneType[]
    /**
     * Explicit "remove the recorded basis" action, NOT an empty text box.
     *
     * On this mutation an empty string is the clear signal, not an absence: the server reads
     * a supplied-but-blank justification as an instruction to null the stored basis, and
     * says so in its own error text. Deriving the clear from `justification === ''` would
     * make every untouched form a clear.
     */
    clearJustification: boolean
    /**
     * Set to ATTESTED only when re-asserting a WITHDRAWN attestation; null otherwise.
     *
     * Omitting it means PRESERVE, so a form that never sent it saved the justification and
     * the reason onto a still-withdrawn row -- the audit trail looked immaculate and the
     * component stayed withdrawn and uncounted. The bulk path already forces ATTESTED for
     * exactly this reason; the single-component form has to as well.
     */
    state: 'ATTESTED' | null
}

export function emptyAttestationForm (): AttestationForm {
    return {
        levelOfSupport: null,
        justification: '',
        party: null,
        endOfGuaranteedSupportDate: null,
        endOfSupportDate: null,
        endOfLifeDate: null,
        supportNotes: '',
        reason: '',
        clearMilestones: [],
        clearJustification: false,
        state: null
    }
}

/**
 * The mutation variables for a form, with untouched fields OMITTED.
 *
 * setSbomComponentSupport is a PATCH: an absent argument means "leave this alone", a
 * supplied one means "set it". So a form that posted every field on every save would
 * overwrite a level of support somebody else recorded with null each time a colleague
 * edited a date -- silently, because the write would succeed.
 *
 * The trap is blank strings rather than nulls: an untouched text input holds '', not null,
 * and '' is a value the server will happily store over an existing justification. Anything
 * blank or whitespace-only is therefore treated as untouched.
 *
 * A populated clearMilestones survives that rule deliberately: clearing is an explicit
 * instruction that happens to be about absence.
 */
export const MILESTONE_FIELD_TO_TYPE: Record<string, SupportMilestoneType> = {
    endOfGuaranteedSupportDate: 'END_OF_GUARANTEED_SUPPORT',
    endOfSupportDate: 'END_OF_SUPPORT',
    endOfLifeDate: 'END_OF_LIFE'
}

/**
 * @param baseline the stored attestation the form was seeded from. When supplied, fields
 *        EQUAL to it are omitted.
 *
 *        This is not an optimisation. A seeded form holds the stored dates, so without a
 *        diff every save re-sends them -- which re-stamps each milestone's lastAssessed and
 *        asserts a fresh assessment that never happened, quietly ageing the record forward.
 *        It also makes clearing impossible: sending endOfSupportDate while asking to clear
 *        END_OF_SUPPORT is rejected outright as "cannot set and clear the same milestone in
 *        one call".
 */
export function attestationVariables (
    sbomComponentUuid: string,
    form: AttestationForm,
    baseline?: Partial<Record<string, unknown>> | null
): Record<string, unknown> {
    const vars: Record<string, unknown> = { sbomComponentUuid }
    const text = (v: string) => (v && v.trim() ? v.trim() : undefined)
    const unchanged = (field: string, value: unknown): boolean =>
        !!baseline && (baseline[field] ?? null) === (value ?? null)

    if (form.levelOfSupport && !unchanged('attestedLevelOfSupport', form.levelOfSupport)) {
        vars.levelOfSupport = form.levelOfSupport
    }
    // The wire name is supportParty, not party. Pinned by a spec: a rename on either side
    // would stop sending it and the server would read that as "leave the party alone".
    if (form.party && !unchanged('supportParty', form.party)) vars.supportParty = form.party
    // Order matters: an explicit clear wins over typed text, so a user who types and then
    // clicks Clear gets the clear they asked for rather than the text they abandoned.
    if (form.clearJustification) vars.justification = ''
    else if (text(form.justification) && !unchanged('justification', text(form.justification))) {
        vars.justification = text(form.justification)
    }
    if (text(form.supportNotes) && !unchanged('supportNotes', text(form.supportNotes))) {
        vars.supportNotes = text(form.supportNotes)
    }
    if (text(form.reason)) vars.reason = text(form.reason)
    for (const field of Object.keys(MILESTONE_FIELD_TO_TYPE)) {
        const value = (form as any)[field] as string | null
        if (!value) continue
        // Never set a milestone this save is also clearing: the server refuses the pair, and
        // a seeded form holds the stored date, so a clear would otherwise always collide.
        if (form.clearMilestones.includes(MILESTONE_FIELD_TO_TYPE[field])) continue
        if (unchanged(field, value)) continue
        vars[field] = value
    }
    if (form.clearMilestones.length) vars.clearMilestones = form.clearMilestones
    if (form.state) vars.state = form.state
    return vars
}

/**
 * Problems to show BEFORE the round trip, phrased as the operator's problem.
 *
 * These mirror server-side guards rather than replacing them -- the server is still the
 * authority and will reject the same things. The point is that a form which lets you press
 * Save and then reports a validation failure has wasted the attempt and, on a slow link,
 * left you unsure whether anything was written.
 *
 * The ACTIVELY_MAINTAINED case is the exception: there is no server guard, because that
 * level does not require a basis. The form is the only thing standing between an operator
 * and a silently wiped justification, which is why the variables builder omits rather than
 * blanks.
 */
export function validateAttestation (form: AttestationForm): string[] {
    const errors: string[] = []
    const hasReason = !!(form.reason && form.reason.trim())
    if (form.clearJustification && !hasReason) {
        errors.push('Clearing the justification needs a reason: the audit row is the only'
            + ' record that it happened.')
    }
    if (form.clearMilestones.length && !hasReason) {
        errors.push('Clearing a date needs a reason: the audit row is the only record that it'
            + ' happened.')
    }
    const negative = form.levelOfSupport === 'NO_LONGER_MAINTAINED'
        || form.levelOfSupport === 'ABANDONED'
    if (negative && !form.justification.trim() && !form.clearJustification) {
        errors.push(`A level of ${form.levelOfSupport} is a negative claim about someone`
            + " else's project, so it needs a justification recording its basis.")
    }
    if (negative && form.clearJustification) {
        errors.push(`You cannot clear the justification while setting ${form.levelOfSupport}:`
            + ' that would leave an unsupported negative claim.')
    }
    return errors
}

/** Nothing to send beyond the id -- the caller should not fire a mutation at all. */
export function isEmptyAttestation (form: AttestationForm): boolean {
    return Object.keys(attestationVariables('x', form)).length === 1
}

// Re-exported so the form and the list agree on the filter type without a second import
// path; keeps the attestation module the single place the form imports from.
export type { SupportAttestationFilter }
