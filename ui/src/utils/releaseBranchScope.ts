// TPF-135: which branches the CVE and SBOM-component release searches list. The names are the
// Pro schema's enum ReleaseBranchScope (releaseBranchScopeSchemaDrift.spec pins them).
export const RELEASE_BRANCH_SCOPE = {
    ACTIVE_BRANCHES: 'ACTIVE_BRANCHES',
    ALL_BRANCHES: 'ALL_BRANCHES'
} as const

export type ReleaseBranchScope = keyof typeof RELEASE_BRANCH_SCOPE

// The scope an "Include archived branches" checkbox asks for.
export function branchScopeOf (includeArchived: boolean): ReleaseBranchScope {
    return includeArchived ? RELEASE_BRANCH_SCOPE.ALL_BRANCHES : RELEASE_BRANCH_SCOPE.ACTIVE_BRANCHES
}
