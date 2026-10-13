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

// the label separator, control characters and zero-width characters, each read as one space
const LABEL_NOISE = /[·\u0000-\u001F\u007F​‌‍⁠﻿]/g

/** User text made safe to sit between the label's separators: no separator, no control or zero-width characters. */
export function cleanLabelText (s: string | null | undefined): string {
    if (!s) return ''
    return String(s).replace(LABEL_NOISE, ' ').replace(/\s+/g, ' ').trim()
}

/** Notes longer than this are cut in a label; the full text stays on the key. */
export const MAX_NOTES_IN_LABEL = 48

// the server records a denial as "<note> | Denied: <reason>", or "Denied: <reason>" when there was no note
const DENIED_SUFFIX = /(\s*\|\s*)?Denied(:.*)?$/

/** The key's notes as written, cleaned, without the denial the server appends to a DENIED request. */
export function apiKeyNotesUncut (key: any): string {
    const notes = cleanLabelText(key?.notes)
    return key?.status === 'DENIED' ? notes.replace(DENIED_SUFFIX, '').trim() : notes
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
