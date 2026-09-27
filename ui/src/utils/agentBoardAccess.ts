// What the person may do on a board (board-permissions.md §4.6): the board's myPermissions, served
// per caller. The UI hides a control the server would refuse -- hidden, not disabled.
//
// BOARD_WRITE: register, authorize, order, hold, release, escalate, decide, answer, reopen, human
// sign-off, strength and budget, lock. CONFIGURATION_WRITE: board edit, roles, reseed, apply.

export type BoardFunction = 'BOARD_READ' | 'BOARD_AGENT' | 'BOARD_WRITE' | 'CONFIGURATION_READ' | 'CONFIGURATION_WRITE'

interface WithPermissions { myPermissions?: string[] | null }

/** Whether the person holds this function on the board. No board, or none served, is nothing held. */
export function boardCan (board: WithPermissions | null | undefined, fn: BoardFunction): boolean {
    return !!board?.myPermissions?.includes(fn)
}

/** The operator verbs: the person runs this board. */
export function canOperate (board: WithPermissions | null | undefined): boolean {
    return boardCan(board, 'BOARD_WRITE')
}

/**
 * Reading the board as configuration -- its spec (RD2-6): CONFIGURATION_READ, or CONFIGURATION_WRITE,
 * which reads what it writes. A null myPermissions (some key-authenticated reads) is nothing held.
 */
export function canConfigureRead (board: WithPermissions | null | undefined): boolean {
    return boardCan(board, 'CONFIGURATION_READ') || boardCan(board, 'CONFIGURATION_WRITE')
}

/** The configuration verbs: the person configures this board. */
export function canConfigure (board: WithPermissions | null | undefined): boolean {
    return boardCan(board, 'CONFIGURATION_WRITE')
}

/**
 * The spec modal's message when the read is refused (RD2-6): the server's words, without the GraphQL
 * prefix; a bare "Not authorized" says which function the read needs.
 */
export function specRefusal (e: any): string {
    const raw = String(e?.message ?? e ?? '').replace(/^GraphQL error:\s*/, '').trim()
    if (!raw || /^not authori[sz]ed\.?$/i.test(raw)) return 'Needs Configuration read on this board to show it as a spec.'
    return raw
}
