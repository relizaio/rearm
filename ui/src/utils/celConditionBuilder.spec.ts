import { describe, it, expect } from 'vitest'
import {
    builderIssues, compile, conditionToCel, isTriviallyTrueCel, parseCelToBuilder,
    type BuilderState, type Condition
} from './celConditionBuilder'

const one = (c: Condition, operator: 'AND' | 'OR' = 'AND'): BuilderState =>
    ({ topOperator: 'AND', groups: [{ operator, conditions: [c] }] })

describe('a complete clause compiles to its CEL', () => {
    it.each<[string, Condition, string]>([
        ['one lifecycle', { type: 'LIFECYCLE', lifecycles: ['ASSEMBLED'] }, 'release.lifecycle == "ASSEMBLED"'],
        ['several lifecycles', { type: 'LIFECYCLE', lifecycles: ['DRAFT', 'ASSEMBLED'] }, 'release.lifecycle in ["DRAFT", "ASSEMBLED"]'],
        ['one branch type', { type: 'BRANCH_TYPE', branchTypes: ['BASE'] }, 'release.branchType == "BASE"'],
        ['several branch types', { type: 'BRANCH_TYPE', branchTypes: ['RELEASE', 'HOTFIX'] }, 'release.branchType in ["RELEASE", "HOTFIX"]'],
        ['an approval entry', { type: 'APPROVAL_ENTRY', approvalEntry: 'e1', approvalState: 'APPROVED' }, 'release.approvals["e1"] == "APPROVED"'],
        ['any approval', { type: 'ANY_APPROVAL', approvalState: 'APPROVED' }, 'release.anyApproved'],
        ['any disapproval', { type: 'ANY_APPROVAL', approvalState: 'DISAPPROVED' }, 'release.anyDisapproved'],
        ['a metric', { type: 'METRICS', metricField: 'criticalVulns', operator: '==', value: 0 }, 'release.criticalVulns == 0'],
        ['first scanned', { type: 'FIRST_SCANNED', present: true }, 'release.firstScanned == true']
    ])('%s', (_label, c, cel) => {
        expect(conditionToCel(c)).toBe(cel)
        expect(compile(one(c))).toBe(cel)
        expect(builderIssues(one(c))).toEqual([])
    })
})

describe('an incomplete clause is an error, never CEL', () => {
    it.each<[string, Condition, string]>([
        ['no approval entry', { type: 'APPROVAL_ENTRY', approvalEntry: '', approvalState: 'APPROVED' }, 'Approval Entry has no approval entry selected'],
        ['no lifecycle', { type: 'LIFECYCLE', lifecycles: [] }, 'Lifecycle has no lifecycle selected'],
        ['no branch type', { type: 'BRANCH_TYPE', branchTypes: [] }, 'Branch Type has no branch type selected'],
        ['no metric value', { type: 'METRICS', metricField: 'highVulns', operator: '>', value: null as unknown as number }, 'Metrics has no value']
    ])('%s', (_label, c, issue) => {
        expect(conditionToCel(c)).toBeNull()
        expect(compile(one(c))).toBeNull()
        expect(builderIssues(one(c))).toEqual([issue])
    })

    it('the rule that shipped an unapproved release: Main AND an unpicked approval entry', () => {
        const state: BuilderState = { topOperator: 'AND', groups: [{ operator: 'AND', conditions: [
            { type: 'BRANCH_TYPE', branchTypes: ['BASE'] },
            { type: 'APPROVAL_ENTRY', approvalEntry: '', approvalState: 'APPROVED' }
        ] }] }
        // Used to compile to (release.branchType == "BASE" && true).
        expect(compile(state)).toBeNull()
        expect(builderIssues(state)).toEqual(['Condition, clause 2: Approval Entry has no approval entry selected'])
    })

    it('an empty group is an error, and the group is named', () => {
        const state: BuilderState = { topOperator: 'AND', groups: [
            { operator: 'AND', conditions: [{ type: 'BRANCH_TYPE', branchTypes: ['BASE'] }] },
            { operator: 'AND', conditions: [] }
        ] }
        expect(compile(state)).toBeNull()
        expect(builderIssues(state)).toEqual(['Group 2 has no conditions'])
    })

    it('an OR group is no excuse: an incomplete alternative is still incomplete', () => {
        const state: BuilderState = { topOperator: 'AND', groups: [{ operator: 'OR', conditions: [
            { type: 'ANY_APPROVAL', approvalState: 'APPROVED' },
            { type: 'LIFECYCLE', lifecycles: [] }
        ] }] }
        expect(compile(state)).toBeNull()
        expect(builderIssues(state)).toEqual(['Condition, clause 2: Lifecycle has no lifecycle selected'])
    })
})

describe('whole-state compile', () => {
    it('no groups at all is an empty condition, which never fires', () => {
        const state: BuilderState = { topOperator: 'AND', groups: [] }
        expect(compile(state)).toBe('')
        expect(builderIssues(state)).toEqual([])
    })

    it('groups join with the top operator and round-trip through the parser', () => {
        const state: BuilderState = { topOperator: 'OR', groups: [
            { operator: 'AND', conditions: [
                { type: 'BRANCH_TYPE', branchTypes: ['BASE'] },
                { type: 'APPROVAL_ENTRY', approvalEntry: 'e1', approvalState: 'APPROVED' }] },
            { operator: 'AND', conditions: [
                { type: 'LIFECYCLE', lifecycles: ['READY_TO_SHIP'] },
                { type: 'FIRST_SCANNED', present: true }] }
        ] }
        const cel = compile(state)
        expect(cel).toBe('(release.branchType == "BASE" && release.approvals["e1"] == "APPROVED") || '
            + '(release.lifecycle == "READY_TO_SHIP" && release.firstScanned == true)')
        expect(parseCelToBuilder(cel!)).toEqual(state)
    })
})

describe('isTriviallyTrueCel', () => {
    it.each([
        '(release.branchType == "BASE" && true)',
        'true && release.lifecycle == "ASSEMBLED"',
        'release.anyApproved || true',
        '(release.branchType == "BASE") && (true)',
        '(release.anyApproved || true) && release.branchType == "BASE"'
    ])('refuses %s', (cel) => {
        expect(isTriviallyTrueCel(cel)).toBe(true)
    })

    it.each([
        '',
        'release.branchType == "BASE"',
        'release.firstScanned == true',
        'release.anyApproved || false',
        '(release.branchType == "BASE") && (release.anyApproved)',
        'release.version == "true"',
        // a bare true is how a rule says "always"; only an embedded one is an accident
        'true',
        '(true)'
    ])('accepts %s', (cel) => {
        expect(isTriviallyTrueCel(cel)).toBe(false)
    })
})
