/**
 * CISA KEV (Known Exploited Vulnerabilities) read surface.
 *
 * Pro-only (like approvalRequests): the knownExploited field and the
 * kevRecordDetails query are absent from the OSS schema, so callers must
 * gate on installationType !== 'OSS' — never fold these fields into the
 * shared release/metrics fragments.
 */

import gql from 'graphql-tag'
import graphqlClient from './graphql'

export interface KevRecordDetails {
    cveId: string
    vendorProject?: string
    product?: string
    vulnerabilityName?: string
    dateAdded?: string
    shortDescription?: string
    requiredAction?: string
    dueDate?: string
    knownRansomwareCampaignUse?: boolean
    notes?: string
    cwes?: string[]
}

export function isProInstallation(installationType?: string): boolean {
    return !!installationType && installationType !== 'OSS'
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
        vendorProject
        product
        vulnerabilityName
        dateAdded
        shortDescription
        requiredAction
        dueDate
        knownRansomwareCampaignUse
        notes
        cwes
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

/** Full catalog entry for one CVE; null when not KEV-listed or on error. */
export async function fetchKevRecordDetails(orgUuid: string, cveId: string): Promise<KevRecordDetails | null> {
    try {
        const response = await graphqlClient.query({
            query: KEV_RECORD_DETAILS_GQL,
            variables: { orgUuid, cveId },
            fetchPolicy: 'no-cache'
        })
        return (response.data as any).kevRecordDetails || null
    } catch (err) {
        console.error('Error fetching KEV record details:', err)
        return null
    }
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
