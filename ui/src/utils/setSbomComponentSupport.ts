// The per-component attestation write.

import gql from 'graphql-tag'
import { isSchemaDriftError } from './graphqlDriftFallback'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import { attestationVariables } from './supportAttestationInput'
import type { AttestationForm } from './supportAttestationInput'

/**
 * The full Pro mutation. Twelve arguments; the CE mirror currently declares four.
 *
 * THERE IS NO FALLBACK, and this is the opposite of the rule used for reads. A read that
 * degrades shows less; a WRITE that degrades stores less while reporting success. Falling
 * back to the CE signature would silently drop levelOfSupport and justification -- the two
 * fields section V.A.4(b) actually asks for -- and the operator would have no way to tell
 * from the UI that their attestation had been hollowed out. Refuse instead, and say what
 * would have been lost.
 */
export const SET_SBOM_COMPONENT_SUPPORT = gql`
    mutation setSbomComponentSupport(
        $sbomComponentUuid: ID!
        $levelOfSupport: LevelOfSupport
        $justification: String
        $supportParty: SupportParty
        $endOfGuaranteedSupportDate: String
        $endOfSupportDate: String
        $endOfLifeDate: String
        $supportNotes: String
        $clearMilestones: [SupportMilestoneType!]
        $reason: String
    ) {
        setSbomComponentSupport(
            sbomComponentUuid: $sbomComponentUuid
            levelOfSupport: $levelOfSupport
            justification: $justification
            supportParty: $supportParty
            endOfGuaranteedSupportDate: $endOfGuaranteedSupportDate
            endOfSupportDate: $endOfSupportDate
            endOfLifeDate: $endOfLifeDate
            supportNotes: $supportNotes
            clearMilestones: $clearMilestones
            reason: $reason
        ) {
            uuid
            supportStatus
            supportSource
            endOfSupportDate
            attestedLevelOfSupport
            justification
        }
    }`

/**
 * Names what a narrower server cannot store, rather than saying "unsupported".
 *
 * An operator told "this server does not support that" has no idea whether to retry, work
 * around it, or escalate. An operator told the level of support and the justification would
 * not be saved knows immediately that the thing they came to record is the thing that
 * cannot be recorded.
 */
export const DRIFT_REFUSAL =
    'This server cannot store a full support attestation: it accepts only the milestone'
    + ' dates and internal notes, so the level of support and the justification would be'
    + ' silently dropped. Nothing was written. Upgrade the backend before attesting.'

export interface AttestationResult {
    uuid: string
    supportStatus: string | null
    supportSource: string | null
    endOfSupportDate: string | null
    attestedLevelOfSupport: string | null
    justification: string | null
}

/**
 * Write one component's attestation. Throws on refusal or failure; never partially writes.
 */
export async function setSbomComponentSupport (
    client: DriftFallbackClient,
    sbomComponentUuid: string,
    form: AttestationForm
): Promise<AttestationResult> {
    try {
        const resp = await (client as any).mutate({
            mutation: SET_SBOM_COMPONENT_SUPPORT,
            variables: attestationVariables(sbomComponentUuid, form)
        })
        return (resp.data as any)?.setSbomComponentSupport
    } catch (err: any) {
        if (isSchemaDriftError(err)) throw new Error(DRIFT_REFUSAL)
        throw err
    }
}
