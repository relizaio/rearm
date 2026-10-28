import { describe, expect, it } from 'vitest'
import { initialBoard, lastBoardKey, readLastBoard, writeLastBoard } from './agentLastBoard'

// The board last opened, per organization, in this browser (relizaLastBoard_<org>).
function memory () {
    const m = new Map<string, string>()
    return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v) }, m }
}
const blocked = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } }

describe('the last board', () => {
    it('is kept under the organization\'s own key', () => {
        expect(lastBoardKey('o1')).toBe('relizaLastBoard_o1')
        const s = memory()
        writeLastBoard('o1', 'b1', s)
        writeLastBoard('o2', 'b9', s)
        expect(s.m.get('relizaLastBoard_o1')).toBe('b1')
        expect(readLastBoard('o1', s)).toBe('b1')
        expect(readLastBoard('o2', s)).toBe('b9')
        expect(readLastBoard('o3', s)).toBeNull()
    })

    it('remembers no board for no board or no organization', () => {
        const s = memory()
        writeLastBoard('o1', null, s)
        writeLastBoard(null, 'b1', s)
        expect(s.m.size).toBe(0)
        expect(readLastBoard(null, s)).toBeNull()
    })

    it('reads as nothing and writes nothing when storage is unavailable', () => {
        expect(readLastBoard('o1', blocked)).toBeNull()
        expect(() => writeLastBoard('o1', 'b1', blocked)).not.toThrow()
        expect(readLastBoard('o1', null)).toBeNull()
    })
})

describe('the board the panel opens on', () => {
    const boards = [{ uuid: 'b1' }, { uuid: 'b2' }]

    it('is the URL\'s, then the remembered one, then the first', () => {
        expect(initialBoard(boards, 'b2', 'b1')).toEqual({ board: 'b2', hidden: null })
        expect(initialBoard(boards, null, 'b2')).toEqual({ board: 'b2', hidden: null })
        expect(initialBoard(boards, undefined, null)).toEqual({ board: 'b1', hidden: null })
    })

    it('passes over a remembered board the list does not hold', () => {
        expect(initialBoard(boards, null, 'gone')).toEqual({ board: 'b1', hidden: null })
    })

    it('opens none in place of a URL board the list does not hold, and says it is hidden (RD2-9)', () => {
        expect(initialBoard(boards, 'secret', 'b2')).toEqual({ board: null, hidden: 'secret' })
    })

    it('is none on an empty list', () => {
        expect(initialBoard([], null, 'b1')).toEqual({ board: null, hidden: null })
    })
})
