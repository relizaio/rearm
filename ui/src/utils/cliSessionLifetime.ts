/**
 * A key's device-login session limit (task RD3-7): sessionMaxMinutes on a personal or Free Form key
 * gives every CLI browser-login session approved on it a hard end. The approver may shorten a
 * session below the key's limit, never lengthen it. The rules mirror the server's
 * (CliSessionCodes), so the form refuses what the server would.
 */

/** The 90-day cap in minutes: the most a key may allow. */
export const MAX_SESSION_MINUTES = 129600

export const SESSION_LIMIT_HELP = 'Bounds device-login sessions approved on this key: a session ends this many minutes after it is approved, refreshes included. '
    + 'Direct use of the key\'s secret is bounded by the secret\'s expiry instead. A change applies to sessions approved afterwards, not to open ones.'

function isWhole (v: number): boolean {
    return Number.isInteger(v)
}

/** Why a key's limit cannot be saved, or '' when it can (empty means no limit). */
export function sessionMinutesProblem (minutes: number | null | undefined): string {
    if (minutes === null || minutes === undefined) return ''
    if (!isWhole(minutes) || minutes < 1 || minutes > MAX_SESSION_MINUTES) {
        return `sessionMaxMinutes is 1 to ${MAX_SESSION_MINUTES} minutes, or empty for no bound`
    }
    return ''
}

/** Why the approver's lifetime for this session cannot be used, or '' when it can (empty means the key's limit). */
export function approvalLifetimeProblem (keyLimit: number | null | undefined, minutes: number | null | undefined): string {
    if (minutes === null || minutes === undefined) return ''
    if (!isWhole(minutes) || minutes < 1 || minutes > MAX_SESSION_MINUTES) return `A session lasts 1 to ${MAX_SESSION_MINUTES} minutes`
    if (keyLimit !== null && keyLimit !== undefined && minutes > keyLimit) {
        return `This key bounds its sessions to ${keyLimit} minutes; choose ${keyLimit} or fewer`
    }
    return ''
}

/** The minutes the session gets: the approver's shorter choice, else the key's limit; null when nothing bounds it. */
export function sessionMinutes (keyLimit: number | null | undefined, minutes: number | null | undefined): number | null {
    if (minutes !== null && minutes !== undefined) return minutes
    return keyLimit ?? null
}

/** The session's hard end if approved at {@code approvedAt}; null when nothing but the 90-day cap bounds it. */
export function sessionEnd (approvedAt: Date, keyLimit: number | null | undefined, minutes: number | null | undefined): Date | null {
    const m = sessionMinutes(keyLimit, minutes)
    return m === null ? null : new Date(approvedAt.getTime() + m * 60_000)
}

/** 30 -> "30 minutes", 90 -> "1 hour 30 minutes", 1440 -> "1 day", 129600 -> "90 days". */
export function describeMinutes (minutes: number): string {
    const days = Math.floor(minutes / 1440)
    const hours = Math.floor((minutes % 1440) / 60)
    const mins = minutes % 60
    const part = (n: number, unit: string) => n === 0 ? '' : `${n} ${unit}${n === 1 ? '' : 's'}`
    return [part(days, 'day'), part(hours, 'hour'), part(mins, 'minute')].filter(Boolean).join(' ') || '0 minutes'
}

/** What the approval page says about the session it is about to approve. */
export function sessionEndSentence (approvedAt: Date, keyLimit: number | null | undefined, minutes: number | null | undefined,
    fmt: (d: Date) => string): string {
    const end = sessionEnd(approvedAt, keyLimit, minutes)
    if (end === null) return 'No hard end: the session slides 30 days with each use, never past 90 days after approval.'
    const m = sessionMinutes(keyLimit, minutes) as number
    const refresh = m <= 60 ? ' The CLI gets one access token and no refresh.' : ''
    return `The session ends at ${fmt(end)} (${describeMinutes(m)}), refreshes included.${refresh}`
}

/** A session list's hard-end cell. */
export function hardEndCell (hardExpiresDate: string | null | undefined, fmt: (d: Date) => string): string {
    return hardExpiresDate ? fmt(new Date(hardExpiresDate)) : '—'
}
