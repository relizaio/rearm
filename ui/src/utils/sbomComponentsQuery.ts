// Release SBOM-components query.

import gql from 'graphql-tag'
import type { DriftFallbackClient } from './graphqlDriftFallback'

/**
 * Which slice of a release's non-root components to load.
 *
 * Mirrors the backend SupportAttestationFilter enum. ATTESTED and UNATTESTED are evaluated
 * server-side with the SAME predicate the coverage gauge counts with, which is the point:
 * the gauge says how many components are undisclosed and this filter is how the operator
 * selects exactly those. A client-side filter over a loaded page could not agree with a
 * gauge computed over the whole release.
 */
export type SupportAttestationFilter = 'ALL' | 'ATTESTED' | 'UNATTESTED'

export const SBOM_COMPONENT_FIELDS = `
            uuid
            sbomComponentUuid
            component {
                uuid
                canonicalPurl
                type
                group
                name
                version
                isRoot
                supportStatus
                supportSource
                endOfSupportDate
                deviceSupportRisk
            }
            artifactParticipations {
                artifact
                exactPurls
            }`

/**
 * Paged, filtered load. Deliberately NOT selecting dependencies / dependedOnBy / ancestors:
 * on the paged query the server hydrates only the page, so those three report edges WITHIN
 * the page rather than within the release -- wrong data presented as complete. The graph
 * view uses the unpaged query for that.
 */
export const SBOM_COMPONENTS_PAGE_QUERY = gql`
    query getReleaseSbomComponentsPage(
        $releaseUuid: ID!
        $attestation: SupportAttestationFilter
        $search: String
        $limit: Int
        $after: ID
    ) {
        getReleaseSbomComponentsPage(
            releaseUuid: $releaseUuid
            attestation: $attestation
            search: $search
            limit: $limit
            after: $after
        ) {
            items {${SBOM_COMPONENT_FIELDS}
            }
            totalCount
            limit
            endCursor
            hasMore
        }
    }`

/** The unpaged query, still used where the whole release is genuinely needed. */
export const SBOM_COMPONENTS_QUERY = gql`
    query getReleaseSbomComponentsList($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {${SBOM_COMPONENT_FIELDS}
        }
    }`

export interface SbomComponentsPage {
    items: any[]
    /**
     * Components matching the filter across all pages. Under UNATTESTED this is a moving
     * target BY DESIGN -- it is the size of the remaining work, so it falls as rows are
     * attested. Never derive "is there another page" from it; use hasMore.
     */
    totalCount: number
    endCursor: string | null
    hasMore: boolean
}

/**
 * Load one page of a release's components.
 *
 * KEYSET, not offset: `after` is the uuid of the last item seen. UNATTESTED is a predicate
 * over mutable state, so attesting a page removes those rows -- an offset would index into a
 * set that had shifted underneath it and silently skip exactly as many components as were
 * just written. Pass `endCursor` from the previous page; omit for the first.
 *
 * An unrecognised cursor is an error from the server, never a silent restart. Callers
 * walking the cursor should surface that rather than looping from the beginning.
 *
 * Always hits the network -- callers (e.g. the post-reconcile refresh, and any walk that
 * writes as it goes) rely on this never serving a stale Apollo-cached response.
 */
export async function loadSbomComponentsPage (
    client: DriftFallbackClient,
    releaseUuid: string,
    opts: {
        attestation?: SupportAttestationFilter,
        search?: string,
        limit?: number,
        after?: string | null
    } = {}
): Promise<SbomComponentsPage> {
    const resp = await client.query({
        query: SBOM_COMPONENTS_PAGE_QUERY,
        variables: {
            releaseUuid,
            attestation: opts.attestation || 'ALL',
            // Empty string is not a search. Sending one would ask the server to match a
            // literal empty substring, which is every row -- harmless but it makes the
            // request look like a filtered one in logs and in the cache key.
            search: opts.search && opts.search.trim() ? opts.search.trim() : null,
            limit: opts.limit || 50,
            after: opts.after || null
        },
        fetchPolicy: 'network-only'
    })
    const page = (resp.data as any)?.getReleaseSbomComponentsPage
    return {
        items: page?.items || [],
        totalCount: page?.totalCount || 0,
        endCursor: page?.endCursor || null,
        hasMore: !!page?.hasMore
    }
}

/**
 * Load a release's SBOM components, unpaged. Always hits the network -- callers
 * (e.g. the post-reconcile refresh) rely on this never serving a stale
 * Apollo-cached response.
 */
export async function loadSbomComponentsForRelease (
    client: DriftFallbackClient,
    releaseUuid: string,
): Promise<any[]> {
    const resp = await client.query({
        query: SBOM_COMPONENTS_QUERY,
        variables: { releaseUuid },
        fetchPolicy: 'network-only'
    })
    return (resp.data as any)?.getReleaseSbomComponents || []
}
