// Task level on the surfaces people read (RD2-1): the depth in the product tree a task belongs to.
//
// The server resolves the level a board reads -- the task's own, else the board's default -- and
// serves it as effectiveLevel; what is worked out here is only how it reads (a chip, a tooltip, a
// lane) and what the editor sends. Pure, so every surface shares one rule and it is testable.

/** The client's process ladder, by level; deeper levels are allowed and read as "level N". */
export const LEVEL_LADDER = [
    'requirements',
    'solution blocks and HLD',
    'objects and architecture decisions',
    'components',
    'modules',
]

/** The board form's hint for the default level. */
export const LEVEL_LADDER_HINT = '0 requirements · 1 solution blocks / HLD · 2 objects and ADRs · 3 components · 4 modules'

/** The highest level the server accepts. */
export const MAX_LEVEL = 9

/** The level the board reads: the served effectiveLevel, else the task's own, else the board default. */
export function levelOf (task: any, board?: any): number | null {
    if (task?.effectiveLevel != null) return task.effectiveLevel
    if (task?.level != null) return task.level
    return board?.defaultTaskLevel ?? null
}

/** Where the level comes from: the task itself, the board's default, or nowhere. */
export function levelSource (task: any, board?: any): 'set' | 'default' | null {
    if (task?.level != null) return 'set'
    return levelOf(task, board) == null ? null : 'default'
}

/** The chip: "L2", or null for a task with no level (no chip, not "L?"). */
export function levelLabel (task: any, board?: any): string | null {
    const l = levelOf(task, board)
    return l == null ? null : `L${l}`
}

/** A level's name on the ladder, or null past it. */
export function levelName (level: number | null | undefined): string | null {
    return level == null ? null : (LEVEL_LADDER[level] ?? null)
}

/**
 * The chip's tooltip: "level 2 · objects and architecture decisions (board default)", or
 * "(set by <actor>)" when the task carries its own. actorName names levelSetBy.
 */
export function levelTooltip (task: any, board?: any, actorName?: (a: any) => string): string | null {
    const l = levelOf(task, board)
    if (l == null) return null
    const name = levelName(l)
    const head = name ? `level ${l} · ${name}` : `level ${l}`
    if (levelSource(task, board) === 'default') return `${head} (board default)`
    return task?.levelSetBy && actorName ? `${head} (set by ${actorName(task.levelSetBy)})` : head
}

/** The editor's placeholder: what the task reads with no level of its own. */
export function levelPlaceholder (board?: any): string {
    return board?.defaultTaskLevel != null ? `board default ${board.defaultTaskLevel}` : 'none'
}

/**
 * The level to send for a draft, or undefined when there is nothing to send: no draft, a value
 * outside 0..9 or not whole, or the level the task already has.
 */
export function levelToSet (task: any, draft: number | null | undefined): number | undefined {
    if (draft == null || !Number.isInteger(draft) || draft < 0 || draft > MAX_LEVEL) return undefined
    return draft === task?.level ? undefined : draft
}

/**
 * Whether a filter text names this task's level: "L2", "l2" and "level 2" all match level 2; a
 * text that names no level matches nothing here (the caller matches it against the rest).
 */
export function matchesLevel (task: any, board: any, text: string | null | undefined): boolean {
    const m = /^\s*(?:l|level\s*)(\d)\s*$/i.exec(text ?? '')
    return !!m && levelOf(task, board) === Number(m[1])
}

/** A board default for the form's input: sent only when changed; blank sends null (clears it). */
export function defaultLevelPatch (board: any, draft: number | null | undefined): { changed: boolean, value: number | null } {
    const next = draft == null ? null : draft
    return { changed: next !== (board?.defaultTaskLevel ?? null), value: next }
}

// ---------- grouping the kanban (generic: RD2-2 adds "group" here) ----------

export interface Lane { key: string, label: string, tasks: any[] }

interface Grouping {
    /** The lane a task goes in; null is the "none" lane, last. */
    keyOf: (task: any, board?: any) => number | string | null
    label: (key: number | string | null) => string
}

/** The ways the kanban can be grouped, by the name the URL carries (?groupBy=level). */
export const GROUPINGS: Record<string, Grouping> = {
    level: {
        keyOf: (t, b) => levelOf(t, b),
        label: k => k == null ? 'no level' : `L${k}${levelName(Number(k)) ? ' · ' + levelName(Number(k)) : ''}`,
    },
}

/** The group-by options the toggle offers: none, then every grouping. */
export const GROUP_BY_OPTIONS = [{ label: 'none', value: 'none' },
    ...Object.keys(GROUPINGS).map(k => ({ label: k, value: k }))]

/**
 * Tasks in lanes for a grouping: one lane per key in ascending order, the "none" lane last, each
 * with its tasks in the order given. 'none' or an unknown grouping is one lane of everything.
 */
export function groupTasks (tasks: any[], groupBy: string | null | undefined, board?: any): Lane[] {
    const g = groupBy ? GROUPINGS[groupBy] : undefined
    if (!g) return [{ key: 'all', label: '', tasks: [...(tasks ?? [])] }]
    const byKey = new Map<number | string | null, any[]>()
    for (const t of tasks ?? []) {
        const k = g.keyOf(t, board)
        if (!byKey.has(k)) byKey.set(k, [])
        byKey.get(k)!.push(t)
    }
    const keys = [...byKey.keys()].sort((a, b) => {
        if (a == null) return 1
        if (b == null) return -1
        return typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b))
    })
    return keys.map(k => ({ key: k == null ? 'none' : String(k), label: `${g.label(k)} (${byKey.get(k)!.length})`, tasks: byKey.get(k)! }))
}

// ---------- the URL (?groupBy=level&level=2) ----------

/** The grouping the URL asks for, or 'none'. */
export function groupByFromQuery (query: Record<string, any> | null | undefined): string {
    const v = query?.groupBy
    return typeof v === 'string' && GROUPINGS[v] ? v : 'none'
}

/** The level filter the URL carries, or null. */
export function levelFromQuery (query: Record<string, any> | null | undefined): number | null {
    const v = query?.level
    const n = typeof v === 'string' && /^\d$/.test(v) ? Number(v) : null
    return n
}

/** The query with the grouping and level filter set; 'none' and null drop their keys. */
export function withLevelQuery (query: Record<string, any> | null | undefined, groupBy: string,
    level: number | null): Record<string, any> {
    const q: Record<string, any> = { ...(query ?? {}) }
    if (groupBy && groupBy !== 'none') q.groupBy = groupBy
    else delete q.groupBy
    if (level != null) q.level = String(level)
    else delete q.level
    return q
}

/** A card's reference with the level after it, "RD2-1 · L2", for cards too small for a chip. */
export function refWithLevel (ref: string, task: any, board?: any): string {
    const l = levelLabel(task, board)
    return l ? `${ref} · ${l}` : ref
}
