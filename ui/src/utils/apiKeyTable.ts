/**
 * Pure layout and formatting helpers for the API key tables (org settings Programmatic Access,
 * the user's own keys on the profile page). Kept free of graphql / naive-ui so the specs can
 * pin the text and the table shape without a component.
 */

/** Client-side page size of every API key table. */
export const API_KEY_PAGE_SIZE = 8

/** The n-data-table pagination prop for an API key table (client-side: the full list is already loaded). */
export function apiKeyPagination (): { pageSize: number } {
    return { pageSize: API_KEY_PAGE_SIZE }
}

/**
 * The key tables on the Programmatic Access tab. Only the scoped table mixes key types (component,
 * instance, cluster, organization-wide, approval, registry); every other table holds a single type,
 * so a Type column there would repeat the same word on every row. The type stays readable in the
 * IDs tooltip (the API id starts with it).
 */
export type ApiKeyTableKind = 'freeForm' | 'keyRequests' | 'user' | 'scoped'

export function showsKeyType (table: ApiKeyTableKind): boolean {
    return table === 'scoped'
}

/**
 * Local date and time to the minute, e.g. 2026-10-06, 13:55, for key and secret dates. Empty for no
 * date. Built from the date parts: slicing a toLocaleString('en-CA') result to 16 characters cut the
 * last minute digit (2026-10-06, 13:5).
 */
export function fmtKeyDate (d: any): string {
    if (!d) return ''
    const t = new Date(d)
    const p = (n: number) => String(n).padStart(2, '0')
    return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())}, ${p(t.getHours())}:${p(t.getMinutes())}`
}

export function isSecretExpired (sec: any, now: number = Date.now()): boolean {
    return !!sec?.expiresDate && new Date(sec.expiresDate).getTime() <= now
}

export interface DetailLine { label: string, value: string }

/**
 * Created, last used and (when set) expires of one secret. A legacy slot 1 predates per-secret
 * dates, so it falls back to the key's own creation date.
 */
export function secretDetailLines (sec: any, row: any): DetailLine[] {
    const created = sec.createdDate || (sec.slot === 1 ? row?.createdDate : null)
    const lines: DetailLine[] = [
        { label: 'Created', value: created ? String(created).slice(0, 10) : 'n/a' },
        { label: 'Last used', value: sec.lastUsedDate ? String(sec.lastUsedDate).slice(0, 10) : 'never' }
    ]
    if (sec.expiresDate) lines.push({ label: 'Expires', value: fmtKeyDate(sec.expiresDate) })
    return lines
}

/** One-line form of secretDetailLines, e.g. created 2026-10-06 · last used never · expires 2026-10-06, 13:55. */
export function secretDetailsText (sec: any, row: any): string {
    return secretDetailLines(sec, row).map(l => `${l.label.toLowerCase()} ${l.value}`).join(' · ')
}

/**
 * The key-level dates folded into one Activity column: created, last accessed and, when known,
 * who last updated the key. Expects the rows as the org settings loader formats them
 * (createdDate / accessDate already display strings, updatedByName resolved).
 */
export function keyActivityLines (row: any): DetailLine[] {
    const lines: DetailLine[] = [
        { label: 'Created', value: row?.createdDate || 'n/a' },
        { label: 'Last accessed', value: row?.accessDate || 'Never' }
    ]
    if (row?.updatedByName) lines.push({ label: 'Updated by', value: row.updatedByName })
    return lines
}
