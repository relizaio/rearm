import { describe, it, expect } from 'vitest'
import { filterUsersBySearch, userMatchesSearch } from './orgUsersSearch'

/** TPF-85 design 4.1, rows 1-10: the Users tab filter over a five-user fixture. */
const USERS = [
    { uuid: '0dfce17a-fc79-4245-937d-eff5213fdfcb', name: 'Olga Admin', email: 'olga@example.io', status: 'ACTIVE' },
    { uuid: '0dfc1111-0000-4000-8000-000000000002', name: 'Pavel Builder', email: 'pavel@example.io', status: 'INACTIVE' },
    { uuid: 'abcd0000-0000-4000-8000-000000000003', name: 'Dana Tester', email: 'abcd@example.io', status: 'ACTIVE' },
    { uuid: '12340000-0000-4000-8000-000000000004', name: 'Ann Dev', email: 'ann@EXAMPLE.io', status: 'ACTIVE' },
    { uuid: 'ffff0000-0000-4000-8000-000000000005', name: 'Zed Ops', email: 'zed@example.io', status: 'ACTIVE' }
]

describe('orgUsersSearch: uuid', () => {
    it('row 1: a full uuid returns exactly that user', () => {
        const shown = filterUsersBySearch(USERS, '0dfce17a-fc79-4245-937d-eff5213fdfcb')
        expect(shown).toHaveLength(1)
        expect(shown[0]).toBe(USERS[0])
    })

    it('row 2: a 4-character prefix returns every user whose uuid starts with it, in input order', () => {
        const shown = filterUsersBySearch(USERS, '0dfc')
        expect(shown).toHaveLength(2)
        expect(shown[0]).toBe(USERS[0])
        expect(shown[1]).toBe(USERS[1])
    })

    it('row 3: the prefix and the full uuid are case-insensitive', () => {
        expect(filterUsersBySearch(USERS, '0DFC')).toEqual([USERS[0], USERS[1]])
        const full = filterUsersBySearch(USERS, '0DFCE17A-FC79-4245-937D-EFF5213FDFCB')
        expect(full).toHaveLength(1)
        expect(full[0]).toBe(USERS[0])
    })

    it('row 4: prefix only, never a part inside or at the tail of the uuid', () => {
        expect(filterUsersBySearch(USERS, 'fc79')).toEqual([])
        expect(filterUsersBySearch(USERS, 'eff5213fdfcb')).toEqual([])
    })

    it('row 5: under the minimum, a non-hex character, or a well-formed prefix no uuid starts with', () => {
        expect(filterUsersBySearch(USERS, '0df')).toEqual([])
        expect(filterUsersBySearch(USERS, '0dfg')).toEqual([])
        expect(filterUsersBySearch(USERS, 'abcd-')).toEqual([])
    })
})

describe('orgUsersSearch: name and email', () => {
    it('row 6: name substring, case-insensitive, a space being part of the term', () => {
        expect(filterUsersBySearch(USERS, 'builder')).toEqual([USERS[1]])
        expect(filterUsersBySearch(USERS, 'OLGA ad')).toEqual([USERS[0]])
    })

    it('row 7: email substring, case-insensitive', () => {
        expect(filterUsersBySearch(USERS, '@example.io')).toEqual(USERS)
        expect(filterUsersBySearch(USERS, 'ann@example')).toEqual([USERS[3]])
    })

    it('row 8: a term that is both a uuid prefix and an email returns that row once', () => {
        const shown = filterUsersBySearch(USERS, 'abcd')
        expect(shown).toHaveLength(1)
        expect(shown[0]).toBe(USERS[2])
    })
})

describe('orgUsersSearch: no match and no term', () => {
    it('row 9: a term nothing matches returns an empty list', () => {
        expect(filterUsersBySearch(USERS, 'nobody')).toEqual([])
        expect(filterUsersBySearch(USERS, '9999')).toEqual([])
    })

    it('row 10: an empty, blank, undefined or null term returns a new array equal to the input', () => {
        for (const raw of ['', '   ', undefined, null]) {
            const shown = filterUsersBySearch(USERS, raw)
            expect(shown).toEqual(USERS)
            expect(shown).not.toBe(USERS)
        }
        const sparse = { uuid: '0dfce17a-fc79-4245-937d-eff5213fdfcb', name: null, email: undefined }
        expect(userMatchesSearch(sparse, 'olga')).toBe(false)
        expect(userMatchesSearch(sparse, '0dfc')).toBe(true)
        expect(userMatchesSearch(USERS[0], '')).toBe(true)
    })
})
