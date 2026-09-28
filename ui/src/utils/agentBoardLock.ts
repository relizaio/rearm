// Locking and unlocking a board by hand (task RD2-17, sweep UI-37): an in-page form and confirm instead of
// window.prompt and an unconfirmed click. Pure, so the rules are testable without the board page.
import { actorLabel } from './agentActors'

/** What agents are told while the board is locked, beside the reason field. */
export const LOCK_NOTE = 'Agents see this reason and stop taking new work.'

/** The lock's reason, trimmed, or null while there is none: the form sends nothing without one. */
export function lockReasonToSend (reason: string | null | undefined): string | null {
    const r = (reason ?? '').trim()
    return r ? r : null
}

/** "Unlock the board? Locked by pm@example.com 2026-09-27 10:00: release freeze". */
export function unlockConfirmText (board: any, when: (at: string) => string): string {
    const l = board?.lock
    const by = actorLabel(l?.lockedBy)
    const parts = ['Unlock the board?']
    if (by || l?.lockedAt) parts.push(['Locked', by ? `by ${by}` : '', l?.lockedAt ? when(l.lockedAt) : ''].filter(Boolean).join(' ')
        + (l?.reason ? `: ${l.reason}` : ''))
    else if (l?.reason) parts.push(`Locked: ${l.reason}`)
    return parts.join(' ')
}
