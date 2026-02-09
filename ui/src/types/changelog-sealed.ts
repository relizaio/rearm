/**
 * Sealed Interface Changelog Types
 * These types match the new backend GraphQL sealed interface (union type)
 */

import { SbomChangesWithAttribution, FindingChangesWithAttribution } from './changelog-attribution'

/**
 * Release metadata
 */
export interface ReleaseInfo {
    uuid: string
    version: string
    lifecycle: string
}

/**
 * Individual code commit
 */
export interface CodeCommit {
    commitId: string | null
    commitUri: string | null
    message: string
    author: string | null
    email: string | null
    changeType: string
}

/**
 * Code changes for a single release (NONE mode)
 */
export interface ReleaseCodeChanges {
    releaseUuid: string
    version: string
    lifecycle: string
    commits: CodeCommit[]
}

/**
 * SBOM changes for a single release (NONE mode)
 */
export interface ReleaseSbomChanges {
    releaseUuid: string
    addedArtifacts: string[]
    removedArtifacts: string[]
}

/**
 * Finding changes for a single release (NONE mode)
 */
export interface ReleaseFindingChanges {
    releaseUuid: string
    appearedCount: number
    resolvedCount: number
    appearedVulnerabilities: string[]
    resolvedVulnerabilities: string[]
}

/**
 * Branch changes for NONE mode (per-release breakdown)
 */
export interface NoneBranchChanges {
    branchUuid: string
    branchName: string
    releases: ReleaseCodeChanges[]
}

/**
 * Commits grouped by type (AGGREGATED mode)
 */
export interface CommitsByType {
    changeType: string
    commits: CodeCommit[]
}

/**
 * Branch changes for AGGREGATED mode (commits grouped by type)
 */
export interface AggregatedBranchChanges {
    branchUuid: string
    branchName: string
    commitsByType: CommitsByType[]
}

/**
 * NONE mode changelog (per-release breakdown)
 */
export interface NoneChangelog {
    __typename: 'NoneChangelog'
    componentUuid: string
    componentName: string
    orgUuid: string
    firstRelease: ReleaseInfo
    lastRelease: ReleaseInfo
    branches: NoneBranchChanges[]
    sbomChanges: ReleaseSbomChanges[]
    findingChanges: ReleaseFindingChanges[]
}

/**
 * AGGREGATED mode changelog (component-level summary)
 */
export interface AggregatedChangelog {
    __typename: 'AggregatedChangelog'
    componentUuid: string
    componentName: string
    orgUuid: string
    firstRelease: ReleaseInfo
    lastRelease: ReleaseInfo
    branches: AggregatedBranchChanges[]
    sbomChanges: SbomChangesWithAttribution
    findingChanges: FindingChangesWithAttribution
}

/**
 * Union type for component changelog
 */
export type ComponentChangelog = NoneChangelog | AggregatedChangelog

/**
 * NONE mode organization changelog (per-component, per-release breakdown)
 */
export interface NoneOrganizationChangelog {
    __typename: 'NoneOrganizationChangelog'
    orgUuid: string
    dateFrom: string
    dateTo: string
    components: ComponentChangelog[]
}

/**
 * AGGREGATED mode organization changelog (org-wide summary with attribution)
 */
export interface AggregatedOrganizationChangelog {
    __typename: 'AggregatedOrganizationChangelog'
    orgUuid: string
    dateFrom: string
    dateTo: string
    components: ComponentChangelog[]
    sbomChanges: SbomChangesWithAttribution
    findingChanges: FindingChangesWithAttribution
}

/**
 * Union type for organization changelog
 */
export type OrganizationChangelog = NoneOrganizationChangelog | AggregatedOrganizationChangelog

/**
 * Type guard to check if changelog is NONE mode
 */
export function isNoneChangelog(changelog: ComponentChangelog): changelog is NoneChangelog {
    return changelog.__typename === 'NoneChangelog'
}

/**
 * Type guard to check if changelog is AGGREGATED mode
 */
export function isAggregatedChangelog(changelog: ComponentChangelog): changelog is AggregatedChangelog {
    return changelog.__typename === 'AggregatedChangelog'
}

/**
 * Type guard to check if organization changelog is NONE mode
 */
export function isNoneOrganizationChangelog(changelog: OrganizationChangelog): changelog is NoneOrganizationChangelog {
    return changelog.__typename === 'NoneOrganizationChangelog'
}

/**
 * Type guard to check if organization changelog is AGGREGATED mode
 */
export function isAggregatedOrganizationChangelog(changelog: OrganizationChangelog): changelog is AggregatedOrganizationChangelog {
    return changelog.__typename === 'AggregatedOrganizationChangelog'
}

/**
 * Helper to convert new sealed interface format to legacy format for backward compatibility
 * This allows existing UI components to work with the new API without changes
 */
export function convertToLegacyFormat(changelog: ComponentChangelog): any {
    if (isNoneChangelog(changelog)) {
        // NONE mode: Convert to legacy per-release format
        return {
            uuid: changelog.componentUuid,
            name: changelog.componentName,
            org: changelog.orgUuid,
            firstRelease: changelog.firstRelease,
            lastRelease: changelog.lastRelease,
            branches: changelog.branches.map(branch => ({
                uuid: branch.branchUuid,
                name: branch.branchName,
                releases: branch.releases.map(release => {
                    // Find SBOM changes for this release
                    const sbom = changelog.sbomChanges.find(s => s.releaseUuid === release.releaseUuid)
                    // Find finding changes for this release
                    const finding = changelog.findingChanges.find(f => f.releaseUuid === release.releaseUuid)
                    
                    // Group commits by type for legacy format
                    const changesByType = new Map<string, CodeCommit[]>()
                    release.commits.forEach(commit => {
                        const type = commit.changeType || 'other'
                        if (!changesByType.has(type)) {
                            changesByType.set(type, [])
                        }
                        changesByType.get(type)!.push(commit)
                    })
                    
                    return {
                        uuid: release.releaseUuid,
                        version: release.version,
                        lifecycle: release.lifecycle,
                        changes: Array.from(changesByType.entries()).map(([type, commits]) => ({
                            changeType: type,
                            commitRecords: commits.map(c => ({
                                linkifiedText: c.message,
                                rawText: c.message,
                                commitAuthor: c.author,
                                commitEmail: c.email
                            }))
                        })),
                        sbomChanges: sbom ? {
                            added: sbom.addedArtifacts.map(purl => ({ purl, version: '' })),
                            removed: sbom.removedArtifacts.map(purl => ({ purl, version: '' }))
                        } : null,
                        findingChanges: finding ? {
                            summary: {
                                totalAppearedCount: finding.appearedCount,
                                totalResolvedCount: finding.resolvedCount,
                                netChange: finding.appearedCount - finding.resolvedCount
                            },
                            appearedVulnerabilities: finding.appearedVulnerabilities.map(id => ({ vulnId: id })),
                            resolvedVulnerabilities: finding.resolvedVulnerabilities.map(id => ({ vulnId: id }))
                        } : null
                    }
                })
            }))
        }
    } else {
        // AGGREGATED mode: Convert to legacy aggregated format
        return {
            uuid: changelog.componentUuid,
            name: changelog.componentName,
            org: changelog.orgUuid,
            firstRelease: changelog.firstRelease,
            lastRelease: changelog.lastRelease,
            branches: changelog.branches.map(branch => ({
                uuid: branch.branchUuid,
                name: branch.branchName,
                changes: branch.commitsByType.map(group => ({
                    changeType: group.changeType,
                    commitRecords: group.commits.map(c => ({
                        linkifiedText: c.message,
                        rawText: c.message,
                        commitAuthor: c.author,
                        commitEmail: c.email
                    }))
                }))
            })),
            sbomChanges: changelog.sbomChanges,
            findingChanges: changelog.findingChanges
        }
    }
}
