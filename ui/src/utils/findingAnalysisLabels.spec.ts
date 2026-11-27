import { describe, it, expect } from 'vitest'
import {
    analysisJustificationLabel,
    analysisResponseLabel,
    analysisScopeLabel,
    analysisStateLabel,
    findingTypeLabel,
    locationTypeLabel
} from './findingAnalysisLabels'

/**
 * FDAW-150 L-1 / L-2: the finding analysis screens print a word for every value of the server's
 * vocabularies, never the stored name. The value lists are written out here as the backend enums
 * (rearm-saas user.graphqls / schema.graphqls), not read from the UI's option arrays, so a value
 * the UI forgets fails here.
 */
const raw = /^[A-Z0-9_]+$/

const VOCABULARIES: Array<[string, (v: string | null | undefined) => string, Record<string, string>]> = [
    ['FindingType', findingTypeLabel, {
        VULNERABILITY: 'Vulnerability', VIOLATION: 'Violation', WEAKNESS: 'Weakness'
    }],
    ['LocationType', locationTypeLabel, {
        PURL: 'Package URL', CODE_POINT: 'Code location'
    }],
    ['AnalysisState', analysisStateLabel, {
        EXPLOITABLE: 'Exploitable', IN_TRIAGE: 'In Triage', FALSE_POSITIVE: 'False Positive',
        NOT_AFFECTED: 'Not Affected', RESOLVED: 'Resolved'
    }],
    ['AnalysisJustification', analysisJustificationLabel, {
        CODE_NOT_PRESENT: 'Code Not Present', CODE_NOT_REACHABLE: 'Code Not Reachable',
        REQUIRES_CONFIGURATION: 'Requires Configuration', REQUIRES_DEPENDENCY: 'Requires Dependency',
        REQUIRES_ENVIRONMENT: 'Requires Environment', PROTECTED_BY_COMPILER: 'Protected by Compiler',
        PROTECTED_AT_RUNTIME: 'Protected at Runtime', PROTECTED_AT_PERIMETER: 'Protected at Perimeter',
        PROTECTED_BY_MITIGATING_CONTROL: 'Protected by Mitigating Control'
    }],
    ['AnalysisResponse', analysisResponseLabel, {
        CAN_NOT_FIX: 'Cannot Fix', WILL_NOT_FIX: 'Will Not Fix', UPDATE: 'Update Available',
        ROLLBACK: 'Rollback', WORKAROUND_AVAILABLE: 'Workaround Available'
    }],
    ['AnalysisScope', (v) => analysisScopeLabel(v), {
        ORG: 'Organization', RESOURCE_GROUP: 'Resource group', COMPONENT: 'Component',
        BRANCH: 'Branch', RELEASE: 'Release'
    }]
]

describe.each(VOCABULARIES)('L-1 %s labels', (_name, label, expected) => {
    it.each(Object.entries(expected))('%s reads %s, never a raw name', (value, word) => {
        expect(label(value)).toBe(word)
        expect(label(value)).not.toBe(value)
        expect(label(value)).not.toMatch(raw)
    })

    it('humanizes an unknown value', () => {
        expect(label('SOME_NEW_VALUE')).toBe('Some new value')
    })

    it.each([null, undefined, ''])('prints nothing for %s', (value) => {
        expect(label(value as string | null | undefined)).toBe('')
    })
})

describe('L-2 analysisScopeLabel on a product', () => {
    it.each([
        [['COMPONENT', 'PRODUCT', 'Feature Set'], 'Product'],
        [['BRANCH', 'PRODUCT', 'Feature Set'], 'Feature Set'],
        [['BRANCH', 'PRODUCT', 'Variant'], 'Variant'],
        [['BRANCH', 'COMPONENT', 'Feature Set'], 'Branch'],
        [['BRANCH', 'PRODUCT', undefined], 'Branch'],
        [['RELEASE', 'PRODUCT', 'Feature Set'], 'Release'],
        [['RELEASE', 'COMPONENT', undefined], 'Release'],
        [['ORG', 'PRODUCT', 'Feature Set'], 'Organization'],
        [['ORG', undefined, undefined], 'Organization']
    ] as Array<[[string, string | undefined, string | undefined], string]>)('%j reads %s', (args, word) => {
        expect(analysisScopeLabel(...args)).toBe(word)
    })
})
