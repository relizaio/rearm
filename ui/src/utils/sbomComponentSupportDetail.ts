// The stored attestation for one component, loaded when the attest form opens.

import gql from 'graphql-tag'
import type { DriftFallbackClient } from './graphqlDriftFallback'
import type { ExistingAttestation } from './useAttestationForm'

/**
 * Deliberately NOT folded into the component-list query.
 *
 * These fields load when the form opens rather than with the component list, because that
 * is when they are needed -- the list does not render any of them.
 *
 * Read through getReleaseSbomComponentGraph, which is the only single-component read this
 * schema offers -- there is no root sbomComponent(uuid:) query. An earlier revision invented
 * one, and the cost was instructive: an unknown field is a GraphQL validation error, and
 * back when this file caught those and reported them as "this server is too old to store
 * attestations", a typo in MY query wore that costume -- a bug disguised as somebody else's
 * outdated backend. The catch is gone now that both schemas carry these fields, so such a
 * typo surfaces as the query error it is. Validate any change to this document against the
 * schema rather than trusting the runtime to describe it accurately.
 */
export const SBOM_COMPONENT_SUPPORT_DETAIL = gql`
    query sbomComponentSupportDetail($releaseUuid: ID!, $sbomComponentUuid: ID!) {
        getReleaseSbomComponentGraph(
            releaseUuid: $releaseUuid
            sbomComponentUuid: $sbomComponentUuid
        ) {
            component {
                uuid
                attestationState
                attestedLevelOfSupport
                justification
                supportParty
                endOfGuaranteedSupportDate
                endOfSupportDate
                endOfLifeDate
                supportMilestones {
                    milestoneType
                    date
                    notes
                }
            }
        }
    }`

/**
 * Kept as a tagged shape rather than collapsed to the attestation, because "no attestation
 * yet" is a null the caller must be able to tell from a failure -- they look alike and the
 * form seeds differently.
 */
export type SupportDetailResult =
    | { kind: 'ok', attestation: ExistingAttestation | null }

export async function loadSbomComponentSupportDetail (
    client: DriftFallbackClient,
    releaseUuid: string,
    sbomComponentUuid: string
): Promise<SupportDetailResult> {
    const resp = await client.query({
            query: SBOM_COMPONENT_SUPPORT_DETAIL,
            variables: { releaseUuid, sbomComponentUuid },
            // Never cached: the form seeds from this, and editing a stale copy is how one
            // operator silently overwrites another's attestation under PATCH semantics.
        fetchPolicy: 'network-only'
    })
    const c = (resp.data as any)?.getReleaseSbomComponentGraph?.component
    if (!c) return { kind: 'ok', attestation: null }
    // A component with no attestation at all still returns a row, with every support
    // field null. Seed from it either way -- the form handles both.
    return { kind: 'ok', attestation: c as ExistingAttestation }
}
