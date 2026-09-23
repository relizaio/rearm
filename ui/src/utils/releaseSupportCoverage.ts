// FDA-Readiness-1: release-scoped support-disclosure coverage, and whether exports carry it.

import gql from 'graphql-tag'
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
 * NO FALLBACK QUERY HERE, and the reason outlives the CE schema lag that first prompted it:
 * an org-wide coverage number rendered against one release -- "34 of 1,240 components across
 * your whole organisation" -- would be confidently wrong in a way an operator has no way to
 * detect. A narrower answer to a different question is not a degraded answer to this one. So
 * a server that cannot answer this query produces an ERROR the caller reports, never a
 * substitute number.
 *
 * Same rule as an absent deviceSupportRisk: absent is "not checked", never a default.
 */
export async function loadReleaseSupportCoverage (
    client: DriftFallbackClient,
    orgUuid: string,
    releaseUuid: string
): Promise<ReleaseSupportCoverage | null> {
    const resp = await client.query({
        query: RELEASE_SUPPORT_COVERAGE_QUERY,
        variables: { orgUuid, releaseUuid },
        fetchPolicy: 'network-only'
    })
    const cov = (resp.data as any)?.sbomComponentSupportCoverage
    // A THROW, not a null. The field is nullable on the wire, but the resolver always builds
    // a row -- an org with no components answers 0/0, and a bad org or a release outside it
    // is an error, never an empty answer. So nothing here means the response was malformed,
    // which is a failure and must reach the caller as one. Returning null would render it
    // through the display's "nothing to show yet" branch: a failure dressed as a benign
    // state, which is exactly what the note on coverageDisplay's error argument warns
    // against.
    if (!cov) throw new Error('the server returned no support coverage for this release')
    return {
        total: cov.total ?? 0,
        attested: cov.attested ?? 0,
        // Non-null on the wire, but a defensive fall-through to UNKNOWN rather than to a
        // state that would reassure: if the field ever arrives missing, "we do not know"
        // is the only answer that cannot mislead.
        exportState: (cov.supportExportState as SupportExportState) || 'UNKNOWN'
    }
}
