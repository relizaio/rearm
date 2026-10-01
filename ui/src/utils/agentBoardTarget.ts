/**
 * A board's target component in the board form and header (task RD2-4): the node the board builds.
 * Kept here, tested, rather than inline in the panel: a select list is data the build does not check.
 */

export interface TargetOption { label: string, value: string, type: string }

/** The form's hint while a new board has no target, and the reason Save is disabled. */
export const TARGET_HINT = 'the node the board builds'

/**
 * The form's choices: the organization's software components, by name. A board's own BOARD_DOCUMENT
 * components are never offered (the store asks for software kinds only; one that carries its kind
 * is left out here too), nor archived ones. The board's current target is kept when the list does
 * not have it (another perspective, say), so Edit still shows it by name, not as a uuid.
 */
export function boardTargetOptions (components: any[] | null | undefined, current?: any | null): TargetOption[] {
    const opts = (components ?? [])
        .filter(c => c?.uuid && c?.status !== 'ARCHIVED' && c?.kind !== 'BOARD_DOCUMENT')
        .map(c => ({ label: String(c.name ?? c.uuid), value: String(c.uuid), type: String(c.type ?? 'COMPONENT') }))
    if (current?.uuid && !opts.some(o => o.value === current.uuid)) {
        opts.push({ label: String(current.name ?? String(current.uuid).slice(0, 8)), value: String(current.uuid), type: String(current.type ?? 'COMPONENT') })
    }
    return opts.sort((a, b) => a.label.localeCompare(b.label))
}

/** How an option reads in the list: the name, then its type in lower case. */
export function targetOptionType (option: TargetOption | null | undefined): string {
    return (option?.type ?? '').toLowerCase()
}

/** Whether Save waits on a target: a new board needs one; an existing board always has one. */
export function targetMissing (isNew: boolean, chosen: string | null | undefined): boolean {
    return isNew && !chosen
}

/**
 * The target to send on save: the chosen one for a new board, and for an existing board only when
 * it changed; undefined when there is nothing to send (never null: a board always has a target).
 */
export function targetPatch (original: any | null, chosen: string | null | undefined): string | undefined {
    if (!chosen) return undefined
    if (!original) return chosen
    return chosen === original.target ? undefined : chosen
}

/**
 * The board's target as far as it can be named (task RD2-4, T-1): the server's targetDetails; on a
 * server that does not resolve them, the component of that uuid in the loaded list; else the uuid
 * alone. Null for a board without a target.
 */
export function targetOf (board: any | null | undefined, components?: any[] | null): { uuid: string, name?: string, type?: string } | null {
    const uuid = board?.targetDetails?.uuid ?? board?.target
    if (!uuid) return null
    if (board?.targetDetails?.name) return board.targetDetails
    const known = (components ?? []).find(c => c?.uuid === uuid)
    return known?.name ? { uuid, name: known.name, type: known.type } : { uuid }
}

/**
 * The header chip: "target <name>" linking to the component in the org, the name from targetOf,
 * the uuid's first eight characters only when nothing names it; null for a board without one.
 */
export function targetChip (board: any | null | undefined, orgUuid: string, components?: any[] | null): { label: string, to: string } | null {
    const t = targetOf(board, components)
    if (!t) return null
    return { label: `target ${t.name ?? String(t.uuid).slice(0, 8)}`, to: `/componentsOfOrg/${orgUuid}/${t.uuid}` }
}
