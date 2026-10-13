import { describe, expect, it } from 'vitest'
import { actorLabel } from './actorLabel'

// the label separator, built so this file stays plain ASCII
const DOT = ' ' + String.fromCharCode(0xB7) + ' '
const KEY = '77781336-0000-4000-8000-000000000001'
const OTHER = 'a1b2c3d4-0000-4000-8000-000000000002'
const USERS = [
    { uuid: 'u-1', name: 'Ada', email: 'ada@example.com' },
    { uuid: 'u-2', name: 'Bob', email: '' }
]
const KEYS = [{ uuid: KEY, type: 'FREEFORM', declaredName: 'CI pipeline', notes: null, status: 'ACTIVE' }]

describe('actorLabel', () => {
    it('names an automatic change System', () => {
        expect(actorLabel(KEY, 'AUTO', USERS, KEYS)).toBe('System')
    })

    it('returns the uuid unchanged while the users list is empty', () => {
        expect(actorLabel('u-1', 'MANUAL', [], KEYS)).toBe('u-1')
        expect(actorLabel(KEY, 'MANUAL', [], KEYS)).toBe(KEY)
    })

    it('names a known person with and without an email', () => {
        expect(actorLabel('u-1', 'MANUAL', USERS, KEYS)).toBe('Ada <ada@example.com>')
        expect(actorLabel('u-2', 'MANUAL', USERS, KEYS)).toBe('Bob')
    })

    it('labels a key the viewer can see with its type word and declared name', () => {
        expect(actorLabel(KEY, 'MANUAL', USERS, KEYS)).toBe('Free Form' + DOT + 'CI pipeline' + DOT + '77781336')
    })

    it('labels a key the viewer cannot see by its short id only', () => {
        expect(actorLabel(OTHER, 'MANUAL', USERS, KEYS)).toBe('Key' + DOT + 'a1b2c3d4')
        expect(actorLabel(OTHER, undefined, USERS, [])).toBe('Key' + DOT + 'a1b2c3d4')
    })

    it('gives an empty label for an empty uuid', () => {
        expect(actorLabel('', 'MANUAL', USERS, KEYS)).toBe('')
        expect(actorLabel(null, 'MANUAL', USERS, KEYS)).toBe('')
    })
})
