/**
 * GraphQL queries for the new sealed interface changelog API
 */

import { gql } from '@apollo/client/core'
import graphqlClient from './graphql'
import { ComponentChangelog, OrganizationChangelog } from '../types/changelog-sealed'

// ========== GraphQL Field Fragments ==========

const RELEASE_INFO_FRAGMENT = `
    __typename
    uuid
    version
    lifecycle
`

const CODE_COMMIT_FRAGMENT = `
    commitId
    commitUri
    message
    author
    email
    changeType
`

const RELEASE_SBOM_CHANGES_FRAGMENT = `
    addedArtifacts {
        purl
        name
        version
    }
    removedArtifacts {
        purl
        name
        version
    }
`

const RELEASE_FINDING_CHANGES_FRAGMENT = `
    appearedCount
    resolvedCount
    appearedVulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
    }
    resolvedVulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
    }
    appearedViolations {
        type
        purl
    }
    resolvedViolations {
        type
        purl
    }
    appearedWeaknesses {
        cweId
        severity
        ruleId
        location
    }
    resolvedWeaknesses {
        cweId
        severity
        ruleId
        location
    }
`

const NONE_RELEASE_CHANGES_FRAGMENT = `
    releaseUuid
    version
    lifecycle
    commits {
        ${CODE_COMMIT_FRAGMENT}
    }
    sbomChanges {
        ${RELEASE_SBOM_CHANGES_FRAGMENT}
    }
    findingChanges {
        ${RELEASE_FINDING_CHANGES_FRAGMENT}
    }
`

const COMMITS_BY_TYPE_FRAGMENT = `
    changeType
    commits {
        ${CODE_COMMIT_FRAGMENT}
    }
`

const COMPONENT_ATTRIBUTION_FRAGMENT = `
    componentUuid
    componentName
    releaseUuid
    releaseVersion
    branchUuid
    branchName
`

const ORG_LEVEL_CONTEXT_FRAGMENT = `
    isNewToOrganization
    wasPreviouslyReported
    isPartiallyResolved
    isFullyResolved
    isInheritedInAllComponents
    componentCount
    affectedComponentNames
`

const SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT = `
    totalAdded
    totalRemoved
    artifacts {
        purl
        name
        version
        isNetAdded
        isNetRemoved
        addedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        removedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
    }
`

const FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT = `
    totalAppeared
    totalResolved
    vulnerabilities {
        vulnId
        purl
        severity
        aliases {
            aliasId
        }
        isNetAppeared
        isNetResolved
        isStillPresent
        appearedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        resolvedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        presentIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        orgContext {
            ${ORG_LEVEL_CONTEXT_FRAGMENT}
        }
    }
    violations {
        type
        purl
        isNetAppeared
        isNetResolved
        isStillPresent
        appearedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        resolvedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        presentIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        orgContext {
            ${ORG_LEVEL_CONTEXT_FRAGMENT}
        }
    }
    weaknesses {
        cweId
        severity
        ruleId
        location
        isNetAppeared
        isNetResolved
        isStillPresent
        appearedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        resolvedIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        presentIn {
            ${COMPONENT_ATTRIBUTION_FRAGMENT}
        }
        orgContext {
            ${ORG_LEVEL_CONTEXT_FRAGMENT}
        }
    }
`

// ========== Shared Component Changelog Fragments ==========

const NONE_CHANGELOG_FIELDS = `
    componentUuid
    componentName
    orgUuid
    firstRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    lastRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    branches {
        branchUuid
        branchName
        componentUuid
        componentName
        releases {
            ${NONE_RELEASE_CHANGES_FRAGMENT}
        }
    }
`

const AGGREGATED_CHANGELOG_FIELDS = `
    componentUuid
    componentName
    orgUuid
    firstRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    lastRelease {
        ${RELEASE_INFO_FRAGMENT}
    }
    branches {
        branchUuid
        branchName
        componentUuid
        componentName
        commitsByType {
            ${COMMITS_BY_TYPE_FRAGMENT}
        }
    }
    sbomChanges {
        ${SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
    }
    findingChanges {
        ${FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
    }
`

// ========== Query Functions ==========

/**
 * Fetch component changelog between two releases
 */
export async function fetchComponentChangelog(params: {
    release1: string
    release2: string
    org: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
}): Promise<ComponentChangelog> {
    const response = await graphqlClient.query({
        query: gql`
            query FetchComponentChangelog(
                $release1: ID!
                $release2: ID!
                $org: ID!
                $aggregated: AggregationType!
                $timeZone: String
            ) {
                componentChangelog(
                    release1: $release1
                    release2: $release2
                    orgUuid: $org
                    aggregated: $aggregated
                    timeZone: $timeZone
                ) {
                    __typename
                    ... on NoneChangelog {
                        ${NONE_CHANGELOG_FIELDS}
                    }
                    ... on AggregatedChangelog {
                        ${AGGREGATED_CHANGELOG_FIELDS}
                    }
                }
            }
        `,
        variables: {
            release1: params.release1,
            release2: params.release2,
            org: params.org,
            aggregated: params.aggregated,
            timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
        },
        fetchPolicy: 'no-cache'
    })

    return (response.data as any).componentChangelog as ComponentChangelog
}

/**
 * Fetch component changelog by date range
 */
export async function fetchComponentChangelogByDate(params: {
    componentUuid: string
    branchUuid?: string
    org: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
    dateFrom: string
    dateTo: string
}): Promise<ComponentChangelog> {
    const response = await graphqlClient.query({
        query: gql`
            query FetchComponentChangelogByDate(
                $componentUuid: ID!
                $branchUuid: ID
                $org: ID!
                $aggregated: AggregationType!
                $timeZone: String
                $dateFrom: DateTime!
                $dateTo: DateTime!
            ) {
                componentChangelogByDate(
                    componentUuid: $componentUuid
                    branchUuid: $branchUuid
                    orgUuid: $org
                    aggregated: $aggregated
                    timeZone: $timeZone
                    dateFrom: $dateFrom
                    dateTo: $dateTo
                ) {
                    __typename
                    ... on NoneChangelog {
                        ${NONE_CHANGELOG_FIELDS}
                    }
                    ... on AggregatedChangelog {
                        ${AGGREGATED_CHANGELOG_FIELDS}
                    }
                }
            }
        `,
        variables: {
            componentUuid: params.componentUuid,
            branchUuid: params.branchUuid || null,
            org: params.org,
            aggregated: params.aggregated,
            timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone,
            dateFrom: params.dateFrom,
            dateTo: params.dateTo
        },
        fetchPolicy: 'no-cache'
    })

    return (response.data as any).componentChangelogByDate as ComponentChangelog
}

/**
 * Fetch organization changelog by date range
 */
export async function fetchOrganizationChangelogByDate(params: {
    orgUuid: string
    perspectiveUuid?: string
    dateFrom: string
    dateTo: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
}): Promise<OrganizationChangelog> {
    const response = await graphqlClient.query({
        query: gql`
            query FetchOrganizationChangelogByDate(
                $orgUuid: ID!
                $perspectiveUuid: ID
                $dateFrom: DateTime!
                $dateTo: DateTime!
                $aggregated: AggregationType!
                $timeZone: String
            ) {
                organizationChangelogByDate(
                    orgUuid: $orgUuid
                    perspectiveUuid: $perspectiveUuid
                    dateFrom: $dateFrom
                    dateTo: $dateTo
                    aggregated: $aggregated
                    timeZone: $timeZone
                ) {
                    __typename
                    ... on NoneOrganizationChangelog {
                        orgUuid
                        dateFrom
                        dateTo
                        components {
                            __typename
                            ... on NoneChangelog {
                                ${NONE_CHANGELOG_FIELDS}
                            }
                        }
                    }
                    ... on AggregatedOrganizationChangelog {
                        orgUuid
                        dateFrom
                        dateTo
                        components {
                            __typename
                            ... on AggregatedChangelog {
                                ${AGGREGATED_CHANGELOG_FIELDS}
                            }
                        }
                        sbomChanges {
                            ${SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                        }
                        findingChanges {
                            ${FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                        }
                    }
                }
            }
        `,
        variables: {
            orgUuid: params.orgUuid,
            perspectiveUuid: params.perspectiveUuid || null,
            dateFrom: params.dateFrom,
            dateTo: params.dateTo,
            aggregated: params.aggregated,
            timeZone: params.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone
        },
        fetchPolicy: 'no-cache'
    })

    return (response.data as any).organizationChangelogByDate as OrganizationChangelog
}
