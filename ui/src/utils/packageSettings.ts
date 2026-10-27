// Regulatory package settings (task TEA-30, the UI of TEA-33): the edition gate, the option lists
// and help texts of the package settings editor, and its pure logic -- what the form starts from,
// what a save sends, the client check before a save and the source and effective lines. The editor
// is generic over kinds: the parts, their requirement, defaults and generator presence come from the
// server's packageKinds answer, never from this file.

import { isProEdition } from './editionCapabilities'
import { TEA_INHERIT, teaSourceLabel } from './teaProfile'

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
    'SIGNED_PAYLOAD', 'PUBLIC_KEY', 'CERTIFICATE_X_509', 'CERTIFICATE_PGP', 'RISK_ASSESSMENT', 'AGENTIC_REPORT', 'OTHER']

export const PACKAGE_ARTIFACT_TYPE_OPTIONS: Option[] = ARTIFACT_TYPES.map(t => opt(t, t.replace(/_/g, ' ')))

export const PACKAGE_PART_LABELS: Record<string, string> = {
    AGGREGATE_SBOM: 'Aggregate SBOM',
    VDR: 'VDR',
    VEX: 'VEX',
    SUPPORT_ADDENDUM: 'Support addendum',
    DEVICE_SUPPORT_STATEMENT: 'Device support statement',
    NARRATIVE: 'Narrative',
    CLE: 'CLE',
    RAW_ARTIFACTS: 'Raw artifacts',
}

export const PACKAGE_KIND_LABELS: Record<string, string> = {
    FDA_PREMARKET: 'FDA premarket',
}

/** A kind's label; an unknown kind renders its value (the editor is generic over packageKinds). */
export function packageKindLabel (kind: string | null | undefined): string {
    return (kind && PACKAGE_KIND_LABELS[kind]) || kind || ''
}

/** A part's label; an unknown part renders its value. */
export function packagePartLabel (part: string | null | undefined): string {
    return (part && PACKAGE_PART_LABELS[part]) || part || ''
}

/** The help text of the Enabled switch, naming the kind. */
export function packageEnabledHelp (kind: string | null | undefined): string {
    return 'Off: releases of this scope do not offer ' + packageKindLabel(kind)
        + ' and no new submission can be made; existing submissions stay readable and can be withdrawn.'
}

/** The effective on/off of a kind at a scope, from the resolver's settings; off when nothing resolves. */
export function packageEnabledLine (resolution: any): string {
    return 'Enabled here: ' + (resolution?.settings?.enabled === true ? 'on' : 'off')
}

export const PACKAGE_FIELD_HELP: Record<string, string> = {
    kind: 'The regulatory package kind these settings tailor; each kind has its own parts.',
    sbomProfile: 'The SBOM profile the aggregate SBOM part is generated under; one name, resolved at this scope',
    selection: 'Whether the part is in the package; a REQUIRED part is always included.',
    suppressedFindings: 'Whether findings suppressed by an analysis stay in the document.',
    inTriageFindings: 'Whether findings still in triage stay in the VEX.',
    vexFormat: 'The format the VEX part is written in.',
    artifactTypes: 'Attached release artifacts of these types ride along as RAW_ARTIFACTS; at least one type when included',
    description: 'Shown beside the settings only; at most 1024 characters.',
    mode: 'Inherit stores nothing here; Override defines the settings at this scope; Follow takes one perspective\'s settings.',
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
    enabled: boolean
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
 * `enabled` comes the same way and is off by default (the built-in default).
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
        enabled: base?.enabled === true,
        sbomProfile: (storedHasFields ? stored?.sbomProfile : null) ?? view?.parent?.settings?.sbomProfile ?? 'default',
        description: stored?.description ?? '',
        parts,
    }
}

/** What an INHERIT form shows, read-only: the parent's settings (the form with no row stored). */
export function packageInheritedForm (view: any, scope: PackageScope): PackageSettingsForm {
    return packageFormFromView({ kind: view?.kind, parent: view?.parent, stored: null }, scope)
}

/** The PackageSettingsInput a save sends: a FOLLOW row sends its mode and perspective only (no `enabled`). */
export function packageSettingsInput (form: PackageSettingsForm, scope: PackageScope): Record<string, any> {
    if (form.mode === PACKAGE_INHERIT) throw new Error('INHERIT is not stored: inheriting again removes the row')
    if (scope === 'COMPONENT' && form.mode === 'FOLLOW_PERSPECTIVE') {
        return { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: form.followedPerspective }
    }
    const out: Record<string, any> = {}
    if (scope === 'COMPONENT') out.mode = 'OVERRIDE'
    out.enabled = form.enabled === true
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
        return 'RAW_ARTIFACTS needs at least one artifact type while included'
    }
    if (!(form.sbomProfile ?? '').trim()) return 'An SBOM profile is required'
    return null
}

function names (r: any, map: Record<string, string>): string {
    return (r?.conflictingPerspectives ?? []).map((p: any) => p.name || map[p.uuid] || p.uuid).join(', ')
}

function sourceWithRevision (r: any, map: Record<string, string>): string {
    const label = teaSourceLabel(r?.source, r?.sourceObject, map)
    const rev = r?.settings?.revision
    if (!r?.source || r.source === 'DEFAULT' || rev == null) return label
    return label + ', revision ' + rev
}

/** The editor's source line, the SBOM editor's text with "package settings" for the name; in INHERIT, where they come from. */
export function packageSourceLine (view: any, map: Record<string, string> = {}, mode: string | null = null): string {
    const parent = view?.parent
    if (mode === PACKAGE_INHERIT && parent?.status !== 'CONFLICT') {
        const from = (!parent || parent.status === 'NOT_FOUND') ? 'built-in defaults' : sourceWithRevision(parent, map)
        return 'Inherited from ' + from + (view?.stored ? ': Save removes the stored settings' : '')
    }
    if (view?.stored) return 'Stored at this scope'
    if (!parent || parent.status === 'NOT_FOUND') {
        return 'No package settings above this scope: pre-filled from the built-in defaults'
    }
    if (parent.status === 'CONFLICT') {
        return 'Conflict: the perspectives ' + names(parent, map) + ' each define package settings; choose Override or Follow here'
    }
    return 'No override: pre-filled from ' + sourceWithRevision(parent, map)
}

/** The resolver's effective view of the package settings at this scope, as one line. */
export function packageEffectiveLine (resolution: any, map: Record<string, string> = {}): string {
    if (!resolution) return ''
    if (resolution.status === 'CONFLICT') {
        return 'Effective here: conflict between perspectives ' + names(resolution, map) + '; a submission is refused'
    }
    if (resolution.status === 'NOT_FOUND') return 'Effective here: not found; a submission is refused'
    return 'Effective here: ' + sourceWithRevision(resolution, map)
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
