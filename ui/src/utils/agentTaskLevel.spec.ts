import { describe, expect, it } from 'vitest'
import {
    defaultLevelPatch, GROUP_BY_OPTIONS, groupByFromQuery, groupTasks, LEVEL_LADDER, LEVEL_LADDER_HINT, levelFromQuery,
    levelLabel, levelOf, levelPlaceholder, levelSource, levelToSet, levelTooltip, matchesLevel, refWithLevel,
    withLevelQuery,
} from './agentTaskLevel'

// Task level on the surfaces people read (RD2-1).
const board = { defaultTaskLevel: 1 }
const actor = (a: any) => a?.name ?? '?'

describe('the level a board reads', () => {
    it('is the served effective level, else the task\'s own, else the board default', () => {
        expect(levelOf({ effectiveLevel: 2, level: null }, board)).toBe(2)
        expect(levelOf({ level: 3 }, board)).toBe(3)
        expect(levelOf({ level: null }, board)).toBe(1)
        expect(levelOf({ level: null }, {})).toBeNull()
        expect(levelOf({ level: 0 }, board)).toBe(0)
        expect(levelSource({ level: 0 }, board)).toBe('set')
        expect(levelSource({ level: null }, board)).toBe('default')
        expect(levelSource({ level: null }, {})).toBeNull()
    })

    it('reads as a chip, none for a task with no level', () => {
        expect(levelLabel({ level: 2 }, {})).toBe('L2')
        expect(levelLabel({ level: null }, board)).toBe('L1')
        expect(levelLabel({ level: null }, {})).toBeNull()
    })

    it('names the ladder in the tooltip and says where the level comes from', () => {
        expect(LEVEL_LADDER).toEqual(['requirements', 'solution blocks and HLD', 'objects and architecture decisions',
            'components', 'modules'])
        expect(levelTooltip({ level: null }, { defaultTaskLevel: 2 }, actor))
            .toBe('level 2 · objects and architecture decisions (board default)')
        expect(levelTooltip({ level: 3, levelSetBy: { name: 'pavel' } }, board, actor)).toBe('level 3 · components (set by pavel)')
        expect(levelTooltip({ level: 7 }, board, actor)).toBe('level 7', 'past the ladder')
        expect(levelTooltip({ level: null }, {}, actor)).toBeNull()
        expect(LEVEL_LADDER_HINT).toBe('0 requirements · 1 solution blocks / HLD · 2 objects and ADRs · 3 components · 4 modules')
    })
})

describe('the editor', () => {
    it('sends a whole level 0..9 that differs, and nothing else', () => {
        expect(levelToSet({ level: null }, 2)).toBe(2)
        expect(levelToSet({ level: null }, 0)).toBe(0)
        expect(levelToSet({ level: 2 }, 2)).toBeUndefined()
        expect(levelToSet({ level: null }, null)).toBeUndefined()
        expect(levelToSet({ level: null }, 10)).toBeUndefined()
        expect(levelToSet({ level: null }, -1)).toBeUndefined()
        expect(levelToSet({ level: null }, 1.5)).toBeUndefined()
        expect(levelPlaceholder({ defaultTaskLevel: 2 })).toBe('board default 2')
        expect(levelPlaceholder({})).toBe('none')
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
    it('matches "L2" and "level 2"', () => {
        for (const text of ['L2', 'l2', 'level 2', 'Level2', ' L2 ']) expect(matchesLevel({ level: 2 }, {}, text), text).toBe(true)
        expect(matchesLevel({ level: null }, { defaultTaskLevel: 2 }, 'L2')).toBe(true)
        expect(matchesLevel({ level: 3 }, {}, 'L2')).toBe(false)
        expect(matchesLevel({ level: 2 }, {}, 'fix L2 bug')).toBe(false)
        expect(matchesLevel({ level: 2 }, {}, '')).toBe(false)
    })

    it('groups by level in ascending order, no level last, each lane counted', () => {
        const tasks = [{ uuid: 'a', level: 3 }, { uuid: 'b', level: null }, { uuid: 'c', level: 0 }, { uuid: 'd', level: 3 }]
        const lanes = groupTasks(tasks, 'level', {})
        expect(lanes.map(l => l.key)).toEqual(['0', '3', 'none'])
        expect(lanes.map(l => l.label)).toEqual(['L0 · requirements (1)', 'L3 · components (2)', 'no level (1)'])
        expect(lanes[1].tasks.map(t => t.uuid)).toEqual(['a', 'd'])
        const withDefault = groupTasks(tasks, 'level', { defaultTaskLevel: 1 })
        expect(withDefault.map(l => l.key)).toEqual(['0', '1', '3'], 'an unset task reads the default')
        expect(groupTasks(tasks, 'none', {})).toEqual([{ key: 'all', label: '', tasks }])
        expect(GROUP_BY_OPTIONS.map(o => o.value)).toEqual(['none', 'level'])
    })

    it('keeps the grouping and the level filter in the query', () => {
        expect(groupByFromQuery({ groupBy: 'level' })).toBe('level')
        expect(groupByFromQuery({ groupBy: 'nonsense' })).toBe('none')
        expect(levelFromQuery({ level: '2' })).toBe(2)
        expect(levelFromQuery({ level: '12' })).toBeNull()
        expect(withLevelQuery({ board: 'b', view: 'kanban' }, 'level', 2)).toEqual({ board: 'b', view: 'kanban', groupBy: 'level', level: '2' })
        expect(withLevelQuery({ groupBy: 'level', level: '2' }, 'none', null)).toEqual({})
    })

    it('puts the level after a small card\'s reference', () => {
        expect(refWithLevel('RD2-1', { level: 2 }, {})).toBe('RD2-1 · L2')
        expect(refWithLevel('RD2-1', { level: null }, {})).toBe('RD2-1')
    })
})
