// Regulatory package settings (task TEA-30, design 4.4 rows 20 and 21): which options apply to which
// part, the option lists against the schema enums, and the form and the save over two kinds.
import { describe, expect, it } from 'vitest'
import {
    PACKAGE_ARTIFACT_TYPE_OPTIONS, PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS, PACKAGE_KIND_LABELS, PACKAGE_PART_LABELS,
    PACKAGE_SELECTION_OPTIONS, PACKAGE_SUPPRESSED_FINDINGS_OPTIONS, PACKAGE_VEX_FORMAT_OPTIONS, packageEffectiveLine,
    PACKAGE_COMPONENT_QUESTION_HINT, packageEnabledHelp, packageEnabledLine, packageStateOf, packageFormError, packageFormFromView, packageKindLabel, packagePartFields, packageRemoveConfirm,
    packageSettingsInput, packageSourceLine, regulatoryPackagesAvailable,
    PACKAGE_MODE_OPTIONS, PACKAGE_PERSPECTIVE_MODE_OPTIONS, packageInheritConfirm, packageInheritedForm, packageModeOptions,
    packageInheritConflictHint, PACKAGE_INHERIT, PACKAGE_FIELD_HELP, packageEnabledLabel,
} from './packageSettings'
import { ENUM_RE, UUID_RE, expectSwept } from './__fixtures__/fdaSweep'
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

    it('the artifact types are the CE ArtifactType enum, labelled in words (FDAW-78 X8)', () => {
        expect(values(PACKAGE_ARTIFACT_TYPE_OPTIONS)).toEqual(enumValuesOf(ceSchema, 'ArtifactType'))
        expect(PACKAGE_ARTIFACT_TYPE_OPTIONS.find(o => o.value === 'USER_DOCUMENT')!.label).toBe('User document')
        expect(PACKAGE_ARTIFACT_TYPE_OPTIONS.find(o => o.value === 'OTHER')!.label).toBe('Uncategorized')
    })

    it.runIf(proSchema)('the lists equal the Pro enums, and every part label is a PackagePart and back', () => {
        expect(values(PACKAGE_ARTIFACT_TYPE_OPTIONS)).toEqual(enumValuesOf(proSchema!, 'ArtifactType'))
        expect(Object.keys(PACKAGE_PART_LABELS).sort()).toEqual(enumValuesOf(proSchema!, 'PackagePart').sort())
        expect(values(PACKAGE_SUPPRESSED_FINDINGS_OPTIONS).sort()).toEqual(enumValuesOf(proSchema!, 'SuppressedFindings').sort())
        expect(values(PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS).sort()).toEqual(enumValuesOf(proSchema!, 'InTriageFindings').sort())
        for (const k of Object.keys(PACKAGE_KIND_LABELS)) expect(enumValuesOf(proSchema!, 'PackageKind')).toContain(k)
    })

    it('an unknown kind renders humanized (FDAW-78 X1); the gate is the Pro edition', () => {
        expect(packageKindLabel('FDA_PREMARKET')).toBe('FDA premarket')
        expect(packageKindLabel('TEST_KIND')).toBe('Test kind')
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
        expect(packageFormError(f)).toBe('Raw artifacts needs at least one artifact type while included')
        f.parts[1].artifactTypes = ['OTHER']
        expect(packageFormError(f)).toBeNull()
        expect(packageFormError({ ...f, sbomProfile: ' ' })).toBe('An SBOM profile is required')
    })

    it('the lines and the removal confirmation', () => {
        expect(packageSourceLine(view(TEST_PARTS))).toBe('Nothing saved for this component: pre-filled from organization settings, revision 3')
        expect(packageSourceLine({ stored: { uuid: 'x' } })).toBe('Saved for this component')
        expect(packageEffectiveLine(view(TEST_PARTS).effective)).toBe('In use here: organization settings, revision 3')
        expect(packageEffectiveLine({ status: 'CONFLICT', conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }] }))
            .toContain('Payments')
        const c = packageRemoveConfirm('FDA_PREMARKET', 'component')
        expect(c.title).toBe('Remove package settings?')
        expect(c.text).toBe('Remove the package settings of FDA premarket at this component? It then resolves them from '
            + 'its parent or the built-in defaults.')
        expect(values(PACKAGE_SELECTION_OPTIONS)).toEqual(['INCLUDE', 'EXCLUDE'])
        expect(values(PACKAGE_VEX_FORMAT_OPTIONS)).toEqual(['CYCLONEDX_VEX', 'OPENVEX'])
    })

    it('state: DISABLED by default, pre-filled from the stored row or the parent, sent on every row but FOLLOW, never the alias', () => {
        expect(packageFormFromView(view(TEST_PARTS), 'ORGANIZATION').state).toBe('DISABLED')
        expect(packageFormFromView(view(TEST_PARTS, { parent: { status: 'NOT_FOUND', settings: null } }), 'ORGANIZATION').state)
            .toBe('DISABLED')
        const onParent = view(TEST_PARTS, { parent: { status: 'RESOLVED', source: 'ORGANIZATION', conflictingPerspectives: [],
            settings: { sbomProfile: 'p', state: 'ENABLED', enabled: true, parts: [] } } })
        expect(packageFormFromView(onParent, 'PERSPECTIVE').state).toBe('ENABLED')
        // A stored row with fields wins over the parent.
        expect(packageFormFromView({ ...onParent, stored: { mode: 'OVERRIDE', state: 'DISABLED', enabled: false, sbomProfile: 'x', parts: [] } },
            'COMPONENT').state).toBe('DISABLED')
        // A stored FOLLOW row (no state) pre-fills from the parent, as its other fields do.
        expect(packageFormFromView({ ...onParent, stored: { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1', state: null, enabled: null } },
            'COMPONENT').state).toBe('ENABLED')
        // FDAW-18: a row read with the deprecated alias only (enabled true, no state) reads ENABLED.
        expect(packageFormFromView({ ...onParent, stored: { mode: 'OVERRIDE', enabled: true, sbomProfile: 'x', parts: [] } },
            'COMPONENT').state).toBe('ENABLED')
        // state wins over the alias when both are read
        expect(packageStateOf({ state: 'DISABLED', enabled: true })).toBe('DISABLED')
        expect(packageStateOf({ state: 'ENABLED', enabled: false })).toBe('ENABLED')
        expect(packageStateOf(null)).toBe('DISABLED')

        const f = { ...packageFormFromView(onParent, 'COMPONENT'), mode: 'OVERRIDE' }
        expect(packageSettingsInput(f, 'COMPONENT').state).toBe('ENABLED')
        expect(packageSettingsInput(f, 'COMPONENT')).not.toHaveProperty('enabled')
        expect(packageSettingsInput({ ...f, state: 'DISABLED' }, 'ORGANIZATION').state).toBe('DISABLED')
        expect(packageSettingsInput({ ...f, state: 'DISABLED' }, 'ORGANIZATION')).not.toHaveProperty('enabled')
        const follow = packageSettingsInput({ ...f, mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' }, 'COMPONENT')
        expect(follow).not.toHaveProperty('state')
        expect(follow).not.toHaveProperty('enabled')
    })

    it('the Enabled help names the kind and follows the value shown, and the effective line reads the resolved settings', () => {
        const off = 'Off: releases of this component do not offer FDA premarket and no new '
            + 'submission can be made; existing submissions stay readable and can be cancelled.'
        const on = 'On: releases of this component offer FDA premarket and can be submitted.'
        expect(packageEnabledHelp('FDA_PREMARKET', false)).toBe(off)
        expect(packageEnabledHelp('FDA_PREMARKET', null)).toBe(off)
        expect(packageEnabledHelp('FDA_PREMARKET', true)).toBe(on)
        // FDAW-18: at a component scope the FDA premarket switch names its Medical device tab question
        expect(packageEnabledHelp('FDA_PREMARKET', true, 'COMPONENT', 'component')).toBe(on + ' On the Medical device tab this is question 2.')
        expect(packageEnabledHelp('FDA_PREMARKET', false, 'COMPONENT', 'component')).toBe(off + ' On the Medical device tab this is question 2.')
        expect(packageEnabledHelp('FDA_PREMARKET', true, 'PERSPECTIVE', 'perspective'))
            .toBe('On: releases of this perspective offer FDA premarket and can be submitted.')
        expect(PACKAGE_COMPONENT_QUESTION_HINT).toBe('On the Medical device tab this is question 2')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { state: 'ENABLED' } })).toBe('In use here: switched on')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { state: 'DISABLED' } })).toBe('In use here: switched off')
        expect(packageEnabledLine({ status: 'RESOLVED', settings: { enabled: true } })).toBe('In use here: switched on')
        expect(packageEnabledLine({ status: 'NOT_FOUND', settings: null })).toBe('In use here: switched off')
        expect(packageEnabledLine(null)).toBe('In use here: switched off')
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
            settings: { sbomProfile: 'parent-profile', state: 'ENABLED', parts: [{ part: 'RAW_ARTIFACTS', options: { selection: 'INCLUDE',
                artifactTypes: ['OTHER'] } }] } }
        const stored = { mode: 'OVERRIDE', state: 'DISABLED', sbomProfile: 'mine', description: 'kept', parts: [] }
        const f = packageInheritedForm(view(TEST_PARTS, { parent, stored }), 'COMPONENT')
        expect(f.mode).toBe('INHERIT')
        expect(f.state).toBe('ENABLED')
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
        expect(packageSourceLine(view(TEST_PARTS), {}, 'INHERIT')).toBe('Inherited from organization settings, revision 3')
        expect(packageSourceLine(view(TEST_PARTS, { parent: { status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: 'p1',
            settings: { revision: 4 } } }), { p1: 'Payments' }, 'INHERIT')).toBe('Inherited from settings of perspective Payments, revision 4')
        expect(packageSourceLine(view(TEST_PARTS, { parent: { status: 'NOT_FOUND', settings: null } }), {}, 'INHERIT'))
            .toBe('Inherited from built-in defaults')
        expect(packageSourceLine(view(TEST_PARTS, { stored: { uuid: 'x' } }), {}, 'INHERIT'))
            .toBe('Inherited from organization settings, revision 3: Save removes the settings saved here')
        expect(packageSourceLine(view(TEST_PARTS, { parent: { status: 'CONFLICT', conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }] } }),
            {}, 'INHERIT')).toContain('Conflict: the perspectives Payments')
        expect(packageSourceLine(view(TEST_PARTS), {}, 'OVERRIDE')).toBe('Nothing saved for this component: pre-filled from organization settings, revision 3')
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

/** FDAW-78 row 14 (REQ-67): the editor's lines, switch help and field help, in words; nothing says `scope`. */
describe('the Regulatory pane in words (FDAW-78 row 14)', () => {
    const UUID = '3bef7cde-2edb-47cb-aa4e-94487c2cdced'
    const C3_ORG_OFF = 'Off: FDA premarket is not offered by default. A product offers it once it is switched on for that product'
        + ' (the product\'s Settings > Medical device, question 2); products already switched on there keep working.'
        + ' Existing submissions stay readable and can be cancelled.'
    const C3_ORG_ON = 'On: every product and component without its own setting offers FDA premarket and can be submitted.'

    it('packageSourceLine: saved, nothing saved, and pre-filled from a parent, per scope word', () => {
        for (const w of ['organization', 'product', 'component', 'perspective']) {
            expect(packageSourceLine({ stored: { uuid: 'x' } }, {}, null, w)).toBe('Saved for this ' + w)
        }
        expect(packageSourceLine({ stored: null, parent: null }, {}, null, 'product'))
            .toBe('Nothing saved for this product or above it: pre-filled from the built-in defaults')
        expect(packageSourceLine({ stored: null, parent: { status: 'RESOLVED', source: 'ORGANIZATION', settings: { revision: 3 } } },
            {}, null, 'product')).toBe('Nothing saved for this product: pre-filled from organization settings, revision 3')
        const inheritView = { stored: { uuid: 'x' }, parent: { status: 'RESOLVED', source: 'ORGANIZATION', settings: { revision: 1 } } }
        expect(packageSourceLine(inheritView, {}, PACKAGE_INHERIT, 'product'))
            .toBe('Inherited from organization settings, revision 1: Save removes the settings saved here')
        expect(packageSourceLine({ ...inheritView, stored: null }, {}, PACKAGE_INHERIT, 'product'))
            .toBe('Inherited from organization settings, revision 1')
    })

    it('packageEffectiveLine: where the settings in use come from, never a uuid', () => {
        const r = (source: string, revision: number | null, sourceObject: string | null = null) =>
            ({ status: 'RESOLVED', source, sourceObject, settings: { revision } })
        expect(packageEffectiveLine(r('ORGANIZATION', 0), {}, 'organization')).toBe('In use here: organization settings, revision 0')
        expect(packageEffectiveLine(r('COMPONENT', 2), {}, 'product')).toBe('In use here: this product\'s own settings, revision 2')
        expect(packageEffectiveLine(r('COMPONENT', 2), {}, 'component')).toBe('In use here: this component\'s own settings, revision 2')
        expect(packageEffectiveLine(r('PERSPECTIVE', 1, 'p1'), { p1: 'Payments' }, 'perspective'))
            .toBe('In use here: settings of perspective Payments, revision 1')
        const nameless = packageEffectiveLine(r('PERSPECTIVE', 1, UUID), {}, 'product')
        expect(nameless).toBe('In use here: settings of a perspective, revision 1')
        expect(nameless).not.toMatch(UUID_RE)
        expect(UUID).toMatch(UUID_RE)
        expect(packageEffectiveLine(r('DEFAULT', null), {}, 'product')).toBe('In use here: built-in defaults')
        expect(packageEffectiveLine(r('REGION', 4), {}, 'product')).toBe('In use here: built-in defaults')
        expect(packageEffectiveLine({ status: 'CONFLICT', conflictingPerspectives: [{ uuid: UUID }] }, {}, 'product'))
            .toBe('In use here: conflict between perspectives a perspective; a submission is refused')
        expect(packageEffectiveLine({ status: 'NOT_FOUND' }, {}, 'product').startsWith('In use here: ')).toBe(true)
        expect(packageEffectiveLine(null)).toBe('')
    })

    it('packageEnabledHelp and the switch label: the C3 texts, character for character', () => {
        expect(packageEnabledLabel('ORGANIZATION')).toBe('On by default')
        expect(packageEnabledLabel('COMPONENT')).toBe('Enabled')
        expect(packageEnabledLabel('PERSPECTIVE')).toBe('Enabled')
        expect(packageEnabledHelp('FDA_PREMARKET', false, 'ORGANIZATION', 'organization')).toBe(C3_ORG_OFF)
        expect(packageEnabledHelp('FDA_PREMARKET', true, 'ORGANIZATION', 'organization')).toBe(C3_ORG_ON)
        expect(packageEnabledHelp('FDA_PREMARKET', false, 'COMPONENT', 'product')).toBe('Off: releases of this product do not offer'
            + ' FDA premarket and no new submission can be made; existing submissions stay readable and can be cancelled.'
            + ' On the Medical device tab this is question 2.')
        expect(packageEnabledHelp('FDA_PREMARKET', true, 'COMPONENT', 'product'))
            .toBe('On: releases of this product offer FDA premarket and can be submitted. On the Medical device tab this is question 2.')
        expect(packageEnabledHelp('FDA_PREMARKET', null, 'PERSPECTIVE', 'perspective')).toBe('Off: releases of this perspective do not'
            + ' offer FDA premarket and no new submission can be made; existing submissions stay readable and can be cancelled.')
    })

    it('the field help and the raw-artifacts texts read in words; no help says scope', () => {
        expect(PACKAGE_FIELD_HELP.mode).toBe('Inherit saves nothing here; Override defines the settings here; Follow takes one perspective\'s settings.')
        expect(PACKAGE_FIELD_HELP.sbomProfile).toBe('The SBOM profile the aggregate SBOM part is generated under; one name, as it is in use here')
        expect(PACKAGE_FIELD_HELP.selection).toBe('Whether the part is in the package; a required part is always included.')
        expect(PACKAGE_FIELD_HELP.artifactTypes)
            .toBe('Attached release artifacts of these types are added to the package as raw artifacts; at least one type when included')
        for (const [k, v] of Object.entries(PACKAGE_FIELD_HELP)) {
            expect(v, k).not.toMatch(/scope/i)
            expect(v, k).not.toMatch(ENUM_RE)
        }
        // control: the old artifactTypes help carries an enum name
        expect('Attached release artifacts of these types ride along as RAW_ARTIFACTS; at least one type when included').toMatch(ENUM_RE)
        expect(PACKAGE_ARTIFACT_TYPE_OPTIONS.map(o => o.label)).toEqual(['BOM', 'Attestation', 'VDR', 'BOV', 'VEX', 'User document',
            'Development document', 'Project document', 'Marketing document', 'Test report', 'Code scanning result', 'SARIF',
            'Build metadata', 'Certification', 'Formulation', 'License', 'Release notes', 'security.txt', 'Threat model', 'Signature',
            'Signed payload', 'Public key', 'X.509 certificate', 'PGP certificate', 'Risk assessment', 'Agentic report',
            'Regulatory document', 'Uncategorized'])
    })

    it('the lines pass the sweep, with its control', () => {
        const lines = [
            packageSourceLine({ stored: { uuid: 'x' } }, {}, null, 'organization'),
            packageEffectiveLine({ status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: UUID, settings: { revision: 1 } }, {}, 'product'),
            packageEnabledLine({ settings: { state: 'ENABLED' } }),
            packageEnabledHelp('FDA_PREMARKET', false, 'ORGANIZATION', 'organization'),
            ...Object.values(PACKAGE_FIELD_HELP),
        ]
        expectSwept(lines.join('\n'))
    })
})
