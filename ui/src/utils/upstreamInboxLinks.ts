// Where an upstream alert of the inbox leads (ReARM Pro, TPF-185 design 3.13). The backend puts no
// link into the payload of an UPSTREAM_ALERT, so the UI builds one: the follow the alert is about,
// or, for a failing source (whose alert names no follow), the Sources tab of the organization.
// Only values that pass isUuid go into a path; the source's name and every other text of the
// payload are never used. Pure; a payload that is missing or malformed gives no links, never an
// error.
import { isUuid, upstreamSourcesAvailable } from './upstream'

export interface UpstreamInboxLinks {
    follow: string | null
    source: string | null
}

export const UPSTREAM_ALERT_EVENT_TYPE = 'UPSTREAM_ALERT'

const NONE: UpstreamInboxLinks = Object.freeze({ follow: null, source: null }) as UpstreamInboxLinks

function payloadOf (row: any): Record<string, any> | null {
    const raw = row?.payloadJson
    if (!raw) return null
    if (typeof raw === 'object') return raw
    if (typeof raw !== 'string') return null
    try {
        const p = JSON.parse(raw)
        return p && typeof p === 'object' && !Array.isArray(p) ? p : null
    } catch {
        return null
    }
}

/** The links of an inbox row: the follow and the source of an upstream alert, none for anything else. */
export function upstreamInboxLinksOf (row: any, installationType: string | undefined | null): UpstreamInboxLinks {
    if (!upstreamSourcesAvailable(installationType)) return { ...NONE }
    if (row?.eventType !== UPSTREAM_ALERT_EVENT_TYPE) return { ...NONE }
    const p = payloadOf(row)
    if (!p) return { ...NONE }
    const org = p.org
    if (!isUuid(org)) return { ...NONE }
    const followMissing = p.follow === null || p.follow === undefined || p.follow === ''
    const follow = isUuid(p.follow) ? `/upstreamFollow/${org}/${p.follow}` : null
    const source = p.kind === 'SOURCE_FAILING' || (followMissing && isUuid(p.source))
        ? `/orgSettings/${org}?tab=upstreamSources`
        : null
    return { follow, source }
}
