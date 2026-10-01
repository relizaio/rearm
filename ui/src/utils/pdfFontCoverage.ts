// What the bundled PDF font can actually draw.
//
// Shared by every PDF this app generates over addendumData -- the addendum and the Device
// Support Statement -- because the failure it prevents is a property of the FONT, not of any
// one document.

/**
 * The codepoint ranges the bundled Roboto can actually draw.
 *
 * pdfmake ships ROBOTO ONLY. A character it has no glyph for is not flagged, substituted or
 * warned about -- it is silently DROPPED, so a component named in Japanese produces an EMPTY
 * cell in a regulatory document while the CSV for the same release shows the text. Blank is
 * the one thing this document must never be ambiguous about.
 *
 * Determined by RENDERING and extracting, not by reading a spec: Latin (including Extended-A,
 * Turkish and Vietnamese), Greek, Cyrillic, punctuation and currency draw; Latin Extended-B,
 * arrows, emoji, Hebrew, Arabic, Hangul, Thai, Devanagari and CJK do not.
 *
 * DELIBERATELY CONSERVATIVE. It is a whitelist, so an unlisted script is refused rather than
 * rendered blank, and it will refuse some characters Roboto could in fact draw. That trade is
 * the point: a false refusal is visible, explained and recoverable via the CSV, while a false
 * render is an invisible hole in a document someone files.
 */
const RENDERABLE = [
    [0x09, 0x0a], [0x0d, 0x0d],
    [0x20, 0x17f],      // Basic Latin, Latin-1 Supplement, Latin Extended-A
    [0x370, 0x3ff],     // Greek
    [0x400, 0x4ff],     // Cyrillic
    [0x1e00, 0x1eff],   // Latin Extended Additional (Vietnamese)
    [0x2010, 0x201f],   // dashes and quotation marks
    [0x2020, 0x2027], [0x2030, 0x203a],
    [0x20a0, 0x20bf],   // currency
    [0x2122, 0x2122]    // trade mark
]

export function isRenderable (code: number): boolean {
    return RENDERABLE.some(([lo, hi]) => code >= lo && code <= hi)
}

/**
 * The distinct characters in these strings that the font cannot draw, in encounter order.
 *
 * Returned rather than thrown so each caller can refuse in its own voice, naming what its
 * own document would have lost.
 */
export function unrenderableCharacters (texts: Array<string | null | undefined>): string[] {
    const offenders = new Set<string>()
    for (const t of texts) {
        if (null === t || undefined === t) continue
        for (const ch of String(t)) {
            if (!isRenderable(ch.codePointAt(0) as number)) offenders.add(ch)
        }
    }
    return [...offenders]
}

/**
 * The refusal message, or null when everything is drawable.
 *
 * @param texts every string the document would print
 * @param documentName named in the message, so an operator generating two different PDFs
 *        knows which one refused
 */
export function fontCoverageRefusal (
    texts: Array<string | null | undefined>,
    documentName: string
): string | null {
    const offenders = unrenderableCharacters(texts)
    if (!offenders.length) return null
    return `This release contains characters the PDF font cannot draw (${offenders.slice(0, 12).join(' ')}).`
        + ` They would be silently dropped, leaving blank sections in a ${documentName} that is`
        + ' meant to be complete. Export the CSV addendum instead -- it carries every character.'
}
