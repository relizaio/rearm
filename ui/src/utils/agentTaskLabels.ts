// The words a task card, the task drawer and the table view say about a task (task 562ac668),
// in one place so the three agree. Pure, so each rule has a spec.
import { actorLabel } from './agentActors'

const TERMINAL = ['COMPLETED', 'CANCELLED']
const HISTORY = ['AWAITING_COORDINATOR', 'ON_HOLD', 'DELIVERING', 'COMPLETED', 'CANCELLED']

export interface SubtaskProgress {
    done: number
    total: number
    /** the children not yet COMPLETED or CANCELLED, as found in the board's task list */
    open: any[]
}

/**
 * How far a split parent's children have got. COMPLETED and CANCELLED both count as done -- the
 * board completes the parent when neither kind is left open. A child missing from the list (not
 * loaded) counts as open, never as done.
 */
export function subtaskProgress (task: any, tasks: any[] | null | undefined): SubtaskProgress {
    const ids: string[] = task?.childTasks ?? []
    const byId = new Map((tasks ?? []).map((t: any) => [t.uuid, t]))
    const open: any[] = []
    let done = 0
    for (const id of ids) {
        const c = byId.get(id)
        if (c && TERMINAL.includes(c.status)) done++
        else open.push(c ?? { uuid: id, status: 'UNKNOWN' })
    }
    return { done, total: ids.length, open }
}

/** The card tag for a split parent, or null when the task has no children. */
export function subtaskTag (task: any, tasks: any[] | null | undefined):
    { text: string, type: 'warning' | 'success' | 'info' } | null {
    const p = subtaskProgress(task, tasks)
    if (p.total === 0) return null
    if (TERMINAL.includes(task?.status)) return { text: `${p.total} subtasks`, type: 'info' }
    if (p.done === p.total) return { text: 'subtasks done', type: 'success' }
    return { text: `waiting on subtasks · ${p.done} of ${p.total} done`, type: 'warning' }
}

export interface RoleTag {
    text: string
    /** current: the task is with this role now; history: it is the role of the last hop */
    kind: 'current' | 'history'
    tooltip: string | null
}

/**
 * The role tag. On QUEUED and ASSIGNED the role is where the task is; on every other status it is
 * the last hop's role, and says so, because the board -- not that role -- decides what happens next.
 */
export function roleTagFor (task: any): RoleTag | null {
    const role = task?.assignment?.role ?? task?.role
    if (!role || task?.status === 'PENDING_INTAKE') return null
    if (task.status === 'QUEUED') {
        return { text: `${role}${task.orderIndex != null ? ' · #' + task.orderIndex : ''}`, kind: 'current', tooltip: null }
    }
    if (task.status === 'ASSIGNED') return { text: role, kind: 'current', tooltip: null }
    if (HISTORY.includes(task.status)) {
        return { text: `last: ${role}`, kind: 'history', tooltip: 'The role of the last hop; the board decides the next one.' }
    }
    return { text: role, kind: 'current', tooltip: null }
}

/**
 * The tracker line under a task's title: its tracker ref; "draft (no tracker ref yet)" only on a
 * board with sources, where a missing ref is the intake state; nothing on a board without sources,
 * where no task ever has one and the title above already names it.
 */
export function refLabel (task: any, boardHasSources: boolean): string | null {
    if (task?.externalRef) return String(task.externalRef).replace(/^github:/, '')
    return boardHasSources ? 'draft (no tracker ref yet)' : null
}

/**
 * Whether a task matches the table's text filter: its key (RD-42), title or tracker ref, case
 * ignored. The key first, since it is what people type (board-documents.md D12).
 */
export function matchesTaskText (task: any, query: string | null | undefined): boolean {
    const q = (query ?? '').trim().toLowerCase()
    if (!q) return true
    return `${task?.key ?? ''} ${task?.title ?? ''} ${task?.externalRef ?? ''}`.toLowerCase().includes(q)
}

/** The table's key column sorts by number, so RD-9 comes before RD-10. */
export function compareTaskKeys (a: any, b: any): number {
    return (a?.number ?? Number.MAX_SAFE_INTEGER) - (b?.number ?? Number.MAX_SAFE_INTEGER)
}

/** The table's short ref: "#123", or "draft" on a board with sources, else a dash. */
export function shortRef (task: any, boardHasSources: boolean): string {
    if (task?.externalRef?.includes('#')) return '#' + String(task.externalRef).split('#').pop()
    if (task?.externalRef) return String(task.externalRef).replace(/^github:/, '')
    return boardHasSources ? 'draft' : '—'
}

/**
 * What a task waits on, named once (RD2-13, sweep UI-42): its dependencies not yet COMPLETED, by key (else the
 * source's issue number, else the uuid's first eight). A finished task waits on nothing. The card used to name
 * the blocker twice, as "blocked by …" and again as "AFTER …".
 */
export function waitsOn (task: any, tasks: any[] | null | undefined): string[] {
    if (TERMINAL.includes(task?.status) || !task?.dependsOn?.length) return []
    const byId = new Map((tasks ?? []).map((t: any) => [t.uuid, t]))
    return (task.dependsOn as string[])
        .map(id => byId.get(id) ?? { uuid: id })
        .filter((d: any) => d.status !== 'COMPLETED')
        .map((d: any) => d.key ?? (String(d.externalRef ?? '').includes('#') ? '#' + String(d.externalRef).split('#').pop()
            : String(d.uuid ?? '').slice(0, 8)))
}

/** The card's one blocker chip: "waits on RD-1, RD-2", or null. */
export function waitsOnLabel (task: any, tasks: any[] | null | undefined): string | null {
    const on = waitsOn(task, tasks)
    return on.length ? `waits on ${on.join(', ')}` : null
}

/** Where a task's design checkpoint record stands, read beside the task (task SPLIT-8, design 3.2). */
export type CheckpointPhase = 'waiting' | 'ahead' | 'lapsed' | 'closed' | 'sent_on' | 'split'

/** The role the task is with now: the assignment's, else the task's (the source {@link roleTagFor} reads). */
export function roleAhead (task: any): string | null {
    return task?.assignment?.role ?? task?.role ?? null
}

/**
 * The order of the hop log's times (agentTaskFormat's hopHistory) and of "newer" here: by string, never by
 * Date, so the two cannot disagree on server times whose fractional digits differ.
 */
export function compareHopTimes (a: string | null | undefined, b: string | null | undefined): number {
    return String(a ?? '').localeCompare(String(b ?? ''))
}

const isNewer = (a: string | null | undefined, b: string | null | undefined) => compareHopTimes(a, b) > 0

/**
 * Whether the task has reached the role its PENDING checkpoint precedes: it is with that role now, or that
 * role signed off or returned it after the checkpoint was reached. Then the checkpoint is behind it -- the
 * flag was cleared, deactivated or moved while the record was PENDING, and nothing will settle it.
 * Role names compare lower-cased, as missingRequiredRoles compares them.
 */
function reachedBeforeRole (task: any, rec: any): boolean {
    const before = String(rec?.beforeRole ?? '').toLowerCase()
    if (!before) return false
    if (String(roleAhead(task) ?? '').toLowerCase() === before) return true
    const by = (h: any) => String(h?.role ?? '').toLowerCase() === before
    return (task?.signOffs ?? []).some((s: any) => by(s) && isNewer(s.signedOffAt, rec.reachedAt)) ||
        (task?.returns ?? []).some((r: any) => by(r) && isNewer(r.returnedAt, rec.reachedAt))
}

/**
 * The phase of a task's design checkpoint, or null without a record. SENT_ON and SPLIT are settled whatever
 * the status. A PENDING record is at the checkpoint only while the task is AWAITING_COORDINATOR; on a
 * COMPLETED or CANCELLED task it was closed from there; on any other status the checkpoint is ahead until
 * the task reaches the role it precedes, and lapsed after.
 */
export function checkpointPhase (task: any): CheckpointPhase | null {
    const rec = task?.designCheckpoint
    if (!rec) return null
    if (rec.state === 'SENT_ON') return 'sent_on'
    if (rec.state === 'SPLIT') return 'split'
    if (task.status === 'AWAITING_COORDINATOR') return 'waiting'
    if (TERMINAL.includes(task.status)) return 'closed'
    return reachedBeforeRole(task, rec) ? 'lapsed' : 'ahead'
}

/** The prefix of the note the server writes on a checkpoint the architect waived with --no-split (task TPF-152). */
const WAIVED_NOTE_PREFIX = 'waived by '

/** The longest waiver note the header chip carries; the tooltip and the hop row carry the whole note. */
const WAIVER_CHIP_NOTE_MAX = 80

/**
 * The waiver a SENT_ON record carries (task TPF-153), or null: its note reads "waived by <role>: <note>" as the
 * server wrote it. A blind reader's copy reads "waived by <role>" alone, so its note is null, and so is an empty
 * tail. Text only: callers interpolate it into text nodes.
 */
export function checkpointWaiver (rec: any): { role: string, note: string | null } | null {
    if (rec?.state !== 'SENT_ON' || typeof rec.note !== 'string' || !rec.note.startsWith(WAIVED_NOTE_PREFIX)) return null
    const rest = rec.note.slice(WAIVED_NOTE_PREFIX.length)
    const i = rest.indexOf(': ')
    if (i < 0) return { role: rest, note: null }
    const note = rest.slice(i + 2)
    return { role: rest.slice(0, i), note: note || null }
}

/**
 * True when the task stopped at the checkpoint although the architect said --no-split on the stopped round: the
 * board's architectCheckpointWaiver is off. Read off the record and the server's standing, never recomputed.
 */
export function waiverIgnored (task: any): boolean {
    const waiver = task?.designCheckpointWaiver
    if (checkpointPhase(task) !== 'waiting' || !waiver || waiver.standing !== false) return false
    return (task.designCheckpoint.design ?? []).includes(waiver.designRound)
}

/** "checkpoint waived by <role>: <note>", or the role alone when the note is null (a blind reader). */
function waivedText (w: { role: string, note: string | null }, note: string | null = w.note): string {
    return note ? `checkpoint waived by ${w.role}: ${note}` : `checkpoint waived by ${w.role}`
}

/**
 * The task header's checkpoint chip, or null. None for a closed or lapsed record: the status tag says the
 * one, the other is history, and the hop log keeps both.
 */
export function checkpointTag (task: any): { text: string, type: 'warning' | 'default' } | null {
    switch (checkpointPhase(task)) {
    case 'waiting': return {
        text: waiverIgnored(task) ? 'at the design checkpoint (--no-split ignored)' : 'at the design checkpoint',
        type: 'warning'
    }
    case 'ahead': return { text: 'design checkpoint ahead', type: 'default' }
    case 'sent_on': {
        const waiver = checkpointWaiver(task.designCheckpoint)
        if (waiver) {
            const note = waiver.note && waiver.note.length > WAIVER_CHIP_NOTE_MAX
                ? waiver.note.slice(0, WAIVER_CHIP_NOTE_MAX) + '...'
                : waiver.note
            return { text: waivedText(waiver, note), type: 'default' }
        }
        const by = actorLabel(task.designCheckpoint.settledBy)
        return { text: by ? `sent on by ${by}` : 'sent on', type: 'default' }
    }
    case 'split': return { text: `split into ${task.childTasks?.length ?? 0}`, type: 'default' }
    default: return null
    }
}

/** The chip's tooltip: where the checkpoint is, and the phase's tail. '' without a record. */
export function checkpointTooltip (task: any): string {
    const phase = checkpointPhase(task)
    if (!phase) return ''
    const rec = task.designCheckpoint
    const head = `design checkpoint before ${rec.beforeRole}, reached after ${rec.afterRole ?? 'the design'} ${rec.reachedAt}`
    switch (phase) {
    case 'waiting': return `${head}; waiting for the coordinator` +
        (waiverIgnored(task) ? '; the architect\'s --no-split on this round was ignored: the board\'s architectCheckpointWaiver is off' : '')
    case 'ahead': return `${head}; now with ${roleAhead(task)}; the task stops at the checkpoint again before ${rec.beforeRole}`
    case 'closed': return `${head}; closed from the checkpoint, not settled`
    case 'lapsed': return `${head}; not settled; the task went on to ${roleAhead(task)}`
    default: {
        const waiver = checkpointWaiver(rec)
        if (waiver) {
            const at = rec.settledAt ? ' ' + rec.settledAt : ''
            const notStanding = task.designCheckpointWaiver?.standing === false
                ? '; the waiver no longer stands (a newer design round, an advisory round or a reopen since)'
                : ''
            return `${head}; checkpoint waived by ${waiver.role}${at}${waiver.note ? ': ' + waiver.note : ''}${notStanding}`
        }
        const by = actorLabel(rec.settledBy)
        const verb = phase === 'split' ? 'split' : 'sent on'
        return `${head}; ${verb}${by ? ' by ' + by : ''}${rec.settledAt ? ' ' + rec.settledAt : ''}${rec.note ? ': ' + rec.note : ''}`
    }
    }
}

/**
 * The task header's split-request chip (task SPLIT-13, parent SPLIT-9 D11), or null. The server carries the
 * request's state (a REQUESTED record exists only while the task waits at the coordinator or is held over that
 * wait), so the chip's one condition is REQUESTED on AWAITING_COORDINATOR; every other state and status is a
 * hop-log row, and a hold says itself what the task waits on.
 */
export function splitRequestTag (task: any): { text: string, type: 'warning' } | null {
    return splitRequestWaits(task) ? { text: `split requested by ${task.splitRequest.role}`, type: 'warning' } : null
}

/** Whether the task's split request waits at the coordinator now: the chip's and the hop row's one condition. */
export function splitRequestWaits (task: any): boolean {
    return task?.splitRequest?.state === 'REQUESTED' && task.status === 'AWAITING_COORDINATOR'
}

/** "1 child", "<n> children": the count of a split request's proposed children. */
export function childCount (n: number): string {
    return `${n} ${n === 1 ? 'child' : 'children'}`
}

/**
 * A proposed child's title as the split request shows it (task TPF-164): " [design from parent]" after the title of
 * a child proposed to build from the parent's reviewed design. Only a true designFromParent marks it.
 */
export function proposedChildTitle (child: any): string {
    return child?.designFromParent === true ? `${child.title} [design from parent]` : `${child?.title ?? ''}`
}

/**
 * The chip's tooltip: the requester's reason and the children it proposes, titles whole (the server bounds
 * them). A blind read has no description; it then reads as the redacted request does. '' without a record.
 */
export function splitRequestTooltip (task: any): string {
    const rec = task?.splitRequest
    if (!rec) return ''
    const head = rec.description ?? `split requested by ${rec.role}`
    const titles = (rec.proposedChildren ?? []).map(proposedChildTitle)
    if (!titles.length) return `${head}; proposes no children`
    return `${head}; proposes ${childCount(titles.length)}: ${titles.join('; ')}`
}
