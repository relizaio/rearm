import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import {
    BOM_SCAN_STATES, builderIssues, compile, conditionToCel, isTriviallyTrueCel, parseCelToBuilder,
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
        ['first scanned', { type: 'FIRST_SCANNED', present: true }, 'release.firstScanned == true'],
        ['a BOM scan state', { type: 'BOM_SCAN_STATE', operator: '==', state: 'ALL_NOT_SCANNABLE' }, 'release.bomScanState == "ALL_NOT_SCANNABLE"'],
        ['a BOM scan state, negated', { type: 'BOM_SCAN_STATE', operator: '!=', state: 'NO_BOMS' }, 'release.bomScanState != "NO_BOMS"']
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
                { type: 'FIRST_SCANNED', present: true },
                { type: 'BOM_SCAN_STATE', operator: '!=', state: 'ALL_NOT_SCANNABLE' }] }
        ] }
        const cel = compile(state)
        expect(cel).toBe('(release.branchType == "BASE" && release.approvals["e1"] == "APPROVED") || '
            + '(release.lifecycle == "READY_TO_SHIP" && release.firstScanned == true && release.bomScanState != "ALL_NOT_SCANNABLE")')
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

describe('the BOM scan state clause', () => {
    it.each(BOM_SCAN_STATES.flatMap(state => (['==', '!='] as const).map(operator => [operator, state] as const)))(
        'release.bomScanState %s "%s" round-trips', (operator, state) => {
            const cel = `release.bomScanState ${operator} "${state}"`
            const parsed = parseCelToBuilder(cel)
            expect(parsed).toEqual(one({ type: 'BOM_SCAN_STATE', operator, state }))
            expect(compile(parsed!)).toBe(cel)
        })

    it.each([
        'release.bomScanState == "PENDING"',
        'release.bomScanState in ["NO_BOMS"]',
        // D6: the counts are not metric fields, so the precondition idiom stays in CEL mode
        'release.scannableBoms >= 1'
    ])('does not parse %s', (cel) => {
        expect(parseCelToBuilder(cel)).toBeNull()
    })
})

describe('the one source of the BOM scan state names', () => {
    it('lists the four states in the server order', () => {
        expect(BOM_SCAN_STATES).toEqual(['NO_BOMS', 'ALL_SCANNABLE', 'SOME_NOT_SCANNABLE', 'ALL_NOT_SCANNABLE'])
    })

    it('is a module that imports nothing and exports the list', () => {
        const lines = readFileSync(fileURLToPath(new URL('./celConditionBuilder.ts', import.meta.url)), 'utf8').split('\n')
        expect(lines.filter(l => /^\s*import\b/.test(l))).toEqual([])
        expect(lines.filter(l => /^export const BOM_SCAN_STATES\b/.test(l))).toHaveLength(1)
    })
})
