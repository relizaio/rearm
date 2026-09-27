import { describe, it, expect } from 'vitest'
import { boardCan, canConfigure, canOperate } from './agentBoardAccess'

// Board enforcement (task d8e7bd7e): controls follow the board's myPermissions.
describe('board access', () => {
    it('an operator runs the board but does not configure it', () => {
        const b = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }
        expect(canOperate(b)).toBe(true)
        expect(canConfigure(b)).toBe(false)
    })
    it('a configurer configures it; a reader does neither', () => {
        expect(canConfigure({ myPermissions: ['BOARD_READ', 'CONFIGURATION_READ', 'CONFIGURATION_WRITE'] })).toBe(true)
        const reader = { myPermissions: ['BOARD_READ'] }
        expect(canOperate(reader)).toBe(false)
        expect(canConfigure(reader)).toBe(false)
        expect(boardCan(reader, 'BOARD_READ')).toBe(true)
    })
    it('an agent function is not an operator one', () => {
        expect(canOperate({ myPermissions: ['BOARD_READ', 'BOARD_AGENT'] })).toBe(false)
    })
    it('nothing served is nothing held', () => {
        expect(canOperate(null)).toBe(false)
        expect(canOperate({})).toBe(false)
        expect(canConfigure({ myPermissions: null })).toBe(false)
    })
})
