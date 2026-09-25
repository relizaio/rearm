// Who asked a task's questions, in which round, about what, and whether a round is still open
// (gaps §1.27, task 6d748622). Everything is on the task read already: the question frames,
// the QUESTIONS rounds with their items and `about`, and the sign-offs' outputs. Pure, like
// agentDocuments.ts, so the specs need no store.
import type { DocumentRelease, Finding } from './agentDocuments'

export type RoleRef = { roleUuid: string | null, roleName: string | null }

export type AnsweredBy =
    | { specification: string | null, round: number | null, release: string }
    | { person: true, round: number | null }

export interface QuestionRound {
    release: string
    round: number | null
    /** Null when nothing says who asked: the label reads "a role". */
    askedBy: RoleRef | null
    /** The role the board routed the questions to, while the round is open; null while nobody is. */
    waitingOn: RoleRef | null
    /** True while the round is open and its frame has no answering role: with the coordinator. */
    withCoordinator: boolean
    about: { specification: string, release: string | null, round: number | null } | null
    state: 'open' | 'answered' | 'withdrawn'
    counts: { open: number, answered: number, withdrawn: number }
    answeredBy: AnsweredBy[]
}

function roleRef (roles: any[] | null | undefined, task: any, uuid: string | null | undefined,
    fallbackName?: string | null): RoleRef | null {
    if (!uuid && !fallbackName) return null
    const rc = uuid ? (roles ?? []).find((r: any) => r?.uuid === uuid) : null
    // A role no longer on the board: the name a sign-off recorded for that uuid at the time.
    const recorded = uuid ? (task?.signOffs ?? []).find((s: any) => s?.roleUuid === uuid)?.role : null
    const name = rc?.name ?? fallbackName ?? recorded ?? null
    return { roleUuid: uuid ?? null, roleName: name }
}

function itemsOf (d: DocumentRelease): Finding[] {
    return (d?.document?.findings?.findings ?? []) as Finding[]
}

/** Every QUESTIONS round of the task, newest first, as the task's documents come. */
export function questionRounds (task: any, roles?: any[] | null): QuestionRound[] {
    const documents: DocumentRelease[] = task?.documents ?? []
    const byUuid = new Map<string, DocumentRelease>()
    for (const d of documents) if (d?.uuid) byUuid.set(d.uuid, d)
    const rounds = documents.filter(d => d?.document?.specification === 'QUESTIONS' && !!d?.document?.findings && !!d?.uuid
        && isOfItsKind(d))
    const frames: any[] = task?.questionStack ?? []
    const signOffs: any[] = task?.signOffs ?? []

    // Oldest first, so a round can inherit its asker from the one before it.
    const oldestFirst = [...rounds].reverse()
    const out: QuestionRound[] = []
    let previousAsker: RoleRef | null = null
    for (let i = 0; i < oldestFirst.length; i++) {
        const d = oldestFirst[i]
        const release = d.uuid as string
        const frame = frames.find(f => f?.questionsRelease === release)
        const signOff = signOffs.find(s => (s?.outputs ?? []).includes(release))
        const askedBy: RoleRef | null = frame?.askingRole
            ? roleRef(roles, task, frame.askingRole)
            : signOff
                ? roleRef(roles, task, signOff.roleUuid, signOff.roleUuid ? null : signOff.role)
                // A board-cut answer or unwind round carries the same items and no session.
                : previousAsker

        const about = d.document?.findings?.about
        const aboutDoc = about?.release ? byUuid.get(about.release) : undefined
        // A later round carries the same items with their new status (a person's answer round,
        // the board's unwind round), so an item reads as the newest round that has it says.
        const newer = oldestFirst.slice(i)
        const items = itemsOf(d).map(f => {
            for (let k = newer.length - 1; k >= 0; k--) {
                const x = itemsOf(newer[k]).find(y => y.id === f.id)
                if (x) return x
            }
            return f
        })
        const counts = {
            open: items.filter(f => f.status === 'OPEN').length,
            withdrawn: items.filter(f => f.status === 'WITHDRAWN').length,
            answered: items.filter(f => f.status !== 'OPEN' && f.status !== 'WITHDRAWN').length,
        }
        const state: QuestionRound['state'] = counts.open > 0 ? 'open'
            : items.length > 0 && counts.withdrawn === items.length ? 'withdrawn' : 'answered'

        // What answered the round: its answered items only. A withdrawn question was not answered,
        // so it names nothing, and a withdrawn round says only that (design 3.2).
        const answeredBy: AnsweredBy[] = []
        for (const f of items) {
            if (f.status === 'OPEN' || f.status === 'WITHDRAWN') continue
            const by = f.resolvedBy ? byUuid.get(f.resolvedBy) : undefined
            if (by?.document?.specification === 'QUESTIONS' || (!f.resolvedBy && f.resolution)) {
                // A person's answer is a QUESTIONS round the server points the items it closed at
                // (stampSelfPointer). Older rows left resolvedBy empty: then the answer round is the
                // first round, from this one on, where the item is no longer open.
                const closing = by ?? newer.find(r => itemsOf(r).some(x => x.id === f.id && x.status !== 'OPEN'))
                const round = closing?.document?.round ?? null
                if (answeredBy.some(a => 'person' in a && a.round === round)) continue
                answeredBy.push({ person: true, round })
            } else if (f.resolvedBy) {
                if (answeredBy.some(a => 'release' in a && a.release === f.resolvedBy)) continue
                answeredBy.push({ specification: by?.document?.specification ?? null,
                    round: by?.document?.round ?? null, release: f.resolvedBy })
            }
        }

        out.push({
            release,
            round: d.document?.round ?? null,
            askedBy,
            waitingOn: state === 'open' && frame?.answeringRole ? roleRef(roles, task, frame.answeringRole) : null,
            withCoordinator: state === 'open' && !!frame && !frame.answeringRole,
            about: about?.specification
                ? { specification: about.specification, release: about.release ?? null, round: aboutDoc?.document?.round ?? null }
                : null,
            state,
            counts,
            answeredBy,
        })
        if (askedBy) previousAsker = askedBy
    }
    return out.reverse()
}

/**
 * Whether a QUESTIONS-filed round really is one. Before task bc7fc25a the board filed its unwind of a
 * tester's or reviewer's findings frame under QUESTIONS with the findings index inside; those
 * rounds are the record, not questions. A round without a kind counts as what it is filed under.
 */
function isOfItsKind (d: DocumentRelease): boolean {
    const kind = (d?.document?.findings as any)?.kind
    return !kind || kind === d?.document?.specification
}

/**
 * What a frame waits on: a questions round, or a findings round -- a tester's or reviewer's round
 * routed back to the maker, which pops when the maker passes and the reviewer's next round closes
 * or re-raises its ids (task bc7fc25a). Null when the frame's round is not on the task's read.
 */
export function frameKind (task: any, frame: any): 'questions' | 'findings' | null {
    const d = (task?.documents ?? []).find((x: any) => x?.uuid && x.uuid === frame?.questionsRelease)
    const spec = d?.document?.specification
    if (!spec) return null
    return spec === 'QUESTIONS' ? 'questions' : 'findings'
}

/** "tester waits on coder to resolve 2 finding(s)": a findings frame's row. */
export function findingsFrameLabel (task: any, frame: any, name: (uuid: string | null | undefined) => string): string {
    const d = (task?.documents ?? []).find((x: any) => x?.uuid && x.uuid === frame?.questionsRelease)
    const open = ((d?.document?.findings?.findings ?? []) as Finding[]).filter(f => f.status === 'OPEN').length
    return `${name(frame?.askingRole) || 'a role'} waits on ${name(frame?.answeringRole) || 'nobody yet'} to resolve `
        + `${open} finding${open === 1 ? '' : 's'}`
}

export function latestQuestionRound (task: any, roles?: any[] | null): QuestionRound | null {
    return questionRounds(task, roles)[0] ?? null
}

/** The round a question frame points at, for the "Waiting on" rows. */
export function questionRoundOf (task: any, roles: any[] | null | undefined, release: string | null | undefined): QuestionRound | null {
    if (!release) return null
    return questionRounds(task, roles).find(r => r.release === release) ?? null
}

function roundText (spec: string, round: number | null): string {
    return round == null ? spec : `${spec} round ${round}`
}

/** "about ARCHITECTURE round 1", or "" when the round says nothing about what it asks about. */
export function aboutLabel (r: QuestionRound | null): string {
    return r?.about ? `about ${roundText(r.about.specification, r.about.round)}` : ''
}

/** "Questions from coder · round 1 · about ARCHITECTURE round 1". */
export function questionRoundLabel (r: QuestionRound): string {
    const parts = [`Questions from ${r.askedBy?.roleName ?? 'a role'}`]
    if (r.round != null) parts.push(`round ${r.round}`)
    const about = aboutLabel(r)
    if (about) parts.push(about)
    return parts.join(' · ')
}

/** "open (2)", "answered" or "withdrawn": never the asking hop's REJECTED. */
export function questionStateLabel (r: QuestionRound): string {
    return r.state === 'open' ? `open (${r.counts.open})` : r.state
}

export function questionStateType (r: QuestionRound): 'warning' | 'success' | 'default' {
    return r.state === 'open' ? 'warning' : r.state === 'answered' ? 'success' : 'default'
}

/** "answered by ARCHITECTURE round 2", or "answered by a person in questions round 3". */
export function answeredByLabel (a: AnsweredBy): string {
    if ('person' in a) return a.round == null ? 'answered by a person' : `answered by a person in questions round ${a.round}`
    return `answered by ${roundText(a.specification ?? 'a document', a.round)}`
}
