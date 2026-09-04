// Release SBOM-components query.

import gql from 'graphql-tag'
import { isSchemaDriftError, loadWithSchemaDriftFallback } from './graphqlDriftFallback'
import type { DriftFallbackClient } from './graphqlDriftFallback'

/**
 * Which slice of a release's non-root components to load.
 *
 * Mirrors the backend SupportAttestationFilter enum. ATTESTED and UNATTESTED are evaluated
 * server-side with the SAME predicate the release-scoped coverage gauge counts with, which
 * is the point: the gauge says how many components are undisclosed and this filter is how
 * the operator selects exactly those. A client-side filter over a loaded page could not
 * agree with a gauge computed over the whole release.
 */
export type SupportAttestationFilter = 'ALL' | 'ATTESTED' | 'UNATTESTED'

/**
 * Everything the CE mirror schema can serve today.
 *
 * The CORE/FULL split is back, for exactly the reason it existed before #311 removed it as
 * "no longer needed": deviceSupportRisk is Pro-only until the CE schema sync lands, and this
 * component ships IN the CE repo. Verified rather than assumed -- supportStatus,
 * supportSource and endOfSupportDate DO exist on CE's SbomComponent; deviceSupportRisk is
 * the single field that does not.
 */
export const SBOM_COMPONENT_CORE_FIELDS = `
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
            }
            artifactParticipations {
                artifact
                exactPurls
            }`

/**
 * CORE plus the Pro-only device verdict. A caller served CORE must treat an absent
 * deviceSupportRisk as "not checked", never as "not at risk" -- isDeviceRiskFlagged already
 * does, since undefined is not a flagged value.
 */
export const SBOM_COMPONENT_FIELDS = SBOM_COMPONENT_CORE_FIELDS.replace(
    '                endOfSupportDate\n',
    '                endOfSupportDate\n                deviceSupportRisk\n')

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

/** The unpaged query, and the first fallback target when the paged one is unknown. */
export const SBOM_COMPONENTS_QUERY = gql`
    query getReleaseSbomComponentsList($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {${SBOM_COMPONENT_FIELDS}
        }
    }`

/** The last fallback: no Pro-only fields, so a CE backend can serve it. */
export const SBOM_COMPONENTS_QUERY_CORE = gql`
    query getReleaseSbomComponentsListCore($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {${SBOM_COMPONENT_CORE_FIELDS}
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
    /**
     * True when the server did not understand the paged query and the whole release was
     * loaded instead. The CE mirror schema lags Pro -- getReleaseSbomComponentsPage and
     * SupportAttestationFilter do not exist there until the schema sync lands -- and this
     * component ships IN the CE repo, so without the fallback a CE install would render the
     * SBOM Components tab as a toolbar over nothing.
     *
     * Callers must treat a degraded page as UNFILTERED: the server applied no attestation
     * filter and no search, so presenting it under a filter label would be a lie about which
     * components these are.
     */
    degraded: boolean
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
    try {
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
            hasMore: !!page?.hasMore,
            degraded: false
        }
    } catch (err: any) {
        // Only a schema-drift-shaped failure warrants the narrower retry. Transport, auth
        // and server errors surface unchanged -- including a rejected cursor, which must
        // reach the caller rather than silently becoming a full unfiltered reload.
        if (!isSchemaDriftError(err)) throw err
        // Two lag windows, so two steps, via the house helper: a Pro backend predating the
        // paged query still has deviceSupportRisk and should keep it; a CE backend has
        // neither and drops to CORE. Falling straight to CORE would throw away the device
        // verdict on the first of those for no reason.
        const res = await loadWithSchemaDriftFallback(client, {
            fullQuery: SBOM_COMPONENTS_QUERY,
            coreQuery: SBOM_COMPONENTS_QUERY_CORE,
            variables: { releaseUuid },
            extractPath: (d: any) => d?.getReleaseSbomComponents || []
        })
        const all = res.data as any[]
        return {
            items: all,
            totalCount: all.length,
            endCursor: null,
            // No cursor and nothing further to fetch: the fallback returned everything.
            hasMore: false,
            degraded: true
        }
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
