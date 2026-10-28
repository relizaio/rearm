// Regulatory package settings (task TEA-30, design 4.4 rows 20 and 21): which options apply to which
// part, the option lists against the schema enums, and the form and the save over two kinds.
import { describe, expect, it } from 'vitest'
import {
    PACKAGE_ARTIFACT_TYPE_OPTIONS, PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS, PACKAGE_KIND_LABELS, PACKAGE_PART_LABELS,
    PACKAGE_SELECTION_OPTIONS, PACKAGE_SUPPRESSED_FINDINGS_OPTIONS, PACKAGE_VEX_FORMAT_OPTIONS, packageEffectiveLine,
    packageEnabledHelp, packageEnabledLine, packageFormError, packageFormFromView, packageKindLabel, packagePartFields, packageRemoveConfirm,
    packageSettingsInput, packageSourceLine, regulatoryPackagesAvailable,
    PACKAGE_MODE_OPTIONS, PACKAGE_PERSPECTIVE_MODE_OPTIONS, packageInheritConfirm, packageInheritedForm, packageModeOptions,
    packageInheritConflictHint, PACKAGE_INHERIT,
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
        const f = { ...packageFormFromView(view(FDA_PARTS), 'COMPONENT'), mode: 'OVERRIDE' }
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

        const f = { ...packageFormFromView(onParent, 'COMPONENT'), mode: 'OVERRIDE' }
        expect(packageSettingsInput(f, 'COMPONENT').enabled).toBe(true)
        expect(packageSettingsInput({ ...f, enabled: false }, 'ORGANIZATION').enabled).toBe(false)
        expect(packageSettingsInput({ ...f, mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, 'COMPONENT'))
            .not.toHaveProperty('enabled')
    })

    it('the Enabled help names the kind and follows the value shown, and the effective line reads the resolved settings', () => {
        const off = 'Off: releases of this scope do not offer FDA premarket and no new '
            + 'submission can be made; existing submissions stay readable and can be withdrawn.'
        expect(packageEnabledHelp('FDA_PREMARKET', false)).toBe(off)
        expect(packageEnabledHelp('FDA_PREMARKET', null)).toBe(off)
        expect(packageEnabledHelp('FDA_PREMARKET', true)).toBe('On: releases of this scope offer FDA premarket and can be submitted.')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { enabled: true } })).toBe('Enabled here: on')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { enabled: false } })).toBe('Enabled here: off')
        expect(packageEnabledLine({ status: 'NOT_FOUND', settings: null })).toBe('Enabled here: off')
        expect(packageEnabledLine(null)).toBe('Enabled here: off')
    })
})

describe('the Inherit mode (UI only, never sent)', () => {
    it('the mode radio per scope: Inherit first; none at the organization, no Follow at a perspective or on CE', () => {
        expect(values(PACKAGE_MODE_OPTIONS)).toEqual(['INHERIT', 'OVERRIDE', 'FOLLOW_PERSPECTIVE'])
        expect(values(PACKAGE_PERSPECTIVE_MODE_OPTIONS)).toEqual(['INHERIT', 'OVERRIDE'])
        expect(packageModeOptions('ORGANIZATION', 'SAAS')).toEqual([])
        expect(values(packageModeOptions('PERSPECTIVE', 'SAAS'))).toEqual(['INHERIT', 'OVERRIDE'])
        expect(values(packageModeOptions('COMPONENT', 'SAAS'))).toEqual(['INHERIT', 'OVERRIDE', 'FOLLOW_PERSPECTIVE'])
        expect(values(packageModeOptions('COMPONENT', 'OSS'))).toEqual(['INHERIT', 'OVERRIDE'])
        expect(PACKAGE_MODE_OPTIONS[0].label).toBe('Inherit: use the perspective or organization settings')
        expect(PACKAGE_PERSPECTIVE_MODE_OPTIONS[0].label).toBe('Inherit: use the organization settings')
    })

    it('opens in INHERIT with no stored row, in the stored mode otherwise, with no mode at the organization', () => {
        expect(packageFormFromView(view(TEST_PARTS), 'COMPONENT').mode).toBe('INHERIT')
        expect(packageFormFromView(view(TEST_PARTS), 'PERSPECTIVE').mode).toBe('INHERIT')
        expect(packageFormFromView(view(TEST_PARTS), 'ORGANIZATION').mode).toBeNull()
        const stored = { mode: 'OVERRIDE', sbomProfile: 'x', parts: [] }
        expect(packageFormFromView(view(TEST_PARTS, { stored }), 'COMPONENT').mode).toBe('OVERRIDE')
        expect(packageFormFromView(view(TEST_PARTS, { stored: { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' } }),
            'COMPONENT').mode).toBe('FOLLOW_PERSPECTIVE')
        expect(packageFormFromView(view(TEST_PARTS, { stored: { ...stored, mode: null } }), 'PERSPECTIVE').mode).toBe('OVERRIDE')
    })

    it('an INHERIT form shows the parent settings whatever is stored, Enabled included', () => {
        const parent = { status: 'RESOLVED', source: 'ORGANIZATION', conflictingPerspectives: [],
            settings: { sbomProfile: 'parent-profile', enabled: true, parts: [{ part: 'RAW_ARTIFACTS', options: { selection: 'INCLUDE',
                artifactTypes: ['OTHER'] } }] } }
        const stored = { mode: 'OVERRIDE', enabled: false, sbomProfile: 'mine', description: 'kept', parts: [] }
        const f = packageInheritedForm(view(TEST_PARTS, { parent, stored }), 'COMPONENT')
        expect(f.mode).toBe('INHERIT')
        expect(f.enabled).toBe(true)
        expect(f.sbomProfile).toBe('parent-profile')
        expect(f.description).toBe('')
        expect(f.parts.find(p => p.part === 'RAW_ARTIFACTS')!.artifactTypes).toEqual(['OTHER'])
        expect(packageFormError(f)).toBeNull()
    })

    it('never builds a save from INHERIT: inheriting again removes the row instead', () => {
        const f = packageFormFromView(view(TEST_PARTS), 'COMPONENT')
        for (const scope of ['COMPONENT', 'PERSPECTIVE', 'ORGANIZATION'] as const) {
            expect(() => packageSettingsInput(f, scope), scope).toThrow('INHERIT is not stored')
        }
    })

    it('the INHERIT source line names the parent, and the confirmation names the scope and its parents', () => {
        expect(packageSourceLine(view(TEST_PARTS), {}, 'INHERIT')).toBe('Inherited from organization, revision 3')
        expect(packageSourceLine(view(TEST_PARTS, { parent: { status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: 'p1',
            settings: { revision: 4 } } }), { p1: 'Payments' }, 'INHERIT')).toBe('Inherited from perspective Payments, revision 4')
        expect(packageSourceLine(view(TEST_PARTS, { parent: { status: 'NOT_FOUND', settings: null } }), {}, 'INHERIT'))
            .toBe('Inherited from built-in defaults')
        expect(packageSourceLine(view(TEST_PARTS, { stored: { uuid: 'x' } }), {}, 'INHERIT'))
            .toBe('Inherited from organization, revision 3: Save removes the stored settings')
        expect(packageSourceLine(view(TEST_PARTS, { parent: { status: 'CONFLICT', conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }] } }),
            {}, 'INHERIT')).toContain('Conflict: the perspectives Payments')
        expect(packageSourceLine(view(TEST_PARTS), {}, 'OVERRIDE')).toBe('No override: pre-filled from organization, revision 3')
        expect(packageInheritConfirm('COMPONENT', 'component')).toEqual({ title: 'Inherit again?',
            content: "Remove this component's override and inherit the perspective or organization settings again?" })
        expect(packageInheritConfirm('PERSPECTIVE', 'perspective').content)
            .toBe("Remove this perspective's override and inherit the organization settings again?")
    })
})

describe('the INHERIT conflict hint', () => {
    const hint = "Inheriting resolves through this component's perspectives: if more than one of them carries its own "
        + 'FDA premarket settings, inheriting stops with a conflict \u2014 use Follow one of its perspectives to pin one.'
    const resolved = { parent: { status: 'RESOLVED', source: 'ORGANIZATION' } }

    it('names the kind, at COMPONENT scope in INHERIT with a perspective', () => {
        expect(packageInheritConflictHint('COMPONENT', PACKAGE_INHERIT, 2, resolved, 'FDA_PREMARKET')).toBe(hint)
    })

    it('is null in another mode or scope, without perspectives, and once the resolution conflicts', () => {
        expect(packageInheritConflictHint('COMPONENT', 'OVERRIDE', 2, resolved, 'FDA_PREMARKET')).toBeNull()
        expect(packageInheritConflictHint('COMPONENT', 'FOLLOW_PERSPECTIVE', 2, resolved, 'FDA_PREMARKET')).toBeNull()
        expect(packageInheritConflictHint('PERSPECTIVE', PACKAGE_INHERIT, 2, resolved, 'FDA_PREMARKET')).toBeNull()
        expect(packageInheritConflictHint('COMPONENT', PACKAGE_INHERIT, 0, resolved, 'FDA_PREMARKET')).toBeNull()
        expect(packageInheritConflictHint('COMPONENT', PACKAGE_INHERIT, 2, { parent: { status: 'CONFLICT' } }, 'FDA_PREMARKET')).toBeNull()
    })
})
