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
    FEDERATED: 'Federated identity',
    EXTERNAL: 'External'
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
 * How a server that does not keep the reason apart records a denial: in the notes, as
 * "<note> | Denied: <reason>", "<note> | Denied" without a reason, or "Denied: <reason>" when there was no note.
 * The server appends it, so split at the last such separator: a requester's note that itself ends in
 * "| Denied: ..." stays their note. null when the notes carry no denial. Read only through legacyDenial.
 */
function splitDeniedNotes (notes: string | null | undefined): { note: string, reason: string } | null {
    const m = /^(?:(.*)\s*\|\s*)?Denied(?::\s*(.*))?$/s.exec(String(notes ?? ''))
    return m ? { note: (m[1] ?? '').trimEnd(), reason: m[2] ?? '' } : null
}

/**
 * The denial split off the notes of a DENIED key whose deniedReason came back null: a key denied before the
 * server kept the reason apart, or any denial on a server that never does (CE). null for any other key. A
 * server that keeps it stores deniedReason on every denial, the empty string when no reason was given, so
 * the requester's note stays theirs. A row whose query did not select deniedReason is never split: it
 * shows the note as stored and no reason, rather than a reason the requester may have written.
 */
function legacyDenial (key: any): { note: string, reason: string } | null {
    if (key?.status !== 'DENIED' || !('deniedReason' in key) || key.deniedReason !== null) return null
    return splitDeniedNotes(key.notes)
}

/** The key's notes as written, cleaned, without the denial tail legacyDenial splits off. */
export function apiKeyNotesUncut (key: any): string {
    const denied = legacyDenial(key)
    return cleanLabelText(denied ? denied.note : key?.notes)
}

/** Why a DENIED key was denied, cleaned like label text; '' when no reason was given or the key is not DENIED. */
export function apiKeyDeniedReason (key: any): string {
    if (key?.status !== 'DENIED') return ''
    return cleanLabelText(key.deniedReason ?? legacyDenial(key)?.reason)
}

/** Label text cut to max characters (whole code points), with an ellipsis when it was longer. */
export function cutLabelText (s: string, max: number = MAX_NOTES_IN_LABEL): string {
    const chars = Array.from(s)
    return chars.length > max ? chars.slice(0, max - 1).join('') + '…' : s
}

/** The key's notes as a label shows them: cleaned, and cut to MAX_NOTES_IN_LABEL characters. */
export function apiKeyNotesForLabel (key: any): string {
    return cutLabelText(apiKeyNotesUncut(key))
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

/**
 * The label of the key with this uuid among keys (with its type word), or, when keys does not hold it,
 * its short id with missingNote after it ("Key · 86d93bbc · not in your keys"). Nothing is guessed about
 * a key that is not there: not its type, not why it is missing.
 */
export function apiKeyLabelOf (uuid: string | null | undefined, keys: any[], missingNote: string = ''): string {
    const key = (keys || []).find((k: any) => k?.uuid === uuid)
    if (key) return apiKeyLabel(key, { withType: true, siblings: keys })
    return [apiKeyLabel({ uuid }, { withType: true, siblings: keys }), missingNote].filter(p => !!p).join(' · ')
}

/**
 * A Free Form key a trust rule may act as, among the org's Free Form keys: its label without the type word
 * (they are all Free Form), and what keeps it from working as expected: inactive (token exchanges are
 * refused), no holder, or a holder who left the organization.
 */
export function actAsKeyLabel (key: any, keys: any[]): string {
    const notes = [
        key?.status === 'INACTIVE' ? 'inactive' : '',
        key?.holderLeft ? 'holder left' : (key?.holder ? '' : 'no holder')
    ]
    return [apiKeyLabel(key, { siblings: keys }), ...notes].filter(p => !!p).join(' \u00B7 ')
}

/**
 * The key a trust rule acts as, by uuid. Not among keys: its full uuid and that it is gone, but only once
 * keys has loaded (an empty list proves nothing, so then it is just the short id).
 */
export function actAsKeyLabelOf (uuid: string | null | undefined, keys: any[]): string {
    if (!uuid) return '?'
    const key = (keys || []).find((k: any) => k?.uuid === uuid)
    if (key) return actAsKeyLabel(key, keys)
    return (keys || []).length ? `${uuid} \u00B7 not among the org's Free Form keys` : apiKeyShortId({ uuid })
}
