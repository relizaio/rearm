// Which tab the component / product page (ComponentView, routes ComponentsOfOrg
// and ProductsOfOrg) opens on.
//
// Every branch or feature-set link in the UI is a route with a :branchuuid
// param and no ?tab= (router-links, router.push calls, chart and inbox URLs).
// When Latest became the default tab, those links landed on Latest, which has
// no branch pane, so the branch they named was not shown. The page resolves
// its tab here instead, so all of them open the tab that lists the branch.

export type ComponentPageTab = 'latest' | 'branches' | 'tags'

/** The tab with no branch and no explicit tab: one row per branch, newest release. */
export const LATEST_TAB: ComponentPageTab = 'latest'
/** Branches on a component, Feature Sets on a product (same tab name, different label). */
export const BRANCHES_TAB: ComponentPageTab = 'branches'
/** Tags; components only. */
export const TAGS_TAB: ComponentPageTab = 'tags'

export interface ComponentPageTabInput {
    /** route.query.tab: an explicit tab always wins. */
    queryTab?: unknown
    /** route.params.branchuuid: the branch or feature set the link names. */
    branchUuid?: unknown
    /** The named branch's type, once the branch list is loaded. */
    branchType?: string | null
    /** False on a product page, which has no Tags tab. Defaults to true. */
    isComponent?: boolean
}

function nonEmptyString (v: unknown): string {
    if (Array.isArray(v)) v = v[0]
    return typeof v === 'string' ? v.trim() : ''
}

/**
 * An explicit ?tab= wins. Otherwise a branch in the path opens the tab that
 * lists it: Tags for a tag on a component, Branches (Feature Sets on a
 * product) for everything else, pull-request branches included. With neither,
 * the page opens on Latest.
 */
export function resolveComponentPageTab (input: ComponentPageTabInput): string {
    const explicit = nonEmptyString(input.queryTab)
    if (explicit) return explicit
    if (!nonEmptyString(input.branchUuid)) return LATEST_TAB
    if (input.branchType === 'TAG' && input.isComponent !== false) return TAGS_TAB
    return BRANCHES_TAB
}
