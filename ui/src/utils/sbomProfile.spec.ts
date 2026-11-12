// Named SBOM profiles (task TEA-30, design 4.1 rows 1 to 8): the option lists against the schema
// enums, the name rule, the form and the save, the source and effective lines, the catalog rows,
// the picker options and the delete confirmation.
import { describe, expect, it } from 'vitest'
import {
    SBOM_COMPONENT_SCOPE_OPTIONS, SBOM_DEPENDENCY_DEPTH_OPTIONS, SBOM_EXCLUDED_COVERAGE_OPTIONS,
    SBOM_EXCLUDED_SCOPE_OPTIONS, SBOM_FIELD_HELP, SBOM_INTERNAL_METADATA_OPTIONS, SBOM_LOCAL_PATHS_OPTIONS,
    SBOM_MODE_OPTIONS, SBOM_PERSONAL_DATA_OPTIONS, SBOM_PROFILE_FIELDS, SBOM_SOURCES_OPTIONS,
    SBOM_STRUCTURE_OPTIONS, SBOM_SUPPORT_METADATA_OPTIONS, SBOM_VULNERABILITY_DOCUMENTS_OPTIONS, sbomBuiltInDefaults,
    sbomCatalogRows, sbomDeleteConfirm, sbomEffectiveLine, sbomFormFromView, sbomNameError, sbomPickerLines,
    sbomPickerMultiple, sbomPickerOptions, sbomProfileInput, sbomProfileNamesAvailable, sbomResetAvailable,
    sbomResetLabel, sbomResetRecipe, sbomRowsForEdition, sbomSourceLabel, sbomSourceLine,
    sbomValueText, sbomPerspectivesDefiningName,
    conflictNames, sbomNotFoundWarning,
} from './sbomProfile'
import { UUID_RE } from './__fixtures__/fdaSweep'
import { ceSchema, enumValuesOf, proSchema } from './schemaDriftSupport'

const values = (opts: { value: string }[]) => opts.map(o => o.value)

describe('SBOM profile option lists (row 1)', () => {
    it('the built-in defaults equal the server built-in', () => {
        expect(sbomBuiltInDefaults()).toEqual({
            mode: null, followedPerspective: null, description: '',
            dependencyDepth: 'FULL', componentScope: 'ALL', structure: 'FLAT',
            sources: ['DELIVERABLE', 'RELEASE', 'SOURCE_CODE'], excludedCoverage: ['DEV', 'TEST'],
            supportMetadata: 'EXCLUDE', internalMetadata: 'EXCLUDE', excludedScope: 'EXCLUDE', personalData: 'EXCLUDE',
            localPaths: 'EXCLUDE', vulnerabilityDocuments: 'NONE',
        })
        expect(sbomBuiltInDefaults()).not.toHaveProperty('optionalDependencies')
    })

    it('the recipe fields are the eleven of FDAW-19 3.6, in the editor order', () => {
        expect([...SBOM_PROFILE_FIELDS]).toEqual(['dependencyDepth', 'componentScope', 'structure', 'sources',
            'excludedCoverage', 'supportMetadata', 'internalMetadata', 'excludedScope', 'personalData', 'localPaths',
            'vulnerabilityDocuments'])
    })

    it('every list is {label, value} pairs with a label', () => {
        for (const list of [SBOM_MODE_OPTIONS, SBOM_DEPENDENCY_DEPTH_OPTIONS, SBOM_COMPONENT_SCOPE_OPTIONS,
            SBOM_STRUCTURE_OPTIONS, SBOM_SOURCES_OPTIONS, SBOM_EXCLUDED_COVERAGE_OPTIONS, SBOM_SUPPORT_METADATA_OPTIONS,
            SBOM_INTERNAL_METADATA_OPTIONS, SBOM_EXCLUDED_SCOPE_OPTIONS, SBOM_PERSONAL_DATA_OPTIONS,
            SBOM_LOCAL_PATHS_OPTIONS, SBOM_VULNERABILITY_DOCUMENTS_OPTIONS]) {
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
        ['SbomComponentScope', SBOM_COMPONENT_SCOPE_OPTIONS],
        ['BomStructureType', SBOM_STRUCTURE_OPTIONS],
        ['SbomSourceKind', SBOM_SOURCES_OPTIONS],
        ['ArtifactCoverageType', SBOM_EXCLUDED_COVERAGE_OPTIONS],
        ['SbomSupportMetadata', SBOM_SUPPORT_METADATA_OPTIONS],
        ['SbomInternalMetadata', SBOM_INTERNAL_METADATA_OPTIONS],
        ['SbomExcludedScope', SBOM_EXCLUDED_SCOPE_OPTIONS],
        ['SbomPersonalData', SBOM_PERSONAL_DATA_OPTIONS],
        ['SbomLocalPaths', SBOM_LOCAL_PATHS_OPTIONS],
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
        expect(sbomValueText('componentScope', 'RUNTIME_ONLY')).toBe('Runtime only: leave out components with scope optional')
        expect(sbomValueText('excludedScope', 'INCLUDE')).toBe('Keep components with scope excluded')
        expect(sbomValueText('personalData', 'INCLUDE'))
            .toBe('Keep maintainer e-mails and handles: every reader of this profile\'s documents receives them')
        expect(sbomValueText('localPaths', 'INCLUDE')).toBe('Keep local build paths')
    })

    it('the personal data INCLUDE option says every reader receives the data (threat 1)', () => {
        expect(SBOM_PERSONAL_DATA_OPTIONS.find(o => o.value === 'INCLUDE')!.label)
            .toBe('Keep maintainer e-mails and handles: every reader of this profile\'s documents receives them')
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

    it('row 4: OVERRIDE at COMPONENT sends the mode, the eleven fields with NONE and copied arrays; ORGANIZATION no mode', () => {
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
            .toBe('Nothing saved for this component: pre-filled from organization, revision 2')
        expect(sbomSourceLine({ stored: null, parent: resolved('PERSPECTIVE', PARENT_PROFILE, 'p1') }, names, 'top'))
            .toBe('Nothing saved for this component: pre-filled from perspective Payments, revision 2')
        // FDAW-78 X27: a perspective with no name reads 'a perspective', never its id
        expect(sbomSourceLine({ stored: null, parent: resolved('FOLLOWED_PERSPECTIVE', PARENT_PROFILE, 'p9') }, names, 'top'))
            .toBe('Nothing saved for this component: pre-filled from a perspective, revision 2')
        expect(sbomSourceLine({ stored: null, parent: resolved('DEFAULT', { ...PARENT_PROFILE, revision: 0 }) }, names, 'default'))
            .toBe('Nothing saved for this component: pre-filled from built-in defaults')
        expect(sbomSourceLine({ stored: { name: 'top' } }, names, 'top')).toBe('Saved for this component')
        expect(sbomEffectiveLine(resolved('ORGANIZATION', PARENT_PROFILE), names)).toBe('In use here: organization, revision 2')
        expect(sbomEffectiveLine(resolved('PERSPECTIVE', PARENT_PROFILE, 'p1'), names))
            .toBe('In use here: perspective Payments, revision 2')
        expect(sbomEffectiveLine(resolved('COMPONENT', { ...PARENT_PROFILE, revision: 0 }), names))
            .toBe('In use here: component, revision 0')
        expect(sbomEffectiveLine(resolved('DEFAULT', PARENT_PROFILE), names)).toBe('In use here: built-in defaults')
    })

    it('CONFLICT names both perspectives; NOT_FOUND says the publish is refused', () => {
        const conflict = { status: 'CONFLICT', source: null, sourceObject: null, profile: null, conflictingPerspectives: [P1, P2] }
        expect(sbomSourceLine({ stored: null, parent: conflict }, {}, 'top'))
            .toBe('Conflict: the perspectives Payments, Retail each define top; choose Override or Follow here')
        expect(sbomEffectiveLine(conflict))
            .toBe('In use here: conflict between perspectives Payments, Retail; a publish naming this profile is refused')
        const notFound = { status: 'NOT_FOUND', profile: null, conflictingPerspectives: [] }
        expect(sbomSourceLine({ stored: null, parent: notFound }, {}, 'top'))
            .toBe('No definition of top above this component: pre-filled from the built-in defaults')
        expect(sbomEffectiveLine(notFound)).toBe('In use here: not found; a publish naming this profile is refused')
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
        expect(opts[0].label).toBe('default (organization, revision 1)')
        expect(opts[1].label.endsWith('(conflict)')).toBe(true)
        expect(opts[1].title).toContain('Payments, Retail')
        expect(opts[1].title).toBe('Perspectives Payments, Retail each define top; override or follow it on the TEA tab, or drop it')
        expect(opts[2].label).toBe('gone (not found)')
        expect(opts[3]).toEqual({ label: 'stale (not found)', value: 'stale',
            title: 'No definition of stale is in use here; drop it or define it' })
    })

    it('the lines under the picker are red exactly for a CONFLICT or a name that resolves nowhere', () => {
        const lines = sbomPickerLines(catalog, ['default', 'top', 'stale'])
        expect(lines.map(l => l.danger)).toEqual([false, true, true])
        expect(lines[0].text).toBe('default: In use here: organization, revision 1')
        expect(lines[1].text).toContain('Payments, Retail')
        expect(lines[2].text).toBe('stale: Not found here: drop it or define it')
    })

    it('multiple on Pro unless the host forces one, single on CE', () => {
        expect(sbomPickerMultiple('SAAS')).toBe(true)
        expect(sbomPickerMultiple('SAAS', true)).toBe(false)
        expect(sbomPickerMultiple('OSS')).toBe(false)
    })

    it('CE names no SBOM profile but default: the rows filter to it, Pro keeps every row', () => {
        const rows = [{ name: 'default' }, { name: 'top' }, { name: null }, { name: 'default' }]
        expect(sbomProfileNamesAvailable('OSS')).toBe(false)
        for (const t of ['SAAS', 'DEMO', 'MANAGED_SERVICE']) expect(sbomProfileNamesAvailable(t)).toBe(true)
        expect(sbomRowsForEdition(rows, 'OSS')).toEqual([{ name: 'default' }, { name: 'default' }])
        expect(sbomRowsForEdition(rows, 'SAAS')).toBe(rows)
        expect(sbomRowsForEdition(null, 'OSS')).toEqual([])
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

describe('the perspectives defining a name', () => {
    it('reads one defining perspective off a PERSPECTIVE parent, every one off a CONFLICT, none otherwise', () => {
        expect(sbomPerspectivesDefiningName({ parent: { status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: 'p1' } }))
            .toEqual([{ uuid: 'p1' }])
        expect(sbomPerspectivesDefiningName({ parent: { status: 'CONFLICT', source: 'PERSPECTIVE', sourceObject: null,
            conflictingPerspectives: [{ uuid: 'p1', name: 'Payments' }, { uuid: 'p2', name: 'Retail' }] } }).map(p => p.uuid))
            .toEqual(['p1', 'p2'])
        expect(sbomPerspectivesDefiningName({ parent: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null } })).toEqual([])
        expect(sbomPerspectivesDefiningName({ parent: { status: 'NOT_FOUND', source: null } })).toEqual([])
        expect(sbomPerspectivesDefiningName({ parent: null })).toEqual([])
        expect(sbomPerspectivesDefiningName(null)).toEqual([])
    })
})

// FDAW-46 (FDAW-19 C1c) row 21: the four knobs on the form and the save, the built-in label of a
// named built-in (the server's fda), and the reset helpers.
const SWITCHES = ['excludedScope', 'personalData', 'localPaths'] as const
const FDA = { ...sbomBuiltInDefaults(), name: 'fda', revision: 0, description: 'Built-in FDA premarket recipe',
    componentScope: 'RUNTIME_ONLY', excludedCoverage: ['DEV', 'TEST', 'BUILD_TIME'], supportMetadata: 'INCLUDE' }

describe('the four knobs on the form and the save (FDAW-46 row 21)', () => {
    it.each(SWITCHES)('a full form with %s INCLUDE sends it, the other two EXCLUDE, and no optionalDependencies', (s) => {
        const form = { ...sbomBuiltInDefaults(), componentScope: 'RUNTIME_ONLY', [s]: 'INCLUDE' }
        for (const scope of ['ORGANIZATION', 'COMPONENT'] as const) {
            const sent = sbomProfileInput({ ...form, mode: scope === 'COMPONENT' ? 'OVERRIDE' : null }, scope)
            expect(sent.componentScope, scope).toBe('RUNTIME_ONLY')
            expect(sent[s], scope).toBe('INCLUDE')
            for (const other of SWITCHES.filter(o => o !== s)) expect(sent[other], scope + ' ' + other).toBe('EXCLUDE')
            expect(sent, scope).not.toHaveProperty('optionalDependencies')
        }
    })

    it('the four option lists and help texts read exactly as FDAW-19 3.6, in order (mutation)', () => {
        const pairs = (l: { value: string, label: string }[]) => l.map(o => [o.value, o.label])
        expect(pairs(SBOM_COMPONENT_SCOPE_OPTIONS)).toEqual([['ALL', 'All components the generators list'],
            ['RUNTIME_ONLY', 'Runtime only: leave out components with scope optional']])
        expect(pairs(SBOM_EXCLUDED_SCOPE_OPTIONS)).toEqual([['EXCLUDE', 'Leave out components with scope excluded'],
            ['INCLUDE', 'Keep components with scope excluded']])
        expect(pairs(SBOM_PERSONAL_DATA_OPTIONS)).toEqual([['EXCLUDE', 'Remove maintainer e-mails and handles'],
            ['INCLUDE', 'Keep maintainer e-mails and handles: every reader of this profile\'s documents receives them']])
        expect(pairs(SBOM_LOCAL_PATHS_OPTIONS)).toEqual([['EXCLUDE', 'Remove local build paths'],
            ['INCLUDE', 'Keep local build paths']])
        expect(SBOM_FIELD_HELP.componentScope).toBe('Runtime only leaves out components the SBOM marks scope optional '
            + '(not needed at runtime) and counts them in the document.')
        expect(SBOM_FIELD_HELP.excludedScope).toBe('Whether components the generator marked scope excluded stay in published SBOMs.')
        expect(SBOM_FIELD_HELP.personalData).toBe('Whether maintainer e-mails and handles stay in published SBOMs; kept, '
            + 'every reader of this profile\'s documents receives them.')
        expect(SBOM_FIELD_HELP.localPaths).toBe('Whether local build paths stay in published SBOMs.')
    })

    it('a FOLLOW form sends none of the four', () => {
        const sent = sbomProfileInput({ ...sbomBuiltInDefaults(), mode: 'FOLLOW_PERSPECTIVE', followedPerspective: 'p1' },
            'COMPONENT')
        for (const f of ['componentScope', ...SWITCHES, 'optionalDependencies']) expect(sent, f).not.toHaveProperty(f)
    })

    it.each(SWITCHES)('a stored row with componentScope RUNTIME_ONLY and %s INCLUDE reads into the form', (s) => {
        const stored = { ...sbomBuiltInDefaults(), name: 'top', componentScope: 'RUNTIME_ONLY', excludedScope: 'EXCLUDE',
            personalData: 'EXCLUDE', localPaths: 'EXCLUDE', [s]: 'INCLUDE' }
        const f = sbomFormFromView({ stored, parent: resolved('DEFAULT', FDA) }, 'ORGANIZATION')
        expect(f.componentScope).toBe('RUNTIME_ONLY')
        expect((f as any)[s]).toBe('INCLUDE')
        for (const other of SWITCHES.filter(o => o !== s)) expect((f as any)[other], other).toBe('EXCLUDE')
    })
})

describe('the built-in label of a named built-in (FDAW-46 row 21)', () => {
    it('DEFAULT reads built-in for fda, built-in defaults for default and for no name', () => {
        expect(sbomSourceLabel('DEFAULT', null, {}, 'fda')).toBe('built-in')
        expect(sbomSourceLabel('DEFAULT', null, {}, 'default')).toBe('built-in defaults')
        expect(sbomSourceLabel('DEFAULT', null, {})).toBe('built-in defaults')
        expect(sbomSourceLabel('ORGANIZATION', null, {}, 'fda')).toBe('organization')
    })

    it('the catalog orders default, alpha, fda and labels fda built-in', () => {
        const rows = sbomCatalogRows([entry('fda', 'RESOLVED', { source: 'DEFAULT' }), entry('alpha'),
            entry('default', 'RESOLVED', { source: 'DEFAULT' })], [])
        expect(rows.map(r => r.name)).toEqual(['default', 'alpha', 'fda'])
        expect(rows.find(r => r.name === 'fda')!.sourceLabel).toBe('built-in')
        expect(rows.find(r => r.name === 'default')!.sourceLabel).toBe('built-in defaults')
    })

    it('the effective line reads built-in for fda only', () => {
        expect(sbomEffectiveLine(resolved('DEFAULT', FDA), {}, 'fda')).toBe('In use here: built-in')
        expect(sbomEffectiveLine(resolved('DEFAULT', FDA), {}, 'default')).toBe('In use here: built-in defaults')
        expect(sbomEffectiveLine(resolved('DEFAULT', FDA), {})).toBe('In use here: built-in defaults')
    })

    it('the picker labels, titles and lines', () => {
        const catalog = [entry('default', 'RESOLVED', { source: 'DEFAULT' }), entry('fda', 'RESOLVED', { source: 'DEFAULT' })]
        const opts = sbomPickerOptions(catalog, [])
        const fda = opts.find(o => o.value === 'fda')!
        const def = opts.find(o => o.value === 'default')!
        expect(fda.label).toBe('fda (built-in)')
        expect(fda.title).toBe('In use here: built-in')
        expect(def.title).toBe('In use here: built-in defaults')
        expect(sbomPickerLines(catalog, ['default', 'fda']).map(l => l.text))
            .toEqual(['default: In use here: built-in defaults', 'fda: In use here: built-in'])
    })
})

describe('the reset helpers (FDAW-46 row 21)', () => {
    const recipe = (p: any) => { const f: any = sbomFormFromView({ stored: p }, 'ORGANIZATION'); delete f.mode
        delete f.followedPerspective; delete f.description; return f }

    it('the reset is offered for default and for a name whose parent is a built-in', () => {
        expect(sbomResetAvailable(null, 'default')).toBe(true)
        expect(sbomResetAvailable({ parent: resolved('DEFAULT', FDA) }, 'fda')).toBe(true)
        expect(sbomResetAvailable({ parent: { status: 'NOT_FOUND', profile: null } }, 'top')).toBe(false)
        expect(sbomResetAvailable({ parent: resolved('ORGANIZATION', FDA) }, 'top')).toBe(false)
    })

    it('resets to the built-in parent profile, else the built-in defaults', () => {
        const r = sbomResetRecipe({ parent: resolved('DEFAULT', FDA) })
        expect(r.componentScope).toBe('RUNTIME_ONLY')
        expect(r.excludedCoverage).toEqual(['DEV', 'TEST', 'BUILD_TIME'])
        expect(r.supportMetadata).toBe('INCLUDE')
        const builtIn = recipe(sbomBuiltInDefaults())
        expect(sbomResetRecipe({ parent: resolved('ORGANIZATION', FDA) })).toEqual(builtIn)
        expect(sbomResetRecipe({ parent: { status: 'NOT_FOUND', profile: null } })).toEqual(builtIn)
        expect(sbomResetRecipe(null)).toEqual(builtIn)
    })

    it('says which built-in it resets to', () => {
        expect(sbomResetLabel('default')).toBe('Reset to built-in defaults')
        expect(sbomResetLabel('fda')).toBe('Reset to the built-in fda recipe')
    })
})

/** FDAW-78 row 14 (REQ-67): the SBOM editor's source line per scope word, and the picker's texts in words. */
describe('the picker and the source line in words (FDAW-78 row 14)', () => {
    const UUID = '3bef7cde-2edb-47cb-aa4e-94487c2cdced'
    const PROFILE = (revision: number) => ({ name: 'fda', revision })

    it('sbomSourceLine by scope word', () => {
        expect(sbomSourceLine({ stored: { name: 'fda' } }, {}, 'fda', 'organization')).toBe('Saved for this organization')
        expect(sbomSourceLine({ stored: null, parent: null }, {}, 'fda', 'product'))
            .toBe('No definition of fda above this product: pre-filled from the built-in defaults')
        expect(sbomSourceLine({ stored: null, parent: resolved('ORGANIZATION', PROFILE(3)) }, {}, 'fda', 'product'))
            .toBe('Nothing saved for this product: pre-filled from organization, revision 3')
    })

    it('sbomPickerOptions: revision in words, a perspective by name or as a perspective, never r<n> or a uuid (F-4)', () => {
        const e = (resolution: any) => ({ name: 'fda', resolution: { conflictingPerspectives: [], ...resolution } })
        const label = (resolution: any, names: Record<string, string> = {}) => sbomPickerOptions([e(resolution)], [], names)[0].label
        const labels = [
            label({ status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null, profile: PROFILE(2) }),
            label({ status: 'RESOLVED', source: 'DEFAULT', sourceObject: null, profile: PROFILE(0) }),
            label({ status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: 'p1', profile: PROFILE(1) }, { p1: 'Payments' }),
            label({ status: 'RESOLVED', source: 'PERSPECTIVE', sourceObject: UUID, profile: PROFILE(1) }),
        ]
        expect(labels).toEqual(['fda (organization, revision 2)', 'fda (built-in)', 'fda (perspective Payments, revision 1)',
            'fda (a perspective, revision 1)'])
        for (const l of labels) {
            expect(l).not.toMatch(/, r\d/)
            expect(l).not.toMatch(UUID_RE)
        }
        // control: the old label and the perspective id match the two expressions
        expect('fda (organization, r2)').toMatch(/, r\d/)
        expect(UUID).toMatch(UUID_RE)
        expect(conflictNames({ conflictingPerspectives: [{ uuid: UUID }] } as any)).toBe('a perspective')
    })

    it('the not-found texts and the composed lines over the row-7 fixture, whole (F-8)', () => {
        const catalog = [entry('default'), entry('top', 'CONFLICT', { conflictingPerspectives: [P1, P2] }), entry('gone', 'NOT_FOUND')]
        const selected = ['default', 'top', 'gone', 'stale']
        expect(sbomNotFoundWarning('stale')).toBe('No definition of stale is in use here; drop it or define it')
        const opts = sbomPickerOptions(catalog, selected)
        expect(opts).toHaveLength(4)
        expect(opts[0].title).toBe('In use here: organization, revision 1')
        expect(opts[2]).toEqual({ label: 'gone (not found)', value: 'gone', title: 'No definition of gone is in use here; drop it or define it' })
        expect(opts[3]).toEqual({ label: 'stale (not found)', value: 'stale', title: 'No definition of stale is in use here; drop it or define it' })
        const lines = sbomPickerLines(catalog, selected)
        expect(lines).toHaveLength(4)
        expect(lines.map(l => l.danger)).toEqual([false, true, true, true])
        expect(lines[0].text).toBe('default: In use here: organization, revision 1')
        expect(lines[1].text).toBe('top: In use here: conflict between perspectives Payments, Retail; a publish naming this profile is refused.'
            + ' Perspectives Payments, Retail each define top; override or follow it on the TEA tab, or drop it')
        expect(lines[2].text).toBe('gone: In use here: not found; a publish naming this profile is refused.'
            + ' No definition of gone is in use here; drop it or define it')
        expect(lines[3].text).toBe('stale: Not found here: drop it or define it')
        const texts = [...opts.flatMap(o => [o.label, o.title ?? '']), ...lines.map(l => l.text)]
        expect(texts).toHaveLength(12)
        for (const t of texts) {
            expect(t).not.toMatch(/scope/i)
            expect(t).not.toMatch(/Effective here/)
        }
        // control: the old literals match the two expressions
        expect('No definition of stale resolves at this scope; drop it or define it').toMatch(/scope/i)
        expect('stale: Not found at this scope: drop it or define it').toMatch(/scope/i)
        expect('default: Effective here: organization, revision 1').toMatch(/Effective here/)
    })
})
