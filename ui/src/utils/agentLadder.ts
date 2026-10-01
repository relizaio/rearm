// The board form's level ladder (task RD3-6): an ordered list of rungs, each numbered by its place from
// 0, named, and optionally described, plus an optional prompt that overrides the ladder section the
// board's roles and coordinator are served. Opt-in: an emptied list removes the ladder (null), and then
// the board's tasks carry no level. Replaced whole when it is saved, as the staleness block is.

import { MAX_LADDER_LEVELS, levelOptions } from './agentTaskLevel'

export interface LadderLevelDraft { name: string, description: string }
export interface LadderDraft { levels: LadderLevelDraft[], prompt: string }
export interface LadderInput {
    levels: { number: number, name: string, description: string | null }[]
    prompt: string | null
}

/** The prompt field's hint: the placeholders the server renders into the text. */
export const LADDER_PROMPT_HINT = 'Ladder prompt (optional): replaces the default ladder section served to every role and the '
    + 'coordinator on this board; {{levels}} is filled with the rungs and {{last}} with the last level\'s number.'

/** What a ladder means, for the form. */
export const LADDER_HELP = 'Levels are opt-in. Without a ladder, tasks carry no level and the level chip, column, filter and '
    + 'editor are hidden. With one, every task has a level (the board default, 0 unless set), a level off the ladder is '
    + 'refused, and the served prompts explain the rungs. Removing it is refused while a task or group carries a level.'

/** The form's draft of a board's ladder (null or absent: no rungs, no prompt). */
export function ladderDraftOf (ladder: any): LadderDraft {
    const levels = [...(ladder?.levels ?? [])].filter(l => l != null)
        .sort((a, b) => (a.number ?? 0) - (b.number ?? 0))
        .map(l => ({ name: l.name ?? '', description: l.description ?? '' }))
    return { levels, prompt: ladder?.prompt ?? '' }
}

/** The draft with a blank rung at the end. */
export function addLadderLevel (draft: LadderDraft): LadderDraft {
    return { ...draft, levels: [...draft.levels, { name: '', description: '' }] }
}

/** The draft without the rung at index i; the rungs after it move up a number. */
export function removeLadderLevel (draft: LadderDraft, i: number): LadderDraft {
    return { ...draft, levels: draft.levels.filter((_, j) => j !== i) }
}

/** The draft with the rung at index i moved by delta places (-1 up, +1 down); a move off either end is none. */
export function moveLadderLevel (draft: LadderDraft, i: number, delta: number): LadderDraft {
    const j = i + delta
    if (i < 0 || i >= draft.levels.length || j < 0 || j >= draft.levels.length) return draft
    const levels = [...draft.levels]
    ;[levels[i], levels[j]] = [levels[j], levels[i]]
    return { ...draft, levels }
}

/** The draft with one field of the rung at index i replaced. */
export function setLadderLevel (draft: LadderDraft, i: number, field: keyof LadderLevelDraft, value: string | null): LadderDraft {
    return { ...draft, levels: draft.levels.map((l, j) => j === i ? { ...l, [field]: value ?? '' } : l) }
}

/**
 * Why the draft cannot be saved, or '' when it can: every rung named, no name twice (case aside, as
 * the server compares), at most MAX_LADDER_LEVELS rungs. An empty list is fine: it removes the ladder.
 */
export function ladderProblem (draft: LadderDraft | null | undefined): string {
    const levels = draft?.levels ?? []
    if (levels.length > MAX_LADDER_LEVELS) return `A ladder has at most ${MAX_LADDER_LEVELS} levels; this one has ${levels.length}`
    const seen = new Set<string>()
    for (let i = 0; i < levels.length; i++) {
        const name = (levels[i].name ?? '').trim()
        if (!name) return `Level ${i} needs a name`
        const k = name.toLowerCase()
        if (seen.has(k)) return `The ladder names level '${name}' twice`
        seen.add(k)
    }
    return ''
}

/** The AgentBoardLadderInput the draft stands for, each rung numbered by its place; null for no rungs. */
export function ladderInputOf (draft: LadderDraft | null | undefined): LadderInput | null {
    const levels = draft?.levels ?? []
    if (!levels.length) return null
    return {
        levels: levels.map((l, i) => ({ number: i, name: (l.name ?? '').trim(), description: (l.description ?? '').trim() || null })),
        prompt: (draft?.prompt ?? '').trim() ? draft!.prompt : null,
    }
}

/**
 * What the form sends as settings.ladder: undefined when nothing changed (left out, kept), null when
 * every rung was removed (the ladder removed, its prompt with it), else the whole ladder.
 */
export function ladderPatch (original: any, draft: LadderDraft | null | undefined): LadderInput | null | undefined {
    const before = ladderInputOf(ladderDraftOf(original))
    const after = ladderInputOf(draft)
    return JSON.stringify(before) === JSON.stringify(after) ? undefined : after
}

/** The rungs the board's default level may take while the draft is edited: the draft's own, named. */
export function draftLevelOptions (draft: LadderDraft | null | undefined): { label: string, value: number }[] {
    return levelOptions({ ladder: ladderInputOf(draft) })
}
