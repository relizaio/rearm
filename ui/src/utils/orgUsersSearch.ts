import { normalizeSearchTerm, textMatches, uuidMatches } from '@/utils/tableSearch'

/**
 * TPF-85: the Org settings Users tab search. One box filters the active and the inactive users
 * tables by uuid (exact or a prefix of at least 4 hex-and-dash characters) and by name and email
 * (case-insensitive substring). The generic pieces are tableSearch.ts.
 */
export interface SearchableUser { uuid?: string | null; name?: string | null; email?: string | null }

/** Does this user match the (already normalized) term? '' matches every row. */
export function userMatchesSearch (user: SearchableUser, term: string): boolean {
    if (!term) return true
    return uuidMatches(user.uuid, term) || textMatches([user.name, user.email], term)
}

/** The users matching raw, as a new array in input order; every row for an empty or blank raw. */
export function filterUsersBySearch<T extends SearchableUser> (users: T[], raw: string | null | undefined): T[] {
    const term = normalizeSearchTerm(raw)
    return users.filter((u) => userMatchesSearch(u, term))
}
