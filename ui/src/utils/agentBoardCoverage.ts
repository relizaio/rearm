// What no key can do on a board (board-permissions.md D13; task 5c70990d), read beside the delivery
// loop's missing capabilities: the two go in one warning on the board page.

export interface CoverageGap {
    function?: string | null
    message?: string | null
}

/** Each coverage gap as the banner shows it: the server's line, and where it is fixed. */
export function coverageLines (gaps?: CoverageGap[] | null): string[] {
    return (gaps ?? []).filter(g => g?.message).map(g => `${g.message} — grant it on the Permissions page.`)
}

/** Whether the board's warning shows: a capability or a coverage gap is missing. */
export function boardWarningShown (missingCapabilities?: string[] | null, gaps?: CoverageGap[] | null): boolean {
    return (missingCapabilities?.length ?? 0) > 0 || coverageLines(gaps).length > 0
}
