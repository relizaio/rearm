// Pure logic for the team-assignment rule match preview. Extracted from the SFC
// so the one thing most likely to drift silently -- anchoring parity with the
// backend -- is unit-tested.
//
// The backend matches with Pattern.matcher(name).matches(), i.e. FULLY ANCHORED.
// Anchoring here too is what keeps the preview honest; without it the preview
// would over-report (a substring match would look like a hit).

export interface PreviewResult {
    // false only when the pattern cannot be compiled by the JS engine. Note the
    // Java engine accepts constructs JS does not (possessive quantifiers,
    // atomic groups, inline flags), so this is "cannot preview", NOT "invalid" --
    // callers must not block saving on it.
    previewable: boolean
    error: string
    total: number
    names: string[]
    more: number
}

export interface PreviewComponent { name?: string, type?: string }

export const PREVIEW_LIMIT = 8

export function previewMatches (
    pattern: string,
    componentType: string,
    components: PreviewComponent[],
    limit: number = PREVIEW_LIMIT,
): PreviewResult {
    if (!pattern) return { previewable: true, error: '', total: 0, names: [], more: 0 }
    let re: RegExp
    try {
        re = new RegExp(`^(?:${pattern})$`)
    } catch (e: any) {
        return {
            previewable: false,
            error: e?.message || 'Cannot preview this pattern in the browser',
            total: 0, names: [], more: 0
        }
    }
    const matched = (components || [])
        .filter(c => componentType === 'ANY' || !componentType || c.type === componentType)
        .filter(c => re.test(c.name || ''))
        .map(c => c.name as string)
    return {
        previewable: true,
        error: '',
        total: matched.length,
        names: matched.slice(0, limit),
        more: Math.max(0, matched.length - limit)
    }
}
