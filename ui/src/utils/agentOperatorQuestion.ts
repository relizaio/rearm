// A hop parked for the operator (task RD4-5): the session working a task holds it at OPERATOR level with a
// question, and a person's release carries the answer. The server records both on the task's status
// history -- the HOLD row's note is "awaiting the operator: <question>", the release row's "released by
// <person>: <answer>" -- so the page reads the question and the answer from there, after the hold is gone
// as well as while it stands. Pure, so the specs need no store.
//
// The coordinator seat parks a task nobody is working the same way (task RD4-17), from PENDING_INTAKE, QUEUED,
// AWAITING_COORDINATOR or DELIVERING. Its hold records returnTo, the status a person's answer returns the task to,
// and anything else a person does on the task (answering its questions, an attestation, a cancel, a new order ...)
// answers it too (architecture round 2): the release row then reads "<action> by <person>: <note>", for example
// "cancelled by Pat: a duplicate", and the page shows that row's note whole as the answer.

export const AWAITING_THE_OPERATOR = 'awaiting the operator: '

function rowParksAHop (row: any): boolean {
    return row?.to === 'ON_HOLD' && row?.from === 'ASSIGNED' && row?.trigger === 'HOLD' && row?.actor?.kind === 'SESSION'
}

/** A HOLD row an agent placed with a question for the operator: a hop's own (RD4-5) or the seat's (RD4-17). */
function rowAsksTheOperator (row: any): boolean {
    return row?.to === 'ON_HOLD' && row?.trigger === 'HOLD' && row?.actor?.kind === 'SESSION'
        && (row?.note ?? '').startsWith(AWAITING_THE_OPERATOR)
}

/**
 * Whether the task's hold is a hop its holder parked for the operator: an OPERATOR-level MANUAL hold a session
 * placed on the task it was working, read as the server reads it.
 */
export function parkedHop (task: any): boolean {
    const h = task?.hold
    if (task?.status !== 'ON_HOLD' || !h || h.kind !== 'MANUAL' || h.level !== 'OPERATOR' || h.heldBy?.kind !== 'SESSION') {
        return false
    }
    const rows: any[] = task.statusHistory ?? []
    for (let i = rows.length - 1; i >= 0; i--) {
        if (rows[i]?.to === 'ON_HOLD') return rowParksAHop(rows[i])
    }
    return false
}

/**
 * Whether the coordinator seat parked the task for an operator decision (task RD4-17): an OPERATOR-level MANUAL
 * hold a session placed that records the status to return to. Only the seat's hold records one.
 */
export function seatParked (task: any): boolean {
    const h = task?.hold
    return task?.status === 'ON_HOLD' && !!h && !!h.returnTo && h.kind === 'MANUAL' && h.level === 'OPERATOR'
        && h.heldBy?.kind === 'SESSION'
}

/** Whether the task waits on a person's answer to an agent's question: a hop's own hold, or the seat's. */
export function awaitingOperator (task: any): boolean {
    return parkedHop(task) || seatParked(task)
}

/** Where a person's answer puts a task the seat parked, in the page's words ("delivering"); null otherwise. */
export function returnsTo (task: any): string | null {
    return seatParked(task) ? String(task.hold.returnTo).toLowerCase().replace(/_/g, ' ') : null
}

/**
 * The status a task shows for what may be done with it: a task the seat parked reads as the status a person's
 * answer returns it to, since acting on it as that status answers the question (task RD4-17).
 */
export function effectiveStatus (task: any): string | null {
    return seatParked(task) ? task.hold.returnTo : (task?.status ?? null)
}

/** The line beside a parked task's action buttons (RD4-17): acting answers the question, so it names it. */
export function actingAnswers (task: any): string | null {
    if (!seatParked(task)) return null
    return `Awaiting the operator: ${questionOf(task.hold.reason)} Acting here answers it: what you do is recorded as`
        + ` the answer, and the task returns to ${returnsTo(task)}.`
}

/**
 * The kanban card's hold chip: a human gate, a question for the operator (the question on hover), or a plain hold
 * (its reason on hover).
 */
export function holdChip (task: any): { label: string, tooltip: string, operator: boolean } | null {
    if (task?.status !== 'ON_HOLD') return null
    const h = task.hold
    if (h?.kind === 'HUMAN_GATE') return { label: '\u270b human review', tooltip: h.reason ?? 'on hold', operator: false }
    if (awaitingOperator(task)) {
        return { label: 'awaiting the operator', tooltip: questionOf(h?.reason), operator: true }
    }
    return { label: 'on hold', tooltip: h?.reason ?? 'on hold', operator: false }
}

/** The question of a parked hop's hold, without the words the task shows before it. */
export function questionOf (reason: string | null | undefined): string {
    const r = reason ?? ''
    return r.startsWith(AWAITING_THE_OPERATOR) ? r.slice(AWAITING_THE_OPERATOR.length) : r
}

/** The answer in a release row's note, without "released by <person>: " before it. */
export function answerOf (row: any): string {
    const note: string = row?.note ?? ''
    const name: string | undefined = row?.actor?.name
    const prefix = name ? `released by ${name}: ` : null
    if (prefix && note.startsWith(prefix)) return note.slice(prefix.length)
    const m = /^released by [^:]*: /.exec(note)
    return m ? note.slice(m[0].length) : note
}

export interface OperatorQuestion {
    question: string
    /** Who asked: the hop working the task, or the coordinator seat (RD4-17). */
    askedByCoordinator: boolean
    askedAt: string | null
    askedBy: any
    /** Null while the hop still waits. */
    answer: string | null
    answeredAt: string | null
    answeredBy: any
}

/**
 * Every question a hop of this task, or the coordinator seat, parked it on for the operator, oldest first, each
 * with the answer the person's release gave, or none yet. A release that answered nothing -- the task was
 * cancelled while it waited -- leaves the question unanswered.
 */
export function operatorQuestions (task: any): OperatorQuestion[] {
    const rows: any[] = task?.statusHistory ?? []
    const out: OperatorQuestion[] = []
    rows.forEach((row, i) => {
        if (!rowAsksTheOperator(row)) return
        const next = rows.slice(i + 1).find((r: any) => r?.from === 'ON_HOLD')
        const answered = next?.trigger === 'RELEASE_HOLD'
        out.push({
            question: questionOf(row.note),
            askedByCoordinator: row.from !== 'ASSIGNED',
            askedAt: row.at ?? null,
            askedBy: row.actor ?? null,
            answer: answered ? answerOf(next) : null,
            answeredAt: answered ? (next.at ?? null) : null,
            answeredBy: answered ? (next.actor ?? null) : null,
        })
    })
    return out
}

/** A question for the operator is released with the answer, which the server requires; any other release may be bare. */
export function releaseNeedsAnswer (task: any, note: string | null | undefined): boolean {
    return awaitingOperator(task) && !(note ?? '').trim()
}
