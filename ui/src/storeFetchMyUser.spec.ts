import { beforeEach, describe, expect, it, vi } from 'vitest'

// TPF-168 U1 (TPF-165 4.2, D8): store.fetchMyUser returns the typed license seat refusal, reason and
// server text, before the generic Not authorized / offline branches. The GraphQL client and
// keycloak are mocked so the store module loads under node.
const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: vi.fn() } }))
vi.mock('@/utils/keycloak', () => ({ default: { token: 'test', updateToken: vi.fn(), login: vi.fn(), logout: vi.fn() } }))

const store = (await import('@/store')).default.store

const NO_ROLE_TEXT = 'You signed in, but no ReARM role is assigned to your account. Ask your ReARM administrator to add you to a ReARM user group or assign you the application role in your identity provider. Reference: NO-ROLE.'
const OVER_LIMIT_TEXT = 'Your ReARM account is outside the licensed number of users. Please contact your ReARM administrator. Reference: OVER-LIMIT.'
const SEAT_FULL_TEXT = 'You signed in, but ReARM has no free user seat for a new account. Please contact your ReARM administrator. Reference: SEAT-FULL.'

/** The answer Apollo 4 hands back for errorPolicy 'all': no data, the errors on `error.errors`. */
const answer = (errors: any[]) => ({ data: { user: null }, error: { errors } })
const seatError = (message: string, reason: string) =>
    ({ message, extensions: { code: 'LICENSE_SEAT_REFUSED', reason, classification: 'BAD_REQUEST' } })

describe('store.fetchMyUser: license seat refusal', () => {
    beforeEach(() => {
        query.mockReset()
    })

    it('returns _seatRefused with the reason and the server text verbatim', async () => {
        query.mockResolvedValue(answer([seatError(NO_ROLE_TEXT, 'NO_ROLE')]))
        expect(await store.dispatch('fetchMyUser')).toEqual({ _seatRefused: 'NO_ROLE', _errorMessage: NO_ROLE_TEXT })
        expect(query.mock.calls[0][0].errorPolicy).toBe('all')
    })

    it.each([['OVER_LIMIT', OVER_LIMIT_TEXT], ['SEAT_FULL', SEAT_FULL_TEXT]])('returns %s the same way', async (reason, text) => {
        query.mockResolvedValue(answer([seatError(text, reason)]))
        expect(await store.dispatch('fetchMyUser')).toEqual({ _seatRefused: reason, _errorMessage: text })
    })

    it('a plain Not authorized still returns _unauthorized', async () => {
        query.mockResolvedValue(answer([{ message: 'Not authorized' }]))
        expect(await store.dispatch('fetchMyUser')).toEqual({ _unauthorized: true, _errorMessage: 'Not authorized' })
    })

    it('a seat code with an unknown reason, or a reason without the code, is not a seat refusal', async () => {
        query.mockResolvedValue(answer([{ message: 'x', extensions: { code: 'LICENSE_SEAT_REFUSED', reason: 'OTHER' } }]))
        expect(await store.dispatch('fetchMyUser')).toEqual({ _offline: true, _errorMessage: 'x' })
        query.mockResolvedValue(answer([{ message: 'Not authorized', extensions: { reason: 'NO_ROLE' } }]))
        expect(await store.dispatch('fetchMyUser')).toEqual({ _unauthorized: true, _errorMessage: 'Not authorized' })
    })

    it('a user answer is committed and returned as before', async () => {
        query.mockResolvedValue({ data: { user: { uuid: 'u1', email: 'a@b.c' } } })
        expect(await store.dispatch('fetchMyUser')).toEqual({ uuid: 'u1', email: 'a@b.c' })
        expect(store.getters.myuser).toEqual({ uuid: 'u1', email: 'a@b.c' })
    })
})
