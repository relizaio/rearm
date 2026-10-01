// The permission editors and scope BOARD (board-permissions.md §5; task 428b4a71).
//
// Pure, so what each section offers and what a loaded key or user keeps are testable without
// mounting the editor.

import constants from './constants'

/**
 * The object scopes the editors load into ScopedPermissions and save back. A loader that left a
 * scope out would drop its grants on the next save, since a save replaces the whole set.
 */
export const EDITOR_SCOPES = ['PERSPECTIVE', 'COMPONENT', 'INSTANCE', 'BOARD']

/** Whether an editor keeps a grant of this scope when it loads a key, a user or a team. */
export function editorKeepsScope (scope?: string | null): boolean {
    return !!scope && EDITOR_SCOPES.includes(scope)
}

/** A board's name for a grant on it, or null when the editor's list does not have it. */
export function boardNameOf (boards: { uuid: string, name?: string | null }[] | null | undefined,
    uuid: string): string | null {
    return (boards ?? []).find(b => b.uuid === uuid)?.name ?? null
}

/**
 * What a board card offers: the board functions and the configuration pair, narrowed to the
 * functions the editor allows (a personal key is capped by its owner's).
 */
export function boardScopeFunctions (allowed?: string[] | null): string[] {
    return constants.BoardScopeFunctions.filter(f => !allowed || allowed.includes(f))
}

/** Whether a function is a board function: never offered on a product or a component. */
export function isBoardFunction (f: string): boolean {
    return constants.BoardFunctions.includes(f)
}
