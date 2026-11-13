// FDAW-80: the published artifact revisions TEA withdrew (its artifact endpoints answer 410), as the
// release page's TEA tab shows them: the notice above the collections table and the mark on each entry.
// No Vue import, so teaWithdrawn.spec.ts runs it (the shape of teaPublication.ts).

import graphqlQueries from './graphqlQueries'
import { labelOrLower } from './fdaLabels'
import { isSchemaDriftError, type DriftFallbackClient } from './graphqlDriftFallback'

/** The Pro TeaWithdrawnReason values; teaProfileSchemaDrift.spec.ts holds this list to the Pro enum. */
export const TEA_WITHDRAWN_REASONS = ['VDR_SNAPSHOT'] as const
export type TeaWithdrawnReason = typeof TEA_WITHDRAWN_REASONS[number]

export const TEA_WITHDRAWN_LABELS: Record<TeaWithdrawnReason, string> = {
    VDR_SNAPSHOT: 'VDR snapshot, not served on TEA',
}

export const TEA_WITHDRAWN_UNKNOWN = 'Whether TEA still serves every artifact revision listed here could not be checked. Reload the page to check again.'

/** One element of TeaPublishPreview.withdrawn. */
export interface TeaWithdrawnRevision {
    teaUuid: string
    revision: number
    reason: string | null
}

/** What the page holds: the served list (null when not read), and whether the read failed. */
export interface TeaWithdrawnState {
    withdrawn: TeaWithdrawnRevision[] | null
    failed: boolean
}

/** The mark of an entry: 'Withdrawn: ' and the reason's label, its lower-case form, or 'not served on TEA'. */
export function teaWithdrawnText (reason: string | null | undefined): string {
    const label = labelOrLower(TEA_WITHDRAWN_LABELS, reason)
    return 'Withdrawn: ' + (label || 'not served on TEA')
}

function pairKey (teaUuid: string, revision: number): string {
    return teaUuid + ':' + revision
}

/** The rows of teaEntryRows, each copied with `withdrawn`: its mark, or '' when the list does not name it. */
export function teaMarkWithdrawn (rows: any[] | null | undefined, withdrawn: TeaWithdrawnRevision[] | null | undefined): any[] {
    if (!rows) return []
    const reasons = new Map<string, string | null>()
    for (const w of withdrawn || []) {
        const k = pairKey(w.teaUuid, w.revision)
        if (!reasons.has(k)) reasons.set(k, w.reason)
    }
    return rows.map(r => {
        const k = pairKey(r.teaUuid, r.revision)
        return { ...r, withdrawn: reasons.has(k) ? teaWithdrawnText(reasons.get(k)) : '' }
    })
}

/** The notice above the collections table, or null when nothing is withdrawn. Names no button and no act. */
export function teaWithdrawnNotice (collections: any[] | null | undefined, withdrawn: TeaWithdrawnRevision[] | null | undefined): string | null {
    if (!withdrawn || withdrawn.length === 0) return null
    const pairs = new Set(withdrawn.map(w => pairKey(w.teaUuid, w.revision)))
    const n = pairs.size
    let latest: any = null
    for (const c of collections || []) {
        if (c && (latest === null || c.version > latest.version)) latest = c
    }
    const listed = new Set<string>()
    for (const e of latest?.entries || []) {
        const k = pairKey(e.teaUuid, e.revision)
        if (pairs.has(k)) listed.add(k)
    }
    const m = listed.size
    let text = n === 1
        ? '1 artifact revision this release published is withdrawn: TEA no longer serves it.'
        : n + ' artifact revisions this release published are withdrawn: TEA no longer serves them.'
    if (withdrawn.every(w => w.reason === 'VDR_SNAPSHOT')) {
        text += ' A VDR snapshot stored by a release action is never served on TEA.'
    }
    if (m === 0) {
        text += ' Only earlier collection versions list ' + (n === 1 ? 'it' : 'them') + '.'
    } else {
        const x = m < n ? m + ' of them' : (m === 1 ? 'it' : 'them')
        const y = m === 1 ? 'it' : 'them'
        text += ' The latest collection version still lists ' + x + '; the next publish of this release writes a version without ' + y + '.'
    }
    return text
}

// Set once a backend rejected the document as schema drift (a backend without the field); not asked
// again until the page is loaded anew.
let driftRejected = false

/** Clears the drift memory, for the spec. */
export function resetTeaWithdrawn (): void {
    driftRejected = false
}

/** Reads TeaPublishPreview.withdrawn of a release. Never throws. */
export async function loadTeaWithdrawn (client: DriftFallbackClient, release: string): Promise<TeaWithdrawnState> {
    if (driftRejected) return { withdrawn: null, failed: false }
    try {
        const resp = await client.query({
            query: graphqlQueries.ReleaseTeaWithdrawnGql,
            variables: { release },
            fetchPolicy: 'no-cache',
        })
        const list = resp?.data?.releaseTeaPublicationView?.withdrawn
        if (!Array.isArray(list)) return { withdrawn: null, failed: true }
        return { withdrawn: list, failed: false }
    } catch (err) {
        if (isSchemaDriftError(err)) {
            driftRejected = true
            return { withdrawn: null, failed: false }
        }
        console.warn('Could not read the withdrawn TEA revisions', err)
        return { withdrawn: null, failed: true }
    }
}
