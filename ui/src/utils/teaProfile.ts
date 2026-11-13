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

/** How the organization's TEA API is served (task TPF-144): the schema's enum TeaServing, in its order. */
export const TEA_SERVING_MODES = ['NONE', 'LEGACY_UUID', 'DOMAIN'] as const
export type TeaServing = typeof TEA_SERVING_MODES[number]

/** Whether the instance hosts the organization's /.well-known/tea on its domain: the schema's enum TeaWellKnownHosting. */
export const TEA_WELL_KNOWN_HOSTINGS = ['NONE', 'DOMAIN'] as const
export type TeaWellKnownHosting = typeof TEA_WELL_KNOWN_HOSTINGS[number]

/** The label of each serving mode, keyed in the order of TEA_SERVING_MODES. */
export const TEA_SERVING_LABELS: Record<TeaServing, string> = {
    NONE: 'Not served',
    LEGACY_UUID: 'Legacy path with the organization TEA id',
    DOMAIN: 'On the assigned domain',
}

/** What each serving mode does, for the help next to its option, keyed in the order of TEA_SERVING_MODES. */
export const TEA_SERVING_DESCRIPTIONS: Record<TeaServing, string> = {
    NONE: "The organization's TEA API answers nothing: no TEA path of this instance serves it, whatever its TEA profiles publish.",
    LEGACY_UUID: "Served on this instance's host at /tea/<organization TEA id>/v1.0.0/. The TEA id is part of every URL, so no domain is needed; it is not served on a custom domain.",
    DOMAIN: 'Served at https://<assigned domain>/tea/v1.0.0/, without the TEA id in the URL. Needs a domain a global administrator assigned to this organization; only in this mode can the instance also host /.well-known/tea on that domain.',
}

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
    rawArtifacts: 'Which existing release artifacts ride along with a publication. A VDR snapshot stored by a release action never does, whatever is chosen here.',
    productComponents: 'For a product: whether its component releases are published with it.',
    minimumLifecycle: 'The lifecycle a release must have reached before it can be published.',
    tei: 'Stamps a TEI onto the release identifiers at publish.',
    profiles: 'The SBOM profiles this scope publishes, one aggregated SBOM each, in this order; every name must '
        + 'resolve at this scope while publishing is ENABLED.',
    autoPublish: 'When on, a release is published on TEA when it reaches Shipped and published again each time it '
        + 'changes after that. When off, publishing is left to the Publish button and to approval-policy actions. '
        + 'Turning it on needs the Publish Externally (TEA) permission.',
    packageProfile: "A device with a regulatory package also publishes under its package's SBOM profile, so "
        + 'customers can pin the serial the package carries.',
}

/** Publish at shipped (FDAW-39, design 3.3): the editor's label of the TeaAutoPublish field. */
export const TEA_AUTO_PUBLISH_LABEL = 'Publish at shipped'

/** The two values of TeaAutoPublish, in this order. */
export const TEA_AUTO_PUBLISH_OPTIONS: Option[] = [
    opt('DISABLED', 'Off: publish by hand or by an approval-policy action'),
    opt('AT_SHIPPED', 'On: publish when the release reaches Shipped'),
]

/** Added to the help on CE: the setting is stored there, and only Pro publishes on it. */
export const TEA_AUTO_PUBLISH_CE_SUFFIX = ' Takes effect on ReARM Pro.'

/** The help under the publish-at-shipped field; on CE (OSS) with the suffix. */
export function teaAutoPublishHelp (installationType: string | null | undefined): string {
    return TEA_FIELD_HELP.autoPublish + (installationType === 'OSS' ? TEA_AUTO_PUBLISH_CE_SUFFIX : '')
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
    autoPublish: string
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
        autoPublish: 'AT_SHIPPED',
    }
}

function fieldsOf (p: any): Omit<TeaProfileForm, 'mode' | 'followedPerspective'> {
    const d = teaBuiltInDefaults()
    const out: any = {}
    for (const f of TEA_PROFILE_FIELDS) {
        const v = p?.[f]
        out[f] = Array.isArray(v) ? [...v] : (v ?? (d as any)[f])
    }
    out.autoPublish = p?.autoPublish ?? d.autoPublish
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
 * `additive`: the backend serves publish at shipped, so the input carries `autoPublish` (an absent
 * field saves it off, FDAW-39 3.1); false, the default, for a backend that may not know the field.
 */
export function teaProfileInput (form: TeaProfileForm, scope: TeaScope, installationType?: string | null,
    additive = false): Record<string, any> {
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
    if (additive === true) out.autoPublish = form.autoPublish
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

/**
 * The muted hint under an INHERIT form's source line at COMPONENT scope: inheriting resolves through the
 * component's perspectives, and more than one of them with its own row is a conflict. Null elsewhere,
 * for a component with no perspective, and once the resolution already conflicts (the source line
 * says so itself).
 */
export function teaInheritConflictHint (scope: TeaScope, mode: string | null | undefined, perspectiveCount: number,
    view: any, carried = 'TEA profile'): string | null {
    if (scope !== 'COMPONENT' || mode !== TEA_INHERIT || perspectiveCount < 1) return null
    if (view?.parent?.status === 'CONFLICT') return null
    return "Inheriting resolves through this component's perspectives: if more than one of them carries its own "
        + carried + ', inheriting stops with a conflict — use Follow one of its perspectives to pin one.'
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

/**
 * The state of publish at shipped on the row the editor shows (FDAW-39 3.4): a stored row with its own
 * fields decides by its own state; a FOLLOW_PERSPECTIVE row and no row at all by the effective profile.
 */
export function teaShownAutoPublishState (view: any): string | undefined {
    const stored = view?.stored
    if (stored && stored.mode !== 'FOLLOW_PERSPECTIVE') return stored.autoPublishState ?? undefined
    return view?.effective?.profile?.autoPublishState ?? undefined
}

/** Publish at shipped is paused on the row itself: its settings changed since a holder confirmed them. */
export function TEA_AUTO_PUBLISH_PAUSED_OWN (scopeWord: string): string {
    return 'Publish at shipped is paused: these settings changed since a holder confirmed them. Save them as a holder '
        + 'of Publish Externally (TEA) on this ' + scopeWord + ', or as an organization admin, to resume.'
}

/** Publish at shipped is paused on the row this scope inherits or follows. */
export function TEA_AUTO_PUBLISH_PAUSED_INHERITED (source: string, scopeWord: string): string {
    return 'Publish at shipped is paused in the TEA profile of ' + source + ' that this ' + scopeWord
        + ' resolves to. A holder of Publish Externally (TEA), or an organization admin, saves that profile to resume.'
}

/** The paused banner (FDAW-39 3.4), or null unless the shown row's state is NEEDS_CONFIRMATION. */
export function teaAutoPublishBanner (view: any, scopeWord: string, names: Record<string, string> = {}): string | null {
    if (teaShownAutoPublishState(view) !== 'NEEDS_CONFIRMATION') return null
    const stored = view?.stored
    if (stored && stored.mode !== 'FOLLOW_PERSPECTIVE') return TEA_AUTO_PUBLISH_PAUSED_OWN(scopeWord)
    return TEA_AUTO_PUBLISH_PAUSED_INHERITED(teaSourceLabel(view?.effective?.source, view?.effective?.sourceObject, names), scopeWord)
}

/** The kinds of documents a published collection version carries, as the Public confirmations name them. */
export const TEA_DOCUMENT_KINDS = '(SBOMs, VEX documents, device support statements, published artifacts)'

/** The Public confirmation's count where the backend serves the collection version count (FDAW-39 3.6). */
export function TEA_PUBLIC_CONFIRM_COUNTED (releases: number, versions: number): string {
    return releases + ' published release(s) resolving to this profile, with all ' + versions + ' of their collection '
        + 'versions, earlier versions included, and every document in them ' + TEA_DOCUMENT_KINDS + ', become readable '
        + 'by anyone who knows the TEA URL as soon as you save; visibility is live.'
}

/** The Public confirmation where the backend serves the count and nothing is published yet. */
export const TEA_PUBLIC_CONFIRM_NONE_YET = 'No release is published under this profile yet; every future publication '
    + 'under it will be public. Every version published under it later will be public, with every document in it '
    + TEA_DOCUMENT_KINDS + '.'

/** Whether a collection version count was served and is above zero (FDAW-39 3.6). */
export const isCounted = (n: unknown): boolean => typeof n === 'number' && n > 0

/**
 * The confirmation before a save makes a profile PUBLIC. With the collection version count (FDAW-39
 * 3.6) it names the versions and the document kinds; without it (an older backend) today's words.
 */
export function teaPublicConfirmText (publishedReleases: number | null | undefined, publishedVersions?: number | null): string {
    if (!publishedReleases) {
        if (typeof publishedVersions === 'number') return TEA_PUBLIC_CONFIRM_NONE_YET
        return 'No release is published under this profile yet; every future publication under it will be public.'
    }
    if (isCounted(publishedVersions)) return TEA_PUBLIC_CONFIRM_COUNTED(publishedReleases, publishedVersions as number)
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
    /** The collection versions of the published releases (FDAW-38); absent on a backend without the field. */
    publishedVersions?: number | null
    /** Whether the write starts publish at shipped for the object (FDAW-57); absent on an older backend. */
    autoPublishStarts?: boolean | null
    /** The members of a perspective whose removal starts publish at shipped for them, in name order. */
    autoPublishStartsFor?: TeaWidenedComponent[] | null
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
    return { title: PUBLIC_CONFIRM_TITLE, text: path + teaPublicConfirmText(exposure.publishedReleases, exposure.publishedVersions),
        confirmButtonText: 'Make it public' }
}

/** How many names a confirmation lists before it says "and <N> more". */
export const LISTED_NAMES = 5

/** The first LISTED_NAMES names, then ", and <n> more". */
export function listedNames (names: string[]): string {
    const listed = names.slice(0, LISTED_NAMES).join(', ')
    return names.length > LISTED_NAMES ? listed + ', and ' + (names.length - LISTED_NAMES) + ' more' : listed
}

export const TEA_TURN_ON_TITLE = 'Turn on publish at shipped?'
export const TEA_TURN_ON_BUTTON = 'Turn on'
export const TEA_MEMBERSHIP_BUTTON = 'Change perspectives'

/** What turning publish at shipped on does, said before a save that starts it (FDAW-39 3.5). */
export const TEA_TURN_ON_TEXT = 'Saving publishes nothing by itself. A release already at Shipped or later under this '
    + 'profile is published the next time it changes (it is saved, its lifecycle changes or its vulnerability findings '
    + 'change), unless it is hidden on TEA. After that, every such change publishes it again; a change that leaves the '
    + 'published documents as they are adds no version. Component releases resolving to this profile are published on '
    + 'their own when they ship; a product published later lists them as already published.'

/** The lead of the turn-on confirmation of a FOLLOW_PERSPECTIVE save, with its trailing space. */
export function TEA_TURN_ON_FOLLOW_LEAD (followed: string, scopeWord: string, source: string): string {
    return 'Following perspective ' + followed + ' turns on publish at shipped for this ' + scopeWord
        + ' through the TEA profile of ' + source + '. '
}

export function TEA_START_REMOVAL_OBJECT (scopeWord: string, source: string): string {
    return 'Removing this override turns on publish at shipped for this ' + scopeWord + ' through the TEA profile of '
        + source + '; its releases at Shipped or later are published the next time they change.'
}

export function TEA_START_REMOVAL_MEMBERS (count: number, names: string): string {
    return 'Removing this override turns on publish at shipped for ' + count + ' component(s) of this perspective: '
        + names + '; their releases at Shipped or later are published the next time they change.'
}

export function TEA_START_MEMBERSHIP (scopeWord: string, what: string, source: string): string {
    return 'Changing this ' + scopeWord + "'s perspectives" + what + ' turns on publish at shipped for it through the '
        + 'TEA profile of ' + source + '; its releases at Shipped or later are published the next time they change.'
}

/**
 * Whether a save of `input` starts publish at shipped here: it turns publishing on with AT_SHIPPED
 * while the shown row is not already firing (a paused row counts as not firing). An input without
 * `autoPublish` (a backend without the field) never does.
 */
export function teaWouldStartAutoPublish (view: any, input: Record<string, any> | null | undefined): boolean {
    return input?.publishing === 'ENABLED' && input?.autoPublish === 'AT_SHIPPED'
        && teaShownAutoPublishState(view) !== 'ARMED'
}

/**
 * The turn-on confirmation before a save, or null. A FOLLOW_PERSPECTIVE save asks exactly when the
 * server's dry-run says it starts (the only save it computes that for); every other save asks on
 * teaWouldStartAutoPublish.
 */
export function teaTurnOnConfirm (view: any, input: Record<string, any> | null | undefined,
    exposure: TeaExposureChange | null | undefined, scopeWord: string, names: Record<string, string> = {}): TeaConfirm | null {
    if (input?.mode === 'FOLLOW_PERSPECTIVE') {
        if (exposure?.autoPublishStarts !== true) return null
        const followed = input.followedPerspective
        const lead = TEA_TURN_ON_FOLLOW_LEAD((followed && names[followed]) || followed || '', scopeWord,
            teaSourceLabel(exposure.afterSource, exposure.afterSourceObject, names))
        return { title: TEA_TURN_ON_TITLE, text: lead + TEA_TURN_ON_TEXT, confirmButtonText: TEA_TURN_ON_BUTTON }
    }
    if (!teaWouldStartAutoPublish(view, input)) return null
    return { title: TEA_TURN_ON_TITLE, text: TEA_TURN_ON_TEXT, confirmButtonText: TEA_TURN_ON_BUTTON }
}

/** Whether the dry-run says the write starts publish at shipped, for the object or for members. */
export function teaStartsAutoPublish (exposure: TeaExposureChange | null | undefined): boolean {
    return exposure?.autoPublishStarts === true || (exposure?.autoPublishStartsFor?.length ?? 0) > 0
}

/**
 * The sentence a removal or a membership change adds when it starts publish at shipped, or null.
 * `what` is the membership change's bracket (" (adding Payments)").
 */
export function teaAutoPublishStartText (exposure: TeaExposureChange | null | undefined, kind: 'removal' | 'membership',
    scopeWord: string, names: Record<string, string> = {}, what = ''): string | null {
    if (!exposure || !teaStartsAutoPublish(exposure)) return null
    const source = teaSourceLabel(exposure.afterSource, exposure.afterSourceObject, names)
    if (kind === 'membership') return TEA_START_MEMBERSHIP(scopeWord, what, source)
    const members = exposure.autoPublishStartsFor ?? []
    if (members.length) return TEA_START_REMOVAL_MEMBERS(members.length, listedNames(members.map(m => m.name)))
    return TEA_START_REMOVAL_OBJECT(scopeWord, source)
}

/**
 * The confirmation before a removal: the plain one, or the PUBLIC one when the parent it falls
 * back to is PUBLIC or, for a perspective row, when members of the perspective then resolve to a
 * PUBLIC profile (named, up to five).
 */
export function teaConfirmBeforeRemoval (exposure: TeaExposureChange | null | undefined, scopeWord: string,
    names: Record<string, string> = {}): TeaConfirm {
    const start = teaAutoPublishStartText(exposure, 'removal', scopeWord, names)
    const withStart = (text: string) => start ? text + ' ' + start : text
    if (!exposure || !teaNeedsPublicConfirm(exposure)) {
        return { title: 'Remove this TEA profile override?',
            text: withStart('The ' + scopeWord + ' then resolves its TEA profile from its parent again.'), confirmButtonText: 'Remove' }
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
    return { title: PUBLIC_CONFIRM_TITLE, text: withStart(text + teaPublicConfirmText(exposure.publishedReleases, exposure.publishedVersions)),
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
    if (!exposure) return null
    const changes = [
        ...(added.length ? ['adding ' + added.join(', ')] : []),
        ...(removed.length ? ['removing ' + removed.join(', ')] : []),
    ]
    const what = changes.length ? ' (' + changes.join('; ') + ')' : ''
    const start = teaAutoPublishStartText(exposure, 'membership', scopeWord, names, what)
    if (!teaNeedsPublicConfirm(exposure)) {
        return start ? { title: TEA_TURN_ON_TITLE, text: start, confirmButtonText: TEA_MEMBERSHIP_BUTTON } : null
    }
    const text = 'Changing this ' + scopeWord + "'s perspectives" + what + ' resolves it to the PUBLIC profile of '
        + teaSourceLabel(exposure.afterSource, exposure.afterSourceObject, names) + '. '
        + teaPublicConfirmText(exposure.publishedReleases, exposure.publishedVersions)
    return { title: 'Make this ' + scopeWord + ' public?', text: start ? text + ' ' + start : text,
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
