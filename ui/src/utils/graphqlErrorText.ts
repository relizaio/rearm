// The message of a failed GraphQL call as the error dialogs show it, with the server's known
// wrapper prefixes taken off. Its own module, with no imports, so pure helpers (teaPublication.ts)
// and their specs can use it without loading the GraphQL client; commonFunctions re-exports it.

export function parseGraphQLError (err: string): string {
    const knownPrefixes = ['BOM processing failed: ', 'BOM validation failed: ', 'Rebom error: ']
    let cleaned = err
    for (const prefix of knownPrefixes) {
        if (cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length)
        }
    }
    return cleaned
}

/**
 * The classification graphql-java writes on the entry it adds when a non-null field resolves to
 * null after its data fetcher refused (design TEA-30 3.7.1, D19). That entry repeats nothing the
 * user needs; the refusal is the other entry.
 */
export const NULL_PROPAGATION_CLASSIFICATION = 'NullValueInNonNullableField'

/**
 * The response's error entries of a failed call: Apollo 4's `errors`, Apollo 3's `graphQLErrors`,
 * or a network error's `result.errors`, whichever is an array first; else none.
 */
export function graphqlErrorEntries (err: any): any[] {
    const candidates = [err?.errors, err?.graphQLErrors, err?.networkError?.result?.errors]
    for (const c of candidates) {
        if (Array.isArray(c)) return c
    }
    return []
}

/**
 * The text of a failed GraphQL call: the first error entry that is not graphql-java's
 * null-propagation entry and has a message, rather than Apollo's message, which joins every entry.
 * The filter is by classification only, never by the message text; without such an entry the
 * error's own message is shown, as before. Either way only parseGraphQLError's prefixes come off.
 */
export function graphqlRefusalText (err: unknown): string {
    const e: any = err
    const entry = graphqlErrorEntries(e).find((x: any) =>
        x?.extensions?.classification !== NULL_PROPAGATION_CLASSIFICATION
        && typeof x?.message === 'string' && x.message.trim() !== '')
    const text = entry ? entry.message : String(e?.message ?? e ?? '')
    return parseGraphQLError(text)
}
