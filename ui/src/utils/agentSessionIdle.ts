// How a session's close and idle state read on its page, and the org's idle-close window
// (task 6e7fe6fe). Pure, so the specs need no store.
import { actorLabel, AgentActor } from './agentActors'

/** The org's idle-close window in hours: the server's default and bounds (OrganizationData.Settings). */
export const IDLE_CLOSE_HOURS_DEFAULT = 24
export const IDLE_CLOSE_HOURS_MIN = 1
export const IDLE_CLOSE_HOURS_MAX = 720

/** The window the form shows: the org's setting, else the default the server applies. */
export function idleCloseHoursOf (settings: any): number {
    const h = settings?.agentSessionIdleCloseHours
    return typeof h === 'number' ? h : IDLE_CLOSE_HOURS_DEFAULT
}

/** Who closed the session and why, or '' for an open one or a row closed before this was recorded. */
export function closeAttribution (session: any): string {
    if (session?.status !== 'CLOSED') return ''
    const who = actorLabel(session.closedBy as AgentActor | null)
    const why = (session.closeReason ?? '').trim()
    if (who && why) return `${who} — ${why}`
    return who || why
}

/**
 * Whether the page warns that the sweep is about to close the session: it was warned and has
 * made no call since (any activity clears idleWarnedAt server-side).
 */
export function isIdleWarned (session: any): boolean {
    return session?.status === 'OPEN' && !!session?.idleWarnedAt
}

/** The force-close reason to send: trimmed, or null when the admin gave none. */
export function forceCloseReason (reason: string | null | undefined): string | null {
    const r = (reason ?? '').trim()
    return r || null
}
