// Earlier revisions of tasks, boards and role configs (task 22ddc644). The server reads them
// from the audit rows each save already writes and serves each snapshot through the live
// type; here two snapshots are compared field by field. Pure and dependency-free like
// agentDocuments.ts, so the spec can cover it without a store.

export type RevisionKind = 'task' | 'board' | 'role'

export interface Revision {
    revision: number
    at?: string | null
    task?: any
    board?: any
    roleConfig?: any
}

/** One entry of a history list: the live object first (revision null), then the revisions. */
export interface HistoryEntry {
    revision: number | null
    at: string | null
    snapshot: any
}

export interface FieldChange {
    key: string
    change: 'changed' | 'added' | 'removed'
    before: string
    after: string
    /** Long or multi-line text on either side: shown as two texts side by side, not inline. */
    long: boolean
    /** The raw values, for the side-by-side view. */
    beforeValue: any
    afterValue: any
}

/** The snapshot a revision carries for its kind; null when the server could not parse it. */
export function snapshotOf (kind: RevisionKind, rev: Revision | null | undefined): any {
    if (!rev) return null
    if (kind === 'task') return rev.task ?? null
    if (kind === 'board') return rev.board ?? null
    return rev.roleConfig ?? null
}

/**
 * The list the history view shows, newest first: the live object when the host has it (the
 * newest audit row is the state before the last save, so without it the last edit would not be
 * visible), then each revision.
 */
export function historyEntries (kind: RevisionKind, revisions: Revision[], current?: any): HistoryEntry[] {
    const out: HistoryEntry[] = []
    if (current) out.push({ revision: null, at: null, snapshot: current })
    for (const r of revisions ?? []) out.push({ revision: r.revision, at: r.at ?? null, snapshot: snapshotOf(kind, r) })
    return out
}

const IGNORED = new Set(['__typename'])

/**
 * A value as sorted JSON without the client's `__typename` at any depth (T-1, run 1): Apollo adds
 * it to every nested object, so it is neither a change nor something a person should read.
 */
function canonical (v: any): string {
    return JSON.stringify(v, (_k, val) => (val && typeof val === 'object' && !Array.isArray(val))
        ? Object.keys(val).filter(k => !IGNORED.has(k)).sort().reduce((acc: any, k) => { acc[k] = val[k]; return acc }, {})
        : val)
}

const short = (id: any): string => (typeof id === 'string' ? id.slice(0, 8) : '')

/** Who did something, as the board's actors read: the name, else the kind and a short id. */
function actorText (a: any): string {
    if (a.name) return String(a.name)
    return [String(a.kind ?? '').toLowerCase(), short(a.uuid)].filter(Boolean).join(' ')
}

function clip (s: string): string {
    return s.length > 120 ? s.slice(0, 117) + '...' : s
}

/**
 * The objects a snapshot carries, in a person's words rather than as JSON (T-1, run 1). Null when
 * the key is not one of them, or the value does not look like it.
 */
function summarise (key: string | undefined, v: any): string | null {
    if (key === 'assignment' && (v.role || v.session)) {
        return [v.role, v.session ? `session ${short(v.session)}` : null].filter(Boolean).join(' · ')
    }
    if (key === 'hold' && (v.level || v.kind)) {
        const what = [v.level, v.kind].filter(Boolean).map((x: string) => x.toLowerCase().replace(/_/g, ' ')).join(' ')
        return v.reason ? `${what}: ${v.reason}` : what
    }
    if (key === 'lock' && v.level) {
        return v.reason ? `${String(v.level).toLowerCase()}: ${v.reason}` : String(v.level).toLowerCase()
    }
    if (key === 'coordinatorSeat' && v.session) return `session ${short(v.session)}`
    // An actor on its own -- orderSetBy, budgetSetBy, lastUpdatedActor and the like.
    const keys = Object.keys(v).filter(k => !IGNORED.has(k))
    if (keys.length && keys.every(k => ['kind', 'uuid', 'name'].includes(k)) && v.kind) return actorText(v)
    return null
}

const LONG_TEXT = 80

function isLong (v: any): boolean {
    return typeof v === 'string' && (v.length > LONG_TEXT || v.includes('\n'))
}

/**
 * A value in one short line: arrays by length, known objects (by their field's key) in words,
 * other objects as compact JSON without `__typename`, nothing as a dash.
 */
export function describeValue (v: any, key?: string): string {
    if (v === null || v === undefined || v === '') return '—'
    if (Array.isArray(v)) return `${v.length} item${v.length === 1 ? '' : 's'}`
    if (typeof v === 'object') return clip(summarise(key, v) ?? canonical(v))
    return clip(String(v))
}

/**
 * What changed from `before` to `after`, top-level fields in `after`'s order. A field only one
 * side carries at all (undefined, not null) was not selected on that side -- the live object and
 * the revisions come from different queries -- so it is skipped rather than read as removed.
 * Arrays of the same length whose items changed say so, since the length alone would not.
 */
export function diffSnapshots (before: any, after: any): FieldChange[] {
    if (!before || !after) return []
    const keys: string[] = []
    for (const k of Object.keys(after)) if (!IGNORED.has(k)) keys.push(k)
    for (const k of Object.keys(before)) if (!IGNORED.has(k) && !keys.includes(k)) keys.push(k)
    const out: FieldChange[] = []
    for (const key of keys) {
        const a = before[key]
        const b = after[key]
        if (a === undefined || b === undefined) continue
        if (canonical(a) === canonical(b)) continue
        const emptyA = a === null || a === ''
        const emptyB = b === null || b === ''
        const beforeText = describeValue(a, key)
        let afterText = describeValue(b, key)
        if (Array.isArray(a) && Array.isArray(b) && a.length === b.length) afterText += ' (changed)'
        out.push({
            key,
            change: emptyA ? 'added' : emptyB ? 'removed' : 'changed',
            before: beforeText,
            after: afterText,
            long: isLong(a) || isLong(b),
            beforeValue: a,
            afterValue: b,
        })
    }
    return out
}

/** Short facts about one snapshot for its row in the list (the design's columns per kind). */
export function revisionSummary (kind: RevisionKind, s: any): string[] {
    if (!s) return ['unreadable snapshot']
    const out: string[] = []
    if (kind === 'task') {
        if (s.status) out.push(String(s.status).replace(/_/g, ' '))
        if (s.role) out.push(`role ${s.role}`)
        if (s.hold) out.push(`hold ${String(s.hold.kind ?? s.hold.level ?? '').toLowerCase()}`)
        if (s.orderIndex != null) out.push(`order ${s.orderIndex}`)
        if (s.budgetMicros != null) out.push(`budget $${(s.budgetMicros / 1_000_000).toFixed(2)}`)
        if (s.requiredStrength != null) out.push(`strength ${s.requiredStrength}`)
    } else if (kind === 'board') {
        if (s.status) out.push(String(s.status))
        if (s.lock?.level) out.push(`lock ${String(s.lock.level).toLowerCase()}`)
        if (s.cycleCap != null) out.push(`cycle cap ${s.cycleCap}`)
        if (s.budgetMicros != null) out.push(`budget $${(s.budgetMicros / 1_000_000).toFixed(2)}`)
    } else {
        out.push(s.active === false ? 'inactive' : 'active')
        if (s.orderIndex != null) out.push(`order ${s.orderIndex}`)
        if (typeof s.prompt === 'string') out.push(`prompt ${s.prompt.length} chars`)
    }
    return out
}
