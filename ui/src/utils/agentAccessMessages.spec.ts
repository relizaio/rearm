import { describe, expect, it } from 'vitest'
import { hiddenBoardText, isNotAuthorized, noBoardsText, sessionLoadErrorText, taskLoadErrorText } from './agentAccessMessages'

// Access refusals that say so (RD2-9).
describe('refusals that say so', () => {
    it('tell an admin there are no boards, and anyone else what they would need', () => {
        expect(noBoardsText(true)).toMatch(/^No boards yet\./)
        expect(noBoardsText(false)).toBe('No boards you can see. Ask an org admin for Board read on a board.')
        expect(hiddenBoardText('b-1')).toBe("You don't have access to this board (needs Board read): b-1")
    })

    it('read a refusal as one, and keep any other error\'s words', () => {
        expect(isNotAuthorized(new Error('GraphQL error: Not authorized: this needs BOARD_READ on board X'))).toBe(true)
        expect(isNotAuthorized(new Error('Access Denied'))).toBe(true)
        expect(isNotAuthorized(new Error('Task not found'))).toBe(false)
        expect(taskLoadErrorText(new Error('Not authorized'))).toBe("You don't have access to this task's board (needs Board read)")
        expect(taskLoadErrorText(new Error('GraphQL error: timeout'))).toBe('Could not load the task: timeout')
    })

    it('end the session page\'s wait with what the server said', () => {
        expect(sessionLoadErrorText(new Error('Not authorized'))).toBe('Not authorized')
        expect(sessionLoadErrorText(new Error('Session not found: s1'))).toBe('Session not found')
        expect(sessionLoadErrorText(null)).toBe('Session not found')
        expect(sessionLoadErrorText(new Error('boom'))).toBe('Could not load the session: boom')
    })
})
