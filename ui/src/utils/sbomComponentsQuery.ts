// Release SBOM-components query, split CORE / FULL for schema-drift tolerance.
//
// The SAME UI ships to a Pro backend and to CE installs whose backend is a delayed
// mirror of Pro. `supportSuggestion` (FDA-Readiness-1 PR4) exists on Pro before it
// reaches the CE mirror, and GraphQL validates the WHOLE document -- so selecting it
// against a lagging CE backend would reject the entire query and blank the whole SBOM
// components tab, not just the suggestion chips. Suggestions are advisory, so degrading
// to CORE (table renders, no chips) is strictly better than showing nothing.
//
// Both documents are written out in full rather than composed from a shared fragment
// string, so both stay statically analysable: ui/scripts/validate-graphql.mjs skips
// dynamically built documents, and sbomComponentsSchemaDrift.spec.ts hard-gates CORE
// against the in-repo CE schema. A CORE selection that silently drifted off CE would
// defeat the entire point of having a fallback.

import gql from 'graphql-tag'
import { loadWithSchemaDriftFallback, type DriftFallbackClient } from '@/utils/graphqlDriftFallback'

export const SBOM_COMPONENTS_CORE_QUERY = gql`
    query getReleaseSbomComponentsCore($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {
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
                endOfSupportDate
                endOfLifeDate
                supportSource
                supportNotes
            }
            artifactParticipations {
                artifact
                exactPurls
            }
        }
    }`

export const SBOM_COMPONENTS_FULL_QUERY = gql`
    query getReleaseSbomComponentsList($releaseUuid: ID!) {
        getReleaseSbomComponents(releaseUuid: $releaseUuid) {
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
                endOfSupportDate
                endOfLifeDate
                supportSource
                supportNotes
                supportSuggestion {
                    endOfSupportDate
                    basis
                    product
                    cycle
                    matchedOn
                    extendedSupportDate
                    catalogAsOf
                    upstreamUrl
                }
            }
            artifactParticipations {
                artifact
                exactPurls
            }
        }
    }`

export interface SbomComponentsLoad {
    rows: any[]
    // True when only CORE was served: rows render, suggestion chips are absent.
    degraded: boolean
}

/**
 * Load a release's SBOM components, falling back to the CORE selection when the
 * deployed backend does not know the suggestion field.
 *
 * @param skipFull set once a prior load proved the backend rejects FULL, to avoid
 *                 paying the reject-then-retry round-trip on every later load.
 */
export async function loadSbomComponentsForRelease (
    client: DriftFallbackClient,
    releaseUuid: string,
    skipFull = false,
): Promise<SbomComponentsLoad> {
    const result = await loadWithSchemaDriftFallback(client, {
        fullQuery: SBOM_COMPONENTS_FULL_QUERY,
        coreQuery: SBOM_COMPONENTS_CORE_QUERY,
        variables: { releaseUuid },
        extractPath: (d: any) => d?.getReleaseSbomComponents,
        skipFull,
    })
    return { rows: result.data || [], degraded: result.degraded }
}
