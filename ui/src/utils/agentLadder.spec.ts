import { describe, expect, it } from 'vitest'
import { addLadderLevel, draftLevelOptions, LADDER_PROMPT_HINT, ladderDraftOf, ladderInputOf, ladderPatch, ladderProblem,
    moveLadderLevel, removeLadderLevel, setLadderLevel } from './agentLadder'
import { boardFieldOfError } from './agentBoardNaming'

// The board form's level ladder (task RD3-6).
const LADDER = { levels: [
    { number: 1, name: 'solution', description: null },
    { number: 0, name: 'requirements', description: 'what the client asked for' },
], prompt: null }

describe('the ladder draft', () => {
    it('reads a board\'s ladder in order, or none', () => {
        expect(ladderDraftOf(LADDER)).toEqual({ levels: [
            { name: 'requirements', description: 'what the client asked for' },
            { name: 'solution', description: '' }], prompt: '' })
        expect(ladderDraftOf(null)).toEqual({ levels: [], prompt: '' })
        expect(ladderDraftOf({ levels: [{ number: 0, name: 'a' }], prompt: 'Levels: {{levels}}' }).prompt).toBe('Levels: {{levels}}')
    })

    it('adds, removes, reorders and edits a rung without touching the draft it was given', () => {
        const d = ladderDraftOf(LADDER)
        const added = addLadderLevel(d)
        expect(added.levels).toHaveLength(3)
        expect(added.levels[2]).toEqual({ name: '', description: '' })
        expect(d.levels).toHaveLength(2)
        expect(removeLadderLevel(d, 0).levels.map(l => l.name)).toEqual(['solution'])
        expect(moveLadderLevel(d, 1, -1).levels.map(l => l.name)).toEqual(['solution', 'requirements'])
        expect(moveLadderLevel(d, 0, -1)).toBe(d)
        expect(moveLadderLevel(d, 1, 1)).toBe(d)
        expect(setLadderLevel(d, 1, 'name', 'solution blocks').levels[1].name).toBe('solution blocks')
        expect(d.levels[1].name).toBe('solution')
    })

    it('refuses an unnamed rung, a name twice (case aside) and more than ten rungs; an empty list is fine', () => {
        expect(ladderProblem(ladderDraftOf(LADDER))).toBe('')
        expect(ladderProblem({ levels: [], prompt: '' })).toBe('')
        expect(ladderProblem(addLadderLevel(ladderDraftOf(LADDER)))).toBe('Level 2 needs a name')
        expect(ladderProblem({ levels: [{ name: 'a', description: '' }, { name: '  ', description: 'x' }], prompt: '' }))
            .toBe('Level 1 needs a name')
        expect(ladderProblem({ levels: [{ name: 'Solution', description: '' }, { name: 'solution ', description: '' }], prompt: '' }))
            .toBe("The ladder names level 'solution' twice")
        const eleven = { levels: Array.from({ length: 11 }, (_, i) => ({ name: `l${i}`, description: '' })), prompt: '' }
        expect(ladderProblem(eleven)).toBe('A ladder has at most 10 levels; this one has 11')
        expect(ladderProblem({ ...eleven, levels: eleven.levels.slice(0, 10) })).toBe('')
    })
})

describe('the ladder patch', () => {
    it('numbers each rung by its place, trims names and descriptions, a blank prompt as null', () => {
        expect(ladderInputOf({ levels: [{ name: ' requirements ', description: '  ' }, { name: 'solution', description: ' HLD ' }],
            prompt: '   ' })).toEqual({ levels: [
            { number: 0, name: 'requirements', description: null },
            { number: 1, name: 'solution', description: 'HLD' }], prompt: null })
        expect(ladderInputOf({ levels: [], prompt: 'orphan' })).toBeNull()
    })

    it('sends nothing unchanged, the whole ladder changed, and null when every rung was removed', () => {
        const d = ladderDraftOf(LADDER)
        expect(ladderPatch(LADDER, d)).toBeUndefined()
        expect(ladderPatch(null, { levels: [], prompt: '' })).toBeUndefined()
        expect(ladderPatch(LADDER, setLadderLevel(d, 1, 'description', 'blocks'))).toEqual({ levels: [
            { number: 0, name: 'requirements', description: 'what the client asked for' },
            { number: 1, name: 'solution', description: 'blocks' }], prompt: null })
        expect(ladderPatch(LADDER, moveLadderLevel(d, 1, -1))?.levels.map(l => `${l.number} ${l.name}`))
            .toEqual(['0 solution', '1 requirements'])
        expect(ladderPatch(LADDER, removeLadderLevel(removeLadderLevel(d, 0), 0))).toBeNull()
        expect(ladderPatch(LADDER, { ...d, prompt: 'Our ladder: {{levels}}; the last is {{last}}.' })?.prompt)
            .toBe('Our ladder: {{levels}}; the last is {{last}}.')
        expect(ladderPatch(null, setLadderLevel(addLadderLevel({ levels: [], prompt: '' }), 0, 'name', 'requirements')))
            .toEqual({ levels: [{ number: 0, name: 'requirements', description: null }], prompt: null })
    })

    it('offers the draft\'s rungs to the board\'s default level, and names the placeholders', () => {
        expect(draftLevelOptions(ladderDraftOf(LADDER))).toEqual([{ label: '0 · requirements', value: 0 }, { label: '1 · solution', value: 1 }])
        expect(draftLevelOptions({ levels: [], prompt: '' })).toEqual([])
        expect(LADDER_PROMPT_HINT).toContain('{{levels}}')
        expect(LADDER_PROMPT_HINT).toContain('{{last}}')
    })

    it('shows a ladder refusal beside the ladder, a default level off it beside the default', () => {
        expect(boardFieldOfError('settings.ladder level 1 needs a name')).toBe('ladder')
        expect(boardFieldOfError('settings.ladder cannot be removed while 2 task(s) or group(s) carry a level it does not have: RD-1 (level 1); clear the levels first'))
            .toBe('ladder')
        expect(boardFieldOfError("defaultWorkLevel 4 is not on the board's ladder: 0 requirements, 1 solution")).toBe('defaultWorkLevel')
    })
})
