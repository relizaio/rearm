// Regulatory package settings (task TEA-30, the UI of TEA-33): the edition gate, the option lists
// and help texts of the package settings editor, and its pure logic -- what the form starts from,
// what a save sends, the client check before a save and the source and effective lines. The editor
// is generic over kinds: the parts, their requirement, defaults and generator presence come from the
// server's packageKinds answer, never from this file.

import { isProEdition } from './editionCapabilities'
import { TEA_INHERIT, teaInheritConflictHint } from './teaProfile'
import { ARTIFACT_TYPE_LABELS, humanizeEnum, labelOrHumanized, PACKAGE_KIND_LABELS } from './fdaLabels'

/**
 * Whether this edition serves regulatory package settings and submissions. The Pro backend does;
 * the CE backend has neither (no CE backend sync on the 2026-10 TEA work). A separate gate from
 * teaProfilesAvailable: a CE sync may bring one without the other. teaProfileSchemaDrift.spec.ts
 * ties it to the CE schema.
 */
export function regulatoryPackagesAvailable (installationType: string | undefined | null): boolean {
    return isProEdition(installationType)
}

export interface Option { label: string, value: string }

const opt = (value: string, label: string): Option => ({ label, value })

export const PACKAGE_SELECTION_OPTIONS: Option[] = [
    opt('INCLUDE', 'Included'),
    opt('EXCLUDE', 'Excluded'),
]

export const PACKAGE_SUPPRESSED_FINDINGS_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include suppressed findings'),
    opt('EXCLUDE', 'Exclude suppressed findings'),
]

export const PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include findings in triage'),
    opt('EXCLUDE', 'Exclude findings in triage'),
]

export const PACKAGE_VEX_FORMAT_OPTIONS: Option[] = [
    opt('CYCLONEDX_VEX', 'CycloneDX VEX'),
    opt('OPENVEX', 'OpenVEX'),
]

/** Every ArtifactType value of the schema (both schemas agree; the drift spec pins it). */
export const ARTIFACT_TYPES = ['BOM', 'ATTESTATION', 'VDR', 'BOV', 'VEX', 'USER_DOCUMENT', 'DEVELOPMENT_DOCUMENT',
    'PROJECT_DOCUMENT', 'MARKETING_DOCUMENT', 'TEST_REPORT', 'CODE_SCANNING_RESULT', 'SARIF', 'BUILD_META',
    'CERTIFICATION', 'FORMULATION', 'LICENSE', 'RELEASE_NOTES', 'SECURITY_TXT', 'THREAT_MODEL', 'SIGNATURE',
    'SIGNED_PAYLOAD', 'PUBLIC_KEY', 'CERTIFICATE_X_509', 'CERTIFICATE_PGP', 'RISK_ASSESSMENT', 'AGENTIC_REPORT', 'REGULATORY_DOCUMENT', 'OTHER']

export const PACKAGE_ARTIFACT_TYPE_OPTIONS: Option[] = ARTIFACT_TYPES.map(t => opt(t, labelOrHumanized(ARTIFACT_TYPE_LABELS, t)))

export const PACKAGE_PART_LABELS: Record<string, string> = {
    AGGREGATE_SBOM: 'Aggregate SBOM',
    VDR: 'VDR',
    VEX: 'VEX',
    SUPPORT_ADDENDUM: 'Support addendum',
    DEVICE_SUPPORT_STATEMENT: 'Device support statement',
    NARRATIVE: 'Narrative',
    CLE: 'CLE',
    RAW_ARTIFACTS: 'Raw artifacts',
    CHECK_REPORT: 'Check report',
}

/** Defined in fdaLabels.ts (the release History parses with it) and read here under its old name. */
export { PACKAGE_KIND_LABELS }

/** A kind's label; an unknown kind renders humanized (FDAW-78 3.1; the editor is generic over packageKinds). */
export function packageKindLabel (kind: string | null | undefined): string {
    return (kind && PACKAGE_KIND_LABELS[kind]) || humanizeEnum(kind)
}

/** A part's label; an unknown part renders humanized. */
export function packagePartLabel (part: string | null | undefined): string {
    return (part && PACKAGE_PART_LABELS[part]) || humanizeEnum(part)
}

/** FDAW-18: the switch of a product's FDA premarket row is question 2 of its Medical device tab. */
export const PACKAGE_COMPONENT_QUESTION_HINT = 'On the Medical device tab this is question 2'

/** The label of the switch (FDAW-78 3.5, C3): the organization's row is a default, every other row `Enabled`. */
export function packageEnabledLabel (scope?: PackageScope | null): string {
    return scope === 'ORGANIZATION' ? 'On by default' : 'Enabled'
}

/**
 * The help text of the switch, naming the kind and describing the value the switch shows (FDAW-78 3.5, C3):
 * at the organization it says what the default does and that a product switched on for itself keeps
 * working; elsewhere what releases of this `scopeWord` offer. At a COMPONENT scope the FDA premarket switch
 * also names the Medical device tab question it answers (FDAW-18).
 */
export function packageEnabledHelp (kind: string | null | undefined, enabled: boolean | null | undefined,
    scope?: PackageScope | null, scopeWord?: string | null): string {
    const k = packageKindLabel(kind)
    let line: string
    if (scope === 'ORGANIZATION') {
        line = enabled === true
            ? 'On: every product and component without its own setting offers ' + k + ' and can be submitted.'
            : 'Off: ' + k + ' is not offered by default. A product offers it once it is switched on for that product'
                + ' (the product\'s Settings > Medical device, question 2); products already switched on there keep'
                + ' working. Existing submissions stay readable and can be cancelled.'
    } else {
        const w = scopeWord || 'component'
        line = enabled === true
            ? 'On: releases of this ' + w + ' offer ' + k + ' and can be submitted.'
            : 'Off: releases of this ' + w + ' do not offer ' + k
                + ' and no new submission can be made; existing submissions stay readable and can be cancelled.'
    }
    return scope === 'COMPONENT' && kind === 'FDA_PREMARKET' ? line + ' ' + PACKAGE_COMPONENT_QUESTION_HINT + '.' : line
}

/**
 * The switch of a settings row (FDAW-18): `state`, ENABLED or DISABLED; a row read without `state` but with
 * the deprecated `enabled` alias true reads ENABLED; anything else, a FOLLOW row included, DISABLED.
 */
export function packageStateOf (settings: any): 'ENABLED' | 'DISABLED' {
    if (settings?.state === 'ENABLED') return 'ENABLED'
    if (settings?.state == null && settings?.enabled === true) return 'ENABLED'
    return 'DISABLED'
}

/** The effective on/off of a kind at a scope, from the resolver's settings; off when nothing resolves. */
export function packageEnabledLine (resolution: any): string {
    return 'In use here: ' + (packageStateOf(resolution?.settings) === 'ENABLED' ? 'switched on' : 'switched off')
}

export const PACKAGE_FIELD_HELP: Record<string, string> = {
    kind: 'The regulatory package kind these settings tailor; each kind has its own parts.',
    sbomProfile: 'The SBOM profile the aggregate SBOM part is generated under; one name, as it is in use here',
    selection: 'Whether the part is in the package; a required part is always included.',
    suppressedFindings: 'Whether findings suppressed by an analysis stay in the document.',
    inTriageFindings: 'Whether findings still in triage stay in the VEX.',
    vexFormat: 'The format the VEX part is written in.',
    artifactTypes: 'Attached release artifacts of these types are added to the package as raw artifacts; at least one type when included',
    description: 'Shown beside the settings only; at most 1024 characters.',
    mode: 'Inherit saves nothing here; Override defines the settings here; Follow takes one perspective\'s settings.',
}

/** The editor-only mode of a scope with no stored row (as TEA_INHERIT): never sent, going back removes the row. */
export const PACKAGE_INHERIT = TEA_INHERIT

/** The component modes, Inherit first. */
export const PACKAGE_MODE_OPTIONS: Option[] = [
    opt(PACKAGE_INHERIT, 'Inherit: use the perspective or organization settings'),
    opt('OVERRIDE', 'Override: this component defines its own package settings'),
    opt('FOLLOW_PERSPECTIVE', 'Follow one of its perspectives'),
]

/** The perspective modes: inherit from the organization or define its own; a perspective follows nothing. */
export const PACKAGE_PERSPECTIVE_MODE_OPTIONS: Option[] = [
    opt(PACKAGE_INHERIT, 'Inherit: use the organization settings'),
    opt('OVERRIDE', 'Override: this perspective defines its own package settings'),
]

/** The mode radio of a scope, as teaModeOptions: none at the organization, two at a perspective, three at a component. */
export function packageModeOptions (scope: PackageScope, installationType?: string | null): Option[] {
    if (scope === 'ORGANIZATION') return []
    if (scope === 'PERSPECTIVE') return PACKAGE_PERSPECTIVE_MODE_OPTIONS
    if (installationType === 'OSS') return [PACKAGE_PERSPECTIVE_MODE_OPTIONS[0], PACKAGE_MODE_OPTIONS[1]]
    return PACKAGE_MODE_OPTIONS
}

/** Which option beyond `selection` applies to which part (PackagePartOptions.applicableTo, TEA-33 3.5.1). */
export const PACKAGE_PART_FIELDS: Record<string, string[]> = {
    VDR: ['suppressedFindings'],
    VEX: ['suppressedFindings', 'inTriageFindings', 'vexFormat'],
    RAW_ARTIFACTS: ['artifactTypes'],
}

/** The option fields a part shows and sends: `selection` for every part, then the applicable ones. */
export function packagePartFields (part: string): string[] {
    return ['selection', ...(PACKAGE_PART_FIELDS[part] ?? [])]
}

export type PackageScope = 'ORGANIZATION' | 'PERSPECTIVE' | 'COMPONENT'

export interface PackagePartForm {
    part: string
    requirement: string
    defaultSelection: string
    generatorPresent: boolean
    selection: string
    suppressedFindings: string
    inTriageFindings: string
    vexFormat: string
    artifactTypes: string[]
}

export interface PackageSettingsForm {
    mode: string | null
    followedPerspective: string | null
    /** The switch, as the server stores it (FDAW-18): ENABLED or DISABLED. */
    state: 'ENABLED' | 'DISABLED'
    sbomProfile: string
    description: string
    parts: PackagePartForm[]
}

function partOptions (settings: any, part: string): any {
    return (settings?.parts ?? []).find((p: any) => p?.part === part)?.options ?? null
}

/**
 * What the form starts from. The parts come from the kind's descriptor in its order; each part's
 * options from the stored row when it carries fields (not FOLLOW), else the parent's settings, else
 * nothing, with nulls filled as the server fills them. A REQUIRED part is INCLUDE whatever a row says.
 * `state` comes the same way and is DISABLED by default (the built-in default).
 */
export function packageFormFromView (view: any, scope: PackageScope): PackageSettingsForm {
    const stored = view?.stored
    const storedHasFields = stored && stored.mode !== 'FOLLOW_PERSPECTIVE'
    const base = storedHasFields ? stored : view?.parent?.settings
    const parts: PackagePartForm[] = (view?.kind?.parts ?? []).map((kp: any) => {
        const o = partOptions(base, kp.part) ?? {}
        const required = kp.requirement === 'REQUIRED'
        return {
            part: kp.part,
            requirement: kp.requirement,
            defaultSelection: kp.defaultSelection,
            generatorPresent: kp.generatorPresent !== false,
            selection: required ? 'INCLUDE' : (o.selection ?? kp.defaultSelection ?? 'EXCLUDE'),
            suppressedFindings: o.suppressedFindings ?? 'EXCLUDE',
            inTriageFindings: o.inTriageFindings ?? 'EXCLUDE',
            vexFormat: o.vexFormat ?? 'CYCLONEDX_VEX',
            artifactTypes: Array.isArray(o.artifactTypes) ? [...o.artifactTypes] : [],
        }
    })
    const mode = scope === 'ORGANIZATION' ? null
        : !stored ? PACKAGE_INHERIT
            : scope === 'COMPONENT' ? (stored.mode ?? 'OVERRIDE') : 'OVERRIDE'
    return {
        mode,
        followedPerspective: scope === 'COMPONENT' ? (stored?.followedPerspective ?? null) : null,
        state: packageStateOf(base),
        sbomProfile: (storedHasFields ? stored?.sbomProfile : null) ?? view?.parent?.settings?.sbomProfile ?? 'default',
        description: stored?.description ?? '',
        parts,
    }
}

/** What an INHERIT form shows, read-only: the parent's settings (the form with no row stored). */
export function packageInheritedForm (view: any, scope: PackageScope): PackageSettingsForm {
    return packageFormFromView({ kind: view?.kind, parent: view?.parent, stored: null }, scope)
}

/** The PackageSettingsInput a save sends: a FOLLOW row sends its mode and perspective only (no `state`); never the `enabled` alias. */
export function packageSettingsInput (form: PackageSettingsForm, scope: PackageScope): Record<string, any> {
    if (form.mode === PACKAGE_INHERIT) throw new Error('INHERIT is not stored: inheriting again removes the row')
    if (scope === 'COMPONENT' && form.mode === 'FOLLOW_PERSPECTIVE') {
        return { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: form.followedPerspective }
    }
    const out: Record<string, any> = {}
    if (scope === 'COMPONENT') out.mode = 'OVERRIDE'
    out.state = form.state === 'ENABLED' ? 'ENABLED' : 'DISABLED'
    out.sbomProfile = form.sbomProfile
    const description = (form.description ?? '').trim()
    out.description = description || null
    out.parts = form.parts.map(p => {
        const entry: Record<string, any> = { part: p.part }
        for (const f of packagePartFields(p.part)) {
            if (f === 'selection') entry.selection = p.requirement === 'REQUIRED' ? 'INCLUDE' : p.selection
            else if (f === 'artifactTypes') entry.artifactTypes = [...new Set(p.artifactTypes ?? [])]
            else entry[f] = (p as any)[f]
        }
        return entry
    })
    return out
}

/** The client copy of the server's SETTINGS_INVALID rules; null when the form may be sent. */
export function packageFormError (form: PackageSettingsForm): string | null {
    if (form.mode === 'FOLLOW_PERSPECTIVE' || form.mode === PACKAGE_INHERIT) return null
    const raw = form.parts.find(p => p.part === 'RAW_ARTIFACTS')
    if (raw && raw.selection === 'INCLUDE' && !(raw.artifactTypes ?? []).length) {
        return 'Raw artifacts needs at least one artifact type while included'
    }
    if (!(form.sbomProfile ?? '').trim()) return 'An SBOM profile is required'
    return null
}

function names (r: any, map: Record<string, string>): string {
    return (r?.conflictingPerspectives ?? []).map((p: any) => p.name || map[p.uuid] || 'a perspective').join(', ')
}

const STORED_SOURCES = ['ORGANIZATION', 'COMPONENT', 'PERSPECTIVE', 'FOLLOWED_PERSPECTIVE']

/**
 * Where package settings come from, in words (FDAW-78 3.5, L6): `organization settings`, `this product's own
 * settings` (`typeWord` product or component), `settings of perspective <name>` (`settings of a perspective`
 * when the map has no name: never the uuid), and `built-in defaults` for DEFAULT, none or any other value.
 * The TEA editor's teaSourceLabel is TEA's and stays.
 */
export function packageSourceLabel (r: any, map: Record<string, string> = {}, typeWord?: string | null): string {
    switch (r?.source) {
    case 'ORGANIZATION': return 'organization settings'
    case 'COMPONENT': return 'this ' + (typeWord || 'component') + '\'s own settings'
    case 'PERSPECTIVE':
    case 'FOLLOWED_PERSPECTIVE': {
        const name = r?.sourceObject ? map[r.sourceObject] : null
        return name ? 'settings of perspective ' + name : 'settings of a perspective'
    }
    default: return 'built-in defaults'
    }
}

function typeWordOf (scopeWord?: string | null): string {
    return scopeWord === 'product' ? 'product' : 'component'
}

function sourceWithRevision (r: any, map: Record<string, string>, typeWord?: string | null): string {
    const label = packageSourceLabel(r, map, typeWord)
    const rev = r?.settings?.revision
    if (!STORED_SOURCES.includes(r?.source) || rev == null) return label
    return label + ', revision ' + rev
}

/** The INHERIT hint of the TEA editor, worded for a kind's package settings. */
export function packageInheritConflictHint (scope: PackageScope, mode: string | null | undefined, perspectiveCount: number,
    view: any, kind: string | null | undefined): string | null {
    return teaInheritConflictHint(scope, mode, perspectiveCount, view, packageKindLabel(kind) + ' settings')
}

/**
 * The editor's source line (FDAW-78 3.5, L6), worded for the `scopeWord` (organization, perspective, product
 * or component); in INHERIT, where the settings come from.
 */
export function packageSourceLine (view: any, map: Record<string, string> = {}, mode: string | null = null,
    scopeWord: string = 'component'): string {
    const parent = view?.parent
    const typeWord = typeWordOf(scopeWord)
    if (mode === PACKAGE_INHERIT && parent?.status !== 'CONFLICT') {
        const from = (!parent || parent.status === 'NOT_FOUND') ? 'built-in defaults' : sourceWithRevision(parent, map, typeWord)
        return 'Inherited from ' + from + (view?.stored ? ': Save removes the settings saved here' : '')
    }
    if (view?.stored) return 'Saved for this ' + scopeWord
    if (!parent || parent.status === 'NOT_FOUND') {
        return 'Nothing saved for this ' + scopeWord + ' or above it: pre-filled from the built-in defaults'
    }
    if (parent.status === 'CONFLICT') {
        return 'Conflict: the perspectives ' + names(parent, map) + ' each define package settings; choose Override or Follow here'
    }
    return 'Nothing saved for this ' + scopeWord + ': pre-filled from ' + sourceWithRevision(parent, map, typeWord)
}

/** The resolver's view of the package settings in use here, as one line; `scopeWord` words a COMPONENT source. */
export function packageEffectiveLine (resolution: any, map: Record<string, string> = {}, scopeWord: string = 'component'): string {
    if (!resolution) return ''
    if (resolution.status === 'CONFLICT') {
        return 'In use here: conflict between perspectives ' + names(resolution, map) + '; a submission is refused'
    }
    if (resolution.status === 'NOT_FOUND') return 'In use here: not found; a submission is refused'
    return 'In use here: ' + sourceWithRevision(resolution, map, typeWordOf(scopeWord))
}

/** The confirmation before a stored row goes back to INHERIT; the editor then removes the row. */
export function packageInheritConfirm (scope: PackageScope, scopeWord: string): { title: string, content: string } {
    const parent = scope === 'PERSPECTIVE' ? 'organization' : 'perspective or organization'
    return { title: 'Inherit again?',
        content: 'Remove this ' + scopeWord + "'s override and inherit the " + parent + ' settings again?' }
}

/** The confirmation before a package settings removal; the kind label and scope word go into text only. */
export function packageRemoveConfirm (kind: string, scopeWord: string): { title: string, text: string, confirmButtonText: string } {
    return {
        title: 'Remove package settings?',
        text: 'Remove the package settings of ' + packageKindLabel(kind) + ' at this ' + scopeWord
            + '? It then resolves them from its parent or the built-in defaults.',
        confirmButtonText: 'Remove',
    }
}
