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

/** The configuration verbs: the person configures this board. */
export function canConfigure (board: WithPermissions | null | undefined): boolean {
    return boardCan(board, 'CONFIGURATION_WRITE')
}
