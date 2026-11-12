// FDAW-78 architecture section 4, row 11 (REQ-65): one UI label module with a stated fallback. Every
// expected string is a literal written here, never the production table.
import { describe, expect, it, vi } from 'vitest'

vi.mock('./fetchClient', () => ({ fetchWithAuth: vi.fn(), fetchArrayBufferWithAuth: vi.fn() }))
import {
    ARTIFACT_TYPE_LABELS, ATTESTATION_STATE_TEXT, HISTORY_ACTION_LABELS, HISTORY_SCOPE_LABELS, REQUIREMENT_LABELS,
    UNKNOWN_REASON_TEXT, documentFooterIdentity, documentInstantText, fdaLifecycleLabel, humanizeEnum, labelOrHumanized,
    labelOrLower, lowerEnum, pluralWord,
} from './fdaLabels'
import { ARTIFACT_TYPES, packageKindLabel, packagePartLabel } from './packageSettings'
import { labelOf, SUBMISSION_STATE_LABELS } from './submissions'
import { justifiedUnknownLabel, type JustifiedUnknownReason } from './justifiedUnknownLabels'
import { ENUM_RE, expectSweepControl } from './__fixtures__/fdaSweep'

describe('the fallback (FDAW-78 3.1)', () => {
    it('humanizes an unknown value; empty for none', () => {
        expect(humanizeEnum('SOME_NEW_VALUE')).toBe('Some new value')
        expect(lowerEnum('SOME_NEW_VALUE')).toBe('some new value')
        expect(humanizeEnum(null)).toBe('')
        expect(humanizeEnum(undefined)).toBe('')
        expect(humanizeEnum('')).toBe('')
        expect(lowerEnum(null)).toBe('')
        expect(labelOrHumanized(REQUIREMENT_LABELS, 'CONDITIONAL')).toBe('Conditional')
        expect(labelOrHumanized(REQUIREMENT_LABELS, 'REQUIRED')).toBe('Required')
        expect(labelOrHumanized(REQUIREMENT_LABELS, null)).toBe('')
        expect(labelOrLower(ATTESTATION_STATE_TEXT, 'SUSPENDED')).toBe('suspended')
    })
})

describe('the tables, each compared whole', () => {
    it('REQUIREMENT_LABELS', () => {
        expect(REQUIREMENT_LABELS).toEqual({ REQUIRED: 'Required', OPTIONAL: 'Optional' })
    })

    it('HISTORY_ACTION_LABELS', () => {
        expect(HISTORY_ACTION_LABELS).toEqual({ ADDED: 'Added', REMOVED: 'Removed', CHANGED: 'Changed', UPDATED: 'Updated' })
    })

    it('HISTORY_SCOPE_LABELS: the 22 values of ReleaseUpdateScope', () => {
        expect(HISTORY_SCOPE_LABELS).toEqual({
            RELEASE_CREATED: 'Release created', LIFECYCLE: 'Lifecycle', SOURCE_CODE_ENTRY: 'Source code entry',
            PARENT_RELEASE: 'Parent release', ARTIFACT: 'Artifact', INBOUND_DELIVERY: 'Inbound delivery',
            OUTBOUND_DELIVERY: 'Outbound delivery', VARIANT: 'Variant', VERSION: 'Version', NOTES: 'Notes', TAGS: 'Tags',
            MARKETING_VERSION: 'Marketing version', TRIGGER: 'Trigger', INPUT_TRIGGER: 'Input trigger', GUARD: 'Guard',
            APPROVED_ENVIRONMENT: 'Approved environment', SUPPORT_WINDOW: 'Release support dates',
            FDA_NARRATIVE: 'FDA assessment narrative', SYNTHETIC_ARTIFACT: 'Generated artifact', TEA_PUBLICATION: 'TEA publication',
            SUBMISSION: 'Regulatory submission', DECLARED_LICENSE: 'Declared license',
        })
        expect(Object.keys(HISTORY_SCOPE_LABELS)).toHaveLength(22)
    })

    it('ARTIFACT_TYPE_LABELS: the 28 artifact types, in the order of ARTIFACT_TYPES', () => {
        expect(ARTIFACT_TYPE_LABELS).toEqual({
            BOM: 'BOM', ATTESTATION: 'Attestation', VDR: 'VDR', BOV: 'BOV', VEX: 'VEX', USER_DOCUMENT: 'User document',
            DEVELOPMENT_DOCUMENT: 'Development document', PROJECT_DOCUMENT: 'Project document',
            MARKETING_DOCUMENT: 'Marketing document', TEST_REPORT: 'Test report', CODE_SCANNING_RESULT: 'Code scanning result',
            SARIF: 'SARIF', BUILD_META: 'Build metadata', CERTIFICATION: 'Certification', FORMULATION: 'Formulation',
            LICENSE: 'License', RELEASE_NOTES: 'Release notes', SECURITY_TXT: 'security.txt', THREAT_MODEL: 'Threat model',
            SIGNATURE: 'Signature', SIGNED_PAYLOAD: 'Signed payload', PUBLIC_KEY: 'Public key',
            CERTIFICATE_X_509: 'X.509 certificate', CERTIFICATE_PGP: 'PGP certificate', RISK_ASSESSMENT: 'Risk assessment',
            AGENTIC_REPORT: 'Agentic report', REGULATORY_DOCUMENT: 'Regulatory document', OTHER: 'Uncategorized',
        })
        expect(Object.keys(ARTIFACT_TYPE_LABELS)).toEqual(ARTIFACT_TYPES)
    })

    it('ATTESTATION_STATE_TEXT and UNKNOWN_REASON_TEXT: the server\'s texts, character for character', () => {
        expect(ATTESTATION_STATE_TEXT).toEqual({ ATTESTED: 'attested', WITHDRAWN: 'withdrawn' })
        expect(UNKNOWN_REASON_TEXT).toEqual({
            NO_COMMITTED_END_OF_SUPPORT: 'no committed end of support',
            COMMUNITY_PROJECT: 'community project, no support commitment',
            SUPPLIER_PUBLISHES_NO_DATE: 'supplier publishes no end-of-support date',
            DISTRIBUTION_PUBLISHES_NO_HASH: 'distribution publishes no per-package hash',
            VENDORED_SOURCE_NO_HASH: 'vendored source, no distributed artifact to hash',
            OTHER: 'see the manufacturer\'s recorded justification',
        })
        const members: JustifiedUnknownReason[] = ['NO_COMMITTED_END_OF_SUPPORT', 'COMMUNITY_PROJECT',
            'SUPPLIER_PUBLISHES_NO_DATE', 'DISTRIBUTION_PUBLISHES_NO_HASH', 'VENDORED_SOURCE_NO_HASH', 'OTHER']
        expect(Object.keys(UNKNOWN_REASON_TEXT).sort()).toEqual([...members].sort())
    })

    it('no table value is an enum name or OTHER', () => {
        const tables = [REQUIREMENT_LABELS, HISTORY_ACTION_LABELS, HISTORY_SCOPE_LABELS, ARTIFACT_TYPE_LABELS,
            ATTESTATION_STATE_TEXT, UNKNOWN_REASON_TEXT]
        let n = 0
        for (const t of tables) {
            for (const [k, v] of Object.entries(t)) {
                expect(v, k).not.toMatch(ENUM_RE)
                expect(v, k).not.toBe('OTHER')
                n++
            }
        }
        expect(n).toBe(2 + 4 + 22 + 28 + 2 + 6)
        expectSweepControl()
        expect('NO_COMMITTED_END_OF_SUPPORT').toMatch(ENUM_RE)
    })
})

describe('the helpers', () => {
    it('fdaLifecycleLabel: the 11 lifecycle labels in order, an unknown humanized, none empty', () => {
        expect(['CANCELLED', 'REJECTED', 'PENDING', 'DRAFT', 'ASSEMBLED', 'READY_TO_SHIP', 'GENERAL_AVAILABILITY',
            'END_OF_MARKETING', 'END_OF_DISTRIBUTION', 'END_OF_SUPPORT', 'END_OF_LIFE'].map(fdaLifecycleLabel))
            .toEqual(['Cancelled', 'Rejected', 'Pending', 'Draft', 'Assembled', 'Ready to Ship', 'Shipped', 'End of Marketing',
                'End of Distribution', 'End of Support', 'End of Life'])
        expect(fdaLifecycleLabel('SUNSET_REVIEW')).toBe('Sunset review')
        expect(fdaLifecycleLabel(null)).toBe('')
    })

    it('pluralWord', () => {
        expect(pluralWord('Feature Set')).toBe('feature sets')
        expect(pluralWord('Branch')).toBe('branches')
        expect(pluralWord('Class')).toBe('classes')
        expect(pluralWord('Box')).toBe('boxes')
        expect(pluralWord('Policy')).toBe('policies')
        expect(pluralWord('Day')).toBe('days')
        expect(pluralWord('')).toBe('')
    })

    it('documentInstantText: minute precision, truncated, UTC named; empty or unparsable as stated', () => {
        expect(documentInstantText('2026-11-12T08:43:00.873Z')).toBe('2026-11-12 08:43 UTC')
        expect(documentInstantText('2026-12-31T23:59:59.999Z')).toBe('2026-12-31 23:59 UTC')
        expect(documentInstantText('')).toBe('')
        expect(documentInstantText(null)).toBe('')
        expect(documentInstantText('last spring')).toBe('')
        expect(documentInstantText('last spring', true)).toBe('last spring')
    })

    it('documentFooterIdentity: device and version, no trailing space', () => {
        expect(documentFooterIdentity('Pump', '1.2.0')).toBe('Pump 1.2.0')
        expect(documentFooterIdentity(null, '1.2.0')).toBe('(unnamed device) 1.2.0')
        expect(documentFooterIdentity('Pump', null)).toBe('Pump')
    })

    it('the label functions of the other modules fall back to the humanized value (X1)', () => {
        expect(labelOf(SUBMISSION_STATE_LABELS, 'ON_HOLD')).toBe('On hold')
        expect(packageKindLabel('EU_MDR')).toBe('Eu mdr')
        expect(packagePartLabel('CHECK_REPORT')).toBe('Check report')
        expect(justifiedUnknownLabel('NEW_CODE')).toBe('New code')
    })
})
