// The rows of a source's list in the start of a follow (ReARM Pro): every decision the dialog takes
// about the rows it loaded, kept here so that it can be tested without a mount. Pure: no Vue, no
// client, no store. The dialog holds two sets (the browse list and a lookup by identifier); each
// set is the rows loaded so far, the paging state of its last page and the facts about rows that
// are missing, accumulated over every page loaded into it.
import {
    identifierValueProblem, labelOf, offerCutSentence, offerEndSentence, offerFailureSentence, offerFetcherBusy, offerLoadedCount,
    offerLookupTypeProblem, offerMaxRowsSentence, offerMissingHint, offerMoreSentence, offerRowsDroppedSentence, offerSourceFailed,
    offerTokenUnusableSentence,
} from './upstreamLabels'

/** The page size sent with every list call: the maximum of the backend and of TEA. */
export const OFFER_PAGE_SIZE = 100
/** Rows per page of the table; the table pages over the loaded rows and never sends a request. */
export const OFFER_TABLE_PAGE = 25
/** Identifiers shown in the row; the others are in the expanded row. */
export const OFFER_IDENTIFIERS_SHOWN = 3
/** Code points of a name shown in the row. */
export const OFFER_NAME_SHOWN = 120
/** Code points of an identifier value shown in the row. */
export const OFFER_VALUE_SHOWN = 100

const ELLIPSIS = '...'
const IDENTIFIER_TYPES = ['CPE', 'TEI', 'PURL', 'COMPLIANCE_DOCUMENT']
const IDENTIFIER_VALUE = /^[\x20-\x7E]{1,2048}$/

export type OfferNext = 'NONE' | 'TOKEN' | 'TOKEN_UNUSABLE'

export interface OfferIdentifier {
    idType: string
    idValue: string
}

export interface OfferRow {
    upstreamUuid: string
    name: string | null
    identifiers: OfferIdentifier[]
    identifiersDropped: number
    activeFollow: string | null
}

export interface OfferPage {
    source?: string | null
    kind?: string | null
    failure: string | null
    sourceFailure: string | null
    message: string | null
    items: OfferRow[] | null
    next: OfferNext | null
    nextPageToken: string | null
    rowsReceived: number | null
    rowsDropped: number | null
    cut: string | null
}

/** The rows loaded into one set and what is known about the rows that are not. */
export interface OfferSet {
    rows: OfferRow[]
    /** next of the last page loaded. */
    next: OfferNext | null
    /** The token of the last page: kept only for next TOKEN and a non-empty token. */
    token: string | null
    /** Whether a page of the set was cut with MAX_ROWS, and the rowsReceived of the latest such page. */
    maxRowsReceived: number | null
    /** Whether a page of the set was cut with OTHER. */
    cutOther: boolean
    /** The sum of rowsDropped over the pages of the set. */
    rowsDropped: number
}

export type OfferSecondLine = 'END' | 'MORE' | 'TOKEN_UNUSABLE'

/** The statement under the table, in the order of the lines. */
export interface OfferStatements {
    count: string
    second: OfferSecondLine
    secondText: string
    /** Whether the button Load more is offered: next TOKEN with a token. */
    canLoadMore: boolean
    warnings: string[]
    hint: string | null
}

export function emptyOfferSet (): OfferSet {
    return { rows: [], next: null, token: null, maxRowsReceived: null, cutOther: false, rowsDropped: 0 }
}

/**
 * The problem of a lookup, or the empty string: a type must be chosen, and the value, trimmed,
 * must be 1 to 2048 characters of U+0020 to U+007E (the backend's rule; the backend does not trim).
 */
export function identifierProblem (type: string | null | undefined, value: string | null | undefined): string {
    if (typeof type !== 'string' || !IDENTIFIER_TYPES.includes(type)) return offerLookupTypeProblem
    const trimmed = typeof value === 'string' ? value.trim() : ''
    return IDENTIFIER_VALUE.test(trimmed) ? '' : identifierValueProblem
}

/** The identifier as it is sent, trimmed; null when it has a problem. */
export function identifierInput (type: string | null | undefined, value: string | null | undefined): OfferIdentifier | null {
    if (identifierProblem(type, value)) return null
    return { idType: type as string, idValue: (value as string).trim() }
}

/**
 * The loaded rows with a page merged in, keyed by upstreamUuid: a row whose uuid is already loaded
 * replaces the earlier row in its place, new rows are appended in the order served. The inputs are
 * not changed.
 */
export function mergeOfferPage (loaded: readonly OfferRow[], items: readonly OfferRow[] | null | undefined): OfferRow[] {
    const out = loaded.slice()
    const at = new Map<string, number>()
    out.forEach((row, i) => at.set(row.upstreamUuid, i))
    for (const row of items ?? []) {
        const i = at.get(row.upstreamUuid)
        if (i === undefined) {
            at.set(row.upstreamUuid, out.length)
            out.push(row)
        } else {
            out[i] = row
        }
    }
    return out
}

/** The set with a page that was served without a failure merged in. */
export function applyOfferPage (set: OfferSet, page: OfferPage): OfferSet {
    const next = page.next ?? null
    const token = next === 'TOKEN' && typeof page.nextPageToken === 'string' && page.nextPageToken !== '' ? page.nextPageToken : null
    return {
        rows: mergeOfferPage(set.rows, page.items),
        next,
        token,
        maxRowsReceived: page.cut === 'MAX_ROWS' ? (page.rowsReceived ?? 0) : set.maxRowsReceived,
        cutOther: set.cutOther || page.cut === 'OTHER',
        rowsDropped: set.rowsDropped + Math.max(0, page.rowsDropped ?? 0),
    }
}

/**
 * The statement of a loaded set: the count; exactly one of no more rows, more rows (with Load more)
 * and more rows that cannot be loaded; a warning for each cause of missing rows; and the hint when
 * rows are missing. next TOKEN without a token reads as more rows that cannot be loaded.
 */
export function offerStatements (set: OfferSet): OfferStatements {
    const canLoadMore = set.next === 'TOKEN' && !!set.token
    let second: OfferSecondLine
    if (set.next === 'NONE') second = 'END'
    else if (canLoadMore) second = 'MORE'
    else second = 'TOKEN_UNUSABLE'
    const secondText = second === 'END' ? offerEndSentence : second === 'MORE' ? offerMoreSentence : offerTokenUnusableSentence
    const warnings: string[] = []
    if (set.maxRowsReceived !== null) warnings.push(offerMaxRowsSentence(set.maxRowsReceived))
    if (set.cutOther) warnings.push(offerCutSentence)
    if (set.rowsDropped > 0) warnings.push(offerRowsDroppedSentence(set.rowsDropped))
    const hint = second === 'TOKEN_UNUSABLE' || warnings.length ? offerMissingHint : null
    return { count: offerLoadedCount(set.rows.length), second, secondText, canLoadMore, warnings, hint }
}

/** Whether a loaded set is the plain empty answer: no row, the end of the list, nothing missing. */
export function isCleanEmpty (set: OfferSet): boolean {
    const s = offerStatements(set)
    return set.rows.length === 0 && s.second === 'END' && s.warnings.length === 0
}

/**
 * The rows that hold the text, compared without case, in the name, the uuid or the whole value of
 * any served identifier. An empty or blank text keeps every row. The order is kept.
 */
export function filterOfferRows (rows: readonly OfferRow[], text: string | null | undefined): OfferRow[] {
    const needle = (text ?? '').trim().toLowerCase()
    if (!needle) return rows.slice()
    const holds = (value: string | null | undefined) => typeof value === 'string' && value.toLowerCase().includes(needle)
    return rows.filter(row => holds(row.name) || holds(row.upstreamUuid) || (row.identifiers ?? []).some(i => holds(i.idValue)))
}

/** The text cut for display to max code points, then the ellipsis; never half a character. */
export function cutForDisplay (text: string, max: number): string {
    const points = Array.from(text)
    return points.length > max ? points.slice(0, max).join('') + ELLIPSIS : text
}

/** Whether a text is longer than the display cut. */
export function isCut (text: string | null | undefined, max: number): boolean {
    return typeof text === 'string' && Array.from(text).length > max
}

/** Whether a row has more than it shows: a cut name or value, more identifiers, or dropped identifiers. */
export function rowIsExpandable (row: OfferRow): boolean {
    const identifiers = row.identifiers ?? []
    return isCut(row.name, OFFER_NAME_SHOWN)
        || identifiers.length > OFFER_IDENTIFIERS_SHOWN
        || identifiers.some(i => isCut(i.idValue, OFFER_VALUE_SHOWN))
        || (row.identifiersDropped ?? 0) > 0
}

/** The table page (from 1) that holds the row at this index. */
export function tablePageOf (index: number): number {
    return Math.floor(Math.max(0, index) / OFFER_TABLE_PAGE) + 1
}

/**
 * The words of a page served with a failure. For SOURCE_FAILURE they are the sentence beside the
 * tag of the probe result: the served message, except for FETCHER_BUSY and FAILED, whose served
 * sentences speak of a probe. For the others the served message, or the copy of the backend's
 * sentence when the message is null or empty; never the raw value.
 */
export function offerFailureText (page: Pick<OfferPage, 'failure' | 'sourceFailure' | 'message'>): string {
    const served = typeof page.message === 'string' && page.message.trim() !== '' ? page.message : ''
    if (page.failure === 'SOURCE_FAILURE') {
        if (page.sourceFailure === 'FETCHER_BUSY') return offerFetcherBusy
        if (page.sourceFailure === 'FAILED') return offerSourceFailed
        return served
    }
    return served || labelOf(offerFailureSentence, page.failure)
}
