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
