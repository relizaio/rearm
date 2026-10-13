// API keys told apart (task t20261012-112845-31586). Screens that list several keys named them by type,
// notes or raw id, so two Free Form keys without notes, or two personal keys, read the same. A key now
// reads "<declared name, else notes, else type word> · <uuid8>". Pure: strings only, never markup.

/** The word a person reads for each ApiTypeEnum value. */
export const API_KEY_TYPE_WORDS: Record<string, string> = {
    USER: 'Personal',
    FREEFORM: 'Free Form',
    ORGANIZATION_RW: 'Org-wide read/write',
    ORGANIZATION: 'Org-wide read',
    COMPONENT: 'Component',
    INSTANCE: 'Instance',
    CLUSTER: 'Cluster',
    APPROVAL: 'Approval',
    FEDERATED: 'Federated identity'
}

/** "Free Form" for FREEFORM; the raw type when it has no word; "Key" when there is no type. */
export function apiKeyTypeWord (type: string | null | undefined): string {
    if (!type) return 'Key'
    return API_KEY_TYPE_WORDS[type] || type
}

// read as one space in a label: the label separator, C0 and C1 control characters, zero-width spaces and
// joiners that hide text, and the bidi controls that would let a note reorder the short id after it.
// ZWNJ and ZWJ (U+200C, U+200D) stay: scripts and emoji sequences need them and they reorder nothing.
const LABEL_NOISE = /[\u00B7\u0000-\u001F\u007F-\u009F\u200B\u200E\u200F\u202A-\u202E\u2060\u2066-\u2069\uFEFF]/g

/** User text made safe to sit between the label's separators: no separator, no control, zero-width or bidi characters. */
export function cleanLabelText (s: string | null | undefined): string {
    if (!s) return ''
    return String(s).replace(LABEL_NOISE, ' ').replace(/\s+/g, ' ').trim()
}

/** Notes longer than this are cut in a label; the full text stays on the key. */
export const MAX_NOTES_IN_LABEL = 48

/**
 * The server records a denial in the notes as "<note> | Denied: <reason>", "<note> | Denied" without a reason,
 * or "Denied: <reason>" when there was no note (ApiKeyService). Split at the first such separator, so a note
 * that itself says "Denied:" stays whole. null when the notes carry no denial.
 */
export function splitDeniedNotes (notes: string | null | undefined): { note: string, reason: string } | null {
    const m = /^(?:(.*?)\s*\|\s*)?Denied(?::\s*(.*))?$/s.exec(String(notes ?? ''))
    return m ? { note: m[1] ?? '', reason: m[2] ?? '' } : null
}

/** The key's notes as written, cleaned, without the denial the server appends to a DENIED request. */
export function apiKeyNotesUncut (key: any): string {
    const denied = key?.status === 'DENIED' ? splitDeniedNotes(key?.notes) : null
    return cleanLabelText(denied ? denied.note : key?.notes)
}

/** The key's notes as a label shows them: cleaned, and cut to MAX_NOTES_IN_LABEL characters. */
export function apiKeyNotesForLabel (key: any): string {
    const chars = Array.from(apiKeyNotesUncut(key))
    return chars.length > MAX_NOTES_IN_LABEL ? chars.slice(0, MAX_NOTES_IN_LABEL - 1).join('') + '…' : chars.join('')
}

export type ApiKeyNameKind = 'declared' | 'notes' | 'type'

/** What names the key: its declared name, else its notes, else its type word. */
export function apiKeyName (key: any): { text: string, kind: ApiKeyNameKind } {
    const declared = cleanLabelText(key?.declaredName)
    if (declared) return { text: declared, kind: 'declared' }
    const notes = apiKeyNotesForLabel(key)
    if (notes) return { text: notes, kind: 'notes' }
    return { text: apiKeyTypeWord(key?.type), kind: 'type' }
}

/** The first 8 characters of the key's uuid, longer when another key among siblings starts the same. */
export function apiKeyShortId (key: any, siblings?: any[]): string {
    const uuid = String(key?.uuid || '')
    let n = Math.min(8, uuid.length)
    const clash = (len: number) => (siblings || []).some((o: any) => !!o?.uuid && o.uuid !== uuid && String(o.uuid).startsWith(uuid.slice(0, len)))
    while (n < uuid.length && clash(n)) n++
    return uuid.slice(0, n)
}

export interface ApiKeyLabelOptions {
    /** lead with the type word (never twice: not when the type word already names the key) */
    withType?: boolean
    /** the keys shown alongside, so the short id stays unique among them */
    siblings?: any[]
}

/** "Free Form · CI pipeline · 77781336": the string form, for dialog titles and option labels. */
export function apiKeyLabel (key: any, opts: ApiKeyLabelOptions = {}): string {
    const name = apiKeyName(key)
    const parts = [opts.withType && name.kind !== 'type' ? apiKeyTypeWord(key?.type) : '', name.text, apiKeyShortId(key, opts.siblings)]
    return parts.filter(p => !!p).join(' · ')
}
