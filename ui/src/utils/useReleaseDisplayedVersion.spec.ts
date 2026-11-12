import { describe, expect, it, vi } from 'vitest'

// commonFunctions boots the GraphQL client (and Keycloak) on import; the composable only uses
// its message extractor, which runs for real here.
vi.mock('@/utils/graphql', () => ({ default: {} }))

const { useReleaseDisplayedVersion } = await import('./useReleaseDisplayedVersion')
const { UPDATE_RELEASE_DISPLAYED_VERSION } = await import('./displayedVersion')

// FDAW-74 row 11 (3.7): the release block's state, with the deferred client of
// useReleaseDeclaredLicense.spec.ts so a response can land after a reset.

type Deferred = { resolve: (v: any) => void, reject: (e: any) => void }
function setup () {
    const pending: Deferred[] = []
    const mutations: Deferred[] = []
    const query = vi.fn((_opts: any) => new Promise((resolve, reject) => { pending.push({ resolve, reject }) }))
    const mutate = vi.fn((_opts: any) => new Promise((resolve, reject) => { mutations.push({ resolve, reject }) }))
    const s = useReleaseDisplayedVersion({ query, mutate } as any)
    return { s, pending, mutations, query, mutate }
}
function driftError (): any {
    const err: any = new Error('Validation error (FieldUndefined@[release/displayedVersion]) : Field \'displayedVersion\' in type \'Release\' is undefined')
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
const TOO_LONG = 'The displayed version is longer than 128 characters'
async function loaded () {
    const t = setup()
    const p = t.s.load('r1', 'o1')
    t.pending[0].resolve({ data: { release: { uuid: 'r1', displayedVersion: 'GV-2.4.2' } } })
    expect(await p).toBe('loaded')
    return t
}

describe('useReleaseDisplayedVersion', () => {
    it('(a) loads the stored value', async () => {
        const { s } = await loaded()
        expect(s.value.value).toBe('GV-2.4.2')
        expect(s.baseline.value).toBe('GV-2.4.2')
        expect(s.loaded.value).toBe(true)
        expect(s.dirty.value).toBe(false)
        expect(s.isSet.value).toBe(true)
    })

    it('(b) saves the changed value and takes the answer as the baseline', async () => {
        const { s, mutate, mutations } = await loaded()
        s.value.value = 'GV-2.4.1 (Build 118)'
        const p = s.save()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(UPDATE_RELEASE_DISPLAYED_VERSION)
        expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', displayedVersion: 'GV-2.4.1 (Build 118)' } })
        mutations[0].resolve({ data: { updateRelease: { uuid: 'r1', displayedVersion: 'GV-2.4.1 (Build 118)' } } })
        expect(await p).toBe('saved')
        expect(s.baseline.value).toBe('GV-2.4.1 (Build 118)')
    })

    it('(c) clears with the empty string', async () => {
        const { s, mutate, mutations } = await loaded()
        const p = s.save(true)
        expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'r1', org: 'o1', displayedVersion: '' } })
        mutations[0].resolve({ data: { updateRelease: { uuid: 'r1', displayedVersion: null } } })
        expect(await p).toBe('cleared')
        expect(s.isSet.value).toBe(false)
    })

    it('(d) sends nothing when not dirty', async () => {
        const { s, mutate } = await loaded()
        expect(await s.save()).toBe('unchanged')
        expect(mutate).toHaveBeenCalledTimes(0)
    })

    it('(e) shows the server refusal and keeps the baseline', async () => {
        const { s, mutations } = await loaded()
        s.value.value = '9'.repeat(129)
        const p = s.save()
        mutations[0].reject({ graphQLErrors: [{ message: TOO_LONG }] })
        expect(await p).toBe('refused')
        expect(s.error.value).toBe(TOO_LONG)
        expect(s.baseline.value).toBe('GV-2.4.2')
    })

    it('(f) a server without the field is unsupported, a failed request is failed; neither is loaded', async () => {
        const a = setup()
        const pa = a.s.load('r1', 'o1')
        a.pending[0].reject(driftError())
        expect(await pa).toBe('unsupported')
        expect(a.s.loaded.value).toBe(false)
        const b = setup()
        const pb = b.s.load('r1', 'o1')
        b.pending[0].reject(new Error('network down'))
        expect(await pb).toBe('failed')
        expect(b.s.loaded.value).toBe(false)
    })

    it('(g) drops an answer that lands after a reset', async () => {
        const { s, pending } = setup()
        const p = s.load('r1')
        s.reset()
        pending[0].resolve({ data: { release: { uuid: 'r1', displayedVersion: 'GV-2.4.2' } } })
        expect(await p).toBe('dropped')
        expect(s.loaded.value).toBe(false)
    })
})
