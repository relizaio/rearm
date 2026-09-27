import { describe, expect, it } from 'vitest'
import {
    dependencyOptions, GROUP_PALETTE, groupByKey, groupDraftOf, groupFieldOfError, groupInputOf, groupKeyTaken, groupColour, groupFromQuery, groupLabel, groupLaneLabel, groupLayers, groupOptions,
    groupProgress, groupRank, groupToSend, groupWaitingOn, matchesGroupOrTag, MAX_TAGS, NO_GROUP, normaliseTag,
    parseTags, passesGroupAndTag, registerGroupFields, sortedGroups, tagFromQuery, tagOptions, tagsProblem, tagsToSet, waitingOnLabel,
    withGroupQuery,
} from './agentTaskGroups'
import { groupByFromQuery, groupTasks } from './agentTaskLevel'

// Task groups and tags on the surfaces people read (RD2-31).
const board = {
    groups: [
        { key: 'ui-work', name: 'Front end', order: 2, dependsOn: ['core-work'], status: 'OPEN',
            progress: { total: 4, done: 1, open: 3, complete: false } },
        { key: 'core-work', name: 'Core services', order: 1, dependsOn: [], status: 'OPEN',
            progress: { total: 5, done: 3, open: 2, complete: false } },
        { key: 'old-work', order: 3, dependsOn: [], status: 'CLOSED', progress: { total: 2, done: 2, open: 0, complete: true } },
        { key: 'docs-work', order: 4, dependsOn: ['old-work', 'ui-work'], status: 'OPEN', progress: { total: 0, done: 0, open: 0 } },
    ],
}

describe('a group\'s colour', () => {
    it('is the same for the same key and a palette colour', () => {
        expect(groupColour('core-work')).toBe(groupColour('core-work'))
        expect(GROUP_PALETTE).toContain(groupColour('core-work'))
        expect(groupColour(null)).toBeNull()
        expect(groupColour('')).toBeNull()
    })

    it('differs between neighbouring keys', () => {
        for (const [a, b] of [['batch-1', 'batch-2'], ['batch-2', 'batch-3'], ['core-work', 'ui-work'],
            ['first', 'second'], ['g1', 'g2'], ['phase-a', 'phase-b']]) {
            expect(groupColour(a), `${a} and ${b}`).not.toBe(groupColour(b))
        }
    })

    it('spreads over the palette', () => {
        const used = new Set(Array.from({ length: 40 }, (_, i) => groupColour(`group-${i}`)))
        expect(used.size).toBeGreaterThanOrEqual(12)
    })
})

describe('groups as they read', () => {
    it('are in the board\'s order', () => {
        expect(sortedGroups(board).map(g => g.key)).toEqual(['core-work', 'ui-work', 'old-work', 'docs-work'])
        expect(sortedGroups({})).toEqual([])
        expect(groupByKey(board, 'ui-work')?.name).toBe('Front end')
        expect(groupLabel(groupByKey(board, 'ui-work'))).toBe('ui-work · Front end')
        expect(groupLabel(groupByKey(board, 'old-work'))).toBe('old-work')
    })

    it('show their progress', () => {
        expect(groupProgress(groupByKey(board, 'core-work'))).toBe('3 of 5 done')
        expect(groupProgress(groupByKey(board, 'docs-work'))).toBe('no tasks')
        expect(groupProgress(null)).toBe('no tasks')
    })

    it('wait on the dependencies that still have an open task', () => {
        expect(groupWaitingOn(groupByKey(board, 'ui-work'), board)).toEqual(['core-work'])
        expect(groupWaitingOn(groupByKey(board, 'docs-work'), board)).toEqual(['ui-work'], 'a finished dependency holds nothing back')
        expect(groupWaitingOn(groupByKey(board, 'core-work'), board)).toEqual([])
    })

    it('label a gated task', () => {
        expect(waitingOnLabel({ waitingOnGroups: ['first'] })).toBe('waiting on group first')
        expect(waitingOnLabel({ waitingOnGroups: ['a', 'b'] })).toBe('waiting on groups a, b')
        expect(waitingOnLabel({ waitingOnGroups: [] })).toBeNull()
        expect(waitingOnLabel({})).toBeNull()
    })
})

describe('tags', () => {
    it('are normalised as the server stores them', () => {
        expect(normaliseTag('  Client-Req ')).toBe('client-req')
        expect(normaliseTag('   ')).toBeNull()
        expect(normaliseTag(null)).toBeNull()
        expect(parseTags('Client-Req, sandbox-only,, client-req , URGENT')).toEqual(['client-req', 'sandbox-only', 'urgent'])
        expect(parseTags('')).toEqual([])
    })

    it('are sent only when the list changed, keeping values', () => {
        const task = { tags: [{ key: 'a', value: 'v' }, { key: 'b' }] }
        expect(tagsToSet(task, ['a', 'b'])).toBeUndefined()
        expect(tagsToSet(task, [' A ', 'b'])).toBeUndefined()
        expect(tagsToSet(task, ['a'])).toEqual([{ key: 'a', value: 'v' }])
        expect(tagsToSet(task, ['a', 'b', 'C'])).toEqual([{ key: 'a', value: 'v' }, { key: 'b' }, { key: 'c' }])
        expect(tagsToSet(task, [])).toEqual([])
        expect(tagsToSet({}, [])).toBeUndefined()
        expect(tagsProblem(Array.from({ length: MAX_TAGS }, (_, i) => `t${i}`))).toBeNull()
        expect(tagsProblem(Array.from({ length: MAX_TAGS + 1 }, (_, i) => `t${i}`))).toBe('At most 20 tags on a task')
    })
})

describe('selects, filters and the URL', () => {
    it('offer the groups in order, open ones for a move, none first', () => {
        expect(groupOptions(board).map(o => o.value)).toEqual(['core-work', 'ui-work', 'old-work', 'docs-work'])
        expect(groupOptions(board, { openOnly: true, none: true })).toEqual([
            { label: 'none', value: NO_GROUP },
            { label: 'core-work · Core services', value: 'core-work' },
            { label: 'ui-work · Front end', value: 'ui-work' },
            { label: 'docs-work', value: 'docs-work' },
        ])
        expect(groupToSend(NO_GROUP)).toBeNull()
        expect(groupToSend(null)).toBeNull()
        expect(groupToSend('ui-work')).toBe('ui-work')
    })

    it('match a filter text against the group and the tags', () => {
        const t = { group: { key: 'core-work' }, tags: [{ key: 'client-req' }] }
        expect(matchesGroupOrTag(t, 'CORE')).toBe(true)
        expect(matchesGroupOrTag(t, 'client')).toBe(true)
        expect(matchesGroupOrTag(t, 'ui-work')).toBe(false)
        expect(matchesGroupOrTag(t, '')).toBe(false)
        expect(matchesGroupOrTag({}, 'core')).toBe(false)
    })

    it('filter by group, none, and tag', () => {
        const a = { group: { key: 'core-work' }, tags: [{ key: 'x' }] }
        const b = { tags: [] }
        expect(passesGroupAndTag(a, 'core-work', null)).toBe(true)
        expect(passesGroupAndTag(a, 'ui-work', null)).toBe(false)
        expect(passesGroupAndTag(b, NO_GROUP, null)).toBe(true)
        expect(passesGroupAndTag(a, NO_GROUP, null)).toBe(false)
        expect(passesGroupAndTag(a, null, 'x')).toBe(true)
        expect(passesGroupAndTag(a, null, 'y')).toBe(false)
        expect(passesGroupAndTag(b, null, null)).toBe(true)
        expect(tagOptions([a, { tags: [{ key: 'b' }, { key: 'x' }] }])).toEqual([{ label: 'b', value: 'b' }, { label: 'x', value: 'x' }])
    })

    it('round-trip the group and tag through the query', () => {
        const q = withGroupQuery({ board: 'b1', groupBy: 'group' }, 'core-work', 'client-req')
        expect(q).toEqual({ board: 'b1', groupBy: 'group', group: 'core-work', tag: 'client-req' })
        expect(groupFromQuery(q)).toBe('core-work')
        expect(tagFromQuery(q)).toBe('client-req')
        const none = withGroupQuery(q, NO_GROUP, null)
        expect(none).toEqual({ board: 'b1', groupBy: 'group', group: 'none' })
        expect(groupFromQuery(none)).toBe(NO_GROUP)
        expect(withGroupQuery(q, null, null)).toEqual({ board: 'b1', groupBy: 'group' })
        expect(groupFromQuery({})).toBeNull()
        expect(tagFromQuery({ tag: '' })).toBeNull()
    })
})

describe('lanes and the PERT', () => {
    it('rank lanes by the board\'s order and name them', () => {
        expect(['ui-work', 'zzz', 'core-work'].sort((a, b) => groupRank(a, board) - groupRank(b, board)))
            .toEqual(['core-work', 'ui-work', 'zzz'])
        expect(groupLaneLabel('core-work', board)).toBe('core-work · Core services')
        expect(groupLaneLabel('gone', board)).toBe('gone')
        expect(groupLaneLabel(null, board)).toBe('Ungrouped')
    })

    it('group the kanban into lanes in the board\'s order, Ungrouped last, each counted', () => {
        const tasks = [{ uuid: 'a', group: { key: 'ui-work' } }, { uuid: 'b', group: null }, { uuid: 'c', group: { key: 'core-work' } },
            { uuid: 'd', group: { key: 'ui-work' } }, { uuid: 'e', group: { key: 'docs-work' } }, { uuid: 'f', group: { key: 'old-work' } }]
        const lanes = groupTasks(tasks, 'group', board)
        // The board's order, not the keys' alphabetical one (docs, old, ui).
        expect(lanes.map(l => l.key)).toEqual(['core-work', 'ui-work', 'old-work', 'docs-work', 'none'])
        expect(lanes.map(l => l.label)).toEqual(['core-work · Core services (1)', 'ui-work · Front end (2)', 'old-work (1)',
            'docs-work (1)', 'Ungrouped (1)'])
        expect(lanes[1].tasks.map(t => t.uuid)).toEqual(['a', 'd'])
        expect(groupByFromQuery({ groupBy: 'group' })).toBe('group')
    })

    it('lay groups out by dependency depth', () => {
        const layers = groupLayers(board)
        expect(Object.fromEntries(layers)).toEqual({ 'core-work': 0, 'ui-work': 1, 'old-work': 0, 'docs-work': 2 })
        const loop = { groups: [{ key: 'a', dependsOn: ['b'] }, { key: 'b', dependsOn: ['a'] }] }
        expect([...groupLayers(loop).keys()].sort()).toEqual(['a', 'b'])
    })
})

describe('the New task form', () => {
    it('sends the group, the tags and the level only when set', () => {
        expect(registerGroupFields({ group: NO_GROUP, tagsText: ' ', level: null })).toEqual({})
        expect(registerGroupFields({ group: 'core-work', tagsText: 'Client-Req, urgent', level: 0 }))
            .toEqual({ group: 'core-work', tags: [{ key: 'client-req' }, { key: 'urgent' }], level: 0 })
        expect(registerGroupFields({})).toEqual({})
    })
})

describe('the groups tab\'s form', () => {
    it('sends the whole group, blanks as null, the uuid on an edit', () => {
        const d = groupDraftOf()
        d.key = ' Core-Work '
        d.name = ' Core '
        expect(groupInputOf(d)).toEqual({ key: 'core-work', name: 'Core', description: null, dependsOn: [],
            defaultLevel: null, status: 'OPEN' })
        const edit = groupDraftOf({ uuid: 'g1', key: 'ui-work', dependsOn: ['core-work'], defaultLevel: 2, status: 'CLOSED' })
        expect(groupInputOf(edit)).toEqual({ uuid: 'g1', key: 'ui-work', name: null, description: null,
            dependsOn: ['core-work'], defaultLevel: 2, status: 'CLOSED' })
    })

    it('offers every other group as a dependency', () => {
        expect(dependencyOptions(board, 'ui-work').map(o => o.value)).toEqual(['core-work', 'old-work', 'docs-work'])
        expect(dependencyOptions(board, '').map(o => o.value)).toHaveLength(4)
    })

    it('puts each refusal beside its field', () => {
        expect(groupFieldOfError('A group key is 2 to 24 lower-case letters, digits and hyphens')).toBe('key')
        expect(groupFieldOfError('group core-work exists on this board')).toBe('key')
        expect(groupFieldOfError('group ui-work is referenced by 2 tasks; its key is immutable')).toBe('key')
        expect(groupFieldOfError('group a depends on itself')).toBe('dependsOn')
        expect(groupFieldOfError('group cycle: a → b → a')).toBe('dependsOn')
        expect(groupFieldOfError('level is 0 to 9')).toBe('defaultLevel')
        expect(groupFieldOfError('A group name is one line')).toBe('name')
        expect(groupFieldOfError('Not authorized')).toBeNull()
        expect(groupFieldOfError(null)).toBeNull()
    })
})

describe('a key the board already has (RD2-31 T-1)', () => {
    const withUuids = { groups: board.groups.map((g, i) => ({ ...g, uuid: `g${i}` })) }
    it('is refused for a new group, in the server\'s words, whatever its spelling', () => {
        const d = groupDraftOf()
        d.key = ' UI-Work '
        expect(groupKeyTaken(withUuids, d)).toBe('group ui-work exists on this board')
        d.key = 'fresh-work'
        expect(groupKeyTaken(withUuids, d)).toBeNull()
        d.key = ''
        expect(groupKeyTaken(withUuids, d)).toBeNull()
        expect(groupFieldOfError('group ui-work exists on this board')).toBe('key')
    })

    it('is a group\'s own key on an edit, and refused when an edit renames onto another', () => {
        const own = groupDraftOf(withUuids.groups[0])
        expect(groupKeyTaken(withUuids, own)).toBeNull()
        own.key = 'core-work'
        expect(groupKeyTaken(withUuids, own)).toBe('group core-work exists on this board')
    })
})
