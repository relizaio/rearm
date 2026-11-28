/**
 * A board's target in the board form and header (task RD2-4): the node the board builds, a
 * component or a product (task TPF-274).
 * Kept here, tested, rather than inline in the panel: a select list is data the build does not check.
 */

import { isUuid } from './upstream'

/** An option of the target select; unlisted: a uuid typed or pasted that no listed item holds. */
export interface TargetOption { label: string, value: string, type: string, unlisted?: boolean }

/** How an unlisted uuid reads in the list: the server judges it when the board is saved. */
export const UNLISTED_TARGET_TYPE = 'not in the list, checked on save'

/** The form's hint while a new board has no target, and the reason Save is disabled. */
export const TARGET_HINT = 'the node the board builds'

/** A text as a target uuid: trimmed and lower-cased when it is a well-formed uuid, else null. */
function enteredUuid (text: unknown): string | null {
    if (typeof text !== 'string') return null
    const t = text.trim().toLowerCase()
    return isUuid(t) ? t : null
}

/**
 * The form's choices: the organization's software components and its products, by name, then by
 * type (a component before a product of the same name). A board's own BOARD_DOCUMENT components
 * are never offered (the store asks for software kinds only; one that carries its kind is left out
 * here too), nor archived items. The board's current target is kept when the list does not have it
 * (another perspective, say), so Edit still shows it by name, not as a uuid. Each entered text (the
 * typed pattern, the chosen value) that is a well-formed uuid no option holds adds one unlisted
 * option, so it can be chosen and sent; the server accepts or refuses it on save.
 */
export function boardTargetOptions (items: any[] | null | undefined, current?: any | null,
    entered?: Array<string | null | undefined>): TargetOption[] {
    const opts: TargetOption[] = (items ?? [])
        .filter(c => c?.uuid && c?.status !== 'ARCHIVED' && c?.kind !== 'BOARD_DOCUMENT')
        .map(c => ({ label: String(c.name ?? c.uuid), value: String(c.uuid), type: String(c.type ?? 'COMPONENT') }))
    if (current?.uuid && !opts.some(o => o.value === current.uuid)) {
        opts.push({ label: String(current.name ?? String(current.uuid).slice(0, 8)), value: String(current.uuid), type: String(current.type ?? 'COMPONENT') })
    }
    for (const text of entered ?? []) {
        const uuid = enteredUuid(text)
        if (uuid && !opts.some(o => o.value === uuid)) opts.push({ label: uuid, value: uuid, type: '', unlisted: true })
    }
    return opts.sort((a, b) => a.label.localeCompare(b.label) || a.type.localeCompare(b.type))
}

/**
 * The select's filter: a well-formed uuid (any case, spaces around it) matches only the option of
 * that uuid; any other text matches the names that contain it, case-insensitive, as the select's
 * own filter did. An empty pattern keeps every option.
 */
export function targetFilter (pattern: string, option: TargetOption | any): boolean {
    const p = String(pattern ?? '').trim().toLowerCase()
    if (!p) return true
    if (isUuid(p)) return option?.value === p
    return String(option?.label ?? '').toLowerCase().includes(p)
}

/** How an option reads in the list: the name, then its type in lower case. */
export function targetOptionType (option: TargetOption | null | undefined): string {
    if (option?.unlisted) return UNLISTED_TARGET_TYPE
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
 * server that does not resolve them, the component or product of that uuid in the loaded list; else
 * the uuid alone. Null for a board without a target.
 */
export function targetOf (board: any | null | undefined, items?: any[] | null): { uuid: string, name?: string, type?: string } | null {
    const uuid = board?.targetDetails?.uuid ?? board?.target
    if (!uuid) return null
    if (board?.targetDetails?.name) return board.targetDetails
    const known = (items ?? []).find(c => c?.uuid === uuid)
    return known?.name ? { uuid, name: known.name, type: known.type } : { uuid }
}

/**
 * The header chip: "target <name>" linking to the target in the org (the Products page for a
 * product, else the Components page), the name and type from targetOf, the uuid's first eight
 * characters only when nothing names it; null for a board without one.
 */
export function targetChip (board: any | null | undefined, orgUuid: string, items?: any[] | null): { label: string, to: string } | null {
    const t = targetOf(board, items)
    if (!t) return null
    const page = t.type === 'PRODUCT' ? '/productsOfOrg/' : '/componentsOfOrg/'
    return { label: `target ${t.name ?? String(t.uuid).slice(0, 8)}`, to: page + orgUuid + '/' + t.uuid }
}
