// Regulatory package settings (task TEA-30, design 4.4 rows 20 and 21): which options apply to which
// part, the option lists against the schema enums, and the form and the save over two kinds.
import { describe, expect, it } from 'vitest'
import {
    PACKAGE_ARTIFACT_TYPE_OPTIONS, PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS, PACKAGE_KIND_LABELS, PACKAGE_PART_LABELS,
    PACKAGE_SELECTION_OPTIONS, PACKAGE_SUPPRESSED_FINDINGS_OPTIONS, PACKAGE_VEX_FORMAT_OPTIONS, packageEffectiveLine,
    packageEnabledHelp, packageEnabledLine, packageFormError, packageFormFromView, packageKindLabel, packagePartFields, packageRemoveConfirm,
    packageSettingsInput, packageSourceLine, regulatoryPackagesAvailable,
} from './packageSettings'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'

const values = (opts: { value: string }[]) => opts.map(o => o.value)

const FDA_PARTS = [
    { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'VDR', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'VEX', requirement: 'OPTIONAL', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'NARRATIVE', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'SUPPORT_ADDENDUM', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'DEVICE_SUPPORT_STATEMENT', requirement: 'OPTIONAL', defaultSelection: 'EXCLUDE', generatorPresent: false },
    { part: 'RAW_ARTIFACTS', requirement: 'OPTIONAL', defaultSelection: 'EXCLUDE', generatorPresent: true },
]
const TEST_PARTS = [
    { part: 'AGGREGATE_SBOM', requirement: 'REQUIRED', defaultSelection: 'INCLUDE', generatorPresent: true },
    { part: 'RAW_ARTIFACTS', requirement: 'OPTIONAL', defaultSelection: 'EXCLUDE', generatorPresent: true },
]

describe('parts and options (row 20)', () => {
    it('selection applies to every part; the others as PackagePartOptions.applicableTo says', () => {
        expect(packagePartFields('VDR')).toEqual(['selection', 'suppressedFindings'])
        expect(packagePartFields('VEX')).toEqual(['selection', 'suppressedFindings', 'inTriageFindings', 'vexFormat'])
        expect(packagePartFields('RAW_ARTIFACTS')).toEqual(['selection', 'artifactTypes'])
        for (const p of ['AGGREGATE_SBOM', 'SUPPORT_ADDENDUM', 'DEVICE_SUPPORT_STATEMENT', 'NARRATIVE', 'CLE', 'UNKNOWN']) {
            expect(packagePartFields(p), p).toEqual(['selection'])
        }
    })

    it('the artifact types are the CE ArtifactType enum, labelled with spaces', () => {
        expect(values(PACKAGE_ARTIFACT_TYPE_OPTIONS)).toEqual(enumValuesOf(ceSchema, 'ArtifactType'))
        expect(PACKAGE_ARTIFACT_TYPE_OPTIONS.find(o => o.value === 'USER_DOCUMENT')!.label).toBe('USER DOCUMENT')
    })

    it.runIf(proSchema)('the lists equal the Pro enums, and every part label is a PackagePart and back', () => {
        expect(values(PACKAGE_ARTIFACT_TYPE_OPTIONS)).toEqual(enumValuesOf(proSchema!, 'ArtifactType'))
        expect(Object.keys(PACKAGE_PART_LABELS).sort()).toEqual(enumValuesOf(proSchema!, 'PackagePart').sort())
        expect(values(PACKAGE_SUPPRESSED_FINDINGS_OPTIONS).sort()).toEqual(enumValuesOf(proSchema!, 'SuppressedFindings').sort())
        expect(values(PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS).sort()).toEqual(enumValuesOf(proSchema!, 'InTriageFindings').sort())
        for (const k of Object.keys(PACKAGE_KIND_LABELS)) expect(enumValuesOf(proSchema!, 'PackageKind')).toContain(k)
    })

    it('an unknown kind renders its value; the gate is the Pro edition', () => {
        expect(packageKindLabel('FDA_PREMARKET')).toBe('FDA premarket')
        expect(packageKindLabel('TEST_KIND')).toBe('TEST_KIND')
        expect(regulatoryPackagesAvailable('OSS')).toBe(false)
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE']) expect(regulatoryPackagesAvailable(t)).toBe(true)
    })
})

const view = (kindParts: any[], changes: Record<string, any> = {}) => ({
    stored: null,
    parent: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null, conflictingPerspectives: [],
        settings: { sbomProfile: 'parent-profile', revision: 3, parts: [] } },
    effective: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null, conflictingPerspectives: [],
        settings: { sbomProfile: 'parent-profile', revision: 3 } },
    kind: { kind: 'X', parts: kindParts },
    ...changes,
})

describe('the form and the save over two kinds (row 21)', () => {
    it('parts in descriptor order; REQUIRED is INCLUDE even when stored EXCLUDE; nulls filled', () => {
        const stored = { mode: 'OVERRIDE', sbomProfile: 'mine', description: 'kept', parts: [
            { part: 'VDR', options: { selection: 'EXCLUDE' } },
            { part: 'VEX', options: { selection: null, suppressedFindings: null, inTriageFindings: null, vexFormat: null } },
        ] }
        const f = packageFormFromView(view(FDA_PARTS, { stored }), 'COMPONENT')
        expect(f.parts.map(p => p.part)).toEqual(FDA_PARTS.map(p => p.part))
        expect(f.parts.find(p => p.part === 'VDR')!.selection).toBe('INCLUDE')
        const vex = f.parts.find(p => p.part === 'VEX')!
        expect([vex.selection, vex.suppressedFindings, vex.inTriageFindings, vex.vexFormat])
            .toEqual(['INCLUDE', 'EXCLUDE', 'EXCLUDE', 'CYCLONEDX_VEX'])
        expect(f.parts.find(p => p.part === 'RAW_ARTIFACTS')!.artifactTypes).toEqual([])
        expect(f.parts.find(p => p.part === 'DEVICE_SUPPORT_STATEMENT')!.generatorPresent).toBe(false)
        expect(f.sbomProfile).toBe('mine')
        expect(f.description).toBe('kept')
        expect(f.mode).toBe('OVERRIDE')

        const two = packageFormFromView(view(TEST_PARTS), 'ORGANIZATION')
        expect(two.parts.map(p => [p.part, p.selection])).toEqual([['AGGREGATE_SBOM', 'INCLUDE'], ['RAW_ARTIFACTS', 'EXCLUDE']])
        expect(two.sbomProfile).toBe('parent-profile')
        expect(two.description).toBe('')
        expect(two.mode).toBeNull()
        expect(packageFormFromView(view(TEST_PARTS, { parent: { status: 'NOT_FOUND', settings: null } }), 'ORGANIZATION').sbomProfile)
            .toBe('default')
    })

    it('a save sends one entry per part with the applicable fields only, types deduplicated; FOLLOW sends two keys', () => {
        const f = packageFormFromView(view(FDA_PARTS), 'COMPONENT')
        const raw = f.parts.find(p => p.part === 'RAW_ARTIFACTS')!
        raw.selection = 'INCLUDE'
        raw.artifactTypes = ['USER_DOCUMENT', 'USER_DOCUMENT', 'TEST_REPORT']
        f.parts.find(p => p.part === 'AGGREGATE_SBOM')!.selection = 'EXCLUDE'
        const sent = packageSettingsInput({ ...f, description: '  d  ' }, 'COMPONENT')
        expect(sent.mode).toBe('OVERRIDE')
        expect(sent.sbomProfile).toBe('parent-profile')
        expect(sent.description).toBe('d')
        expect(sent.parts.map((p: any) => p.part)).toEqual(FDA_PARTS.map(p => p.part))
        expect(sent.parts.find((p: any) => p.part === 'AGGREGATE_SBOM')).toEqual({ part: 'AGGREGATE_SBOM', selection: 'INCLUDE' })
        const vdr = sent.parts.find((p: any) => p.part === 'VDR')
        expect(Object.keys(vdr).sort()).toEqual(['part', 'selection', 'suppressedFindings'])
        expect(vdr).not.toHaveProperty('vexFormat')
        expect(Object.keys(sent.parts.find((p: any) => p.part === 'VEX')).sort())
            .toEqual(['inTriageFindings', 'part', 'selection', 'suppressedFindings', 'vexFormat'])
        expect(sent.parts.find((p: any) => p.part === 'RAW_ARTIFACTS')).toEqual({ part: 'RAW_ARTIFACTS', selection: 'INCLUDE',
            artifactTypes: ['USER_DOCUMENT', 'TEST_REPORT'] })
        expect(packageSettingsInput({ ...f, mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, 'COMPONENT'))
            .toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' })
        expect(packageSettingsInput(f, 'ORGANIZATION')).not.toHaveProperty('mode')
    })

    it('refuses an included RAW_ARTIFACTS with no type, and a blank profile', () => {
        const f = packageFormFromView(view(TEST_PARTS), 'ORGANIZATION')
        expect(packageFormError(f)).toBeNull()
        f.parts[1].selection = 'INCLUDE'
        expect(packageFormError(f)).toBe('RAW_ARTIFACTS needs at least one artifact type while included')
        f.parts[1].artifactTypes = ['OTHER']
        expect(packageFormError(f)).toBeNull()
        expect(packageFormError({ ...f, sbomProfile: ' ' })).toBe('An SBOM profile is required')
    })

    it('the lines and the removal confirmation', () => {
        expect(packageSourceLine(view(TEST_PARTS))).toBe('No override: pre-filled from organization, revision 3')
        expect(packageSourceLine({ stored: { uuid: 'x' } })).toBe('Stored at this scope')
        expect(packageEffectiveLine(view(TEST_PARTS).effective)).toBe('Effective here: organization, revision 3')
        expect(packageEffectiveLine({ status: 'CONFLICT', conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }] }))
            .toContain('Payments')
        const c = packageRemoveConfirm('FDA_PREMARKET', 'component')
        expect(c.title).toBe('Remove package settings?')
        expect(c.text).toBe('Remove the package settings of FDA premarket at this component? It then resolves them from '
            + 'its parent or the built-in defaults.')
        expect(values(PACKAGE_SELECTION_OPTIONS)).toEqual(['INCLUDE', 'EXCLUDE'])
        expect(values(PACKAGE_VEX_FORMAT_OPTIONS)).toEqual(['CYCLONEDX_VEX', 'OPENVEX'])
    })

    it('enabled: off by default, pre-filled from the stored row or the parent, sent on every row but FOLLOW', () => {
        expect(packageFormFromView(view(TEST_PARTS), 'ORGANIZATION').enabled).toBe(false)
        expect(packageFormFromView(view(TEST_PARTS, { parent: { status: 'NOT_FOUND', settings: null } }), 'ORGANIZATION').enabled)
            .toBe(false)
        const onParent = view(TEST_PARTS, { parent: { status: 'RESOLVED', source: 'ORGANIZATION', conflictingPerspectives: [],
            settings: { sbomProfile: 'p', enabled: true, parts: [] } } })
        expect(packageFormFromView(onParent, 'PERSPECTIVE').enabled).toBe(true)
        // A stored row with fields wins over the parent.
        expect(packageFormFromView({ ...onParent, stored: { mode: 'OVERRIDE', enabled: false, sbomProfile: 'x', parts: [] } },
            'COMPONENT').enabled).toBe(false)
        // A stored FOLLOW row (enabled null) pre-fills from the parent, as its other fields do.
        expect(packageFormFromView({ ...onParent, stored: { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', enabled: null } },
            'COMPONENT').enabled).toBe(true)

        const f = packageFormFromView(onParent, 'COMPONENT')
        expect(packageSettingsInput(f, 'COMPONENT').enabled).toBe(true)
        expect(packageSettingsInput({ ...f, enabled: false }, 'ORGANIZATION').enabled).toBe(false)
        expect(packageSettingsInput({ ...f, mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, 'COMPONENT'))
            .not.toHaveProperty('enabled')
    })

    it('the Enabled help names the kind, and the effective line reads the resolved settings', () => {
        expect(packageEnabledHelp('FDA_PREMARKET')).toBe('Off: releases of this scope do not offer FDA premarket and no new '
            + 'submission can be made; existing submissions stay readable and can be withdrawn.')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { enabled: true } })).toBe('Enabled here: on')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { enabled: false } })).toBe('Enabled here: off')
        expect(packageEnabledLine({ status: 'NOT_FOUND', settings: null })).toBe('Enabled here: off')
        expect(packageEnabledLine(null)).toBe('Enabled here: off')
    })
})
