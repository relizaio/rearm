// TEA profiles (task TEA-2): the option lists the profile editor renders, and the pure logic of the
// editor -- what the form starts from, what a save sends, and the copy of the source line, the
// public banner and the confirmation before a profile goes PUBLIC (on the server's exposure answer
// since task TEA-9), plus how the org table names a row. Kept out of the .vue files so the specs
// pin them.

export type TeaScope = 'ORGANIZATION' | 'PERSPECTIVE' | 'COMPONENT'

/**
 * Whether this edition serves TEA profiles. Every edition does: the CE backend serves TEA profiles,
 * SBOM profiles, EXTERNAL keys and the TEA publication reads since the CE sync of SCORE-72 (Pro main
 * 3befcf24a), with the single built-in `default` SBOM profile on CE, so the TEA tabs, the page banner
 * and PUBLISH_EXTERNALLY show on CE too. The parameter is kept so the call sites and the specs keep
 * their shape; teaProfileSchemaDrift.spec.ts ties this to the CE schema. TEA output events stay
 * Pro-only (teaOutputEvents.ts).
 */
export function teaProfilesAvailable (installationType: string | undefined | null): boolean {
    return true
}

/** The one SBOM profile name CE serves: its backend stores every profile under it, whatever name is sent. */
export const CE_SBOM_PROFILE = 'default'

/** The permission functions that exist only where the backend serves TEA (task TEA-3); both editions do since SCORE-72. */
export const TEA_PERMISSION_FUNCTIONS: string[] = ['PUBLISH_EXTERNALLY']

/**
 * The permission functions the editors may offer on this edition: every one of `functions`, less the
 * TEA functions where the backend serves no TEA. Since SCORE-72 both editions serve TEA and declare
 * PUBLISH_EXTERNALLY, so this keeps every function; the filter stays for the shape of the call sites.
 */
export function editionPermissionFunctions (functions: string[], installationType: string | undefined | null): string[] {
    return teaProfilesAvailable(installationType) ? functions : functions.filter(f => !TEA_PERMISSION_FUNCTIONS.includes(f))
}

export interface Option { label: string, value: string }

const opt = (value: string, label: string): Option => ({ label, value })

/**
 * The editor-only mode of a scope with no stored row: it resolves through its parents. Never sent:
 * going back to it removes the row (teaProfileInput refuses it).
 */
export const TEA_INHERIT = 'INHERIT'

/** The component modes, Inherit first: no row, an own profile, or one followed perspective. */
export const TEA_MODE_OPTIONS: Option[] = [
    opt(TEA_INHERIT, 'Inherit: use the perspective or organization profile'),
    opt('OVERRIDE', 'Override: this component carries its own profile'),
    opt('FOLLOW_PERSPECTIVE', 'Follow one of its perspectives'),
]

/** The perspective modes: a perspective inherits from the organization or carries its own; it follows nothing. */
export const TEA_PERSPECTIVE_MODE_OPTIONS: Option[] = [
    opt(TEA_INHERIT, 'Inherit: use the organization profile'),
    opt('OVERRIDE', 'Override: this perspective carries its own profile'),
]

/**
 * The mode radio of a scope: none at the organization (its only parent is the built-in defaults,
 * its Reset to defaults stays); a perspective's two; a component's three, where CE (no Follow, no
 * perspectives) keeps Inherit from the organization and Override.
 */
export function teaModeOptions (scope: TeaScope, installationType?: string | null): Option[] {
    if (scope === 'ORGANIZATION') return []
    if (scope === 'PERSPECTIVE') return TEA_PERSPECTIVE_MODE_OPTIONS
    if (installationType === 'OSS') return [TEA_PERSPECTIVE_MODE_OPTIONS[0], TEA_MODE_OPTIONS[1]]
    return TEA_MODE_OPTIONS
}

/** The mode a form opens in: INHERIT with no stored row, the stored mode otherwise; none at the organization. */
export function teaInitialMode (stored: any, scope: TeaScope): string | null {
    if (scope === 'ORGANIZATION') return null
    if (!stored) return TEA_INHERIT
    return scope === 'COMPONENT' ? (stored.mode ?? 'OVERRIDE') : 'OVERRIDE'
}

export const TEA_PUBLISHING_OPTIONS: Option[] = [
    opt('ENABLED', 'Enabled'),
    opt('DISABLED', 'Disabled: conceal everything resolving here, block new publishes'),
]

export const TEA_VISIBILITY_OPTIONS: Option[] = [
    opt('PRIVATE', 'Private: TEA readers need a key'),
    opt('PUBLIC', 'Public: anyone who knows the TEA URL'),
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
    rawArtifacts: 'Which existing release artifacts ride along with a publication.',
    productComponents: 'For a product: whether its component releases are published with it.',
    minimumLifecycle: 'The lifecycle a release must have reached before it can be published.',
    tei: 'Stamps a TEI onto the release identifiers at publish.',
    profiles: 'The SBOM profiles this scope publishes, one aggregated SBOM each, in this order; every name must '
        + 'resolve at this scope while publishing is ENABLED.',
}

/**
 * The profile fields a save sends, in the order the editor shows them. The SBOM recipe fields moved
 * to named SBOM profiles (TEA-27; sbomProfile.ts): a TEA row names the profiles it publishes instead.
 */
export const TEA_PROFILE_FIELDS = ['publishing', 'visibility', 'rawArtifacts', 'productComponents',
    'minimumLifecycle', 'tei', 'teiDomain', 'profiles'] as const

export interface TeaProfileForm {
    mode: string | null
    followedPerspective: string | null
    publishing: string
    visibility: string
    rawArtifacts: string
    productComponents: string
    minimumLifecycle: string
    tei: string
    teiDomain: string | null
    profiles: string[]
}

/** The built-in defaults: the profile every organization has before it saves one. */
export function teaBuiltInDefaults (): TeaProfileForm {
    return {
        mode: null,
        followedPerspective: null,
        publishing: 'DISABLED',
        visibility: 'PRIVATE',
        rawArtifacts: 'NONE',
        productComponents: 'PUBLISH_WITH_PRODUCT',
        minimumLifecycle: 'ASSEMBLED',
        tei: 'DISABLED',
        teiDomain: null,
        profiles: ['default'],
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
        mode: teaInitialMode(stored, scope),
        followedPerspective: scope === 'COMPONENT' ? (stored?.followedPerspective ?? null) : null,
        ...fieldsOf(base),
    }
}

/** What an INHERIT form shows, read-only: the parent's effective profile, else the built-in defaults. */
export function teaInheritedForm (view: any): TeaProfileForm {
    return { mode: TEA_INHERIT, followedPerspective: null, ...fieldsOf(view?.parent?.profile) }
}

/**
 * The TeaProfileInput a save sends: a FOLLOW row sends its mode and perspective only. `profiles` is
 * sent as a copy in the picker's order; on CE (OSS) the first name only, the CE rule of exactly one.
 */
export function teaProfileInput (form: TeaProfileForm, scope: TeaScope, installationType?: string | null): Record<string, any> {
    if (form.mode === TEA_INHERIT) throw new Error('INHERIT is not stored: inheriting again removes the row')
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
    // CE stores every TEA profile with the one `default` SBOM profile, whatever the form holds.
    out.profiles = installationType === 'OSS' ? [CE_SBOM_PROFILE] : [...(form.profiles ?? [])]
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

/** Where an INHERIT form's values come from: the parent, or nothing under a perspective conflict. */
export function teaInheritedLine (view: any, names: Record<string, string> = {}): string {
    const parent = view?.parent
    if (parent?.status === 'CONFLICT') return 'Cannot inherit: its perspectives each carry a TEA profile'
    const from = (!parent || !parent.profile) ? 'built-in defaults' : teaSourceLabel(parent.source, parent.sourceObject, names)
    return 'Inherited from ' + from + (view?.stored ? ': Save removes the stored override' : '')
}

/** The editor's source line; in INHERIT, where the inherited values come from. */
export function teaSourceLine (view: any, names: Record<string, string> = {}, mode: string | null = null): string {
    if (mode === TEA_INHERIT) return teaInheritedLine(view, names)
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

/**
 * The server's answer to "what does this save or removal do to the effective visibility here"
 * (teaProfileExposureChange, task TEA-9). The editor confirms on it rather than on the form, so
 * the UI and the server's organization-admin gate cannot disagree.
 */
export interface TeaExposureChange {
    before: 'PUBLIC' | 'PRIVATE' | 'UNRESOLVED'
    after: 'PUBLIC' | 'PRIVATE' | 'UNRESOLVED'
    path: 'NONE' | 'ROW_PUBLIC' | 'FOLLOW_RESOLVES_PUBLIC' | 'REMOVAL_RESOLVES_PUBLIC' | 'MEMBERSHIP_RESOLVES_PUBLIC'
    afterSource?: string | null
    afterSourceObject?: string | null
    publishedReleases: number
    /** The other components the write makes PUBLIC (a perspective-row removal, TEA-9 round 2). */
    widened?: TeaWidenedComponent[] | null
}

/** A component, other than the assessed object, that the write makes PUBLIC. */
export interface TeaWidenedComponent {
    component: string
    name: string
    afterSource?: string | null
    afterSourceObject?: string | null
}

/** A Swal confirmation: what the editor shows before sending the save or the removal. */
export interface TeaConfirm { title: string, text: string, confirmButtonText: string }

const PUBLIC_CONFIRM_TITLE = 'Make this TEA profile public?'

/** Whether the operation makes the object itself PUBLIC where it was not. */
function teaObjectWidens (exposure: TeaExposureChange | null | undefined): boolean {
    return exposure?.after === 'PUBLIC' && exposure?.before !== 'PUBLIC'
}

/**
 * Whether the operation makes the object, or any other component, PUBLIC where it was not: what
 * needs the red confirmation. A missing `widened` (an older backend) counts as empty.
 */
export function teaNeedsPublicConfirm (exposure: TeaExposureChange | null | undefined): boolean {
    return teaObjectWidens(exposure) || (exposure?.widened?.length ?? 0) > 0
}

/**
 * The confirmation before a save, or null when nothing becomes PUBLIC. A FOLLOW names the followed
 * perspective (from the form) and the profile it resolves to; a PUBLIC row carries the count only.
 */
export function teaConfirmBeforeSave (exposure: TeaExposureChange | null | undefined, scopeWord: string,
    names: Record<string, string> = {}, followedPerspective: string | null = null): TeaConfirm | null {
    if (!exposure || !teaNeedsPublicConfirm(exposure)) return null
    let path = ''
    if (exposure.path === 'FOLLOW_RESOLVES_PUBLIC') {
        const followed = (followedPerspective && names[followedPerspective]) || followedPerspective || ''
        path = 'Following perspective ' + followed + ' resolves this ' + scopeWord + ' to the PUBLIC profile of '
            + teaSourceLabel(exposure.afterSource, exposure.afterSourceObject, names) + '. '
    }
    return { title: PUBLIC_CONFIRM_TITLE, text: path + teaPublicConfirmText(exposure.publishedReleases),
        confirmButtonText: 'Make it public' }
}

/** How many names a confirmation lists before it says "and <N> more". */
export const LISTED_NAMES = 5

/**
 * The confirmation before a removal: the plain one, or the PUBLIC one when the parent it falls
 * back to is PUBLIC or, for a perspective row, when members of the perspective then resolve to a
 * PUBLIC profile (named, up to five).
 */
export function teaConfirmBeforeRemoval (exposure: TeaExposureChange | null | undefined, scopeWord: string,
    names: Record<string, string> = {}): TeaConfirm {
    if (!exposure || !teaNeedsPublicConfirm(exposure)) {
        return { title: 'Remove this TEA profile override?',
            text: 'The ' + scopeWord + ' then resolves its TEA profile from its parent again.', confirmButtonText: 'Remove' }
    }
    const widened = exposure.widened ?? []
    const objectWidens = teaObjectWidens(exposure)
    let text = ''
    if (widened.length) {
        const listed = widened.slice(0, LISTED_NAMES).map(w => w.name).join(', ')
        const more = widened.length > LISTED_NAMES ? ', and ' + (widened.length - LISTED_NAMES) + ' more' : ''
        text += widened.length + ' component(s) of this perspective then resolve to a PUBLIC profile: ' + listed + more + '. '
    }
    if (objectWidens) {
        text += 'Removing the override lets this ' + scopeWord + ' resolve to the PUBLIC profile of '
            + teaSourceLabel(exposure.afterSource, exposure.afterSourceObject, names) + '. '
    }
    return { title: PUBLIC_CONFIRM_TITLE, text: text + teaPublicConfirmText(exposure.publishedReleases),
        confirmButtonText: objectWidens ? 'Remove and make it public' : 'Remove and make them public' }
}

/** The confirmation before a stored row goes back to INHERIT; the editor then removes the row. */
export function teaInheritConfirm (scope: TeaScope, scopeWord: string): { title: string, content: string } {
    const parent = scope === 'PERSPECTIVE' ? 'organization' : 'perspective or organization'
    return { title: 'Inherit again?',
        content: 'Remove this ' + scopeWord + "'s override and inherit the " + parent + ' profile again?' }
}

/** A perspective as the membership helpers read it: the org list and a component's perspectiveDetails. */
export interface TeaPerspectiveRef { uuid: string, name?: string | null }

/** What a membership write changes, by perspective name, and the names map its confirmation reads. */
export interface TeaMembershipDelta { added: string[], removed: string[], names: Record<string, string> }

/**
 * The perspectives a membership write adds and removes, by name: `next` is exactly the list the
 * write sends, `current` what the component holds today (its perspectiveDetails; null or missing
 * when the caller does not know, so nothing reads as removed), `perspectives` the organization's
 * perspective list the page holds. A uuid no list names reads as itself.
 */
export function teaMembershipDelta (next: string[], current: TeaPerspectiveRef[] | null | undefined,
    perspectives: TeaPerspectiveRef[] | null | undefined): TeaMembershipDelta {
    const names: Record<string, string> = {}
    for (const p of current ?? []) if (p?.uuid && p.name) names[p.uuid] = p.name
    for (const p of perspectives ?? []) if (p?.uuid && p.name) names[p.uuid] = p.name
    const held = (current ?? []).map(p => p.uuid)
    const nameOf = (u: string) => names[u] || u
    return {
        added: next.filter(u => !held.includes(u)).map(nameOf),
        removed: held.filter(u => !next.includes(u)).map(nameOf),
        names,
    }
}

/**
 * The confirmation before a membership write (setPerspectivesOnComponent) makes the component
 * PUBLIC, or null when it does not. `added` and `removed` are perspective names (teaMembershipDelta);
 * `scopeWord` says "product" where the caller edits a product.
 */
export function teaConfirmBeforeMembership (exposure: TeaExposureChange | null | undefined, added: string[],
    removed: string[], names: Record<string, string> = {}, scopeWord = 'component'): TeaConfirm | null {
    if (!exposure || !teaNeedsPublicConfirm(exposure)) return null
    const changes = [
        ...(added.length ? ['adding ' + added.join(', ')] : []),
        ...(removed.length ? ['removing ' + removed.join(', ')] : []),
    ]
    const what = changes.length ? ' (' + changes.join('; ') + ')' : ''
    return { title: 'Make this ' + scopeWord + ' public?',
        text: 'Changing this ' + scopeWord + "'s perspectives" + what + ' resolves it to the PUBLIC profile of '
            + teaSourceLabel(exposure.afterSource, exposure.afterSourceObject, names) + '. '
            + teaPublicConfirmText(exposure.publishedReleases),
        confirmButtonText: 'Make it public' }
}

/** What the org TEA table knows of an object: its name and, for a component or product, its status and type. */
export interface TeaNamedObject { name: string, status?: string | null, type?: string | null }

/**
 * How the org TEA table names the object of a row: by name, tagged archived when it is. The uuid
 * shows only for an object in no list that could not be read either.
 */
export function teaRowObject (row: { object?: string | null }, objects: Record<string, TeaNamedObject>):
    { name: string, archived: boolean } {
    const o = row.object ? objects[row.object] : undefined
    if (!o) return { name: row.object ?? '', archived: false }
    return { name: o.name || row.object || '', archived: o.status === 'ARCHIVED' }
}

/**
 * Where the org TEA table links a row's object: a component (or product) opens its settings on the
 * TEA tab, a perspective the organization's Perspectives tab, where its TEA profile is edited. A row
 * on no object (the organization) links nowhere.
 */
export function teaRowLink (row: { scope?: string | null, object?: string | null }, orgUuid: string,
    objectType?: string | null): string | null {
    if (!row.object) return null
    if (row.scope === 'COMPONENT') {
        const base = objectType === 'PRODUCT' ? '/productsOfOrg/' : '/componentsOfOrg/'
        return base + orgUuid + '/' + row.object + '?componentSettingsView=true&componentSettingsTab=tea'
    }
    if (row.scope === 'PERSPECTIVE') return '/orgSettings/' + orgUuid + '?tab=perspectives'
    return null
}
