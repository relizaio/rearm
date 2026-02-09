/**
 * GraphQL queries for the new sealed interface changelog API
 */

import { gql } from '@apollo/client/core'
import graphqlClient from './graphql'
import { ComponentChangelog, OrganizationChangelog, convertToLegacyFormat } from '../types/changelog-sealed'

/**
 * GraphQL fragments for reusable field selections
 */
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

const RELEASE_CODE_CHANGES_FRAGMENT = `
    releaseUuid
    version
    lifecycle
    commits {
        ${CODE_COMMIT_FRAGMENT}
    }
`

const COMMITS_BY_TYPE_FRAGMENT = `
    changeType
    commits {
        ${CODE_COMMIT_FRAGMENT}
    }
`

const RELEASE_SBOM_CHANGES_FRAGMENT = `
    releaseUuid
    addedArtifacts
    removedArtifacts
`

const RELEASE_FINDING_CHANGES_FRAGMENT = `
    releaseUuid
    appearedCount
    resolvedCount
    appearedVulnerabilities
    resolvedVulnerabilities
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

/**
 * Fetch component changelog using the new sealed interface API
 * Returns either NoneChangelog or AggregatedChangelog based on aggregation type
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
                        componentUuid
                        componentName
                        orgUuid
                        firstRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        lastRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        branches {
                            branchUuid
                            branchName
                            releases {
                                releaseUuid
                                version
                                lifecycle
                                commits {
                                    commitId
                                    commitUri
                                    message
                                    author
                                    email
                                    changeType
                                }
                            }
                        }
                        sbomChanges {
                            releaseUuid
                            addedArtifacts
                            removedArtifacts
                        }
                        findingChanges {
                            releaseUuid
                            appearedCount
                            resolvedCount
                            appearedVulnerabilities
                            resolvedVulnerabilities
                        }
                    }
                    ... on AggregatedChangelog {
                        componentUuid
                        componentName
                        orgUuid
                        firstRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        lastRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        branches {
                            branchUuid
                            branchName
                            commitsByType {
                                changeType
                                commits {
                                    commitId
                                    commitUri
                                    message
                                    author
                                    email
                                    changeType
                                }
                            }
                        }
                        aggregatedSbomChanges: sbomChanges {
                            totalAdded
                            totalRemoved
                            artifacts {
                                purl
                                name
                                version
                                isNetAdded
                                isNetRemoved
                                addedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                removedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                        }
                        aggregatedFindingChanges: findingChanges {
                            totalAppeared
                            totalResolved
                            vulnerabilities {
                                vulnId
                                purl
                                severity
                                isNetAppeared
                                isNetResolved
                                isStillPresent
                                appearedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                resolvedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                presentIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                            violations {
                                type
                                purl
                                isNetAppeared
                                isNetResolved
                                isStillPresent
                                appearedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                resolvedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                presentIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                            weaknesses {
                                cweId
                                ruleId
                                location
                                isNetAppeared
                                isNetResolved
                                isStillPresent
                                appearedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                resolvedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                presentIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                        }
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

    const changelog = (response.data as any).componentChangelog
    
    // Normalize aliased fields for AggregatedChangelog
    if (changelog.__typename === 'AggregatedChangelog') {
        changelog.sbomChanges = changelog.aggregatedSbomChanges
        changelog.findingChanges = changelog.aggregatedFindingChanges
        delete changelog.aggregatedSbomChanges
        delete changelog.aggregatedFindingChanges
    }
    
    return changelog as ComponentChangelog
}

/**
 * Fetch component changelog and convert to legacy format for backward compatibility
 * This allows existing UI components to work without changes
 */
export async function fetchComponentChangelogLegacy(params: {
    release1: string
    release2: string
    org: string
    aggregated: 'NONE' | 'AGGREGATED'
    timeZone?: string
}): Promise<any> {
    const changelog = await fetchComponentChangelog(params)
    return convertToLegacyFormat(changelog)
}

/**
 * Fetch component changelog by date range using the new sealed interface API
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
                        componentUuid
                        componentName
                        orgUuid
                        firstRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        lastRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        branches {
                            branchUuid
                            branchName
                            releases {
                                releaseUuid
                                version
                                lifecycle
                                commits {
                                    commitId
                                    commitUri
                                    message
                                    author
                                    email
                                    changeType
                                }
                            }
                        }
                        sbomChanges {
                            releaseUuid
                            addedArtifacts
                            removedArtifacts
                        }
                        findingChanges {
                            releaseUuid
                            appearedCount
                            resolvedCount
                            appearedVulnerabilities
                            resolvedVulnerabilities
                        }
                    }
                    ... on AggregatedChangelog {
                        componentUuid
                        componentName
                        orgUuid
                        firstRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        lastRelease {
                            __typename
                            uuid
                            version
                            lifecycle
                        }
                        branches {
                            branchUuid
                            branchName
                            commitsByType {
                                changeType
                                commits {
                                    commitId
                                    commitUri
                                    message
                                    author
                                    email
                                    changeType
                                }
                            }
                        }
                        aggregatedSbomChanges: sbomChanges {
                            totalAdded
                            totalRemoved
                            artifacts {
                                purl
                                name
                                version
                                isNetAdded
                                isNetRemoved
                                addedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                removedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                        }
                        aggregatedFindingChanges: findingChanges {
                            totalAppeared
                            totalResolved
                            vulnerabilities {
                                vulnId
                                purl
                                severity
                                isNetAppeared
                                isNetResolved
                                isStillPresent
                                appearedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                resolvedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                presentIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                            violations {
                                type
                                purl
                                isNetAppeared
                                isNetResolved
                                isStillPresent
                                appearedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                resolvedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                presentIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                            weaknesses {
                                cweId
                                ruleId
                                location
                                isNetAppeared
                                isNetResolved
                                isStillPresent
                                appearedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                resolvedIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                                presentIn {
                                    componentUuid
                                    componentName
                                    releaseUuid
                                    releaseVersion
                                    branchUuid
                                    branchName
                                }
                            }
                        }
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

    const changelog = (response.data as any).componentChangelogByDate
    
    // Normalize aliased fields for AggregatedChangelog
    if (changelog.__typename === 'AggregatedChangelog') {
        changelog.sbomChanges = changelog.aggregatedSbomChanges
        changelog.findingChanges = changelog.aggregatedFindingChanges
        delete changelog.aggregatedSbomChanges
        delete changelog.aggregatedFindingChanges
    }
    
    return changelog as ComponentChangelog
}

/**
 * Fetch organization changelog by date range using the new sealed interface API
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
                                    releases {
                                        ${RELEASE_CODE_CHANGES_FRAGMENT}
                                    }
                                }
                                sbomChanges {
                                    ${RELEASE_SBOM_CHANGES_FRAGMENT}
                                }
                                findingChanges {
                                    ${RELEASE_FINDING_CHANGES_FRAGMENT}
                                }
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
                                    commitsByType {
                                        ${COMMITS_BY_TYPE_FRAGMENT}
                                    }
                                }
                                aggregatedSbomChanges: sbomChanges {
                                    ${SBOM_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                                }
                                aggregatedFindingChanges: findingChanges {
                                    ${FINDING_CHANGES_WITH_ATTRIBUTION_FRAGMENT}
                                }
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

    const orgChangelog = (response.data as any).organizationChangelogByDate
    
    // Normalize aliased fields in nested components for AggregatedOrganizationChangelog
    if (orgChangelog.__typename === 'AggregatedOrganizationChangelog' && orgChangelog.components) {
        orgChangelog.components.forEach((component: any) => {
            if (component.__typename === 'AggregatedChangelog') {
                if (component.aggregatedSbomChanges) {
                    component.sbomChanges = component.aggregatedSbomChanges
                    delete component.aggregatedSbomChanges
                }
                if (component.aggregatedFindingChanges) {
                    component.findingChanges = component.aggregatedFindingChanges
                    delete component.aggregatedFindingChanges
                }
            }
        })
    }
    
    return orgChangelog as OrganizationChangelog
}

/**
 * Type guard to check if response is from new API
 */
export function isNewChangelogFormat(data: any): data is ComponentChangelog {
    return data && typeof data.__typename === 'string' && 
           (data.__typename === 'NoneChangelog' || data.__typename === 'AggregatedChangelog')
}
