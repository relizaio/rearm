import { describe, expect, it } from 'vitest'
import { loadOrgKeyRefs, releaseActorLabel } from './actorLabel'

const DOT = ' \u00B7 '
const KEY = '77781336-0000-4000-8000-000000000001'
const OTHER = 'a1b2c3d4-0000-4000-8000-000000000002'
const USERS = [
    { uuid: 'u-1', name: 'Ada', email: 'ada@example.com' },
    { uuid: 'u-2', name: 'Bob', email: '' },
    { uuid: 'u-3', name: null, email: 'cy@example.com' },
    { uuid: 'u-4', name: null, email: null }
]
const KEYS = [{ uuid: KEY, type: 'FREEFORM', declaredName: 'CI pipeline', notes: null, status: 'ACTIVE' }]

describe('releaseActorLabel', () => {
    it('names an automatic change System', () => {
        expect(releaseActorLabel(KEY, 'AUTO', USERS, KEYS)).toBe('System')
    })

    it('names a known person, falling back to the email, then the uuid', () => {
        expect(releaseActorLabel('u-1', 'MANUAL', USERS, KEYS)).toBe('Ada <ada@example.com>')
        expect(releaseActorLabel('u-2', 'MANUAL', USERS, KEYS)).toBe('Bob')
        expect(releaseActorLabel('u-3', 'MANUAL', USERS, KEYS)).toBe('cy@example.com')
        expect(releaseActorLabel('u-4', 'API', USERS, KEYS)).toBe('u-4')
    })

    it('cleans a person name of bidi and zero-width characters', () => {
        const users = [{ uuid: 'u-5', name: 'Eve\u202Eevil', email: 'eve@example.com\u200B' }]
        expect(releaseActorLabel('u-5', 'MANUAL', users, KEYS)).toBe('Eve evil <eve@example.com>')
    })

    it('labels a key the viewer can see with its type word and declared name', () => {
        expect(releaseActorLabel(KEY, 'API', USERS, KEYS)).toBe('Free Form' + DOT + 'CI pipeline' + DOT + '77781336')
    })

    it('labels a known key even while the users list is empty', () => {
        expect(releaseActorLabel(KEY, 'API', [], KEYS)).toBe('Free Form' + DOT + 'CI pipeline' + DOT + '77781336')
    })

    it('labels an API change by an unseen key by its short id', () => {
        expect(releaseActorLabel(OTHER, 'API', USERS, KEYS)).toBe('Key' + DOT + 'a1b2c3d4')
        expect(releaseActorLabel(OTHER, 'API', USERS, [])).toBe('Key' + DOT + 'a1b2c3d4')
    })

    it('never labels a person as a key: a manual change by someone not in users keeps the uuid', () => {
        expect(releaseActorLabel(OTHER, 'MANUAL', USERS, KEYS)).toBe(OTHER)
        expect(releaseActorLabel(OTHER, undefined, USERS, KEYS)).toBe(OTHER)
        expect(releaseActorLabel('u-1', 'MANUAL', [], KEYS)).toBe('u-1')
        expect(releaseActorLabel(OTHER, 'API', [], KEYS)).toBe(OTHER)
    })

    it('gives an empty label for an empty uuid', () => {
        expect(releaseActorLabel('', 'MANUAL', USERS, KEYS)).toBe('')
        expect(releaseActorLabel(null, 'MANUAL', USERS, KEYS)).toBe('')
    })
})

describe('releaseActorLabel with the other update types of a history row', () => {
    it('names a known person and keeps the uuid of an unknown actor on MANUAL_AND_AUTO and TEST', () => {
        for (const updateType of ['MANUAL_AND_AUTO', 'TEST']) {
            expect(releaseActorLabel('u-1', updateType, USERS, KEYS)).toBe('Ada <ada@example.com>')
            expect(releaseActorLabel(OTHER, updateType, USERS, KEYS)).toBe(OTHER)
        }
    })
})

describe('loadOrgKeyRefs', () => {
    const store = (keys: any) => {
        const calls: any[] = []
        return { calls, dispatch: async (action: string, org: string) => { calls.push([action, org]); return keys } }
    }

    it('asks for an admin only', async () => {
        const s = store(KEYS)
        expect(await loadOrgKeyRefs(s, 'org-1', false)).toEqual([])
        expect(s.calls).toEqual([])
        expect(await loadOrgKeyRefs(s, 'org-1', true)).toEqual(KEYS)
        expect(s.calls).toEqual([['fetchApiKeyRefsOfOrg', 'org-1']])
    })

    it('answers [] without an org or when nothing comes back', async () => {
        const s = store(undefined)
        expect(await loadOrgKeyRefs(s, '', true)).toEqual([])
        expect(s.calls).toEqual([])
        expect(await loadOrgKeyRefs(s, 'org-1', true)).toEqual([])
    })
})
