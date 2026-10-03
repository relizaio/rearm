// TEA profiles (task TEA-2): the option lists the profile editor renders, and the pure logic of the
// editor -- what the form starts from, what a save sends, and the copy of the source line, the
// public banner and the confirmation before a profile goes PUBLIC. Kept out of the .vue files so
// the specs pin them.

import { isProEdition } from './editionCapabilities'

export type TeaScope = 'ORGANIZATION' | 'PERSPECTIVE' | 'COMPONENT'

/**
 * Whether this edition serves TEA profiles. The Pro backend does; the CE backend has no TEA profile
 * fields yet (the 2026-10 TEA work ships no CE backend sync), so a CE UI hides the TEA tabs and the
 * page banner instead of failing their reads. teaProfileSchemaDrift.spec.ts ties this to the CE
 * schema and fails once that schema gains the fields: lift the gate then.
 */
export function teaProfilesAvailable (installationType: string | undefined | null): boolean {
    return isProEdition(installationType)
}

export interface Option { label: string, value: string }

const opt = (value: string, label: string): Option => ({ label, value })

export const TEA_MODE_OPTIONS: Option[] = [
    opt('OVERRIDE', 'Override: this component carries its own profile'),
    opt('FOLLOW_PERSPECTIVE', 'Follow one of its perspectives'),
]

export const TEA_PUBLISHING_OPTIONS: Option[] = [
    opt('ENABLED', 'Enabled'),
    opt('DISABLED', 'Disabled: conceal everything resolving here, block new publishes'),
]

export const TEA_VISIBILITY_OPTIONS: Option[] = [
    opt('PRIVATE', 'Private: TEA readers need a key'),
    opt('PUBLIC', 'Public: anyone who knows the TEA URL'),
]

export const TEA_DEPENDENCY_DEPTH_OPTIONS: Option[] = [
    opt('FULL', 'Full dependency tree'),
    opt('TOP_LEVEL_ONLY', 'Top-level dependencies only'),
]

export const TEA_OPTIONAL_DEPENDENCIES_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include optional (dev) dependencies'),
    opt('EXCLUDE', 'Exclude optional (dev) dependencies'),
]

export const TEA_STRUCTURE_OPTIONS: Option[] = [
    opt('FLAT', 'Flat'),
    opt('HIERARCHICAL', 'Hierarchical'),
]

export const TEA_SOURCES_OPTIONS: Option[] = [
    opt('DELIVERABLE', 'Deliverable SBOMs'),
    opt('RELEASE', 'Release SBOMs'),
    opt('SOURCE_CODE', 'Source code SBOMs'),
]

export const TEA_EXCLUDED_COVERAGE_OPTIONS: Option[] = [
    opt('DEV', 'Development'),
    opt('TEST', 'Test'),
    opt('BUILD_TIME', 'Build time'),
]

export const TEA_SUPPORT_METADATA_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include support metadata'),
    opt('EXCLUDE', 'Exclude support metadata'),
]

export const TEA_INTERNAL_METADATA_OPTIONS: Option[] = [
    opt('INCLUDE', 'Include internal metadata'),
    opt('EXCLUDE', 'Exclude internal metadata'),
]

export const TEA_RAW_ARTIFACTS_OPTIONS: Option[] = [
    opt('NONE', 'None'),
    opt('NON_BOM', 'Non-BOM artifacts'),
    opt('ALL', 'All artifacts'),
]

export const TEA_PRODUCT_COMPONENTS_OPTIONS: Option[] = [
    opt('PUBLISH_WITH_PRODUCT', 'Publish component releases with the product'),
    opt('PRODUCT_ONLY', 'Publish the product only'),
]

// The server accepts only these three: DRAFT and below are too early, and the post-shipment
// lifecycles rank with GENERAL_AVAILABILITY as thresholds.
export const TEA_MINIMUM_LIFECYCLE_OPTIONS: Option[] = [
    opt('ASSEMBLED', 'Assembled'),
    opt('READY_TO_SHIP', 'Ready to Ship'),
    opt('GENERAL_AVAILABILITY', 'Shipped (General Availability)'),
]

export const TEA_TEI_OPTIONS: Option[] = [
    opt('DISABLED', 'Disabled'),
    opt('UUID', 'UUID: tei://<domain>/uuid/<TEA release uuid>'),
]

export const TEA_FIELD_HELP: Record<string, string> = {
    publishing: 'DISABLED conceals every release resolving to this profile and blocks new publishes.',
    visibility: 'Resolved at request time: switching it changes who can read what is already published.',
    dependencyDepth: 'Whether published SBOMs carry the whole dependency tree or only top-level dependencies.',
    optionalDependencies: 'Whether optional (development) dependencies stay in published SBOMs.',
    structure: 'The shape of the merged SBOM.',
    sources: 'Which SBOMs of a release are merged into what is published.',
    excludedCoverage: 'SBOMs of these coverage types are left out.',
    supportMetadata: 'Support facts woven into published SBOMs; needs the organization support injection.',
    internalMetadata: 'ReARM-internal metadata in published SBOMs.',
    rawArtifacts: 'Which existing release artifacts ride along with a publication.',
    productComponents: 'For a product: whether its component releases are published with it.',
    minimumLifecycle: 'The lifecycle a release must have reached before it can be published.',
    tei: 'Stamps a TEI onto the release identifiers at publish.',
    vulnerabilityDocuments: 'Reserved for a later release.',
}

/** The profile fields a save sends, in the order the editor shows them. */
export const TEA_PROFILE_FIELDS = ['publishing', 'visibility', 'dependencyDepth', 'optionalDependencies', 'structure',
    'sources', 'excludedCoverage', 'supportMetadata', 'internalMetadata', 'rawArtifacts', 'productComponents',
    'minimumLifecycle', 'tei', 'teiDomain', 'vulnerabilityDocuments'] as const

export interface TeaProfileForm {
    mode: string | null
    followedPerspective: string | null
    publishing: string
    visibility: string
    dependencyDepth: string
    optionalDependencies: string
    structure: string
    sources: string[]
    excludedCoverage: string[]
    supportMetadata: string
    internalMetadata: string
    rawArtifacts: string
    productComponents: string
    minimumLifecycle: string
    tei: string
    teiDomain: string | null
    vulnerabilityDocuments: string
}

/** The built-in defaults: the profile every organization has before it saves one. */
export function teaBuiltInDefaults (): TeaProfileForm {
    return {
        mode: null,
        followedPerspective: null,
        publishing: 'DISABLED',
        visibility: 'PRIVATE',
        dependencyDepth: 'FULL',
        optionalDependencies: 'INCLUDE',
        structure: 'FLAT',
        sources: ['DELIVERABLE', 'RELEASE', 'SOURCE_CODE'],
        excludedCoverage: ['DEV', 'TEST'],
        supportMetadata: 'EXCLUDE',
        internalMetadata: 'EXCLUDE',
        rawArtifacts: 'NONE',
        productComponents: 'PUBLISH_WITH_PRODUCT',
        minimumLifecycle: 'ASSEMBLED',
        tei: 'DISABLED',
        teiDomain: null,
        vulnerabilityDocuments: 'NONE',
    }
}

function fieldsOf (p: any): Omit<TeaProfileForm, 'mode' | 'followedPerspective'> {
    const d = teaBuiltInDefaults()
    const out: any = {}
    for (const f of TEA_PROFILE_FIELDS) {
        const v = p?.[f]
        out[f] = Array.isArray(v) ? [...v] : (v ?? (d as any)[f])
    }
    return out
}

/**
 * What the form starts from: the stored row when it carries a profile, else the effective parent
 * (the pre-fill), else the built-in defaults. A FOLLOW row stores no profile field, so its fields
 * are pre-filled from the parent and switching it to OVERRIDE shows them ready to edit.
 */
export function teaFormFromView (view: any, scope: TeaScope): TeaProfileForm {
    const stored = view?.stored
    const storedHasProfile = stored && stored.mode !== 'FOLLOW_PERSPECTIVE'
    const base = storedHasProfile ? stored : view?.parent?.profile
    return {
        mode: scope === 'COMPONENT' ? (stored?.mode ?? 'OVERRIDE') : null,
        followedPerspective: scope === 'COMPONENT' ? (stored?.followedPerspective ?? null) : null,
        ...fieldsOf(base),
    }
}

/** The TeaProfileInput a save sends: a FOLLOW row sends its mode and perspective only. */
export function teaProfileInput (form: TeaProfileForm, scope: TeaScope): Record<string, any> {
    if (scope === 'COMPONENT' && form.mode === 'FOLLOW_PERSPECTIVE') {
        return { mode: 'FOLLOW_PERSPECTIVE', followedPerspective: form.followedPerspective }
    }
    const out: Record<string, any> = {}
    if (scope === 'COMPONENT') out.mode = 'OVERRIDE'
    for (const f of TEA_PROFILE_FIELDS) {
        const v = (form as any)[f]
        out[f] = Array.isArray(v) ? [...v] : v
    }
    out.teiDomain = form.tei === 'UUID' ? (form.teiDomain || null) : null
    return out
}

/** How a resolution's source reads: the organization, a perspective by name, or the defaults. */
export function teaSourceLabel (source: string | null | undefined, sourceObject: string | null | undefined,
    names: Record<string, string> = {}): string {
    switch (source) {
    case 'ORGANIZATION': return 'organization'
    case 'PERSPECTIVE':
    case 'FOLLOWED_PERSPECTIVE': return 'perspective ' + ((sourceObject && names[sourceObject]) || sourceObject || '')
    case 'COMPONENT': return 'component'
    default: return 'built-in defaults'
    }
}

/** The editor's source line. */
export function teaSourceLine (view: any, names: Record<string, string> = {}): string {
    if (view?.stored) return 'Stored override'
    const parent = view?.parent
    if (!parent || parent.status === 'CONFLICT' || !parent.profile) return 'No override: pre-filled from built-in defaults'
    return 'No override: pre-filled from ' + teaSourceLabel(parent.source, parent.sourceObject, names)
}

/**
 * The persistent red banner, or null when nothing here is public. A stored row with its own fields decides by its
 * own visibility; a FOLLOW_PERSPECTIVE row stores no fields, so like no row at all it is decided by the effective
 * profile it resolves to.
 */
export function teaPublicBanner (view: any, names: Record<string, string> = {}): string | null {
    const stored = view?.stored
    if (stored && stored.mode !== 'FOLLOW_PERSPECTIVE') {
        return stored.visibility === 'PUBLIC'
            ? 'This TEA profile is PUBLIC: releases resolving to it are readable without authentication.'
            : null
    }
    const eff = view?.effective
    if (eff?.profile?.visibility === 'PUBLIC') {
        return 'This TEA profile is PUBLIC, inherited from ' + teaSourceLabel(eff.source, eff.sourceObject, names)
            + ': releases resolving to it are readable without authentication.'
    }
    return null
}

/** The confirmation before a save makes a profile PUBLIC. */
export function teaPublicConfirmText (publishedReleases: number): string {
    if (!publishedReleases) {
        return 'No release is published under this profile yet; every future publication under it will be public.'
    }
    return publishedReleases + ' published release(s) resolving to this profile become readable by anyone who knows '
        + 'the TEA URL as soon as you save; visibility is live.'
}

/** Whether saving this form needs the PUBLIC confirmation first. */
export function teaNeedsPublicConfirm (form: TeaProfileForm, scope: TeaScope, view: any): boolean {
    if (scope === 'COMPONENT' && form.mode === 'FOLLOW_PERSPECTIVE') return false
    return form.visibility === 'PUBLIC' && view?.stored?.visibility !== 'PUBLIC'
}
