import { describe, it, expect } from 'vitest'
import { boardCan, canConfigure, canOperate, canConfigureRead, specRefusal, ASK_ADMIN_HINT, INBOX_HINT, subscribeOffer } from './agentBoardAccess'

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

// Controls shown to people who cannot act (RD2-6): reading the board as a spec, and what the spec
// modal says when the read is refused.
describe('reading the board as configuration', () => {
    it('needs CONFIGURATION_READ, or CONFIGURATION_WRITE which reads what it writes', () => {
        expect(canConfigureRead({ myPermissions: ['BOARD_READ'] })).toBe(false)
        expect(canConfigureRead({ myPermissions: ['BOARD_READ', 'CONFIGURATION_READ'] })).toBe(true)
        expect(canConfigureRead({ myPermissions: ['CONFIGURATION_WRITE'] })).toBe(true)
        expect(canConfigureRead({ myPermissions: null })).toBe(false)
        expect(canConfigureRead(null)).toBe(false)
    })

    it('shows the server\'s refusal in the modal, and says what a bare Not authorized needs', () => {
        expect(specRefusal(new Error('GraphQL error: Not authorized'))).toBe('Needs Configuration read on this board to show it as a spec.')
        expect(specRefusal(new Error('Board not found: b1'))).toBe('Board not found: b1')
        expect(specRefusal(null)).toBe('Needs Configuration read on this board to show it as a spec.')
    })
})

// RD2-14: subscriptions are an org admin's; a writer's board events already reach the inbox; a reader asks.
describe('what the board header offers for notifications', () => {
    const writer = { myPermissions: ['BOARD_READ', 'BOARD_WRITE'] }
    const reader = { myPermissions: ['BOARD_READ'] }

    it('offers Subscribe to an org admin, whatever the board grants', () => {
        expect(subscribeOffer(reader, true)).toEqual({ kind: 'subscribe' })
        expect(subscribeOffer(writer, true)).toEqual({ kind: 'subscribe' })
    })

    it('tells a writer the events reach the inbox, and a reader to ask an admin', () => {
        expect(subscribeOffer(writer, false)).toEqual({ kind: 'hint', text: INBOX_HINT })
        expect(INBOX_HINT).toBe('Board events reach your inbox (Board write)')
        expect(subscribeOffer(reader, false)).toEqual({ kind: 'hint', text: ASK_ADMIN_HINT })
        expect(subscribeOffer({ myPermissions: null }, false)).toEqual({ kind: 'hint', text: ASK_ADMIN_HINT })
        expect(ASK_ADMIN_HINT).toBe('Ask an org admin to subscribe a channel to this board')
    })
})

