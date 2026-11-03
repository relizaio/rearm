import { describe, expect, it } from 'vitest'
import { RELEASE_BRANCH_SCOPE, branchScopeOf } from './releaseBranchScope'

/** TPF-135 architecture round 2, 4.2 test 10: the checkbox value maps to the scope name. */
describe('branchScopeOf (TPF-135)', () => {
    it('an unchecked box asks for the active branches, a checked one for every branch', () => {
        expect(branchScopeOf(false)).toBe('ACTIVE_BRANCHES')
        expect(branchScopeOf(true)).toBe('ALL_BRANCHES')
    })

    it('the two scopes are the only names, each spelled as its own key', () => {
        expect(Object.keys(RELEASE_BRANCH_SCOPE)).toEqual(['ACTIVE_BRANCHES', 'ALL_BRANCHES'])
        Object.entries(RELEASE_BRANCH_SCOPE).forEach(([k, v]) => expect(v).toBe(k))
    })
})
