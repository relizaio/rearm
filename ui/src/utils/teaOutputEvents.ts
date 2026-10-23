// TEA output events (task TEA-13, the UI half of TEA-7): the two action types TEA_PUBLISH and
// TEA_HIDE in the component's Actions tab and the approval policy's Policy-Wide Actions, and the
// pure logic of the public warning -- when a scope resolves PUBLIC, which saved wiring makes a
// TEA_PUBLISH fire on a component, and the copy of the confirmation and the badge. Kept out of the
// .vue files so the specs pin them.

import { LISTED_NAMES, Option, TeaConfirm, teaProfilesAvailable, teaSourceLabel } from './teaProfile'

/** A resolved TEA profile as teaProfileEditorView answers it (TEA_RESOLVED_PROFILE_GQL). */
export interface TeaResolvedView {
    status?: string | null
    source?: string | null
    sourceObject?: string | null
    profile?: { publishing?: string | null, visibility?: string | null, [field: string]: any } | null
    [field: string]: any
}

/** The answer of teaProfileEditorView (graphqlQueries TEA_PROFILE_EDITOR_VIEW), as far as this file reads it. */
export interface TeaEditorView {
    stored?: any
    effective?: TeaResolvedView | null
    parent?: TeaResolvedView | null
    publishedReleases?: number | null
    [field: string]: any
}

/** A row of teaProfilesOfOrg (graphqlQueries TEA_PROFILES_OF_ORG). */
export interface TeaProfileRow {
    uuid?: string
    scope?: string | null
    object?: string | null
    mode?: string | null
    followedPerspective?: string | null
    publishing?: string | null
    visibility?: string | null
}

/** The two TEA action types, in the order the selects show them. They carry no fields of their own. */
export const TEA_OUTPUT_EVENT_TYPE_OPTIONS: Option[] = [
    { label: 'TEA Publish', value: 'TEA_PUBLISH' },
    { label: 'TEA Hide', value: 'TEA_HIDE' },
]

const TEA_PUBLISH = 'TEA_PUBLISH'
const INSERT_AFTER = 'VDR_SNAPSHOT_ARTIFACT'

/**
 * The base list with the two TEA entries inserted right after VDR_SNAPSHOT_ARTIFACT (at the end if the
 * base has none), where the backend serves TEA; the base unchanged on CE, whose schema has neither value.
 */
export function outputEventTypeOptionsForEdition (base: Option[], installationType: string | null | undefined): Option[] {
    if (!teaProfilesAvailable(installationType)) return [...base]
    const at = base.findIndex(o => o.value === INSERT_AFTER)
    const cut = at < 0 ? base.length : at + 1
    return [...base.slice(0, cut), ...TEA_OUTPUT_EVENT_TYPE_OPTIONS, ...base.slice(cut)]
}

/** The label of a stored type: every base entry plus the two TEA ones, whatever the edition; an unknown type reads as itself. */
export function outputEventTypeLabel (base: Option[], type: string): string {
    const option = [...base, ...TEA_OUTPUT_EVENT_TYPE_OPTIONS].find(o => o.value === type)
    return option ? option.label : type
}

/** TEA-7 3.12: a RESOLVED effective profile with publishing ENABLED and visibility PUBLIC. */
export function teaScopeIsPublic (view: TeaEditorView | null | undefined): boolean {
    const e = view?.effective
    return e?.status === 'RESOLVED' && e?.profile?.publishing === 'ENABLED' && e?.profile?.visibility === 'PUBLIC'
}

/** What resolves PUBLIC in an organization, for the policy page: its own profile, and PUBLIC rows by scope. */
export interface TeaOrgPublicSources { organization: boolean, perspectives: number, components: number, publishedReleases: number }

/**
 * null when nothing in the organization resolves PUBLIC. `organization` is the organization's own effective
 * profile; a perspective or component row counts when it carries its own fields (a FOLLOW_PERSPECTIVE row
 * decides nothing by itself) with publishing ENABLED and visibility PUBLIC. `publishedReleases` is the
 * organization view's count.
 */
export function teaOrgPublicSources (orgView: TeaEditorView | null | undefined, rows: TeaProfileRow[] | null | undefined): TeaOrgPublicSources | null {
    const organization = teaScopeIsPublic(orgView)
    let perspectives = 0
    let components = 0
    for (const r of rows ?? []) {
        if (!r || r.mode === 'FOLLOW_PERSPECTIVE' || r.publishing !== 'ENABLED' || r.visibility !== 'PUBLIC') continue
        if (r.scope === 'PERSPECTIVE') perspectives++
        else if (r.scope === 'COMPONENT') components++
    }
    if (!organization && perspectives === 0 && components === 0) return null
    return { organization, perspectives, components, publishedReleases: Number(orgView?.publishedReleases ?? 0) }
}

const list = (v: any): string[] => (Array.isArray(v) ? v.filter((x: any) => typeof x === 'string') : [])

/**
 * Every piece that makes a TEA_PUBLISH fire on this component, selected as the engine selects outputs
 * (ReleaseTriggerService 611, 663, 674, 684 to 692), canonical and sorted. TEA_HIDE is never a piece.
 *  "action:<uuid>"                       per local TEA_PUBLISH action in outputTriggers;
 *  "rule:<rule>:<true|false>:<action>"   per local rule with enabled !== false, per TEA_PUBLISH action (local or
 *                                        policy-wide) in its outputEvents (true) and outputEventsOnFalse (false);
 *  "policy:<rule>:<true|false>:<action>" per policy rule with enabled !== false whose ref on this component, if
 *                                        any, has disabled !== true: on the true branch the ref's override when in
 *                                        effect (overrideOutputEventsLocally === true and outputEventsOverride an
 *                                        array; an empty array replaces the list and names nothing), else the
 *                                        rule's own outputEvents; on the false branch always the rule's
 *                                        outputEventsOnFalse.
 * A disabled local rule, a policy rule disabled on the policy and a ref with disabled: true contribute nothing.
 * An action uuid is a TEA_PUBLISH when outputTriggers or globals holds it with that type.
 */
export function teaPublishWiring (component: any, rules: any[], globals: any[]): string[] {
    const locals: any[] = Array.isArray(component?.outputTriggers) ? component.outputTriggers : []
    const publishes = new Set<string>()
    for (const a of [...locals, ...(globals ?? [])]) {
        if (a?.type === TEA_PUBLISH && a.uuid) publishes.add(a.uuid)
    }
    const pieces = new Set<string>()
    for (const a of locals) {
        if (a?.type === TEA_PUBLISH && a.uuid) pieces.add('action:' + a.uuid)
    }
    const branches = (prefix: string, rule: string, onTrue: string[], onFalse: string[]) => {
        for (const id of onTrue) if (publishes.has(id)) pieces.add(prefix + ':' + rule + ':true:' + id)
        for (const id of onFalse) if (publishes.has(id)) pieces.add(prefix + ':' + rule + ':false:' + id)
    }
    const localRules: any[] = Array.isArray(component?.releaseInputTriggers) ? component.releaseInputTriggers : []
    for (const r of localRules) {
        if (!r || r.enabled === false) continue
        branches('rule', r.uuid ?? '', list(r.outputEvents), list(r.outputEventsOnFalse))
    }
    const refs: any[] = Array.isArray(component?.globalInputEventRefs) ? component.globalInputEventRefs : []
    for (const g of rules ?? []) {
        if (!g || g.enabled === false) continue
        const ref = refs.find((x: any) => x?.uuid === g.uuid)
        if (ref?.disabled === true) continue
        const overridden = ref?.overrideOutputEventsLocally === true && Array.isArray(ref.outputEventsOverride)
        branches('policy', g.uuid ?? '', overridden ? list(ref.outputEventsOverride) : list(g.outputEvents), list(g.outputEventsOnFalse))
    }
    return [...pieces].sort()
}

/** The pieces of `after` that `before` lacks, both computed on the same rules and globals. */
export function teaPublishWiringAdded (before: any, after: any, rules: any[], globals: any[]): string[] {
    const held = new Set(teaPublishWiring(before, rules, globals))
    return teaPublishWiring(after, rules, globals).filter(p => !held.has(p))
}

/**
 * The distinct names of the TEA_PUBLISH actions the added pieces name, in piece order: a local name from
 * outputTriggers, a policy-wide name from globals, the uuid when neither knows it.
 */
export function teaActionNames (added: string[], outputTriggers: any[], globals: any[]): string[] {
    const names: string[] = []
    for (const piece of added) {
        const id = piece.slice(piece.lastIndexOf(':') + 1)
        const local = (outputTriggers ?? []).find((a: any) => a?.uuid === id)
        const global = (globals ?? []).find((a: any) => a?.uuid === id)
        const name = local?.name || global?.name || id
        if (!names.includes(name)) names.push(name)
    }
    return names
}

// Constants on purpose: SweetAlert2 renders title and confirmButtonText as HTML, so no interpolated name
// may go there (TEA-13 security review F-4). Names go into `text` only, which it renders as text.
const AUTO_PUBLISH_TITLE = 'Publish automatically to the public?'
const AUTO_PUBLISH_BUTTON = 'Publish publicly'

function listedNames (names: string[]): string {
    const listed = names.slice(0, LISTED_NAMES).join(', ')
    return names.length > LISTED_NAMES ? listed + ', and ' + (names.length - LISTED_NAMES) + ' more' : listed
}

function sourceOf (view: TeaEditorView, names: Record<string, string>): string {
    return teaSourceLabel(view.effective?.source, view.effective?.sourceObject, names)
}

/**
 * The confirmation before a component save that gains a TEA publish piece, or null when the component's
 * scope is not PUBLIC. The count is stated only for the component's own row, where the view's count is
 * this component's (the TEA-9 dry-runs embed the same number).
 */
export function teaConfirmBeforeAutoPublish (view: TeaEditorView | null | undefined, names: Record<string, string>,
    scopeWord: string, actionNames: string[]): TeaConfirm | null {
    if (!view || !teaScopeIsPublic(view)) return null
    let text = 'Action(s) ' + listedNames(actionNames) + ' publish releases of this ' + scopeWord
        + ' on TEA whenever a rule fires them, and this ' + scopeWord + ' resolves to the PUBLIC TEA profile of '
        + sourceOf(view, names) + ': everything they publish is readable by anyone who knows the TEA URL.'
    if (view.effective?.source === 'COMPONENT') {
        const n = Number(view.publishedReleases ?? 0)
        text += n
            ? ' ' + n + ' release(s) are already published under this profile; every release the action publishes joins them.'
            : ' No release is published under this profile yet; every release the action publishes will be public.'
    }
    return { title: AUTO_PUBLISH_TITLE, text, confirmButtonText: AUTO_PUBLISH_BUTTON }
}

/** The confirmation before a new policy-wide TEA_PUBLISH is saved, or null when nothing in the organization is PUBLIC. */
export function teaConfirmBeforeGlobalAutoPublish (sources: TeaOrgPublicSources | null | undefined, actionName: string): TeaConfirm | null {
    if (!sources) return null
    let text = "Policy-wide action '" + actionName + "' publishes releases on TEA whenever a policy rule fires it, "
        + 'for every component on this approval policy. '
    if (sources.organization) {
        const n = Number(sources.publishedReleases ?? 0)
        text += "The organization's TEA profile is PUBLIC, so every component without a PRIVATE profile of its own publishes publicly; "
            + (n
                ? n + ' published release(s) are already under the organization profile, and every release the action publishes joins them. '
                : 'no release is published under the organization profile yet, and every release the action publishes will be public. ')
    }
    if (sources.perspectives > 0) {
        text += sources.perspectives + ' perspective profile(s) are PUBLIC: components in those perspectives publish publicly. '
    }
    if (sources.components > 0) {
        text += sources.components + ' component profile(s) are PUBLIC. '
    }
    text += 'Components with their own PRIVATE profile are not affected. '
        + 'Components assigned to this policy later, including by an organization rule on their name, are enrolled too.'
    return { title: AUTO_PUBLISH_TITLE, text, confirmButtonText: AUTO_PUBLISH_BUTTON }
}

/** The badge's title on a TEA_PUBLISH row of the component page, or null when no badge. */
export function teaPublicBadgeTitle (view: TeaEditorView | null | undefined, names: Record<string, string>, scopeWord: string): string | null {
    if (!view || !teaScopeIsPublic(view)) return null
    return 'Publishes to the public: this ' + scopeWord + ' resolves to the PUBLIC TEA profile of ' + sourceOf(view, names) + '.'
}

/** The badge's title on a policy-wide TEA_PUBLISH row of the policy page, or null when no badge. */
export function teaOrgPublicBadgeTitle (sources: TeaOrgPublicSources | null | undefined): string | null {
    if (!sources) return null
    const parts: string[] = []
    if (sources.organization) parts.push('the organization profile is PUBLIC')
    if (sources.perspectives > 0) parts.push(sources.perspectives + ' perspective profile(s) are PUBLIC')
    if (sources.components > 0) parts.push(sources.components + ' component profile(s) are PUBLIC')
    if (!parts.length) return null
    return 'Publishes to the public: ' + parts.join('; ') + '.'
}
