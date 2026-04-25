import gql from 'graphql-tag'
import graphqlClient from './graphql'

/**
 * Per-org cache of configuredBaseIntegrations. The list is keyed by org uuid;
 * a missing entry means we haven't loaded yet for that org. Cleared per-page
 * mount in practice — rearm UI doesn't re-mount the same org constantly.
 */
const dtrackConfiguredCache = new Map<string, boolean>()

export async function isDtrackConfiguredForOrg (orgUuid: string): Promise<boolean> {
    if (!orgUuid) return false
    const cached = dtrackConfiguredCache.get(orgUuid)
    if (cached !== undefined) return cached
    try {
        const resp = await graphqlClient.query({
            query: gql`
                query configuredBaseIntegrations($org: ID!) {
                    configuredBaseIntegrations(org: $org)
                }`,
            variables: { org: orgUuid },
            fetchPolicy: 'cache-first'
        })
        const list: string[] = resp.data?.configuredBaseIntegrations || []
        const has = list.includes('DEPENDENCYTRACK')
        dtrackConfiguredCache.set(orgUuid, has)
        return has
    } catch {
        // Conservatively treat lookup failure as "not configured" so we don't
        // mislabel artifacts as DTrack-pending in an org that may never have
        // wired the integration up.
        dtrackConfiguredCache.set(orgUuid, false)
        return false
    }
}

export type ReleaseScanStatusKind = 'enrichment-pending' | 'dtrack-pending' | 'ready'

export interface ReleaseScanStatus {
    kind: ReleaseScanStatusKind
    label: string
    title: string
}

/**
 * Decide whether to show a pending badge or fall through to the regular
 * vulnerability/violation circles for a release row in a list view.
 *
 *  enrichment-pending  any BOM artifact still being enriched in rebom
 *  dtrack-pending      DTrack is configured but at least one BOM artifact
 *                      hasn't been submitted yet (no project UUID, no failure)
 *  ready               nothing pending — caller renders the existing circles
 */
export function getReleaseScanStatus (release: any, dtrackConfigured: boolean): ReleaseScanStatus {
    const artifacts: any[] = collectArtifactsForStatus(release)
    const hasEnrichmentPending = artifacts.some((a) => a?.enrichmentStatus === 'PENDING')
    if (hasEnrichmentPending) {
        return {
            kind: 'enrichment-pending',
            label: 'Enriching…',
            title: 'BOM enrichment in progress for at least one artifact'
        }
    }
    if (dtrackConfigured) {
        const hasDtrackPending = artifacts.some((a) => isBomDtrackPending(a))
        if (hasDtrackPending) {
            return {
                kind: 'dtrack-pending',
                label: 'DTrack pending',
                title: 'Awaiting Dependency-Track submission for at least one BOM'
            }
        }
    }
    return { kind: 'ready', label: '', title: '' }
}

function collectArtifactsForStatus (release: any): any[] {
    const out: any[] = []
    if (Array.isArray(release?.artifactDetails)) out.push(...release.artifactDetails)
    if (Array.isArray(release?.sourceCodeEntryDetails?.artifactDetails)) {
        out.push(...release.sourceCodeEntryDetails.artifactDetails)
    }
    if (Array.isArray(release?.variantDetails)) {
        for (const v of release.variantDetails) {
            const dels = v?.outboundDeliverableDetails || []
            for (const d of dels) {
                if (Array.isArray(d?.artifactDetails)) out.push(...d.artifactDetails)
            }
        }
    }
    return out
}

function isBomDtrackPending (a: any): boolean {
    if (!a || a.type !== 'BOM') return false
    const m = a.metrics || {}
    if (m.dtrackSubmissionFailed) return false
    if (m.dependencyTrackFullUri) return false
    return true
}
