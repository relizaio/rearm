// A hop parked for the operator (task RD4-5): the session working a task holds it at OPERATOR level with a
// question, and a person's release carries the answer. The server records both on the task's status
// history -- the HOLD row's note is "awaiting the operator: <question>", the release row's "released by
// <person>: <answer>" -- so the page reads the question and the answer from there, after the hold is gone
// as well as while it stands. Pure, so the specs need no store.

export const AWAITING_THE_OPERATOR = 'awaiting the operator: '

function rowParksAHop (row: any): boolean {
    return row?.to === 'ON_HOLD' && row?.from === 'ASSIGNED' && row?.trigger === 'HOLD' && row?.actor?.kind === 'SESSION'
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
    askedAt: string | null
    askedBy: any
    /** Null while the hop still waits. */
    answer: string | null
    answeredAt: string | null
    answeredBy: any
}

/**
 * Every question a hop of this task parked for the operator, oldest first, each with the answer the person's
 * release gave, or none yet. A release that answered nothing -- the task was cancelled while it waited -- leaves
 * the question unanswered.
 */
export function operatorQuestions (task: any): OperatorQuestion[] {
    const rows: any[] = task?.statusHistory ?? []
    const out: OperatorQuestion[] = []
    rows.forEach((row, i) => {
        if (!rowParksAHop(row) || !(row.note ?? '').startsWith(AWAITING_THE_OPERATOR)) return
        const next = rows.slice(i + 1).find((r: any) => r?.from === 'ON_HOLD')
        const answered = next?.trigger === 'RELEASE_HOLD'
        out.push({
            question: questionOf(row.note),
            askedAt: row.at ?? null,
            askedBy: row.actor ?? null,
            answer: answered ? answerOf(next) : null,
            answeredAt: answered ? (next.at ?? null) : null,
            answeredBy: answered ? (next.actor ?? null) : null,
        })
    })
    return out
}

/** A parked hop is released with the answer, which the server requires; any other release may be bare. */
export function releaseNeedsAnswer (task: any, note: string | null | undefined): boolean {
    return parkedHop(task) && !(note ?? '').trim()
}
