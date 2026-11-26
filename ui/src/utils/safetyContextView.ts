// Text for the release Safety Context tab: the product (device) view of component safety levels,
// its per-scheme summary and completeness banners, and the cells of rows whose source details the
// server withheld (sourceRedacted). Every string is plain text; the component renders it as text.

import { DECLARED_NOT_VERIFIED, GATE_RULE_LABELS } from '@/graphql/safetyContext'

/** The server's completeness reasons, in the order the banners are shown. */
export const INCOMPLETE_REASONS = ['HIDDEN_RELEASES', 'CONSTITUENT_CAP', 'INVENTORY_PENDING', 'READ_CAP'] as const
export type IncompleteReason = typeof INCOMPLETE_REASONS[number]

const SOURCES_SENTENCE = 'Levels come from SAFETY_CONTEXT (CycloneDX 2.0) artifacts uploaded to this release, or to'
    + ' other releases of the same component, branch or organization, for packages this release contains.'
const PRODUCT_SENTENCE = 'This is a product release. The table lists the levels that apply to this release and to'
    + ' each component release it contains, resolved the way each component release\'s own Safety Context tab'
    + ' resolves them; each row names the release it applies to.'
const OTHER_SENTENCES = 'A level set for this release overrides one set more broadly; the row names the level it'
    + ' overrides. A package version without a level shows the level of its previous version, marked carried,'
    + ' until one is declared. Where assets declare different levels for one component and scheme, the most'
    + ' stringent is marked.'

/** The header alert of the tab; a product view replaces the sentence on where levels come from. */
export function headerText (productView: boolean): string {
    return [DECLARED_NOT_VERIFIED, productView ? PRODUCT_SENTENCE : SOURCES_SENTENCE, OTHER_SENTENCES].join(' ')
}

export const SUMMARY_TITLE = 'Most stringent level per scheme'
export const REDACTED_SOURCE_TEXT = 'uploaded on a release not visible to you'
export const REDACTED_REVIEWER_TEXT = 'reviewer (not visible to you)'
export const OWN_RELEASE_TEXT = 'This release'

/** One line of the summary card for one SafetyLevelSummary entry. */
export function summaryLine (s: any): string {
    const unranked: string[] = s?.unrankedLevels || []
    if (s?.level == null) return `${s?.schemeKey}: cannot be ranked (${unranked.join(', ')})`
    const n = s.releaseCount ?? 0
    let line = `${s.schemeKey}: ${s.level} (${n} ${n === 1 ? 'release' : 'releases'})`
    if (s.fromCarriedOnly) line += ' - carried from a previous package version'
    if (unranked.length) line += `; also cannot be ranked: ${unranked.join(', ')}`
    return line
}

/** The summary card's text when the summary is empty. */
export function emptySummaryText (complete: boolean): string {
    return complete
        ? 'No safety integrity levels apply to this product release or its component releases.'
        : 'No safety integrity levels are visible to you.'
}

const MAY_BE_HIGHER = 'The most stringent level of this product may be higher than shown.'

function bannerText (reason: IncompleteReason, v: any): string {
    switch (reason) {
    case 'HIDDEN_RELEASES':
        return `${v.hiddenReleaseCount} component release(s) are not visible to you; ${v.hiddenLevelCount} level(s)`
            + ` on them are not shown. ${MAY_BE_HIGHER}`
    case 'CONSTITUENT_CAP':
        return `Only ${v.evaluatedReleaseCount} of ${v.constituentReleaseCount} releases were evaluated. ${MAY_BE_HIGHER}`
    case 'INVENTORY_PENDING':
        return `The SBOM of ${v.pendingInventoryReleaseCount} release(s) is still being processed; levels for packages`
            + ' not yet indexed are missing.'
    case 'READ_CAP':
        return 'Some releases have more levels than can be listed; the rows and the summary may be incomplete.'
    }
}

/** One warning banner per reason the server listed, in enum order. */
export function banners (v: any): { reason: IncompleteReason, text: string }[] {
    const listed: string[] = v?.incompleteReasons || []
    return INCOMPLETE_REASONS.filter(r => listed.includes(r)).map(reason => ({ reason, text: bannerText(reason, v) }))
}

/** The line under the table when the server holds more rows than it sent; null otherwise. */
export function showingText (received: number, totalRows: number | null | undefined): string | null {
    return totalRows != null && totalRows > received ? `Showing the first ${received} of ${totalRows} rows.` : null
}

/** The "Release" cell of the product view: the viewed release itself, or the component release a row applies to. */
export function releaseCellText (r: any, viewedRelease: string): string {
    if (!r.appliesToRelease || r.appliesToRelease === viewedRelease) return OWN_RELEASE_TEXT
    return `${r.appliesToComponentName} ${r.appliesToReleaseVersion}`
}

/** Who accepted a level, as the "Accepted" cell names them (without the date). */
export function acceptedWho (r: any): string {
    if (r.acceptedVia !== 'MANUAL') return 'auto'
    if (r.sourceRedacted) return REDACTED_REVIEWER_TEXT
    return r.acceptedByName || 'reviewer'
}

/** The "Accepted" cell's tooltip; a redacted row carries no comment. */
export function acceptedDetail (r: any): string {
    return [GATE_RULE_LABELS[r.gateRule] || r.gateRule, r.sourceRedacted ? null : r.acceptComment, r.statusReason,
        r.supersededBy ? 'superseded by a later level' : null].filter(Boolean).join(' - ')
}

/** The "Issuer" tooltip of a level raised by an override. */
export function issuerOverrideTooltip (r: any): string {
    if (r.sourceRedacted) return `Raised from ${r.issuerClassDerived} by an override (not visible to you)`
    return `Raised from ${r.issuerClassDerived} by ${r.overrideActorName || 'an override'}`
}

/** A table row's key: one record can be listed under several constituents and, carried, for several purls. */
export function rowKey (r: any): string {
    return `${r.appliesToRelease}|${r.uuid}|${r.shownForPurl}`
}

/**
 * The number of distinct active, non-carried level records among rows that apply to the same
 * release, component and scheme as r. The "most stringent" tag needs more than one.
 */
export function distinctActiveLevels (rows: any[], r: any): number {
    const uuids = new Set<string>()
    for (const x of rows) {
        if (x.recordStatus === 'ACTIVE' && !x.carried && x.appliesToRelease === r.appliesToRelease
            && x.componentPurl === r.componentPurl && x.schemeKey === r.schemeKey) uuids.add(x.uuid)
    }
    return uuids.size
}
