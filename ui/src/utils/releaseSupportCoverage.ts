// FDA-Readiness-1: release-scoped support-disclosure coverage, and whether exports carry it.

import gql from 'graphql-tag'
import { isSchemaDriftError } from './graphqlDriftFallback'
import type { DriftFallbackClient } from './graphqlDriftFallback'

/**
 * Whether an org's BOM exports carry the support properties the coverage counts describe.
 * Mirrors the backend SupportExportState enum.
 *
 * PARTIAL is the state today: injection rides only the native-CycloneDX artifact download,
 * so the release-level SBOM export -- the file a manufacturer actually attaches to a
 * submission -- carries none of it.
 */
export type SupportExportState = 'ENABLED' | 'DISABLED' | 'PARTIAL' | 'UNKNOWN'

/**
 * Coverage AND export state in one query, deliberately.
 *
 * They come from one resolver on the server for the same reason they are requested together
 * here: a full-coverage gauge rendered beside an export that silently carries nothing is the
 * failure this whole surface exists to prevent, and two requests can disagree.
 */
export const RELEASE_SUPPORT_COVERAGE_QUERY = gql`
    query sbomComponentSupportCoverageForRelease($orgUuid: ID!, $releaseUuid: ID) {
        sbomComponentSupportCoverage(orgUuid: $orgUuid, releaseUuid: $releaseUuid) {
            total
            attested
            supportExportState
        }
    }`

export interface ReleaseSupportCoverage {
    total: number
    attested: number
    exportState: SupportExportState
}

/**
 * The release's coverage, or null when this server cannot answer the question.
 *
 * NO FALLBACK QUERY HERE, and that is a deliberate difference from the component list. The
 * CE mirror's sbomComponentSupportCoverage takes only orgUuid -- it has no releaseUuid
 * argument at all -- so the only thing a CE server could answer is the ORG-WIDE number.
 * That is a different question, not a degraded answer to this one: "34 of 1,240 components
 * across your whole organisation" rendered against one release would be confidently wrong in
 * a way an operator has no way to detect. Returning null and letting the caller say "not
 * available on this server" is the honest failure.
 *
 * Same rule as an absent deviceSupportRisk: absent is "not checked", never a default.
 */
export async function loadReleaseSupportCoverage (
    client: DriftFallbackClient,
    orgUuid: string,
    releaseUuid: string
): Promise<ReleaseSupportCoverage | null> {
    try {
        const resp = await client.query({
            query: RELEASE_SUPPORT_COVERAGE_QUERY,
            variables: { orgUuid, releaseUuid },
            fetchPolicy: 'network-only'
        })
        const cov = (resp.data as any)?.sbomComponentSupportCoverage
        if (!cov) return null
        return {
            total: cov.total ?? 0,
            attested: cov.attested ?? 0,
            // Non-null on the wire, but a defensive fall-through to UNKNOWN rather than to a
            // state that would reassure: if the field ever arrives missing, "we do not know"
            // is the only answer that cannot mislead.
            exportState: (cov.supportExportState as SupportExportState) || 'UNKNOWN'
        }
    } catch (err: any) {
        // Schema drift means this server predates the release-scoped gauge or the export
        // state. Anything else -- auth, transport, a rejected org -- is a real error and
        // belongs with the caller.
        if (!isSchemaDriftError(err)) throw err
        return null
    }
}
