import { describe, expect, it } from 'vitest'
import { boardPerspectiveOptions, perspectiveChips, perspectivesPatch } from './agentBoardPerspectives'

describe('board perspectives (task b9115d09)', () => {
    it('offers perspectives and products, a product marked, archived left out, by name', () => {
        const opts = boardPerspectiveOptions([
            { uuid: 'p2', name: 'payments', type: 'PERSPECTIVE' },
            { uuid: 'c1', name: 'checkout', type: 'PRODUCT' },
            { uuid: 'old', name: 'archived', type: 'PERSPECTIVE', status: 'ARCHIVED' },
            { name: 'no uuid' }
        ])
        expect(opts).toEqual([
            { label: 'payments', value: 'p2' },
            { label: 'product:checkout', value: 'c1' }
        ])
        expect(boardPerspectiveOptions(null)).toEqual([])
    })
    it('sends the list only when it changed', () => {
        expect(perspectivesPatch({ perspectives: ['a', 'b'] }, ['b', 'a'])).toBeUndefined()
        expect(perspectivesPatch({ perspectives: ['a'] }, ['a', 'b'])).toEqual(['a', 'b'])
        expect(perspectivesPatch({ perspectives: ['a'] }, [])).toEqual([])
        expect(perspectivesPatch({}, [])).toBeUndefined()
        expect(perspectivesPatch(null, [])).toBeUndefined()
        expect(perspectivesPatch(null, ['a'])).toEqual(['a'])
    })
    it('shows the board perspectives as the file names them', () => {
        expect(perspectiveChips({ perspectiveNames: ['payments', 'product:checkout'] })).toEqual(['payments', 'product:checkout'])
        expect(perspectiveChips(null)).toEqual([])
    })
})
