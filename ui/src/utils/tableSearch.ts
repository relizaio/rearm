/**
 * Generic pieces of a table search box (TPF-88's contract, shared with TPF-85): one whole term,
 * trimmed and lower-cased; a uuid matches by prefix only once the term looks like one; text
 * fields match by case-insensitive substring. Pure, no Vue or naive-ui import.
 */

/** The shortest term that is tried as a uuid prefix: 3 hex characters match a sixteenth of any big list. */
export const UUID_PREFIX_MIN = 4

const UUID_PREFIX = /^[0-9a-f-]{4,}$/

/** The term as matched: trimmed and lower-cased; '' means no filter. */
export function normalizeSearchTerm (raw: string | null | undefined): string {
    return String(raw ?? '').trim().toLowerCase()
}

/** hex digits and dashes only, at least UUID_PREFIX_MIN characters */
export function looksLikeUuidPrefix (term: string): boolean {
    return UUID_PREFIX.test(term)
}

/** exact or prefix match of a uuid, case-insensitive, only for a term looksLikeUuidPrefix accepts */
export function uuidMatches (uuid: string | null | undefined, term: string): boolean {
    return looksLikeUuidPrefix(term) && String(uuid || '').toLowerCase().startsWith(term)
}

/** case-insensitive substring over the given texts (null and undefined read as '') */
export function textMatches (texts: Array<string | null | undefined>, term: string): boolean {
    return texts.some((t) => String(t ?? '').toLowerCase().includes(term))
}
