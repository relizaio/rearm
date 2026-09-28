// What the task page says instead of staying silent (task RD2-16, sweep UI-33/34/35): who reopened a task,
// to which role and why; that its board is locked; and why an action button is disabled. Each hint says what
// the server would say, so it never claims a refusal the server does not make. Pure, so the specs need no store.
import { actorLabel } from './agentActors'
import { shortPr } from './agentDelivery'

/** A board lock that refuses something: any level but NONE. */
export function boardLocked (board: any): boolean {
    return !!board?.lock?.level && board.lock.level !== 'NONE'
}

/**
 * The task page's lock banner, as the board page words it: no new assignments, and no reopening (the server
 * refuses both on a locked board); a person still completes, cancels and releases.
 */
export function lockBannerText (board: any): string | null {
    if (!boardLocked(board)) return null
    const l = board.lock
    const by = actorLabel(l.lockedBy)
    return `Board locked (${l.level}) — no new assignments and no reopening.`
        + (l.reason ? ` Reason: ${l.reason}.` : '') + (by ? ` Held by ${by}.` : '')
}

/** Who reopened it, as the line says: the board for a delivery that could not land, else the actor. */
function reopenedBy (actor: any): string {
    if (actor?.kind === 'SYSTEM' && actor?.name === 'delivery') return 'the board'
    return actorLabel(actor) || 'someone'
}

/**
 * "Reopened 2× · last <when> to coder by pm@example.com: PR conflicts". A task read from before reopens were
 * served keeps the count and the time alone.
 */
export function reopenLine (task: any, when: (at: string) => string): string | null {
    const n = task?.reopenCount ?? 0
    if (!n) return null
    const last = (task?.reopens ?? []).at(-1)
    const at = last?.at ?? task?.reopenedAt
    const head = `Reopened ${n}× · last ${at ? when(at) : '—'}`
    if (!last) return head
    return head + (last.role ? ` to ${last.role}` : '') + ` by ${reopenedBy(last.by)}` + (last.reason ? `: ${last.reason}` : '')
}

export type HintedAction = 'complete' | 'authorize' | 'order' | 'hold' | 'reopen' | 'release'

export interface ActionDrafts {
    role?: string | null
    order?: number | null
    holdReason?: string | null
    reopenRole?: string | null
    reopenReason?: string | null
    releaseReason?: string | null
}

/** The statuses a person completes from (the server's complete). */
export const COMPLETABLE = ['AWAITING_COORDINATOR', 'PENDING_INTAKE', 'QUEUED', 'ON_HOLD', 'DELIVERING']

/**
 * What will not land, as the server's complete refuses it (task RD3-16): a PR attested abandoned or closed
 * without merging -- the newest attestation settles a PR, as on the board -- or, on a board that delivers
 * without PRs, a task whose newest attestation is abandoned. Empty when nothing blocks.
 */
export function blockedDelivery (task: any, board: any): string[] {
    if (board?.deliveryPolicy?.mode === 'NONE') {
        const newest = (task?.deliveries ?? []).at(-1)
        return newest?.outcome === 'ABANDONED' ? ['its delivery attested abandoned'] : []
    }
    return (task?.pullRequests ?? [])
        .filter((pr: any) => pr.attestation ? pr.attestation.outcome === 'ABANDONED' : pr.state === 'CLOSED')
        .map((pr: any) => shortPr(pr.url) + (pr.attestation ? ' attested abandoned' : ' closed without merging'))
}

/** Why an action's button is disabled, or null when it is not. */
export function disabledReason (action: HintedAction, task: any, board: any, d: ActionDrafts = {}): string | null {
    switch (action) {
    case 'complete': {
        if (!COMPLETABLE.includes(task?.status)) {
            return task?.status === 'ASSIGNED' ? 'assigned to a session; release or force-close it first'
                : `a ${String(task?.status ?? 'task').toLowerCase().replace(/_/g, ' ')} task is not completed by hand`
        }
        const blocked = blockedDelivery(task, board)
        return blocked.length ? `its delivery will not land: ${blocked.join('; ')}; reopen it, or link the PR that replaces it`
            : null
    }
    case 'authorize':
        return d.role ? null : 'pick the role to authorize it for'
    case 'order':
        if (d.order == null) return 'enter an order'
        return d.order === task?.orderIndex ? 'that is its order already' : null
    case 'hold':
        return (d.holdReason ?? '').trim() ? null : 'say why it is held'
    case 'reopen':
        if (boardLocked(board)) {
            const who = board.lock.level === 'OPERATOR' ? 'the operator' : 'the coordinator'
            return `board locked by ${who}` + (board.lock.reason ? `: ${board.lock.reason}` : '') + '; unlock it before reopening'
        }
        if (!d.reopenRole) return 'pick the role to reopen it to'
        return (d.reopenReason ?? '').trim() ? null : 'say why its delivery cannot land'
    case 'release':
        // task RD3-4: a person takes a stalled assignment back to the queue
        if (task?.status !== 'ASSIGNED') return 'only an assigned task has an assignment to release'
        if (!board?.myPermissions?.includes('BOARD_WRITE')) return 'releasing an assignment needs BOARD_WRITE on this board'
        return (d.releaseReason ?? '').trim() ? null : 'say why the assignment is released'
    }
}
