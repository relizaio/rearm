// Task level on the surfaces people read (RD2-1): the depth in the product tree a task belongs to.
//
// The server resolves the level a board reads -- the task's own, else its group's default, else the
// board's default -- and serves it as effectiveLevel; what is worked out here is only how it reads (a
// chip, a tooltip, a lane) and what the editor sends. Pure, so every surface shares one rule and it is
// testable.
//
// Levels are opt-in per board (task RD3-6): a board declares a ladder, its rungs named, and only then
// do its tasks carry a level; every surface here reads nothing on a board without one.

import { groupLaneLabel, groupRank } from './agentTaskGroups'

/** The most rungs a ladder may have, so the highest level a task may carry is MAX_LEVEL (task RD3-6). */
export const MAX_LADDER_LEVELS = 10

/** The highest level the server accepts on any ladder. */
export const MAX_LEVEL = MAX_LADDER_LEVELS - 1

export interface LadderRung { number: number, name: string, description: string | null }

/**
 * The board's ladder, 0 first (task RD3-6): its rungs numbered by their place, as the server stores
 * them; empty for a board without one.
 */
export function ladderOf (board?: any): LadderRung[] {
    const levels: any[] = board?.ladder?.levels ?? []
    return levels.filter(l => l != null)
        .map((l, i) => ({ number: Number.isInteger(l.number) ? l.number : i, name: l.name ?? '', description: l.description ?? null }))
        .sort((a, b) => a.number - b.number)
}

/**
 * Whether the board declares a level ladder (task RD3-6). Levels are opt-in: without one every level
 * reads null on the server, and the chip, the column, the filter, the editor, the group default and
 * the grouping by level are hidden -- a task that kept a level from before is ignored.
 */
export function hasLadder (board?: any): boolean {
    return ladderOf(board).length > 0
}

/** A rung's name on the board's ladder, or null past it or without one. */
export function levelName (level: number | null | undefined, board?: any): string | null {
    if (level == null) return null
    return ladderOf(board).find(r => r.number === level)?.name || null
}

/** A level as it reads everywhere (task RD3-6): "1 · solution", the number alone when the rung is missing. */
export function levelLabel (level: number | null | undefined, board?: any): string | null {
    if (level == null) return null
    const name = levelName(level, board)
    return name ? `${level} · ${name}` : String(level)
}

/** The rungs a picker offers, "0 · requirements" first; none without a ladder. */
export function levelOptions (board?: any): { label: string, value: number }[] {
    return ladderOf(board).map(r => ({ label: levelLabel(r.number, board)!, value: r.number }))
}

/** The board's ladder in one line, for a hint: "0 requirements · 1 solution"; '' without one. */
export function ladderHint (board?: any): string {
    return ladderOf(board).map(r => `${r.number} ${r.name}`).join(' · ')
}

/**
 * The level the board reads: the served effectiveLevel, else the task's own, else its group's default,
 * else the board default, else 0 -- and null on a board without a ladder, whatever the task carries
 * (task RD3-6).
 */
export function levelOf (task: any, board?: any): number | null {
    if (!hasLadder(board)) return null
    if (task?.effectiveLevel != null) return task.effectiveLevel
    if (task?.level != null) return task.level
    const groupLevel = task?.group?.key
        ? (board?.groups ?? []).find((g: any) => g?.key === task.group.key)?.defaultLevel
        : null
    if (groupLevel != null) return groupLevel
    return board?.defaultTaskLevel ?? 0
}

/** Where the level comes from: the task itself, a default (its group's or the board's), or nowhere. */
export function levelSource (task: any, board?: any): 'set' | 'default' | null {
    if (levelOf(task, board) == null) return null
    return task?.level != null ? 'set' : 'default'
}

/** The chip: "2 · objects", or null for a task with no level (no chip), and always without a ladder. */
export function taskLevelLabel (task: any, board?: any): string | null {
    return levelLabel(levelOf(task, board), board)
}

/**
 * The chip's tooltip: "level 2 · objects: what the objects are (board default)", "(group default)"
 * when its group gives it, or "(set by <actor>)" when the task carries its own. actorName names levelSetBy.
 */
export function levelTooltip (task: any, board?: any, actorName?: (a: any) => string): string | null {
    const l = levelOf(task, board)
    if (l == null) return null
    const description = ladderOf(board).find(r => r.number === l)?.description
    const head = `level ${levelLabel(l, board)}${description ? ': ' + description : ''}`
    if (levelSource(task, board) === 'default') {
        const fromGroup = task?.group?.key
            && (board?.groups ?? []).find((g: any) => g?.key === task.group.key)?.defaultLevel === l
        return `${head} (${fromGroup ? 'group' : 'board'} default)`
    }
    return task?.levelSetBy && actorName ? `${head} (set by ${actorName(task.levelSetBy)})` : head
}

/** The editor's placeholder: what the task reads with no level of its own. */
export function levelPlaceholder (board?: any): string {
    if (!hasLadder(board)) return 'none'
    return `board default ${levelLabel(board?.defaultTaskLevel ?? 0, board)}`
}

/**
 * The level to send for a draft, or undefined when there is nothing to send: no draft, no ladder, a
 * value off the board's ladder, or the level the task already has.
 */
export function levelToSet (task: any, draft: number | null | undefined, board?: any): number | undefined {
    if (draft == null || !Number.isInteger(draft) || !ladderOf(board).some(r => r.number === draft)) return undefined
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

/** Whether a task passes the kanban's level filter: any task with no filter, or on a board without a ladder. */
export function passesLevel (task: any, board: any, level: number | null | undefined): boolean {
    return level == null || !hasLadder(board) || levelOf(task, board) === level
}

/** A board default for the form's input: sent only when changed; blank sends null (clears it). */
export function defaultLevelPatch (board: any, draft: number | null | undefined): { changed: boolean, value: number | null } {
    const next = draft == null ? null : draft
    return { changed: next !== (board?.defaultTaskLevel ?? null), value: next }
}

// ---------- grouping the kanban (by level, RD2-1; by task group, RD2-31) ----------

export interface Lane { key: string, label: string, tasks: any[] }

interface Grouping {
    /** The lane a task goes in; null is the "none" lane, last. */
    keyOf: (task: any, board?: any) => number | string | null
    label: (key: number | string | null, board?: any) => string
    /** Where a lane sorts, when not by its key: task groups follow the board's order. */
    rank?: (key: number | string, board?: any) => number
}

/** The ways the kanban can be grouped, by the name the URL carries (?groupBy=level). */
export const GROUPINGS: Record<string, Grouping> = {
    level: {
        keyOf: (t, b) => levelOf(t, b),
        label: (k, b) => k == null ? 'no level' : levelLabel(Number(k), b)!,
    },
    // A lane per task group in the board's order, "Ungrouped" last (RD2-31).
    group: {
        keyOf: t => t?.group?.key ?? null,
        label: (k, b) => groupLaneLabel(k == null ? null : String(k), b),
        rank: (k, b) => groupRank(String(k), b),
    },
}

/** The group-by options the toggle offers: none, then every grouping -- by level only on a board with a ladder. */
export function groupByOptions (board?: any): { label: string, value: string }[] {
    return [{ label: 'none', value: 'none' },
        ...Object.keys(GROUPINGS).filter(k => k !== 'level' || hasLadder(board)).map(k => ({ label: k, value: k }))]
}

/** The grouping in effect: the one asked for, or 'none' for by-level on a board without a ladder (task RD3-6). */
export function groupingFor (groupBy: string | null | undefined, board?: any): string {
    if (!groupBy || !GROUPINGS[groupBy]) return 'none'
    return groupBy === 'level' && !hasLadder(board) ? 'none' : groupBy
}

/**
 * Tasks in lanes for a grouping: one lane per key in ascending order, the "none" lane last, each
 * with its tasks in the order given. 'none', an unknown grouping, or by level on a board without a
 * ladder is one lane of everything.
 */
export function groupTasks (tasks: any[], groupBy: string | null | undefined, board?: any): Lane[] {
    const g = GROUPINGS[groupingFor(groupBy, board)]
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
        if (g.rank) return g.rank(a, board) - g.rank(b, board) || String(a).localeCompare(String(b))
        return typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b))
    })
    return keys.map(k => ({ key: k == null ? 'none' : String(k), label: `${g.label(k, board)} (${byKey.get(k)!.length})`, tasks: byKey.get(k)! }))
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

/** A card's reference with the level after it, "RD2-1 · 2 · objects", for cards too small for a chip. */
export function refWithLevel (ref: string, task: any, board?: any): string {
    const l = taskLevelLabel(task, board)
    return l ? `${ref} · ${l}` : ref
}
