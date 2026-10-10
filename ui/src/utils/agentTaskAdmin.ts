// An org admin's verbs on a task and a session that the API had and the UI did not (task
// 6fdc5a37): a task's required strength, an operator hold, and force-closing a session. Which
// apply, and what each sends; pure, so the specs need no store.

import { boardCan } from './agentBoardAccess'
import { prKey, shortPr } from './agentDelivery'
import { awaitingOperator } from './agentOperatorQuestion'
import { isTerminal } from './agentTaskFormat'

/** The statuses the server places a hold from; ASSIGNED and later are refused (placeHold). */
export const HOLDABLE_STATUSES = ['PENDING_INTAKE', 'QUEUED', 'AWAITING_COORDINATOR']

export function canPlaceHold (task: any, admin: boolean): boolean {
    return admin && HOLDABLE_STATUSES.includes(task?.status)
}

/** The hold to place, or null while there is no reason: the server refuses a blank one. */
export function holdPayload (task: any, reason: string | null | undefined): { task: any, reason: string } | null {
    const r = (reason ?? '').trim()
    return r ? { task, reason: r } : null
}

/**
 * The unassign to send (task RD3-4): an ASSIGNED task back to the queue for the same role, with the
 * reason the server requires; null when either is missing.
 */
export function unassignPayload (task: any, reason: string | null | undefined): { task: any, reason: string } | null {
    const r = (reason ?? '').trim()
    return r && task?.status === 'ASSIGNED' ? { task, reason: r } : null
}

/** The strength floor of the role the task is with, when that role has one. */
export function roleFloor (task: any, roles: any[] | null | undefined): number | null {
    const rc = (roles ?? []).find((r: any) => r?.name === task?.role)
    return typeof rc?.requiredStrength === 'number' ? rc.requiredStrength : null
}

/** What the strength box shows when the task has none of its own. */
export function strengthPlaceholder (task: any, roles: any[] | null | undefined): string {
    const floor = roleFloor(task, roles)
    return floor == null ? 'none' : `role floor ${floor}`
}

/**
 * The strength to send for a draft, or undefined when there is nothing to send: no draft, a
 * negative one, or the value the task already has. Rounded to the server's two decimals.
 */
export function strengthToSet (task: any, draft: number | null | undefined): number | undefined {
    if (draft == null || !Number.isFinite(draft) || draft < 0) return undefined
    const v = Math.round(draft * 100) / 100
    return v === task?.requiredStrength ? undefined : v
}

/**
 * Who may force-close an OPEN session, as the server decides it (board-permissions.md D15, task
 * RD2-5): a person holding BOARD_WRITE on a board the session worked, read from those boards'
 * myPermissions; the org admin, which covers every board and alone covers a session that worked
 * none. Its tasks go back to the queue.
 */
export function canForceClose (session: any, admin: boolean, boards?: any[] | null): boolean {
    if (session?.status !== 'OPEN') return false
    if (admin) return true
    const worked = new Set<string>(session?.boardsWorked ?? [])
    return (boards ?? []).some(b => worked.has(b?.uuid) && boardCan(b, 'BOARD_WRITE'))
}

/** Whether the page needs the org's boards to decide: an open session that worked boards, read by a non-admin. */
export function forceCloseNeedsBoards (session: any, admin: boolean): boolean {
    return !admin && session?.status === 'OPEN' && (session?.boardsWorked ?? []).length > 0
}

// ---------- declaring a linked PR superseded (task RD3-18) ----------

/**
 * A PR URL's repository, as the server keys it (RD3-13): scheme and host lower-cased, trailing slashes and a
 * query or fragment dropped, then the last two path segments ("pull/692") cut. Null for a blank URL.
 */
export function prRepository (url: string | null | undefined): string | null {
    const raw = String(url ?? '').trim()
    if (!raw) return null
    let s = raw.replace(/[?#].*$/, '').replace(/\/+$/, '')
    const m = /^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/]+)(.*)$/.exec(s)
    if (m) s = m[1].toLowerCase() + m[2]
    for (let i = 0; i < 2 && s.lastIndexOf('/') > 0; i++) s = s.slice(0, s.lastIndexOf('/'))
    return s
}

/** The task's PR row for a URL, as the task read resolves it, matched as the board matches PRs. */
function prRow (task: any, url: string): any {
    return (task?.pullRequests ?? []).find((p: any) => prKey(p?.url) === prKey(url))
}

/**
 * Whether a row offers "Declare superseded": its tracker reports it closed and unmerged, and no declaration
 * already settles it as delivered or superseded (one declared abandoned may be superseded, RD3-13).
 */
export function offersSupersede (task: any, url: string): boolean {
    const pr = prRow(task, url)
    if (!pr || pr.state !== 'CLOSED') return false
    const outcome = pr.declaration?.outcome
    return outcome !== 'SUPERSEDED' && outcome !== 'DELIVERED'
}

/** The task's other linked PRs on the same repository: what may replace `url`. */
export function supersedeCandidates (task: any, url: string): string[] {
    const repo = prRepository(url)
    return ((task?.prUrls ?? []) as string[]).filter(u => u !== url && null !== repo && prRepository(u) === repo)
}

/** Why "Declare superseded" is disabled, or null (RD2-16): the permission, then a replacement to name. */
export function supersedeDisabledReason (task: any, url: string, canOperate: boolean): string | null {
    if (!canOperate) return 'declaring a PR superseded needs BOARD_WRITE on this board'
    if (!supersedeCandidates(task, url).length) return 'link the PR that replaces it first, on the same repository'
    return null
}

/** The user mutation's variables, or null while no replacement is picked. */
export function supersedePayload (task: any, oldUrl: string, byUrl: string | null | undefined, note: string | null | undefined):
        { task: any, oldUrl: string, byUrl: string, note: string | null } | null {
    if (!byUrl || !supersedeCandidates(task, oldUrl).includes(byUrl)) return null
    const n = (note ?? '').trim()
    return { task, oldUrl, byUrl, note: n || null }
}

// ---------- unlinking a PR that should never have counted (task t20261010-033525-24393) ----------

/** Whether a row offers "Unlink...": a PR linked to a task not yet completed or cancelled. */
export function offersUnlink (task: any, url: string): boolean {
    return !isTerminal(task) && ((task?.prUrls ?? []) as string[]).some(u => prKey(u) === prKey(url))
}

/** The linked PRs a DELIVERING task still has to deliver once `url` is gone: waiting on, or delivered. */
function othersToDeliver (task: any, url: string): any[] {
    return (task?.pullRequests ?? []).filter((p: any) => prKey(p?.url) !== prKey(url)
        && (p?.unit === 'WAITING' || p?.unit === 'DELIVERED'))
}

/**
 * Why "Unlink..." is disabled, or null (RD2-16), in the server's words (task t20261010-033523-18839) and its order,
 * the task's own label aside: the permission, a task parked for the operator, a delivered PR, the replacement of a
 * superseded one, and the last PR a DELIVERING task has to deliver. Past the pass before DELIVERING (a live tested
 * pass, or every role passed at the coordinator) the page cannot tell, so the server's refusal says it; a read
 * without units checks no delivery.
 */
export function unlinkDisabledReason (task: any, url: string, canOperate: boolean): string | null {
    if (!canOperate) return 'unlinking a PR needs BOARD_WRITE on this board'
    if (awaitingOperator(task)) return 'This task is parked, awaiting the operator; release the hold first, then unlink'
    const pr = prRow(task, url)
    if (pr?.registered && pr?.state === 'MERGED') return `${url} merged; a delivered PR stays linked`
    if (pr?.declaration?.outcome === 'DELIVERED') return `${url} is declared delivered; a delivered PR stays linked`
    const replaced = (task?.pullRequests ?? []).find((o: any) => prKey(o?.url) !== prKey(url)
        && o?.declaration?.outcome === 'SUPERSEDED' && prKey(o.declaration.supersededBy) === prKey(url))
    if (replaced) {
        return `${url} is the replacement of ${replaced.url} (declared superseded by it); unlink ${replaced.url} first,`
            + ' or supersede it by another PR'
    }
    const units = (task?.pullRequests ?? []).some((p: any) => p?.unit)
    if (task?.status === 'DELIVERING' && units && !othersToDeliver(task, url).length) {
        return `Unlinking ${url} would leave this task with no PR to deliver; reopen it to the role that redoes the work,`
            + ' supersede it (task supersedepr), or cancel it'
    }
    return null
}

/**
 * What a person is told before taking out a PR a DELIVERING task still waits on: the server takes it from a person
 * and posts an ALERT naming the tested head that no longer counts. Only DELIVERING is certain to be past the pass
 * here, so earlier the server's ALERT alone says it. Null when nothing is at stake.
 */
export function unlinkWarning (task: any, url: string): string | null {
    const pr = prRow(task, url)
    if (task?.status !== 'DELIVERING' || pr?.unit !== 'WAITING') return null
    const tested = (task?.testedHeads ?? []).find((t: any) => prKey(t?.pr) === prKey(url))
    const head = tested?.head ? `, and the tested head ${String(tested.head).slice(0, 7)} no longer counts` : ''
    return `${shortPr(url)} is ${pr.registered ? 'open' : 'unregistered'} and delivery waits on it: unlinking it posts an ALERT${head}`
}

/** The user mutation's variables: the note trimmed, none when blank (the field caps it at the server's limit). */
export function unlinkPayload (task: any, prUrl: string, note: string | null | undefined):
        { task: any, prUrl: string, note: string | null } {
    const n = (note ?? '').trim()
    return { task, prUrl, note: n || null }
}
