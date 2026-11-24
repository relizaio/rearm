import { beforeEach, describe, expect, it, vi } from 'vitest'

// TPF-168 U2 (TPF-165 4.2, D8): handleFetchUserResult opens the blocking "Sign-in refused" dialog
// for a license seat refusal with the server's text verbatim (not a UI copy of it) and a Sign out
// button that calls keycloak.logout(); an inactive account keeps "Account Inactive".
const fire = vi.fn()
const logout = vi.fn()
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn(), logout: (...a: any[]) => logout(...a) } }))
vi.mock('./keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn(), logout: (...a: any[]) => logout(...a) } }))
vi.mock('./graphql', () => ({ default: { query: vi.fn(), mutate: vi.fn() } }))

const commonFunctions = (await import('./commonFunctions')).default

// Texts a server would send. The dialog must show exactly what it is given, so one of them is a
// text no UI copy holds: a UI that rendered its own wording would fail on it.
const TEXTS: Array<[string, string]> = [
    ['SEAT_FULL', 'You signed in, but ReARM has no free user seat for a new account. Please contact your ReARM administrator. Reference: SEAT-FULL.'],
    ['OVER_LIMIT', 'Your ReARM account is outside the licensed number of users. Please contact your ReARM administrator. Reference: OVER-LIMIT.'],
    ['NO_ROLE', 'Server-chosen wording <b>not html</b>, Reference: NO-ROLE.'],
]

describe('handleFetchUserResult: license seat refusal', () => {
    beforeEach(() => {
        fire.mockReset()
        logout.mockReset()
    })

    it.each(TEXTS)('%s: Sign-in refused with the server text as plain text and a Sign out button', async (reason, text) => {
        fire.mockResolvedValue({ isConfirmed: false })
        await commonFunctions.handleFetchUserResult({ _seatRefused: reason, _errorMessage: text })
        expect(fire).toHaveBeenCalledTimes(1)
        const opts = fire.mock.calls[0][0]
        expect(opts.title).toBe('Sign-in refused')
        expect(opts.text).toBe(text)
        expect(opts.html).toBeUndefined()
        expect(opts.confirmButtonText).toBe('Sign out')
        expect(opts.showConfirmButton).toBe(true)
        expect(opts.showCancelButton).toBe(false)
        expect(opts.allowOutsideClick).toBe(false)
        expect(opts.allowEscapeKey).toBe(false)
        expect(logout).not.toHaveBeenCalled()
    })

    it('Sign out calls keycloak.logout()', async () => {
        fire.mockResolvedValue({ isConfirmed: true })
        await commonFunctions.handleFetchUserResult({ _seatRefused: 'OVER_LIMIT', _errorMessage: TEXTS[1][1] })
        expect(logout).toHaveBeenCalledTimes(1)
    })

    it('_unauthorized keeps Account Inactive', async () => {
        fire.mockResolvedValue({})
        await commonFunctions.handleFetchUserResult({ _unauthorized: true, _errorMessage: 'Not authorized' })
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0][0].title).toBe('Account Inactive')
    })

    it('_offline keeps System Offline', async () => {
        fire.mockResolvedValue({})
        await commonFunctions.handleFetchUserResult({ _offline: true, _errorMessage: 'x' })
        expect(fire.mock.calls[0][0].title).toBe('System Offline')
    })
})
