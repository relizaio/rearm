// Named SBOM profiles (task TEA-30, the UI of TEA-27): the option lists and help texts of the SBOM
// profile editor, and its pure logic -- what the form starts from, what a save sends, the source and
// effective lines, the catalog rows, the picker options and the delete confirmation. The recipe
// option lists moved here from teaProfile.ts with their values and help texts unchanged. Kept out of
// the .vue files so the specs pin them.

import { isProEdition } from './editionCapabilities'
import { regulatoryPackagesAvailable } from './packageSettings'
import { teaSourceLabel } from './teaProfile'

export type SbomScope = 'ORGANIZATION' | 'PERSPECTIVE' | 'COMPONENT'

export interface Option { label: string, value: string }

/** A picker option: the catalog name, its label with the resolution, and the warning as title. */
export interface PickerOption extends Option { title?: string }

const opt = (value: string, label: string): Option => ({ label, value })

export const SBOM_MODE_OPTIONS: Option[] = [
    opt('OVERRIDE', 'Override: this component defines the name itself'),
    opt('FOLLOW_PERSPECTIVE', 'Follow one of its perspectives'),
]

export const SBOM_DEPENDENCY_DEPTH_OPTIONS: Option[] = [
    opt('FULL', 'Full dependency tree'),
    opt('TOP_LEVEL_ONLY', 'Top-level dependencies only'),
]

export const SBOM_OPTIONAL_DEPENDENCIES_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include optional (dev) dependencies'),
    opt('EXCLUDE', 'Exclude optional (dev) dependencies'),
]

export const SBOM_STRUCTURE_OPTIONS: Option[] = [
    opt('FLAT', 'Flat'),
    opt('HIERARCHICAL', 'Hierarchical'),
]

export const SBOM_SOURCES_OPTIONS: Option[] = [
    opt('DELIVERABLE', 'Deliverable SBOMs'),
    opt('RELEASE', 'Release SBOMs'),
    opt('SOURCE_CODE', 'Source code SBOMs'),
]

export const SBOM_EXCLUDED_COVERAGE_OPTIONS: Option[] = [
    opt('DEV', 'Development'),
    opt('TEST', 'Test'),
    opt('BUILD_TIME', 'Build time'),
]

export const SBOM_SUPPORT_METADATA_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include support metadata'),
    opt('EXCLUDE', 'Exclude support metadata'),
]

export const SBOM_INTERNAL_METADATA_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include internal metadata'),
    opt('EXCLUDE', 'Exclude internal metadata'),
]

export const SBOM_VULNERABILITY_DOCUMENTS_OPTIONS: Option[] = [
    opt('NONE', 'None (reserved)'),
]

/** The tooltips of the editor's fields. */
export const SBOM_FIELD_HELP: Record<string, string> = {
    name: 'Channels name profiles; a lower scope overrides a higher one by defining the same name. Letters, '
        + 'digits, dot, underscore and dash, 1 to 64 characters.',
    description: 'Shown in the catalog only; at most 1024 characters.',
    mode: 'Override defines the name at this component; Follow takes one perspective\'s definition.',
    dependencyDepth: 'Whether published SBOMs carry the whole dependency tree or only top-level dependencies.',
    optionalDependencies: 'Whether optional (development) dependencies stay in published SBOMs.',
    structure: 'The shape of the merged SBOM.',
    sources: 'Which SBOMs of a release are merged into what is published.',
    excludedCoverage: 'SBOMs of these coverage types are left out.',
    supportMetadata: 'Support facts woven into published SBOMs; needs the organization support injection.',
    internalMetadata: 'ReARM-internal metadata in published SBOMs.',
    vulnerabilityDocuments: 'Reserved for a later release.',
}

/** The recipe fields a save sends, in the order the editor shows them. */
export const SBOM_PROFILE_FIELDS = ['dependencyDepth', 'optionalDependencies', 'structure', 'sources',
    'excludedCoverage', 'supportMetadata', 'internalMetadata', 'vulnerabilityDocuments'] as const

/** The server's NAME_PATTERN (SbomProfileData). */
export const SBOM_PROFILE_NAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
/** The built-in name every scope resolves (SbomProfileData.DEFAULT_NAME). */
export const SBOM_DEFAULT_NAME = 'default'
export const SBOM_DESCRIPTION_MAX = 1024

export interface SbomProfileForm {
    mode: string | null
    followedPerspective: string | null
    description: string
    dependencyDepth: string
    optionalDependencies: string
    structure: string
    sources: string[]
    excludedCoverage: string[]
    supportMetadata: string
    internalMetadata: string
    vulnerabilityDocuments: string
}

/** A resolution of one name at a scope, as SbomProfileResolution answers it. */
export interface SbomResolution {
    status?: string | null
    source?: string | null
    sourceObject?: string | null
    profile?: any
    conflictingPerspectives?: { uuid: string, name?: string | null, type?: string | null }[] | null
}

/** The editor view, as SbomProfileEditorView answers it. */
export interface SbomEditorView {
    stored?: any
    effective?: SbomResolution | null
    parent?: SbomResolution | null
    supportInjection?: string | null
}

/** The built-in recipe: what `default` is before any scope defines it (the server's builtInDefault). */
export function sbomBuiltInDefaults (): SbomProfileForm {
    return {
        mode: null,
        followedPerspective: null,
        description: '',
        dependencyDepth: 'FULL',
        optionalDependencies: 'INCLUDE',
        structure: 'FLAT',
        sources: ['DELIVERABLE', 'RELEASE', 'SOURCE_CODE'],
        excludedCoverage: ['DEV', 'TEST'],
        supportMetadata: 'EXCLUDE',
        internalMetadata: 'EXCLUDE',
        vulnerabilityDocuments: 'NONE',
    }
}

/** The client copy of the server's name rule; the server validates again. */
export function sbomNameError (name: string | null | undefined): string | null {
    const n = name ?? ''
    if (!n.trim()) return 'A profile name is required'
    if (!SBOM_PROFILE_NAME_PATTERN.test(n)) {
        return 'Letters, digits, dot, underscore and dash only, 1 to 64 characters, starting with a letter or digit'
    }
    return null
}

function recipeOf (p: any): Pick<SbomProfileForm, typeof SBOM_PROFILE_FIELDS[number]> {
    const d: any = sbomBuiltInDefaults()
    const out: any = {}
    for (const f of SBOM_PROFILE_FIELDS) {
        const v = p?.[f]
        out[f] = Array.isArray(v) ? [...v] : (v ?? d[f])
    }
    return out
}

/**
 * What the form starts from: the stored row when it carries a recipe (not FOLLOW), else the
 * effective parent's profile (the pre-fill), else the built-in defaults. The description comes from
 * the stored row only: a parent's description is not inherited.
 */
export function sbomFormFromView (view: SbomEditorView | null | undefined, scope: SbomScope): SbomProfileForm {
    const stored = view?.stored
    const storedHasFields = stored && stored.mode !== 'FOLLOW_PERSPECTIVE'
    const base = storedHasFields ? stored : view?.parent?.profile
    return {
        mode: scope === 'COMPONENT' ? (stored?.mode ?? 'OVERRIDE') : null,
        followedPerspective: scope === 'COMPONENT' ? (stored?.followedPerspective ?? null) : null,
        description: stored?.description ?? '',
        ...recipeOf(base),
    }
}

/** The SbomProfileInput a save sends: a FOLLOW row sends its mode and perspective only. */
export function sbomProfileInput (form: SbomProfileForm, scope: SbomScope): Record<string, any> {
    if (scope === 'COMPONENT' && form.mode === 'FOLLOW_PERSPECTIVE') {
        return { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: form.followedPerspective }
    }
    const out: Record<string, any> = {}
    if (scope === 'COMPONENT') out.mode = 'OVERRIDE'
    for (const f of SBOM_PROFILE_FIELDS) {
        const v = (form as any)[f]
        out[f] = Array.isArray(v) ? [...v] : v
    }
    out.vulnerabilityDocuments = 'NONE'
    const description = (form.description ?? '').trim()
    out.description = description || null
    return out
}

/** How a resolution's source reads; the built-in defaults for DEFAULT and for none. */
export function sbomSourceLabel (source: string | null | undefined, sourceObject: string | null | undefined,
    names: Record<string, string> = {}): string {
    return teaSourceLabel(source, sourceObject, names)
}

/** "<source label>, revision <n>"; the built-in defaults carry no revision. */
function sourceWithRevision (r: SbomResolution, revision: number | null | undefined, names: Record<string, string>): string {
    const label = sbomSourceLabel(r.source, r.sourceObject, names)
    if (!r.source || r.source === 'DEFAULT' || revision == null) return label
    return label + ', revision ' + revision
}

/** The names of a CONFLICT's perspectives, joined. */
export function conflictNames (r: SbomResolution | null | undefined, names: Record<string, string> = {}): string {
    return (r?.conflictingPerspectives ?? []).map(p => p.name || names[p.uuid] || p.uuid).join(', ')
}

/** The editor's source line: where an unsaved form comes from. */
export function sbomSourceLine (view: SbomEditorView | null | undefined, names: Record<string, string> = {},
    name = SBOM_DEFAULT_NAME): string {
    if (view?.stored) return 'Stored at this scope'
    const parent = view?.parent
    if (!parent || parent.status === 'NOT_FOUND') {
        return 'No definition of ' + name + ' above this scope: pre-filled from the built-in defaults'
    }
    if (parent.status === 'CONFLICT') {
        return 'Conflict: the perspectives ' + conflictNames(parent, names) + ' each define ' + name
            + '; choose Override or Follow here'
    }
    return 'No override: pre-filled from ' + sourceWithRevision(parent, parent.profile?.revision, names)
}

/** The resolver's effective view of a name at a scope, as one line. */
export function sbomEffectiveLine (resolution: SbomResolution | null | undefined, names: Record<string, string> = {}): string {
    if (!resolution) return ''
    if (resolution.status === 'CONFLICT') {
        return 'Effective here: conflict between perspectives ' + conflictNames(resolution, names)
            + '; a publish naming this profile is refused'
    }
    if (resolution.status === 'NOT_FOUND') return 'Effective here: not found; a publish naming this profile is refused'
    return 'Effective here: ' + sourceWithRevision(resolution, resolution.profile?.revision, names)
}

export interface SbomCatalogRow {
    name: string
    status: string | null
    source: string | null
    sourceLabel: string
    revision: number | null
    storedHere: boolean
    profile: any
    resolution: SbomResolution | null
}

/**
 * One row per catalog entry, plus one per stored row whose name the catalog lacks (a correct server
 * never answers that; kept so a stored row is never hidden), `default` first, then by name.
 */
export function sbomCatalogRows (entries: any[] | null | undefined, storedRows: any[] | null | undefined,
    names: Record<string, string> = {}): SbomCatalogRow[] {
    const stored = new Map<string, any>()
    for (const r of storedRows ?? []) if (r?.name) stored.set(r.name, r)
    const rows: SbomCatalogRow[] = []
    const seen = new Set<string>()
    for (const e of entries ?? []) {
        if (!e?.name || seen.has(e.name)) continue
        seen.add(e.name)
        const r: SbomResolution = e.resolution ?? {}
        rows.push({
            name: e.name,
            status: r.status ?? null,
            source: r.source ?? null,
            sourceLabel: sbomSourceLabel(r.source, r.sourceObject, names),
            revision: r.profile?.revision ?? null,
            storedHere: stored.has(e.name),
            profile: r.profile ?? null,
            resolution: r,
        })
    }
    for (const [name, row] of stored) {
        if (seen.has(name)) continue
        rows.push({ name, status: null, source: null, sourceLabel: 'stored', revision: row.revision ?? null,
            storedHere: true, profile: row, resolution: null })
    }
    return rows.sort((a, b) => {
        if (a.name === SBOM_DEFAULT_NAME) return b.name === SBOM_DEFAULT_NAME ? 0 : -1
        if (b.name === SBOM_DEFAULT_NAME) return 1
        return a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    })
}

/** The warning a CONFLICT name carries in the picker (option title and the line under it). */
export function sbomConflictWarning (name: string, r: SbomResolution | null | undefined, names: Record<string, string> = {}): string {
    return 'Perspectives ' + conflictNames(r, names) + ' each define ' + name + '; override or follow it on the TEA tab, or drop it'
}

/** The warning a name that resolves nowhere carries in the picker. */
export function sbomNotFoundWarning (name: string): string {
    return 'No definition of ' + name + ' resolves at this scope; drop it or define it'
}

/**
 * The picker's options: every catalog name in the catalog's order, then every selected name the
 * catalog lacks, so a stale stored name stays visible and can be dropped. No option is ever
 * disabled: naive-ui gives a disabled selected option's tag no close button, so it could not be
 * dropped. A CONFLICT or not-found name is marked in its label and title instead.
 */
export function sbomPickerOptions (entries: any[] | null | undefined, selected: string[] | null | undefined,
    names: Record<string, string> = {}): PickerOption[] {
    const out: PickerOption[] = []
    const seen = new Set<string>()
    for (const e of entries ?? []) {
        if (!e?.name || seen.has(e.name)) continue
        seen.add(e.name)
        const r: SbomResolution = e.resolution ?? {}
        if (r.status === 'CONFLICT') {
            out.push({ label: e.name + ' (conflict)', value: e.name, title: sbomConflictWarning(e.name, r, names) })
        } else if (r.status === 'NOT_FOUND') {
            out.push({ label: e.name + ' (not found)', value: e.name, title: sbomNotFoundWarning(e.name) })
        } else {
            const rev = r.profile?.revision
            const label = sbomSourceLabel(r.source, r.sourceObject, names)
                + (r.source && r.source !== 'DEFAULT' && rev != null ? ', r' + rev : '')
            out.push({ label: e.name + ' (' + label + ')', value: e.name, title: sbomEffectiveLine(r, names) })
        }
    }
    for (const name of selected ?? []) {
        if (!name || seen.has(name)) continue
        seen.add(name)
        out.push({ label: name + ' (not found)', value: name, title: sbomNotFoundWarning(name) })
    }
    return out
}

/** One line per selected name: its resolution here, red for a CONFLICT or a name that resolves nowhere. */
export interface SbomPickerLine { name: string, text: string, danger: boolean }

export function sbomPickerLines (entries: any[] | null | undefined, selected: string[] | null | undefined,
    names: Record<string, string> = {}): SbomPickerLine[] {
    const byName = new Map<string, any>()
    for (const e of entries ?? []) if (e?.name) byName.set(e.name, e)
    return (selected ?? []).filter(Boolean).map(name => {
        const e = byName.get(name)
        if (!e) return { name, text: name + ': Not found at this scope: drop it or define it', danger: true }
        const r: SbomResolution = e.resolution ?? {}
        if (r.status === 'CONFLICT') {
            return { name, text: name + ': ' + sbomEffectiveLine(r, names) + '. ' + sbomConflictWarning(name, r, names), danger: true }
        }
        if (r.status === 'NOT_FOUND') {
            return { name, text: name + ': ' + sbomEffectiveLine(r, names) + '. ' + sbomNotFoundWarning(name), danger: true }
        }
        return { name, text: name + ': ' + sbomEffectiveLine(r, names), danger: false }
    })
}

/** Whether the picker takes several names: Pro, unless the host forces one (the package editor). */
export function sbomPickerMultiple (installationType: string | null | undefined, single = false): boolean {
    return !single && isProEdition(installationType)
}

/** A Swal confirmation: a constant title, the names in the text only. */
export interface SbomConfirm { title: string, text: string, confirmButtonText: string }

/**
 * The confirmation before a profile deletion. The title is a constant; the name goes into `text`
 * only (the dialog rule, design 3.8). The server's refusal, when a consumer still names the profile,
 * is shown verbatim by the caller.
 */
export function sbomDeleteConfirm (name: string, scope: SbomScope, scopeWord: string,
    installationType: string | null | undefined): SbomConfirm {
    let text = scope === 'ORGANIZATION' ? '' : 'Remove the definition of ' + name + ' at this ' + scopeWord + '. '
    if (name === SBOM_DEFAULT_NAME) {
        text += 'This scope then resolves default from its parent, or the built-in defaults when no parent defines it. '
            + 'Settings naming default keep publishing.'
    } else {
        const consumers = regulatoryPackagesAvailable(installationType)
            ? 'TEA publishing settings or regulatory package settings'
            : 'TEA publishing settings'
        text += consumers + ' naming ' + name + ' at or below this scope are refused the deletion by the server while '
            + 'the name would stop resolving for them; the server\'s answer names the settings to point at another '
            + 'profile first.'
    }
    return { title: 'Delete SBOM profile?', text, confirmButtonText: 'Delete' }
}

/** The word a scope reads as in the texts: product for a product-derived object. */
export function sbomScopeWord (scope: SbomScope, objectIsProduct = false): string {
    return objectIsProduct ? 'product' : scope.toLowerCase()
}

const FIELD_OPTIONS: Record<string, Option[]> = {
    dependencyDepth: SBOM_DEPENDENCY_DEPTH_OPTIONS,
    optionalDependencies: SBOM_OPTIONAL_DEPENDENCIES_OPTIONS,
    structure: SBOM_STRUCTURE_OPTIONS,
    sources: SBOM_SOURCES_OPTIONS,
    excludedCoverage: SBOM_EXCLUDED_COVERAGE_OPTIONS,
    supportMetadata: SBOM_SUPPORT_METADATA_OPTIONS,
    internalMetadata: SBOM_INTERNAL_METADATA_OPTIONS,
    vulnerabilityDocuments: SBOM_VULNERABILITY_DOCUMENTS_OPTIONS,
}

/** A recipe value as the catalog table shows it: through the option labels; an unknown value renders itself. */
export function sbomValueText (field: string, value: unknown): string {
    const options = FIELD_OPTIONS[field] ?? []
    const one = (v: unknown) => options.find(o => o.value === v)?.label ?? String(v ?? '')
    if (Array.isArray(value)) return value.map(one).join(', ')
    return value == null ? '' : one(value)
}
