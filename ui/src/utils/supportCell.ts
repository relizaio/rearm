// The release SBOM Components list's Support cell, as a pure model (FDAW-68).
//
// The decisions live here so they can be tested without naive-ui; supportCellRender.ts turns
// the model into the column's VNodes. The gate is attestationState: the server serves it
// exactly when a support record exists, whoever wrote it and whatever it holds.
//
// NOT the END_OF_SUPPORT milestone's source. That field is the milestone's own provenance
// (the schema says so), null on every record without an end-of-support date: a level, a
// justification or a justified unknown alone leaves it null. Gating on it rendered those
// records as "nobody has recorded anything" (FDAW-65 acceptance 3, FDAW-14 row 21 (a)).

import { isWithdrawnAttestation, supportTag, WITHDRAWN_TAG } from './supportStatusTag'
import type { SupportTagType } from './supportStatusTag'
import { justifiedUnknownLabel } from './justifiedUnknownLabels'

export type SupportCell =
    | { kind: 'none' }
    | { kind: 'withdrawn', tag: { type: SupportTagType, label: string } }
    | { kind: 'live', tag: { type: SupportTagType, label: string }, level: string | null,
        eosDate: string | null, unknownLabel: string | null, unknownBulk: boolean }

/**
 * The cell for one component.
 *
 * - none: no support record (attestationState absent or empty). Nothing else is read.
 * - withdrawn: a retracted claim. The Withdrawn tag only: its level, date and unknown stay
 *   off screen, because printing them beside "Withdrawn" would put the retracted facts back as
 *   though they still stood.
 * - live: any other state, including one this build does not know (it renders its derived
 *   status rather than a dash that would claim no record exists). The derived status tag, the
 *   attested level text, the end-of-support date, or, when there is no date, the justified
 *   unknown's label and bulk flag (the date wins; the server never stores both).
 */
export function supportCellOf (c: any): SupportCell {
    if (c === null || c === undefined) return { kind: 'none' }
    const state = c.attestationState
    if (state === null || state === undefined || state === '') return { kind: 'none' }
    if (isWithdrawnAttestation(state)) return { kind: 'withdrawn', tag: WITHDRAWN_TAG }
    const eosDate: string | null = c.endOfSupportDate || null
    const code = c.endOfSupportUnknown?.code
    const hasUnknown = !eosDate && typeof code === 'string' && code !== ''
    return {
        kind: 'live',
        tag: supportTag(c.supportStatus),
        level: c.attestedLevelOfSupportText || null,
        eosDate,
        unknownLabel: hasUnknown ? justifiedUnknownLabel(code) : null,
        unknownBulk: hasUnknown ? !!c.endOfSupportUnknown.bulkAct : false
    }
}
