import { describe, expect, it } from 'vitest'
import {
    defaultLevelPatch, groupByFromQuery, groupByOptions, groupingFor, groupTasks, hasLadder, ladderHint, ladderOf,
    levelFromQuery, levelLabel, levelName, levelOf, levelOptions, levelPlaceholder, levelSource, levelToSet, levelTooltip,
    matchesLevel, MAX_LADDER_LEVELS, MAX_LEVEL, passesLevel, refWithLevel, taskLevelLabel, withLevelQuery,
} from './agentTaskLevel'

// Task level on the surfaces people read (RD2-1), opt-in per board through its ladder (task RD3-6).
const LADDER = { levels: [
    { number: 0, name: 'requirements', description: 'what the client asked for' },
    { number: 1, name: 'solution', description: null },
    { number: 2, name: 'objects', description: null },
    { number: 3, name: 'components', description: null },
], prompt: null }
const board = { defaultTaskLevel: 1, ladder: LADDER }
const laddered = { ladder: LADDER }
const noLadder = { defaultTaskLevel: 1 }
const actor = (a: any) => a?.name ?? '?'

describe('the ladder', () => {
    it('is opt-in: a board with rungs has one, none or an empty list does not', () => {
        expect(hasLadder(board)).toBe(true)
        expect(hasLadder(noLadder)).toBe(false)
        expect(hasLadder({ ladder: null })).toBe(false)
        expect(hasLadder({ ladder: { levels: [] } })).toBe(false)
        expect(hasLadder(undefined)).toBe(false)
        expect(MAX_LADDER_LEVELS).toBe(10)
        expect(MAX_LEVEL).toBe(9)
    })

    it('reads the rungs 0 first, numbered by place when the number is missing', () => {
        expect(ladderOf({ ladder: { levels: [{ number: 1, name: 'b' }, { number: 0, name: 'a' }] } }).map(r => r.name)).toEqual(['a', 'b'])
        expect(ladderOf({ ladder: { levels: [{ name: 'a' }, { name: 'b' }] } }).map(r => r.number)).toEqual([0, 1])
        expect(ladderOf(noLadder)).toEqual([])
    })

    it('names a level beside its number, the number alone past the ladder', () => {
        expect(levelLabel(1, board)).toBe('1 · solution')
        expect(levelLabel(0, board)).toBe('0 · requirements')
        expect(levelLabel(7, board)).toBe('7')
        expect(levelLabel(null, board)).toBeNull()
        expect(levelName(3, board)).toBe('components')
        expect(levelName(4, board)).toBeNull()
    })

    it('offers the ladder\'s rungs to a picker, none without a ladder', () => {
        expect(levelOptions(board)).toEqual([
            { label: '0 · requirements', value: 0 }, { label: '1 · solution', value: 1 },
            { label: '2 · objects', value: 2 }, { label: '3 · components', value: 3 }])
        expect(levelOptions(noLadder)).toEqual([])
        expect(ladderHint(board)).toBe('0 requirements · 1 solution · 2 objects · 3 components')
        expect(ladderHint(noLadder)).toBe('')
    })
})

describe('the level a board reads', () => {
    it('is the served effective level, else the task\'s own, else its group\'s, else the board default, else 0', () => {
        expect(levelOf({ effectiveLevel: 2, level: null }, board)).toBe(2)
        expect(levelOf({ level: 3 }, board)).toBe(3)
        expect(levelOf({ level: null }, board)).toBe(1)
        expect(levelOf({ level: null }, laddered), 'a ladder board defaults to 0').toBe(0)
        expect(levelOf({ level: null, group: { key: 'ui' } }, { ...board, groups: [{ key: 'ui', defaultLevel: 2 }] })).toBe(2)
        expect(levelOf({ level: 0 }, board)).toBe(0)
        expect(levelSource({ level: 0 }, board)).toBe('set')
        expect(levelSource({ level: null }, board)).toBe('default')
    })

    it('is nothing on a board without a ladder, whatever the task kept', () => {
        expect(levelOf({ effectiveLevel: 2, level: 2 }, noLadder)).toBeNull()
        expect(levelOf({ level: 3 }, {})).toBeNull()
        expect(levelSource({ level: 3 }, noLadder)).toBeNull()
        expect(taskLevelLabel({ level: 3 }, noLadder)).toBeNull()
        expect(levelTooltip({ level: 3 }, noLadder, actor)).toBeNull()
    })

    it('reads as a chip with the rung\'s name', () => {
        expect(taskLevelLabel({ level: 2 }, board)).toBe('2 · objects')
        expect(taskLevelLabel({ level: null }, board)).toBe('1 · solution')
        expect(taskLevelLabel({ level: null }, laddered)).toBe('0 · requirements')
    })

    it('names the rung and its description in the tooltip and says where the level comes from', () => {
        expect(levelTooltip({ level: null }, { ...board, defaultTaskLevel: 0 }, actor))
            .toBe('level 0 · requirements: what the client asked for (board default)')
        expect(levelTooltip({ level: 3, levelSetBy: { name: 'pavel' } }, board, actor)).toBe('level 3 · components (set by pavel)')
        expect(levelTooltip({ level: null, group: { key: 'ui' } }, { ...board, groups: [{ key: 'ui', defaultLevel: 2 }] }, actor))
            .toBe('level 2 · objects (group default)')
        expect(levelTooltip({ level: 7 }, board, actor)).toBe('level 7', 'past the ladder')
    })
})

describe('the editor', () => {
    it('sends a rung of the ladder that differs, and nothing else', () => {
        expect(levelToSet({ level: null }, 2, board)).toBe(2)
        expect(levelToSet({ level: null }, 0, board)).toBe(0)
        expect(levelToSet({ level: 2 }, 2, board)).toBeUndefined()
        expect(levelToSet({ level: null }, null, board)).toBeUndefined()
        expect(levelToSet({ level: null }, 4, board), 'off the ladder').toBeUndefined()
        expect(levelToSet({ level: null }, -1, board)).toBeUndefined()
        expect(levelToSet({ level: null }, 1.5, board)).toBeUndefined()
        expect(levelToSet({ level: null }, 1, noLadder), 'no ladder, no level').toBeUndefined()
        expect(levelPlaceholder({ ...board, defaultTaskLevel: 2 })).toBe('board default 2 · objects')
        expect(levelPlaceholder(laddered)).toBe('board default 0 · requirements')
        expect(levelPlaceholder(noLadder)).toBe('none')
    })

    it('the board default is sent only when changed; blank sends null', () => {
        expect(defaultLevelPatch({ defaultTaskLevel: 1 }, 1)).toEqual({ changed: false, value: 1 })
        expect(defaultLevelPatch({ defaultTaskLevel: 1 }, 3)).toEqual({ changed: true, value: 3 })
        expect(defaultLevelPatch({ defaultTaskLevel: 1 }, null)).toEqual({ changed: true, value: null })
        expect(defaultLevelPatch(null, null)).toEqual({ changed: false, value: null })
        expect(defaultLevelPatch(null, 0)).toEqual({ changed: true, value: 0 })
    })
})

describe('filters, lanes and the URL', () => {
    it('matches "L2" and "level 2", never without a ladder', () => {
        for (const text of ['L2', 'l2', 'level 2', 'Level2', ' L2 ']) expect(matchesLevel({ level: 2 }, laddered, text), text).toBe(true)
        expect(matchesLevel({ level: null }, { ...board, defaultTaskLevel: 2 }, 'L2')).toBe(true)
        expect(matchesLevel({ level: 3 }, laddered, 'L2')).toBe(false)
        expect(matchesLevel({ level: 2 }, laddered, 'fix L2 bug')).toBe(false)
        expect(matchesLevel({ level: 2 }, laddered, '')).toBe(false)
        expect(matchesLevel({ level: 2 }, noLadder, 'L2')).toBe(false)
    })

    it('filters the kanban by level only on a board with a ladder', () => {
        expect(passesLevel({ level: 2 }, board, 2)).toBe(true)
        expect(passesLevel({ level: 3 }, board, 2)).toBe(false)
        expect(passesLevel({ level: 3 }, board, null)).toBe(true)
        expect(passesLevel({ level: 3 }, noLadder, 2), 'a filter left in the URL is ignored').toBe(true)
    })

    it('groups by level in ascending order, named, each lane counted', () => {
        const tasks = [{ uuid: 'a', level: 3 }, { uuid: 'b', level: null }, { uuid: 'c', level: 0 }, { uuid: 'd', level: 3 }]
        const lanes = groupTasks(tasks, 'level', laddered)
        expect(lanes.map(l => l.key)).toEqual(['0', '3'], 'an unset task reads 0 on a ladder board')
        expect(lanes.map(l => l.label)).toEqual(['0 · requirements (2)', '3 · components (2)'])
        expect(lanes[1].tasks.map(t => t.uuid)).toEqual(['a', 'd'])
        const withDefault = groupTasks(tasks, 'level', board)
        expect(withDefault.map(l => l.key)).toEqual(['0', '1', '3'], 'an unset task reads the default')
        expect(groupTasks(tasks, 'none', board)).toEqual([{ key: 'all', label: '', tasks }])
    })

    it('offers no grouping by level without a ladder, and falls back to none', () => {
        const tasks = [{ uuid: 'a', level: 3 }, { uuid: 'b', level: null }]
        expect(groupByOptions(board).map(o => o.value)).toEqual(['none', 'level', 'group'])
        expect(groupByOptions(noLadder).map(o => o.value)).toEqual(['none', 'group'])
        expect(groupingFor('level', noLadder)).toBe('none')
        expect(groupingFor('level', board)).toBe('level')
        expect(groupingFor('group', noLadder)).toBe('group')
        expect(groupingFor('nonsense', board)).toBe('none')
        expect(groupTasks(tasks, 'level', noLadder)).toEqual([{ key: 'all', label: '', tasks }])
    })

    it('keeps the grouping and the level filter in the query', () => {
        expect(groupByFromQuery({ groupBy: 'level' })).toBe('level')
        expect(groupByFromQuery({ groupBy: 'nonsense' })).toBe('none')
        expect(levelFromQuery({ level: '2' })).toBe(2)
        expect(levelFromQuery({ level: '12' })).toBeNull()
        expect(withLevelQuery({ board: 'b', view: 'kanban' }, 'level', 2)).toEqual({ board: 'b', view: 'kanban', groupBy: 'level', level: '2' })
        expect(withLevelQuery({ groupBy: 'level', level: '2' }, 'none', null)).toEqual({})
    })

    it('puts the named level after a small card\'s reference, nothing without a ladder', () => {
        expect(refWithLevel('RD2-1', { level: 2 }, board)).toBe('RD2-1 · 2 · objects')
        expect(refWithLevel('RD2-1', { level: null }, laddered)).toBe('RD2-1 · 0 · requirements')
        expect(refWithLevel('RD2-1', { level: 2 }, noLadder)).toBe('RD2-1')
    })
})
