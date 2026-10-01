// Pausing and resuming a board by hand (task RD2-17, sweep UI-37): an in-page form and confirm instead of
// window.prompt and an unconfirmed click. Pure, so the rules are testable without the board page.
import { actorLabel } from './agentActors'

/** What agents are told while the board is paused, beside the reason field. */
export const PAUSE_NOTE = 'Agents see this reason and stop taking new work.'

/** The pause's reason, trimmed, or null while there is none: the form sends nothing without one. */
export function pauseReasonToSend (reason: string | null | undefined): string | null {
    const r = (reason ?? '').trim()
    return r ? r : null
}

/** "Resume the board? Paused by pm@example.com 2026-09-27 10:00: release freeze". */
export function resumeConfirmText (board: any, when: (at: string) => string): string {
    const l = board?.pause
    const by = actorLabel(l?.pausedBy)
    const parts = ['Resume the board?']
    if (by || l?.pausedAt) parts.push(['Paused', by ? `by ${by}` : '', l?.pausedAt ? when(l.pausedAt) : ''].filter(Boolean).join(' ')
        + (l?.reason ? `: ${l.reason}` : ''))
    else if (l?.reason) parts.push(`Paused: ${l.reason}`)
    return parts.join(' ')
}
