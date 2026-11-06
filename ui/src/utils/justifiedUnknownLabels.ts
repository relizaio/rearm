// The FDA-facing words for the justified-unknown codes (FDAW-14 3.11). One map, used by the
// bulk dialog, the per-component editor and the list row. The addendum prints the code name,
// not these labels, so the document matches the server's line for line.

export type JustifiedUnknownReason = 'NO_COMMITTED_END_OF_SUPPORT' | 'COMMUNITY_PROJECT'
    | 'SUPPLIER_PUBLISHES_NO_DATE' | 'DISTRIBUTION_PUBLISHES_NO_HASH' | 'VENDORED_SOURCE_NO_HASH' | 'OTHER'

export const JUSTIFIED_UNKNOWN_LABELS: Record<JustifiedUnknownReason, string> = {
    NO_COMMITTED_END_OF_SUPPORT: 'No committed end of support',
    COMMUNITY_PROJECT: 'Community project, no support commitment',
    SUPPLIER_PUBLISHES_NO_DATE: 'Supplier publishes no end-of-support date',
    DISTRIBUTION_PUBLISHES_NO_HASH: 'Distribution publishes no per-package hash',
    VENDORED_SOURCE_NO_HASH: 'Vendored source, no distributed artifact to hash',
    OTHER: 'Other (explain)'
}

/** The codes the server accepts for an end-of-support unknown (JustifiedUnknownField.END_OF_SUPPORT). */
export const END_OF_SUPPORT_CODES: JustifiedUnknownReason[] =
    ['NO_COMMITTED_END_OF_SUPPORT', 'COMMUNITY_PROJECT', 'SUPPLIER_PUBLISHES_NO_DATE', 'OTHER']

/** The codes the server accepts for a hash justification (JustifiedUnknownField.HASH). */
export const HASH_CODES: JustifiedUnknownReason[] =
    ['DISTRIBUTION_PUBLISHES_NO_HASH', 'VENDORED_SOURCE_NO_HASH', 'OTHER']

/** The server's bound on an unknown's text. */
export const JUSTIFIED_UNKNOWN_TEXT_MAX = 512

/** The label of a code; the raw code for one this build does not know. */
export function justifiedUnknownLabel (code: string | null | undefined): string {
    if (!code) return ''
    return (JUSTIFIED_UNKNOWN_LABELS as Record<string, string>)[code] ?? code
}

export function justifiedUnknownOptions (codes: JustifiedUnknownReason[]): Array<{ label: string, value: string }> {
    return codes.map(c => ({ label: JUSTIFIED_UNKNOWN_LABELS[c], value: c }))
}

/**
 * Problems with an unknown about to be sent, phrased for the operator: OTHER needs a text, and
 * the text is bounded as the server bounds it. Empty when it can be sent.
 */
export function validateJustifiedUnknown (code: string | null | undefined, text: string | null | undefined): string[] {
    const out: string[] = []
    const t = (text ?? '').trim()
    if (code === 'OTHER' && !t) out.push('The code Other needs a text explaining why the information cannot be given.')
    // eslint-disable-next-line no-control-regex
    if (t.length > JUSTIFIED_UNKNOWN_TEXT_MAX || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(t)) {
        out.push(`The text is longer than ${JUSTIFIED_UNKNOWN_TEXT_MAX} characters or holds a control character.`)
    }
    return out
}
