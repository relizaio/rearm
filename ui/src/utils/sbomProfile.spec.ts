// Named SBOM profiles (task TEA-30, design 4.1 rows 1 to 8): the option lists against the schema
// enums, the name rule, the form and the save, the source and effective lines, the catalog rows,
// the picker options and the delete confirmation.
import { describe, expect, it } from 'vitest'
import {
    SBOM_DEPENDENCY_DEPTH_OPTIONS, SBOM_EXCLUDED_COVERAGE_OPTIONS, SBOM_FIELD_HELP, SBOM_INTERNAL_METADATA_OPTIONS,
    SBOM_MODE_OPTIONS, SBOM_OPTIONAL_DEPENDENCIES_OPTIONS, SBOM_PROFILE_FIELDS, SBOM_SOURCES_OPTIONS,
    SBOM_STRUCTURE_OPTIONS, SBOM_SUPPORT_METADATA_OPTIONS, SBOM_VULNERABILITY_DOCUMENTS_OPTIONS, sbomBuiltInDefaults,
    sbomCatalogRows, sbomDeleteConfirm, sbomEffectiveLine, sbomFormFromView, sbomNameError, sbomPickerLines,
    sbomPickerMultiple, sbomPickerOptions, sbomProfileInput, sbomSourceLine, sbomValueText,
} from './sbomProfile'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'

const values = (opts: { value: string }[]) => opts.map(o => o.value)

describe('SBOM profile option lists (row 1)', () => {
    it('the built-in defaults equal the server built-in', () => {
        expect(sbomBuiltInDefaults()).toEqual({
            mode: null, followedPerspective: null, description: '',
            dependencyDepth: 'FULL', optionalDependencies: 'INCLUDE', structure: 'FLAT',
            sources: ['DELIVERABLE', 'RELEASE', 'SOURCE_CODE'], excludedCoverage: ['DEV', 'TEST'],
            supportMetadata: 'EXCLUDE', internalMetadata: 'EXCLUDE', vulnerabilityDocuments: 'NONE',
        })
    })

    it('every list is {label, value} pairs with a label', () => {
        for (const list of [SBOM_MODE_OPTIONS, SBOM_DEPENDENCY_DEPTH_OPTIONS, SBOM_OPTIONAL_DEPENDENCIES_OPTIONS,
            SBOM_STRUCTURE_OPTIONS, SBOM_SOURCES_OPTIONS, SBOM_EXCLUDED_COVERAGE_OPTIONS, SBOM_SUPPORT_METADATA_OPTIONS,
            SBOM_INTERNAL_METADATA_OPTIONS, SBOM_VULNERABILITY_DOCUMENTS_OPTIONS]) {
            for (const o of list) {
                expect(Object.keys(o).sort()).toEqual(['label', 'value'])
                expect(o.label.length).toBeGreaterThan(0)
            }
        }
        expect(values(SBOM_MODE_OPTIONS)).toEqual(['OVERRIDE', 'FOLLOW_PERSPECTIVE'])
        for (const f of SBOM_PROFILE_FIELDS) expect(SBOM_FIELD_HELP[f], f).toBeTruthy()
        for (const f of ['name', 'description', 'mode']) expect(SBOM_FIELD_HELP[f], f).toBeTruthy()
    })

    const ENUMS: [string, { value: string }[]][] = [
        ['SbomDependencyDepth', SBOM_DEPENDENCY_DEPTH_OPTIONS],
        ['SbomOptionalDependencies', SBOM_OPTIONAL_DEPENDENCIES_OPTIONS],
        ['BomStructureType', SBOM_STRUCTURE_OPTIONS],
        ['SbomSourceKind', SBOM_SOURCES_OPTIONS],
        ['ArtifactCoverageType', SBOM_EXCLUDED_COVERAGE_OPTIONS],
        ['SbomSupportMetadata', SBOM_SUPPORT_METADATA_OPTIONS],
        ['SbomInternalMetadata', SBOM_INTERNAL_METADATA_OPTIONS],
        ['SbomVulnerabilityDocuments', SBOM_VULNERABILITY_DOCUMENTS_OPTIONS],
        ['ScopedSettingsMode', SBOM_MODE_OPTIONS],
    ]

    it.runIf(proSchema)('every list carries exactly the members of its Pro enum', () => {
        for (const [e, list] of ENUMS) expect(values(list).sort(), e).toEqual(enumValuesOf(proSchema!, e).sort())
    })

    it('the shared enums match the CE schema too', () => {
        expect(values(SBOM_STRUCTURE_OPTIONS).sort()).toEqual(enumValuesOf(ceSchema, 'BomStructureType').sort())
        expect(values(SBOM_EXCLUDED_COVERAGE_OPTIONS).sort()).toEqual(enumValuesOf(ceSchema, 'ArtifactCoverageType').sort())
    })

    it('renders values through the option labels; an unknown value renders itself', () => {
        expect(sbomValueText('dependencyDepth', 'TOP_LEVEL_ONLY')).toBe('Top-level dependencies only')
        expect(sbomValueText('sources', ['DELIVERABLE', 'RELEASE'])).toBe('Deliverable SBOMs, Release SBOMs')
        expect(sbomValueText('structure', 'SPIRAL')).toBe('SPIRAL')
        expect(sbomValueText('structure', null)).toBe('')
    })
})

describe('the name rule (row 2)', () => {
    it('accepts the server pattern and refuses the rest', () => {
        for (const ok of ['default', 'a', 'A1.b_c-d', 'x'.repeat(64)]) expect(sbomNameError(ok), ok).toBeNull()
        expect(sbomNameError('')).toBe('A profile name is required')
        expect(sbomNameError('   ')).toBe('A profile name is required')
        for (const bad of ['x'.repeat(65), '.lead', 'a b', 'a/b', '<b>']) {
            expect(sbomNameError(bad), bad).toBe('Letters, digits, dot, underscore and dash only, 1 to 64 characters, '
                + 'starting with a letter or digit')
        }
    })
})

const PARENT_PROFILE = { ...sbomBuiltInDefaults(), name: 'top', dependencyDepth: 'TOP_LEVEL_ONLY', revision: 2,
    description: 'from the org' }
const resolved = (source: string, profile: any, sourceObject: string | null = null) =>
    ({ status: 'RESOLVED', source, sourceObject, profile, conflictingPerspectives: [] })
const P1 = { uuid: 'p1', name: 'Payments', type: 'PERSPECTIVE' }
const P2 = { uuid: 'p2', name: 'Retail', type: 'PERSPECTIVE' }

describe('the form and the save (rows 3 and 4)', () => {
    it('row 3: a stored OVERRIDE row wins', () => {
        const stored = { ...sbomBuiltInDefaults(), mode: 'OVERRIDE', structure: 'HIERARCHICAL', description: 'mine' }
        const f = sbomFormFromView({ stored, parent: resolved('ORGANIZATION', PARENT_PROFILE) }, 'COMPONENT')
        expect(f.structure).toBe('HIERARCHICAL')
        expect(f.dependencyDepth).toBe('FULL')
        expect(f.mode).toBe('OVERRIDE')
        expect(f.description).toBe('mine')
    })

    it('row 3: a stored FOLLOW row keeps its mode and perspective, fields from the parent', () => {
        const f = sbomFormFromView({ stored: { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' },
            parent: resolved('ORGANIZATION', PARENT_PROFILE) }, 'COMPONENT')
        expect(f.mode).toBe('FOLLOW_PERSPECTIVE')
        expect(f.followedPerspective).toBe('p1')
        expect(f.dependencyDepth).toBe('TOP_LEVEL_ONLY')
    })

    it('row 3: no stored row pre-fills from the parent, a NOT_FOUND parent from the built-in; description from the stored row only', () => {
        const f = sbomFormFromView({ stored: null, parent: resolved('ORGANIZATION', PARENT_PROFILE) }, 'PERSPECTIVE')
        expect(f.dependencyDepth).toBe('TOP_LEVEL_ONLY')
        expect(f.description).toBe('')
        expect(f.mode).toBeNull()
        const sources = f.sources
        expect(sources).not.toBe(PARENT_PROFILE.sources)
        expect(sbomFormFromView({ stored: null, parent: { status: 'NOT_FOUND', profile: null } }, 'COMPONENT'))
            .toEqual({ ...sbomBuiltInDefaults(), mode: 'OVERRIDE' })
    })

    it('row 4: FOLLOW sends exactly mode and perspective', () => {
        const form = { ...sbomBuiltInDefaults(), mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2' }
        expect(sbomProfileInput(form, 'COMPONENT')).toEqual({ mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p2' })
    })

    it('row 4: OVERRIDE at COMPONENT sends the mode, the eight fields with NONE and copied arrays; ORGANIZATION no mode', () => {
        const form = { ...sbomBuiltInDefaults(), mode: 'OVERRIDE', description: '  padded  ', vulnerabilityDocuments: 'X' }
        const sent = sbomProfileInput(form, 'COMPONENT')
        expect(sent.mode).toBe('OVERRIDE')
        for (const f of SBOM_PROFILE_FIELDS) expect(sent, f).toHaveProperty(f)
        expect(sent.vulnerabilityDocuments).toBe('NONE')
        expect(sent.sources).toEqual(form.sources)
        expect(sent.sources).not.toBe(form.sources)
        expect(sent.excludedCoverage).not.toBe(form.excludedCoverage)
        expect(sent.description).toBe('padded')
        const org = sbomProfileInput({ ...form, description: '   ' }, 'ORGANIZATION')
        expect(org).not.toHaveProperty('mode')
        expect(org.description).toBeNull()
        expect(Object.keys(org).sort()).toEqual([...SBOM_PROFILE_FIELDS, 'description'].sort())
    })
})

describe('the source and effective lines (row 5)', () => {
    const names = { p1: 'Payments' }

    it('RESOLVED by organization, perspective by name, component and DEFAULT', () => {
        expect(sbomSourceLine({ stored: null, parent: resolved('ORGANIZATION', PARENT_PROFILE) }, names, 'top'))
            .toBe('No override: pre-filled from organization, revision 2')
        expect(sbomSourceLine({ stored: null, parent: resolved('PERSPECTIVE', PARENT_PROFILE, 'p1') }, names, 'top'))
            .toBe('No override: pre-filled from perspective Payments, revision 2')
        expect(sbomSourceLine({ stored: null, parent: resolved('FOLLOWED_PERSPECTIVE', PARENT_PROFILE, 'p9') }, names, 'top'))
            .toBe('No override: pre-filled from perspective p9, revision 2')
        expect(sbomSourceLine({ stored: null, parent: resolved('DEFAULT', { ...PARENT_PROFILE, revision: 0 }) }, names, 'default'))
            .toBe('No override: pre-filled from built-in defaults')
        expect(sbomSourceLine({ stored: { name: 'top' } }, names, 'top')).toBe('Stored at this scope')
        expect(sbomEffectiveLine(resolved('ORGANIZATION', PARENT_PROFILE), names)).toBe('Effective here: organization, revision 2')
        expect(sbomEffectiveLine(resolved('PERSPECTIVE', PARENT_PROFILE, 'p1'), names))
            .toBe('Effective here: perspective Payments, revision 2')
        expect(sbomEffectiveLine(resolved('COMPONENT', { ...PARENT_PROFILE, revision: 0 }), names))
            .toBe('Effective here: component, revision 0')
        expect(sbomEffectiveLine(resolved('DEFAULT', PARENT_PROFILE), names)).toBe('Effective here: built-in defaults')
    })

    it('CONFLICT names both perspectives; NOT_FOUND says the publish is refused', () => {
        const conflict = { status: 'CONFLICT', source: null, sourceObject: null, profile: null, conflictingPerspectives: [P1, P2] }
        expect(sbomSourceLine({ stored: null, parent: conflict }, {}, 'top'))
            .toBe('Conflict: the perspectives Payments, Retail each define top; choose Override or Follow here')
        expect(sbomEffectiveLine(conflict))
            .toBe('Effective here: conflict between perspectives Payments, Retail; a publish naming this profile is refused')
        const notFound = { status: 'NOT_FOUND', profile: null, conflictingPerspectives: [] }
        expect(sbomSourceLine({ stored: null, parent: notFound }, {}, 'top'))
            .toBe('No definition of top above this scope: pre-filled from the built-in defaults')
        expect(sbomEffectiveLine(notFound)).toBe('Effective here: not found; a publish naming this profile is refused')
    })
})

const entry = (name: string, status = 'RESOLVED', changes: Record<string, any> = {}) => ({ name, resolution: {
    status, source: status === 'RESOLVED' ? 'ORGANIZATION' : null, sourceObject: null,
    profile: status === 'RESOLVED' ? { name, revision: 1 } : null, conflictingPerspectives: [], ...changes } })

describe('the catalog rows (row 6)', () => {
    it('default first, then by name; storedHere exactly for stored names; a stored name the catalog lacks stays', () => {
        const rows = sbomCatalogRows([entry('zeta'), entry('alpha'), entry('default', 'RESOLVED', { source: 'DEFAULT' })],
            [{ name: 'alpha', revision: 3 }, { name: 'orphan', revision: 0 }])
        expect(rows.map(r => r.name)).toEqual(['default', 'alpha', 'orphan', 'zeta'])
        expect(rows.filter(r => r.storedHere).map(r => r.name)).toEqual(['alpha', 'orphan'])
        expect(rows.find(r => r.name === 'default')!.sourceLabel).toBe('built-in defaults')
        expect(rows.find(r => r.name === 'orphan')!.revision).toBe(0)
    })
})

describe('the picker options (row 7)', () => {
    const catalog = [entry('default'), entry('top', 'CONFLICT', { conflictingPerspectives: [P1, P2] }), entry('gone', 'NOT_FOUND')]

    it('never disables an option, marks CONFLICT and not-found names, and appends stale selected names last', () => {
        const opts = sbomPickerOptions(catalog, ['stale', 'top'])
        expect(opts.map(o => o.value)).toEqual(['default', 'top', 'gone', 'stale'])
        for (const o of opts) expect((o as any).disabled, o.value).not.toBe(true)
        expect(opts[0].label).toBe('default (organization, r1)')
        expect(opts[1].label.endsWith('(conflict)')).toBe(true)
        expect(opts[1].title).toContain('Payments, Retail')
        expect(opts[1].title).toBe('Perspectives Payments, Retail each define top; override or follow it on the TEA tab, or drop it')
        expect(opts[2].label).toBe('gone (not found)')
        expect(opts[3]).toEqual({ label: 'stale (not found)', value: 'stale',
            title: 'No definition of stale resolves at this scope; drop it or define it' })
    })

    it('the lines under the picker are red exactly for a CONFLICT or a name that resolves nowhere', () => {
        const lines = sbomPickerLines(catalog, ['default', 'top', 'stale'])
        expect(lines.map(l => l.danger)).toEqual([false, true, true])
        expect(lines[0].text).toBe('default: Effective here: organization, revision 1')
        expect(lines[1].text).toContain('Payments, Retail')
        expect(lines[2].text).toBe('stale: Not found at this scope: drop it or define it')
    })

    it('multiple on Pro unless the host forces one, single on CE', () => {
        expect(sbomPickerMultiple('SAAS')).toBe(true)
        expect(sbomPickerMultiple('SAAS', true)).toBe(false)
        expect(sbomPickerMultiple('OSS')).toBe(false)
    })
})

describe('the delete confirmation (row 8, functional F-10)', () => {
    it('says default reverts to the parent or the built-in', () => {
        const c = sbomDeleteConfirm('default', 'ORGANIZATION', 'organization', 'SAAS')
        expect(c).toEqual({ title: 'Delete SBOM profile?', confirmButtonText: 'Delete',
            text: 'This scope then resolves default from its parent, or the built-in defaults when no parent defines it. '
                + 'Settings naming default keep publishing.' })
    })

    it('names both consumers on Pro and the TEA settings alone on CE', () => {
        expect(sbomDeleteConfirm('top', 'ORGANIZATION', 'organization', 'SAAS').text).toBe('TEA publishing settings or '
            + 'regulatory package settings naming top at or below this scope are refused the deletion by the server while the '
            + 'name would stop resolving for them; the server\'s answer names the settings to point at another profile first.')
        const ce = sbomDeleteConfirm('top', 'ORGANIZATION', 'organization', 'OSS').text
        expect(ce.startsWith('TEA publishing settings naming top at or below this scope')).toBe(true)
        expect(ce).not.toContain('regulatory package settings')
    })

    it('prefixes the scope at COMPONENT and PERSPECTIVE; a name with markup sits in text only, the title is the constant', () => {
        expect(sbomDeleteConfirm('top', 'COMPONENT', 'component', 'SAAS').text.startsWith('Remove the definition of top at this component. '))
            .toBe(true)
        expect(sbomDeleteConfirm('default', 'PERSPECTIVE', 'perspective', 'SAAS').text
            .startsWith('Remove the definition of default at this perspective. This scope then resolves default')).toBe(true)
        const marked = sbomDeleteConfirm('<b>x</b>', 'COMPONENT', 'product', 'SAAS')
        expect(marked.title).toBe('Delete SBOM profile?')
        expect(marked.confirmButtonText).toBe('Delete')
        expect(marked.text).toContain('Remove the definition of <b>x</b> at this product. ')
        expect(marked.text).toContain('naming <b>x</b> at or below')
    })
})
