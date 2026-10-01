// Task groups and tags on the surfaces people read (task RD2-31, task-groups-and-tags.md §6).
//
// The server holds the groups, their progress, which groups a task waits on and the tags; what is
// worked out here is how they read (a colour, a label, a lane, a filter) and what the editors send.
// Pure, so every surface shares one rule and it is testable.

/** A board's group as the page reads it (AgentBoard.groups). */
export interface TaskGroup {
    uuid?: string
    key: string
    name?: string | null
    description?: string | null
    order?: number | null
    dependsOn?: string[] | null
    defaultWorkLevel?: number | null
    status?: 'OPEN' | 'CLOSED' | null
    progress?: { total?: number | null, done?: number | null, open?: number | null, complete?: boolean | null } | null
    spentMicros?: number | null
}

/** Colours a group's edge and chip take; saturated enough to read as a stripe, light enough for text on them. */
export const GROUP_PALETTE = [
    '#2f7ed8', '#d9534f', '#3a9d5d', '#e0892b', '#8e5bc5', '#1fa3a3', '#c2410c', '#6b7f2a',
    '#c0467f', '#4f6bd8', '#9a6b1f', '#2b8a6e', '#b5445a', '#5a7fa3', '#7a4fb0', '#a8792c',
]

/** FNV-1a over the key: the same key always lands on the same colour, on every page and every browser. */
function hash (s: string): number {
    let h = 0x811c9dc5
    for (let i = 0; i < s.length; i++) {
        h ^= s.charCodeAt(i)
        h = Math.imul(h, 0x01000193) >>> 0
    }
    return h >>> 0
}

/** A group's colour, from its key alone; none (null) for a task in no group. */
export function groupColour (key: string | null | undefined): string | null {
    if (!key) return null
    return GROUP_PALETTE[hash(key) % GROUP_PALETTE.length]
}

/** The board's groups in display order: `order`, then key. */
export function sortedGroups (board: any): TaskGroup[] {
    return [...(board?.groups ?? [])].sort((a: TaskGroup, b: TaskGroup) =>
        (a.order ?? 0) - (b.order ?? 0) || String(a.key).localeCompare(String(b.key)))
}

/** A group by key on the board. */
export function groupByKey (board: any, key: string | null | undefined): TaskGroup | undefined {
    return key ? (board?.groups ?? []).find((g: TaskGroup) => g.key === key) : undefined
}

/** "key · name", or the key alone. */
export function groupLabel (g: TaskGroup | null | undefined): string {
    if (!g) return ''
    return g.name ? `${g.key} · ${g.name}` : g.key
}

/** "3 of 5 done", or "no tasks" for an empty group. */
export function groupProgress (g: TaskGroup | null | undefined): string {
    const total = g?.progress?.total ?? 0
    if (!total) return 'no tasks'
    return `${g?.progress?.done ?? 0} of ${total} done`
}

/**
 * The groups a group waits on: the ones it depends on that still have an open task (the server's
 * rule for a task's waitingOnGroups, D4). An empty dependency holds nothing back.
 */
export function groupWaitingOn (g: TaskGroup | null | undefined, board: any): string[] {
    return (g?.dependsOn ?? []).filter(k => (groupByKey(board, k)?.progress?.open ?? 0) > 0)
}

/** A gated task's tag: "waiting on group first", "waiting on groups a, b"; null when it waits on none. */
export function waitingOnLabel (task: any): string | null {
    const keys: string[] = task?.waitingOnGroups ?? []
    if (!keys.length) return null
    return `waiting on group${keys.length > 1 ? 's' : ''} ${keys.join(', ')}`
}

// ---------- tags ----------

/** The most tags a task carries (the server's limit). */
export const MAX_TAGS = 20

/** A tag key as the server stores it: trimmed and lower case; blank is no tag. */
export function normaliseTag (text: string | null | undefined): string | null {
    const t = (text ?? '').trim().toLowerCase()
    return t ? t : null
}

/** Tags typed as free text, comma separated: normalised, blanks and repeats dropped, in the order typed. */
export function parseTags (text: string | null | undefined): string[] {
    const out: string[] = []
    for (const part of (text ?? '').split(',')) {
        const t = normaliseTag(part)
        if (t && !out.includes(t)) out.push(t)
    }
    return out
}

/** A task's tag keys. */
export function tagKeys (task: any): string[] {
    return (task?.tags ?? []).map((t: any) => t?.key).filter((k: any) => !!k)
}

/**
 * The tags to send for a task's edited list (agentTaskSetTags replaces them all), or undefined when
 * the list is what the task already has. Values the task carries are kept for the keys that stay.
 */
export function tagsToSet (task: any, keys: string[]): { key: string, value?: string | null }[] | undefined {
    const next: string[] = []
    for (const k of keys) {
        const t = normaliseTag(k)
        if (t && !next.includes(t)) next.push(t)
    }
    const had = tagKeys(task)
    if (next.length === had.length && next.every((k, i) => k === had[i])) return undefined
    return next.map(k => {
        const kept = (task?.tags ?? []).find((t: any) => t?.key === k)
        return kept?.value != null ? { key: k, value: kept.value } : { key: k }
    })
}

/** Too many tags, said before the server refuses it. */
export function tagsProblem (keys: string[]): string | null {
    return keys.length > MAX_TAGS ? `At most ${MAX_TAGS} tags on a task` : null
}

// ---------- selects, filters and the URL ----------

/** The value a select uses for "no group"; the store sends it as null. */
export const NO_GROUP = ''

/**
 * A select's options over the board's groups, in order: every group, or only OPEN ones (what a task
 * may be registered or moved into), with "none" first when asked.
 */
export function groupOptions (board: any, opts: { openOnly?: boolean, none?: boolean } = {}): { label: string, value: string }[] {
    const groups = sortedGroups(board).filter(g => !opts.openOnly || g.status !== 'CLOSED')
    const out = groups.map(g => ({ label: groupLabel(g), value: g.key }))
    return opts.none ? [{ label: 'none', value: NO_GROUP }, ...out] : out
}

/** The group key to send for a select's value: null for "none". */
export function groupToSend (value: string | null | undefined): string | null {
    return value == null || value === NO_GROUP ? null : value
}

/** Whether a filter text names the task's group or one of its tags (any part, any case). */
export function matchesGroupOrTag (task: any, text: string | null | undefined): boolean {
    const q = (text ?? '').trim().toLowerCase()
    if (!q) return false
    const group = String(task?.group?.key ?? '').toLowerCase()
    return (!!group && group.includes(q)) || tagKeys(task).some(k => k.toLowerCase().includes(q))
}

/** Whether a task is in the group and carries the tag the filters ask for; null filters pass everything. */
export function passesGroupAndTag (task: any, group: string | null | undefined, tag: string | null | undefined): boolean {
    if (group === NO_GROUP) {
        if (task?.group?.key) return false
    } else if (group && task?.group?.key !== group) return false
    if (tag && !tagKeys(task).includes(tag)) return false
    return true
}

/** The distinct tag keys over some tasks, sorted: the tag filter's options. */
export function tagOptions (tasks: any[]): { label: string, value: string }[] {
    const keys = new Set<string>()
    for (const t of tasks ?? []) for (const k of tagKeys(t)) keys.add(k)
    return [...keys].sort().map(k => ({ label: k, value: k }))
}

/** The group filter the URL carries (?group=core-work), or null; "none" names the ungrouped tasks. */
export function groupFromQuery (query: Record<string, any> | null | undefined): string | null {
    const v = query?.group
    if (v === 'none') return NO_GROUP
    return typeof v === 'string' && v ? v : null
}

/** The tag filter the URL carries (?tag=client-req), or null. */
export function tagFromQuery (query: Record<string, any> | null | undefined): string | null {
    const v = query?.tag
    return typeof v === 'string' && v ? v : null
}

/** The query with the group and tag filters set; null drops their keys. */
export function withGroupQuery (query: Record<string, any> | null | undefined, group: string | null,
    tag: string | null): Record<string, any> {
    const q: Record<string, any> = { ...(query ?? {}) }
    if (group === NO_GROUP) q.group = 'none'
    else if (group) q.group = group
    else delete q.group
    if (tag) q.tag = tag
    else delete q.tag
    return q
}

// ---------- the kanban's group lanes ----------

/** Where a group's lane sorts: its place in the board's order; an unknown key after the known ones. */
export function groupRank (key: string, board: any): number {
    const i = sortedGroups(board).findIndex(g => g.key === key)
    return i < 0 ? Number.MAX_SAFE_INTEGER : i
}

/** A group lane's heading: "core-work · Core services", "Ungrouped" for the tasks in none. */
export function groupLaneLabel (key: string | null, board: any): string {
    if (key == null) return 'Ungrouped'
    return groupLabel(groupByKey(board, key) ?? { key })
}

// ---------- PERT ----------

/** A group's depth in its dependencies: 0 for one that waits on nothing on the board. Loops read 0. */
export function groupLayers (board: any): Map<string, number> {
    const groups = sortedGroups(board)
    const known = new Set(groups.map(g => g.key))
    const memo = new Map<string, number>()
    const layer = (key: string, seen: Set<string>): number => {
        if (memo.has(key)) return memo.get(key)!
        if (seen.has(key)) return 0
        seen.add(key)
        const deps = (groupByKey(board, key)?.dependsOn ?? []).filter(d => known.has(d))
        const l = deps.length ? Math.max(...deps.map(d => layer(d, seen))) + 1 : 0
        memo.set(key, l)
        return l
    }
    for (const g of groups) layer(g.key, new Set())
    return memo
}

// ---------- the New task form ----------

/**
 * What the New task form adds to a registration (task RD2-31): the group ("none" sends nothing), the
 * tags typed as free text, and the level. Only what was set is sent.
 */
export function registerGroupFields (draft: { group?: string | null, tagsText?: string | null, workLevel?: number | null }):
    { group?: string, tags?: { key: string }[], workLevel?: number } {
    const out: { group?: string, tags?: { key: string }[], workLevel?: number } = {}
    const group = groupToSend(draft.group)
    if (group) out.group = group
    const tags = parseTags(draft.tagsText)
    if (tags.length) out.tags = tags.map(key => ({ key }))
    if (draft.workLevel != null && Number.isInteger(draft.workLevel)) out.workLevel = draft.workLevel
    return out
}

// ---------- the groups tab's form ----------

/** The add / edit form's draft. */
export interface GroupDraft {
    uuid: string | null
    key: string
    name: string
    description: string
    dependsOn: string[]
    defaultWorkLevel: number | null
    status: 'OPEN' | 'CLOSED'
}

/** A draft for a new group, or for editing one. */
export function groupDraftOf (g?: TaskGroup | null): GroupDraft {
    return {
        uuid: g?.uuid ?? null,
        key: g?.key ?? '',
        name: g?.name ?? '',
        description: g?.description ?? '',
        dependsOn: [...(g?.dependsOn ?? [])],
        defaultWorkLevel: g?.defaultWorkLevel ?? null,
        status: g?.status === 'CLOSED' ? 'CLOSED' : 'OPEN',
    }
}

/**
 * The TaskGroupInput a draft sends (agentBoardGroupSet): the whole group, blanks as null, so an edit
 * says what the group is rather than what changed; the uuid names the group on an edit. On a board
 * without a ladder (withLevel false, task RD3-6) the default level is left out, which keeps it: the
 * server refuses a level there, and a group's level kept from before is not the form's to clear.
 */
export function groupInputOf (d: GroupDraft, opts: { withLevel?: boolean } = {}): Record<string, any> {
    const input: Record<string, any> = {
        key: d.key.trim().toLowerCase(),
        name: d.name.trim() || null,
        description: d.description.trim() || null,
        dependsOn: [...d.dependsOn],
        defaultWorkLevel: d.defaultWorkLevel,
        status: d.status,
    }
    if (opts.withLevel === false) delete input.defaultWorkLevel
    if (d.uuid) input.uuid = d.uuid
    return input
}

/** The groups a group may depend on in the form: every other group of the board. */
export function dependencyOptions (board: any, key: string | null | undefined): { label: string, value: string }[] {
    return groupOptions(board).filter(o => o.value !== (key ?? '').trim().toLowerCase())
}

/**
 * A key the board already gives another group, refused in the form before anything is sent, in the
 * server's words (RD2-31 T-1). agentBoardGroupSet is create-or-edit by key: an Add with a taken key
 * would otherwise overwrite that group, name, dependencies and all, without a word. On an edit the
 * group's own key is not taken; another group's is.
 */
export function groupKeyTaken (board: any, d: GroupDraft): string | null {
    const key = d.key.trim().toLowerCase()
    if (!key) return null
    const holder = groupByKey(board, key)
    if (!holder) return null
    if (d.uuid && holder.uuid === d.uuid) return null
    return `group ${key} exists on this board`
}

/** Which field of the form a refusal is about, so it shows beside it; null for the form as a whole. */
export function groupFieldOfError (message: string | null | undefined): 'key' | 'name' | 'dependsOn' | 'defaultWorkLevel' | null {
    const m = (message ?? '').toLowerCase()
    if (!m) return null
    if (m.includes('group key') || m.includes('exists on this board') || m.includes('key is immutable')) return 'key'
    if (m.includes('depends on') || m.includes('group cycle')) return 'dependsOn'
    // A level off the board's ladder, or on a board without one (task RD3-6).
    if (m.includes('level is') || m.includes('ladder')) return 'defaultWorkLevel'
    if (m.includes('group name')) return 'name'
    return null
}
