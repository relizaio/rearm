// The TEI rule of the one-off ingest (ReARM Pro, TPF-185 design 3.10), checked in the browser so
// that a text that cannot be a TEI sends no request. It is the backend's rule: 1 to 2048
// characters, every one printable ASCII without spaces (0x21 to 0x7E), starting with tei://. The
// text is trimmed first. The check reads the characters only: nothing is resolved, parsed as a URL
// or fetched here, and the TEI is never put into a route.
import { oneOffFailureSentence } from './upstreamLabels'

export const TEI_PREFIX = 'tei://'
export const MAX_TEI_LENGTH = 2048

/** The sentence on failure is the backend's own for INVALID_TEI. */
export const teiRuleSentence: string = oneOffFailureSentence.INVALID_TEI

/** The TEI as it is sent: the text without white space at either end. */
export function teiOf (text: string | null | undefined): string {
    return (text ?? '').trim()
}

/** Null when the text is a usable TEI, otherwise the sentence of the rule. */
export function teiProblem (text: string | null | undefined): string | null {
    const tei = teiOf(text)
    if (tei.length < 1 || tei.length > MAX_TEI_LENGTH) return teiRuleSentence
    for (let i = 0; i < tei.length; i++) {
        const c = tei.charCodeAt(i)
        if (c < 0x21 || c > 0x7E) return teiRuleSentence
    }
    return tei.startsWith(TEI_PREFIX) ? null : teiRuleSentence
}
