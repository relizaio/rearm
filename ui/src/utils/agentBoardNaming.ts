// The board form's naming fields (board-documents.md §5; task fceb1e57): the task-key prefix and
// the documents block { prefix, shared, root }.
//
// The server is the authority on both -- it normalises the prefix, refuses one another board
// claimed, and refuses a root that climbs out of the repository. What is worked out here is only
// the preview the form shows before a save, and which fields a save sends.

const TASK_PREFIX = /^[A-Z0-9]{2,8}$/

/** The prefix as the server stores it: trimmed and upper-cased. */
export function normaliseTaskPrefix (raw?: string | null): string {
    return (raw ?? '').trim().toUpperCase()
}

/** Null when the prefix has the server's shape, 2 to 8 letters and digits; else why not. */
export function taskPrefixProblem (raw?: string | null): string | null {
    const p = normaliseTaskPrefix(raw)
    return !p || TASK_PREFIX.test(p) ? null : 'a task-key prefix is 2 to 8 letters and digits, e.g. RD'
}

/**
 * The prefix a board name suggests, the server's rule (D8): the initials of its words
 * (ReARM Dogfood is RD), or the first two letters of a single word; at most 8.
 */
export function derivedTaskPrefix (name?: string | null): string {
    const words = (name ?? '').split(/[^A-Za-z0-9]+/).filter(w => w)
    let base = words.length >= 2 ? words.map(w => w[0]).join('') : (words[0] ?? '').slice(0, 2)
    base = base.toUpperCase().slice(0, 8)
    while (base.length < 2) base += 'B'
    return base
}

/** The prefix field's placeholder on a new board: the derived default, which the server may number. */
export function taskPrefixPlaceholder (name?: string | null): string {
    return `${derivedTaskPrefix(name)} (from the name; a digit is appended if taken)`
}

/**
 * taskPrefix for the board input: sent only when set and changed. A blank field is not sent, so an
 * existing board keeps its prefix and a new one gets the derived default.
 */
export function taskPrefixPatch (original: any | null, draft?: string | null): string | undefined {
    const p = normaliseTaskPrefix(draft)
    if (!p || p === (original?.taskPrefix ?? null)) return undefined
    return p
}

/** The prefixes a board held before its current one, oldest first; its history lists them all. */
export function priorTaskPrefixes (history?: string[] | null, current?: string | null): string[] {
    return (history ?? []).filter(p => p && p !== current)
}

/**
 * A name as the server slugs a board's (AgentBoardData.slug): lower case, then each run of anything
 * but a-z and 0-9 one hyphen, none at either end. An accented letter is a separator, not a plain
 * letter: "Café" is "caf" (tests/fceb1e57/run-1.md T-4).
 */
export function slug (s?: string | null): string {
    return (s ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

/** The limits the server puts on a registered task (board-documents.md §4.5). */
export const TITLE_MAX = 120
export const DESCRIPTION_MAX = 4000

/**
 * Why a New task title would be refused, or null: the server's rule, so the form says it before the
 * save. A title is one line; the rest goes in the description.
 */
export function taskTitleProblem (title?: string | null): string | null {
    const t = (title ?? '').trim()
    if (/[\r\n]/.test(t)) return 'A title is one line; put the rest in the description'
    if (t.length > TITLE_MAX) return `Titles are at most ${TITLE_MAX} characters (this one is ${t.length}); put the rest in the description`
    return null
}

/** Why a New task description would be refused, or null. */
export function taskDescriptionProblem (description?: string | null): string | null {
    const d = description ?? ''
    return d.length > DESCRIPTION_MAX ? `A description is at most ${DESCRIPTION_MAX} characters (this one is ${d.length})` : null
}

/** The New task form's input: the title trimmed, the description whole or left out when blank. */
export function taskRegisterInput (draft: { title: string, description?: string, externalRef: string, sourceUrl: string }) {
    return {
        title: draft.title.trim(),
        description: draft.description?.trim() ? draft.description : null,
        externalRef: draft.externalRef.trim() || null,
        sourceUrl: draft.sourceUrl.trim() || null,
    }
}

/**
 * The form's documents block. rootSet tells an explicit root -- the empty string, the repository's
 * own root, included -- from none, which leaves the default (boards/{board}/ when shared).
 */
export interface DocumentsDraft {
    prefix: string
    shared: boolean
    rootSet: boolean
    root: string
}

export function documentsDraftOf (board: any | null): DocumentsDraft {
    const d = board?.documents
    return {
        prefix: d?.prefix ?? '',
        shared: !!d?.shared,
        rootSet: d?.root != null,
        root: d?.root ?? '',
    }
}

/** The block as the input takes it: every member, null restoring its default, root '' kept. */
export function documentsInput (draft: DocumentsDraft): { prefix: string | null, shared: boolean, root: string | null } {
    return {
        prefix: draft.prefix.trim() || null,
        shared: !!draft.shared,
        root: draft.rootSet ? draft.root.trim() : null,
    }
}

/**
 * documents for the board input: the whole block when anything in it changed, else undefined (not
 * sent). A new board sends it only when something was set.
 */
export function documentsPatch (original: any | null, draft: DocumentsDraft) {
    const next = documentsInput(draft)
    const before = documentsInput(documentsDraftOf(original))
    const same = next.prefix === before.prefix && next.shared === before.shared && next.root === before.root
    return same ? undefined : next
}

/** The root field's placeholder: the default the board gets when no root is set. */
export function documentsRootPlaceholder (shared: boolean): string {
    return shared ? 'boards/{board}/ (the default for a shared repository)' : 'the repository root (the default)'
}

/**
 * Which field a save refusal belongs to, so it is shown beside that field: the task prefix, the
 * documents block, the default level, the level ladder, the target, or neither (null, shown as a
 * notification).
 */
export function boardFieldOfError (message?: string | null): 'taskPrefix' | 'documents' | 'defaultTaskLevel' | 'ladder' | 'target' | null {
    const m = message ?? ''
    if (/defaultTaskLevel/.test(m)) return 'defaultTaskLevel'
    // The ladder (task RD3-6): a rung unnamed or named twice, too many, or removed while levels are set.
    if (/settings\.ladder/.test(m)) return 'ladder'
    if (/taskPrefix|task-key prefix/.test(m)) return 'taskPrefix'
    if (/documents\.(prefix|root)|documents prefix|documents root/.test(m)) return 'documents'
    // The target (task RD2-4): missing, not found, archived, another org's, or not a member of a perspective.
    if (/\b[Tt]arget\b/.test(m)) return 'target'
    return null
}
