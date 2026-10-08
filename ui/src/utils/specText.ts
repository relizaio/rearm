// Text helpers for the wiring specs, which read component source as text and assert on it.
// Not imported by the app.

/**
 * Removes every match, repeating until nothing changes. One pass leaves a comment behind when
 * removing another one forms it (`<!<!-- x -->-- y -->`), which is what CodeQL's
 * js/incomplete-multi-character-sanitization flags.
 */
function removeAll (text: string, pattern: RegExp): string {
    let previous: string
    do {
        previous = text
        text = text.replace(pattern, '')
    } while (text !== previous)
    return text
}

/** The text without its HTML comments. */
export function withoutHtmlComments (text: string): string {
    return removeAll(text, /<!--[\s\S]*?-->/g)
}

/** The text without HTML, block and line comments. A `//` right after `:` is a URL and stays. */
export function withoutComments (text: string): string {
    return removeAll(withoutHtmlComments(text), /\/\*[\s\S]*?\*\//g)
        .replace(/(^|[^:])\/\/.*$/gm, '$1')
}

/** Every RegExp metacharacter escaped, so the text matches only itself inside a pattern. */
export function escapeRegExp (text: string): string {
    return text.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
}
