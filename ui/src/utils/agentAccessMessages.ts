// Access refusals that say so (task RD2-9): a person the server refuses is told why, not shown an empty
// state that reads as "nothing here" or a spinner that never stops. Pure, so each wording is testable.

/** Whether a server error is a permission refusal. */
export function isNotAuthorized (e: any): boolean {
    return /not authori[sz]ed|access denied|forbidden/i.test(String(e?.message ?? e ?? ''))
}

function messageOf (e: any): string {
    return String(e?.message ?? e ?? '').replace(/^GraphQL error:\s*/, '').trim()
}

/**
 * The boards tab with no board listed. The list is filtered by what the person may read, so only an
 * org admin -- who reads every board -- knows the organization has none; anyone else is told what
 * they would need.
 */
export function noBoardsText (isAdmin: boolean): string {
    return isAdmin
        ? 'No boards yet. A board wires tracker repos to a role pipeline governed by its coordinator.'
        : 'No boards you can see. Ask an org admin for Board read on a board.'
}

/** A board link to a board the list does not hold: one the person cannot read, or one that is gone. */
export function hiddenBoardText (uuid: string): string {
    return `You don't have access to this board (needs Board read): ${uuid}`
}

/** The task page's load error: a refusal says what it needs; anything else keeps the server's words. */
export function taskLoadErrorText (e: any): string {
    if (isNotAuthorized(e)) return "You don't have access to this task's board (needs Board read)"
    return `Could not load the task: ${messageOf(e)}`
}

/** The session page's load error, in place of a spinner that would never stop. */
export function sessionLoadErrorText (e: any): string {
    if (isNotAuthorized(e)) return 'Not authorized'
    const m = messageOf(e)
    if (!m || /not found/i.test(m)) return 'Session not found'
    return `Could not load the session: ${m}`
}
