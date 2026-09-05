// The stored attestation for one component, loaded when the attest form opens.

import gql from 'graphql-tag'
import { isSchemaDriftError } from './graphqlDriftFallback'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { ExistingAttestation } from './useAttestationForm'

/**
 * Deliberately NOT folded into the component-list query.
 *
 * Four of these fields -- attestationState, attestedLevelOfSupport, supportParty,
 * endOfGuaranteedSupportDate -- do not exist on the CE mirror. Adding them to the list
 * would make the list itself CE-invalid and defeat the CORE fallback that keeps the SBOM
 * tab working there. They are only needed when the form opens, so they load then.
 *
 * The form is Pro-only as a whole in any case: the write refuses on a CE server because the
 * mutation there cannot store a level or a justification. So a drift here and a drift on the
 * write mean the same thing to the operator, and carry the same message.
 */
export const SBOM_COMPONENT_SUPPORT_DETAIL = gql`
    query sbomComponentSupportDetail($uuid: ID!) {
        sbomComponent(uuid: $uuid) {
            uuid
            attestationState
            attestedLevelOfSupport
            justification
            supportParty
            endOfGuaranteedSupportDate
            endOfSupportDate
            endOfLifeDate
            supportNotes
        }
    }`

/** Distinguishes "this server cannot answer" from "no attestation yet", which look alike. */
export type SupportDetailResult =
    | { kind: 'ok', attestation: ExistingAttestation | null }
    | { kind: 'unsupported' }

export async function loadSbomComponentSupportDetail (
    client: DriftFallbackClient,
    uuid: string
): Promise<SupportDetailResult> {
    try {
        const resp = await client.query({
            query: SBOM_COMPONENT_SUPPORT_DETAIL,
            variables: { uuid },
            // Never cached: the form seeds from this, and editing a stale copy is how one
            // operator silently overwrites another's attestation under PATCH semantics.
            fetchPolicy: 'network-only'
        })
        const c = (resp.data as any)?.sbomComponent
        if (!c) return { kind: 'ok', attestation: null }
        // A component with no attestation at all still returns a row, with every support
        // field null. Seed from it either way -- the form handles both.
        return { kind: 'ok', attestation: c as ExistingAttestation }
    } catch (err: any) {
        if (isSchemaDriftError(err)) return { kind: 'unsupported' }
        throw err
    }
}
