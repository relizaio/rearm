/**
 * KEV (Known Exploited Vulnerabilities) read surface.
 *
 * CE-available as of the KEV CE-activation slice. The knownExploited field
 * and the kevRecordDetails query are part of the shared schema; callers
 * inline the 'knownExploited' field name and unconditionally fetch the
 * KEV flag (no installationType gate).
 */

import gql from 'graphql-tag'
import graphqlClient from './graphql'

export type KevRansomwareStatus = 'KNOWN' | 'UNKNOWN' | 'UNSPECIFIED'

export interface KevRansomwareCampaign {
    name: string
    url?: string
}

/** One source's assertion. revokedDate set = the source withdrew the
 *  listing (the CVE still counts as KEV; shown as a note). */
export interface KevSourceAssertion {
    source: string
    revokedDate?: string
    vendorProject?: string
    product?: string
    vulnerabilityName?: string
    dateAdded?: string
    shortDescription?: string
    requiredAction?: string
    dueDate?: string
    ransomwareStatus?: KevRansomwareStatus
    ransomwareCampaigns?: KevRansomwareCampaign[]
    notes?: string
    cwes?: string[]
}

export interface KevRecordDetails {
    cveId: string
    ransomwareStatus?: KevRansomwareStatus
    assertions: KevSourceAssertion[]
}

const RELEASE_KEV_FLAGS_GQL = gql`
query releaseKevFlags($releaseUuid: ID!, $orgUuid: ID) {
    release(releaseUuid: $releaseUuid, orgUuid: $orgUuid) {
        uuid
        metrics {
            vulnerabilityDetails {
                vulnId
                knownExploited
            }
        }
    }
}`

const ARTIFACT_KEV_FLAGS_GQL = gql`
query artifactKevFlags($artifactUuid: ID!) {
    artifact(artifactUuid: $artifactUuid) {
        uuid
        metrics {
            vulnerabilityDetails {
                vulnId
                knownExploited
            }
        }
    }
}`

const KEV_RECORD_DETAILS_GQL = gql`
query kevRecordDetails($orgUuid: ID!, $cveId: String!) {
    kevRecordDetails(orgUuid: $orgUuid, cveId: $cveId) {
        cveId
        ransomwareStatus
        assertions {
            source
            revokedDate
            vendorProject
            product
            vulnerabilityName
            dateAdded
            shortDescription
            requiredAction
            dueDate
            ransomwareStatus
            ransomwareCampaigns { name url }
            notes
            cwes
        }
    }
}`

function toKevIdSet(vulnerabilityDetails: any[] | null | undefined): Set<string> {
    const kevIds = new Set<string>()
    if (Array.isArray(vulnerabilityDetails)) {
        vulnerabilityDetails.forEach((v: any) => {
            if (v?.knownExploited && v.vulnId) kevIds.add(v.vulnId)
        })
    }
    return kevIds
}

/**
 * KEV-listed vulnIds among a release's findings. Fail-soft: any error
 * returns an empty set so the findings view never breaks over the badge.
 */
export async function fetchReleaseKevVulnIds(releaseUuid: string, orgUuid: string): Promise<Set<string>> {
    try {
        const response = await graphqlClient.query({
            query: RELEASE_KEV_FLAGS_GQL,
            variables: { releaseUuid, orgUuid },
            fetchPolicy: 'no-cache'
        })
        return toKevIdSet((response.data as any).release?.metrics?.vulnerabilityDetails)
    } catch (err) {
        console.error('Error fetching KEV flags for release:', err)
        return new Set<string>()
    }
}

/** Artifact-level variant of fetchReleaseKevVulnIds, same fail-soft contract. */
export async function fetchArtifactKevVulnIds(artifactUuid: string): Promise<Set<string>> {
    try {
        const response = await graphqlClient.query({
            query: ARTIFACT_KEV_FLAGS_GQL,
            variables: { artifactUuid },
            fetchPolicy: 'no-cache'
        })
        return toKevIdSet((response.data as any).artifact?.metrics?.vulnerabilityDetails)
    } catch (err) {
        console.error('Error fetching KEV flags for artifact:', err)
        return new Set<string>()
    }
}

/**
 * Full catalog entry for one CVE; null means authoritatively not KEV-listed.
 * Unlike the badge fetches this rethrows on transport/backend errors, so the
 * modal can show an error state instead of a false "not listed" claim.
 */
export async function fetchKevRecordDetails(orgUuid: string, cveId: string): Promise<KevRecordDetails | null> {
    const response = await graphqlClient.query({
        query: KEV_RECORD_DETAILS_GQL,
        variables: { orgUuid, cveId },
        fetchPolicy: 'no-cache'
    })
    return (response.data as any).kevRecordDetails || null
}

/**
 * The catalog keys records by CVE id, but a finding's primary id may be a
 * GHSA/other alias with the KEV CVE in its aliases. Prefer the row id when
 * it is already a CVE, else the first CVE-prefixed alias.
 */
export function resolveKevCveId(row: any): string {
    const id = String(row?.id || '')
    if (id.startsWith('CVE-')) return id
    const aliasCve = (Array.isArray(row?.aliases) ? row.aliases : [])
        .map((a: any) => (typeof a === 'string' ? a : a?.aliasId))
        .find((a: any) => typeof a === 'string' && a.startsWith('CVE-'))
    return aliasCve || id
}

/** Stamps knownExploited on processed vulnerability rows (matched by id). */
export function annotateKnownExploited(rows: any[] | null | undefined, kevVulnIds: Set<string>): void {
    if (!Array.isArray(rows) || kevVulnIds.size === 0) return
    rows.forEach((row: any) => {
        if (row?.type === 'Vulnerability' && row.id && kevVulnIds.has(row.id)) {
            row.knownExploited = true
        }
    })
}
