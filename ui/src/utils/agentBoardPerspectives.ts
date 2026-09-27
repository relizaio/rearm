/**
 * A board's perspectives in the board form and panel (board-permissions.md §3, task b9115d09).
 * Kept here, tested, rather than inline in the panel: a select list is data the build does not check.
 */

export interface PerspectiveOption { label: string, value: string }

/**
 * The form's choices: the organization's perspectives and its PRODUCT components used as
 * perspectives, a product marked as the board file marks it. Archived ones are left out; sorted by name.
 */
export function boardPerspectiveOptions (perspectives: any[] | null | undefined): PerspectiveOption[] {
    return (perspectives ?? [])
        .filter(p => p?.uuid && p?.status !== 'ARCHIVED')
        .map(p => ({ label: p.type === 'PRODUCT' ? `product:${p.name}` : String(p.name), value: String(p.uuid) }))
        .sort((a, b) => a.label.localeCompare(b.label))
}

/**
 * The perspectives to send on save: the chosen list when it differs from the board's (a new board
 * sends it when not empty); undefined when nothing changed, so an unrelated edit asks no consent.
 */
export function perspectivesPatch (original: any | null, chosen: string[] | null | undefined): string[] | undefined {
    const next = [...(chosen ?? [])]
    if (!original) return next.length ? next : undefined
    const before = [...(original.perspectives ?? [])]
    const same = before.length === next.length && before.every((p: string) => next.includes(p))
    return same ? undefined : next
}

/** The panel's chips: the board's perspectives as the file names them. */
export function perspectiveChips (board: any | null | undefined): string[] {
    return [...(board?.perspectiveNames ?? [])]
}
