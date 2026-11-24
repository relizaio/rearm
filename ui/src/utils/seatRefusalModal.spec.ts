import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CombinedGraphQLErrors } from '@apollo/client/errors'

// TPF-168 (TPF-165 6.2, recommended routing): the error-link handler opens the Sign-in refused
// dialog for a seat refusal from ANY operation, and a second refusal while it is open (every
// request of a cut-off tab answers one) does not stack another dialog.
const fire = vi.fn()
const logout = vi.fn()
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
vi.mock('./keycloak', () => ({ default: { logout: (...a: any[]) => logout(...a) } }))

const { onSeatRefusalError, showSeatRefusal, seatRefusalShowing } = await import('./seatRefusalModal')
const { seatRefusalOf } = await import('./licenseSeats')

const OVER_LIMIT_TEXT = 'Your ReARM account is outside the licensed number of users. Please contact your ReARM administrator. Reference: OVER-LIMIT.'
const MEMBERSHIP_TEXT = 'ReARM has no free user seat for this membership. Please contact your ReARM administrator. Reference: SEAT-FULL.'
const seatEntry = (message: string, reason: string) => ({ message, extensions: { code: 'LICENSE_SEAT_REFUSED', reason } })

/** A Swal.fire that stays open until `close` is called, as the real blocking dialog does. */
function openDialog () {
    let close: (v: any) => void = () => {}
    fire.mockImplementation(() => new Promise((resolve) => { close = resolve }))
    return (v: any) => close(v)
}

describe('seat refusal routing from any operation', () => {
    beforeEach(() => {
        fire.mockReset()
        logout.mockReset()
    })

    it('a GraphQL error with the seat code opens the dialog with the server text', async () => {
        const close = openDialog()
        const error = new CombinedGraphQLErrors({ errors: [seatEntry(OVER_LIMIT_TEXT, 'OVER_LIMIT')] } as any)
        onSeatRefusalError({ error })
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0][0].title).toBe('Sign-in refused')
        expect(fire.mock.calls[0][0].text).toBe(OVER_LIMIT_TEXT)
        expect(seatRefusalShowing()).toBe(true)
        close({ isConfirmed: true })
        await vi.waitFor(() => expect(seatRefusalShowing()).toBe(false))
        expect(logout).toHaveBeenCalledTimes(1)
    })

    it('the membership text of a refused join or first organization is shown the same way', async () => {
        const close = openDialog()
        onSeatRefusalError({ error: new CombinedGraphQLErrors({ errors: [seatEntry(MEMBERSHIP_TEXT, 'SEAT_FULL')] } as any) })
        expect(fire.mock.calls[0][0].text).toBe(MEMBERSHIP_TEXT)
        close({ isConfirmed: false })
        await vi.waitFor(() => expect(seatRefusalShowing()).toBe(false))
        expect(logout).not.toHaveBeenCalled()
    })

    it('reads the result errors when the error is not a CombinedGraphQLErrors', async () => {
        const close = openDialog()
        onSeatRefusalError({ error: new Error('x'), result: { errors: [seatEntry(OVER_LIMIT_TEXT, 'OVER_LIMIT')] } })
        expect(fire).toHaveBeenCalledTimes(1)
        close({})
        await vi.waitFor(() => expect(seatRefusalShowing()).toBe(false))
    })

    it('a second refusal while the dialog is open does not open another', async () => {
        const close = openDialog()
        const first = showSeatRefusal({ reason: 'OVER_LIMIT', message: OVER_LIMIT_TEXT })
        const second = showSeatRefusal({ reason: 'OVER_LIMIT', message: OVER_LIMIT_TEXT })
        onSeatRefusalError({ error: new CombinedGraphQLErrors({ errors: [seatEntry(OVER_LIMIT_TEXT, 'OVER_LIMIT')] } as any) })
        expect(second).toBe(first)
        expect(fire).toHaveBeenCalledTimes(1)
        close({})
        await first
        expect(seatRefusalShowing()).toBe(false)
    })

    it('any other error opens nothing', () => {
        onSeatRefusalError({ error: new CombinedGraphQLErrors({ errors: [{ message: 'Not authorized' }] } as any) })
        onSeatRefusalError({ error: new CombinedGraphQLErrors({ errors: [{ message: 'x', extensions: { code: 'OTHER', reason: 'NO_ROLE' } }] } as any) })
        onSeatRefusalError({ error: new Error('network down') })
        expect(fire).not.toHaveBeenCalled()
    })
})

describe('the pages that show their own error leave a seat refusal to the dialog', () => {
    it('the invitation join page shows no second dialog over a seat refusal', async () => {
        const { readFileSync } = await import('node:fs')
        const { fileURLToPath } = await import('node:url')
        const page = readFileSync(fileURLToPath(new URL('../components/JoinOrganization.vue', import.meta.url)), 'utf8')
        expect(page).toMatch(/seatRefused = seatRefusalOfError\(error\) !== null/)
        expect(page).toMatch(/\} else if \(!seatRefused\) \{\s*const errText/)
    })

    it('the GraphQL client routes every operation through the handler, before the HTTP link', async () => {
        const { readFileSync } = await import('node:fs')
        const { fileURLToPath } = await import('node:url')
        const client = readFileSync(fileURLToPath(new URL('./graphql.ts', import.meta.url)), 'utf8')
        expect(client).toMatch(/const seatRefusalLink = new ErrorLink\(onSeatRefusalError\)/)
        expect(client).toMatch(/link: {2}headerMiddleware\.concat\(seatRefusalLink\)\.concat\(uploadLink\)/)
    })
})

describe('through a real Apollo client', () => {
    beforeEach(() => {
        fire.mockReset()
    })

    it('a response carrying a seat refusal opens the dialog, and the caller still gets its error', async () => {
        const { ApolloClient, ApolloLink, InMemoryCache, gql } = await import('@apollo/client/core')
        const { ErrorLink } = await import('@apollo/client/link/error')
        const { of } = await import('rxjs')
        const close = openDialog()
        const terminal = new ApolloLink(() => of({ data: { user: null }, errors: [seatEntry(OVER_LIMIT_TEXT, 'OVER_LIMIT')] } as any))
        const client = new ApolloClient({ link: new ErrorLink(onSeatRefusalError).concat(terminal), cache: new InMemoryCache() })
        const resp: any = await client.query({ query: gql`query user { user { uuid } }`, errorPolicy: 'all', fetchPolicy: 'no-cache' })
        expect(fire).toHaveBeenCalledTimes(1)
        expect(fire.mock.calls[0][0].text).toBe(OVER_LIMIT_TEXT)
        expect(seatRefusalOf(resp.error?.errors)?.reason).toBe('OVER_LIMIT')
        close({})
        await vi.waitFor(() => expect(seatRefusalShowing()).toBe(false))
    })
})
