import { describe, expect, it, vi } from 'vitest'

// commonFunctions boots the GraphQL client (and Keycloak) on import; the composable only uses
// its message extractor, which runs for real here.
vi.mock('@/utils/graphql', () => ({ default: {} }))

const { useReleaseDeclaredLicense } = await import('./useReleaseDeclaredLicense')
const { RELEASE_DECLARED_LICENSE_QUERY, UPDATE_RELEASE_DECLARED_LICENSE } = await import('./declaredLicense')

// T-29 (SCORE-42, design ADR-8): the release block's state, run with a deferred client so a
// response can be made to land after a reset or after a later load (the
// useReleaseSupportCoverage.spec.ts shape, with a deferred mutate beside the query).

type Deferred = { resolve: (v: any) => void, reject: (e: any) => void }
function deferredClient () {
    const pending: Deferred[] = []
    const mutations: Deferred[] = []
    const query = vi.fn((_opts: any) => new Promise((resolve, reject) => { pending.push({ resolve, reject }) }))
    const mutate = vi.fn((_opts: any) => new Promise((resolve, reject) => { mutations.push({ resolve, reject }) }))
    return { client: { query, mutate } as any, pending, mutations, query, mutate }
}
function setup () {
    const c = deferredClient()
    const onUnsupported = vi.fn()
    const s = useReleaseDeclaredLicense(c.client, { onUnsupported })
    return { ...c, s, onUnsupported }
}

const ORG = 'org-1'
const read = (override: string | null, effective: any) =>
    ({ data: { release: { uuid: 'rel-A', licenseExpression: override, effectiveLicense: effective } } })
const saved = (override: string | null, effective: any) =>
    ({ data: { updateRelease: { uuid: 'rel-A', licenseExpression: override, effectiveLicense: effective } } })
const MIT_RELEASE = { expression: 'MIT', url: null, level: 'RELEASE' }
const APACHE_COMPONENT = { expression: 'Apache-2.0', url: 'https://x.example/l', level: 'COMPONENT' }
function driftError (): any {
    const err: any = new Error('Validation error (FieldUndefined@[release/effectiveLicense]) : Field \'effectiveLicense\' in type \'Release\' is undefined')
    err.errors = [{ message: err.message, extensions: { classification: 'ValidationError' } }]
    return err
}
/** A loaded release A that overrides with MIT. */
async function loadedWithMit () {
    const t = setup()
    const p = t.s.load('rel-A', ORG)
    t.pending[0].resolve(read('MIT', MIT_RELEASE))
    expect(await p).toBe('loaded')
    return t
}

describe('useReleaseDeclaredLicense: load', () => {
    it('reads the override and the effective licence', async () => {
        const { s, query } = await loadedWithMit()
        expect(query).toHaveBeenCalledWith({ query: RELEASE_DECLARED_LICENSE_QUERY, variables: { releaseID: 'rel-A', orgID: ORG }, fetchPolicy: 'no-cache' })
        expect(s.override.value).toBe('MIT')
        expect(s.baseline.value).toBe('MIT')
        expect(s.effective.value).toEqual(MIT_RELEASE)
        expect(s.loaded.value).toBe(true)
        expect(s.dirty.value).toBe(false)
        expect(s.isOverridden.value).toBe(true)
    })

    it('reads a null override as empty, not overridden', async () => {
        const { s, pending } = setup()
        const p = s.load('rel-A', ORG)
        pending[0].resolve(read(null, APACHE_COMPONENT))
        expect(await p).toBe('loaded')
        expect(s.override.value).toBe('')
        expect(s.isOverridden.value).toBe(false)
        expect(s.effective.value).toEqual(APACHE_COMPONENT)
    })

    it('queries nothing without a release uuid', async () => {
        const { s, query } = setup()
        expect(await s.load(undefined, ORG)).toBe('skipped')
        expect(await s.load('', ORG)).toBe('skipped')
        expect(query).not.toHaveBeenCalled()
    })

    it('reports schema drift once and stays hidden', async () => {
        const { s, pending, onUnsupported } = setup()
        const p = s.load('rel-A', ORG)
        pending[0].reject(driftError())
        expect(await p).toBe('unsupported')
        expect(onUnsupported).toHaveBeenCalledTimes(1)
        expect(s.loaded.value).toBe(false)
    })

    it('swallows a failed request without claiming the server cannot answer', async () => {
        const { s, pending, onUnsupported } = setup()
        const p = s.load('rel-A', ORG)
        pending[0].reject(new Error('502 Bad Gateway'))
        expect(await p).toBe('failed')
        expect(s.loaded.value).toBe(false)
        expect(s.effective.value).toBeNull()
        expect(onUnsupported).not.toHaveBeenCalled()
    })

    it('drops a response that lands after a reset, a drift refusal included', async () => {
        const { s, pending, onUnsupported } = setup()
        const ok = s.load('rel-A', ORG)
        s.reset()
        pending[0].resolve(read('MIT', MIT_RELEASE))
        expect(await ok).toBe('dropped')
        expect(s.loaded.value).toBe(false)
        expect(s.effective.value).toBeNull()

        const drift = s.load('rel-B', ORG)
        s.reset()
        pending[1].reject(driftError())
        expect(await drift).toBe('dropped')
        expect(onUnsupported).not.toHaveBeenCalled()
        expect(s.loaded.value).toBe(false)
    })

    it('keeps only the last-issued of two overlapping loads', async () => {
        const { s, pending } = setup()
        const first = s.load('rel-A', ORG)
        const second = s.load('rel-B', ORG)
        pending[1].resolve(read(null, APACHE_COMPONENT))
        expect(await second).toBe('loaded')
        pending[0].resolve(read('MIT', MIT_RELEASE))
        expect(await first).toBe('dropped')
        expect(s.effective.value).toEqual(APACHE_COMPONENT)
        expect(s.override.value).toBe('')
    })

    // Round 3 (T-2): the catch branch is fenced too; without it a 502 for A landing on B hides B.
    it('drops a failed read for the previous release that settles after B loaded', async () => {
        const { s, pending } = setup()
        const a = s.load('rel-A', ORG)
        s.reset()
        const b = s.load('rel-B', ORG)
        pending[1].resolve(read(null, APACHE_COMPONENT))
        expect(await b).toBe('loaded')
        pending[0].reject(new Error('502 Bad Gateway'))
        expect(await a).toBe('dropped')
        expect(s.loaded.value).toBe(true)
        expect(s.effective.value).toEqual(APACHE_COMPONENT)
    })

    it('reset clears override, baseline, effective, loaded, saving and error together', async () => {
        const { s, mutations } = await loadedWithMit()
        s.override.value = 'Apache-2'
        const p = s.save()
        mutations[0].reject({ graphQLErrors: [{ message: 'refused' }] })
        await p
        const pending = s.save()
        expect(s.saving.value).toBe(true)
        expect(s.error.value).toBe('refused')
        s.reset()
        expect(s.override.value).toBe('')
        expect(s.baseline.value).toBe('')
        expect(s.effective.value).toBeNull()
        expect(s.loaded.value).toBe(false)
        expect(s.saving.value).toBe(false)
        expect(s.error.value).toBeNull()
        mutations[1].resolve(saved('X', MIT_RELEASE))
        await pending
    })
})

describe('useReleaseDeclaredLicense: save', () => {
    it('sends nothing when the override is unchanged', async () => {
        const { s, mutate } = await loadedWithMit()
        s.override.value = ' MIT '
        expect(await s.save()).toBe('unchanged')
        expect(mutate).not.toHaveBeenCalled()
    })

    it('sends the edit and takes the canonical form and the effective licence from the response', async () => {
        const t = setup()
        const p = t.s.load('rel-A', ORG)
        t.pending[0].resolve(read(null, APACHE_COMPONENT))
        await p
        t.s.override.value = 'mit'
        expect(t.s.dirty.value).toBe(true)
        const saving = t.s.save()
        expect(t.mutate).toHaveBeenCalledWith({
            mutation: UPDATE_RELEASE_DECLARED_LICENSE,
            variables: { release: { uuid: 'rel-A', org: ORG, licenseExpression: 'mit' } },
            fetchPolicy: 'no-cache',
        })
        t.mutations[0].resolve(saved('MIT', MIT_RELEASE))
        expect(await saving).toBe('saved')
        expect(t.s.override.value).toBe('MIT')
        expect(t.s.baseline.value).toBe('MIT')
        expect(t.s.effective.value).toEqual(MIT_RELEASE)
        expect(t.s.isOverridden.value).toBe(true)
        expect(t.s.saving.value).toBe(false)
    })

    it('clears with the empty string, never null, and reads the inherited licence back', async () => {
        const { s, mutate, mutations } = await loadedWithMit()
        const p = s.save(true)
        expect(s.override.value).toBe('')
        expect(mutate.mock.calls[0][0].variables).toEqual({ release: { uuid: 'rel-A', org: ORG, licenseExpression: '' } })
        mutations[0].resolve(saved(null, APACHE_COMPONENT))
        expect(await p).toBe('cleared')
        expect(s.baseline.value).toBe('')
        expect(s.isOverridden.value).toBe(false)
        expect(s.effective.value).toEqual(APACHE_COMPONENT)
    })

    it('shows a refusal as the server worded it and keeps the input', async () => {
        const { s, mutations } = await loadedWithMit()
        s.override.value = 'Apache-2'
        const p = s.save()
        mutations[0].reject({ graphQLErrors: [{ message: 'licenseExpression invalid: unknown licence id "Apache-2"' }] })
        expect(await p).toBe('refused')
        expect(s.error.value).toBe('licenseExpression invalid: unknown licence id "Apache-2"')
        expect(s.override.value).toBe('Apache-2')
        expect(s.baseline.value).toBe('MIT')
    })

    it('drops a save response that lands after a reset', async () => {
        const { s, mutations } = await loadedWithMit()
        s.override.value = 'Apache-2.0'
        const p = s.save()
        s.reset()
        mutations[0].resolve(saved('Apache-2.0', { expression: 'Apache-2.0', url: null, level: 'RELEASE' }))
        expect(await p).toBe('dropped')
        expect(s.override.value).toBe('')
        expect(s.baseline.value).toBe('')
        expect(s.effective.value).toBeNull()
        expect(s.loaded.value).toBe(false)
    })

    // Round 3 (T-2): the refusal is fenced too; without it A's message shows under B.
    it('drops a refusal for the previous release that settles after B loaded', async () => {
        const { s, pending, mutations } = await loadedWithMit()
        s.override.value = 'Apache-2'
        const p = s.save()
        s.reset()
        const b = s.load('rel-B', ORG)
        pending[1].resolve(read('GPL-3.0-only', { expression: 'GPL-3.0-only', url: null, level: 'RELEASE' }))
        expect(await b).toBe('loaded')
        mutations[0].reject({ graphQLErrors: [{ message: 'licenseExpression invalid: unknown licence id "Apache-2"' }] })
        expect(await p).toBe('dropped')
        expect(s.error.value).toBeNull()
        expect(s.saving.value).toBe(false)
        expect(s.loaded.value).toBe(true)
        expect(s.override.value).toBe('GPL-3.0-only')
        expect(s.effective.value).toEqual({ expression: 'GPL-3.0-only', url: null, level: 'RELEASE' })
    })

    // Round 3 (T-3): the finally is fenced too; without it A's late reply clears B's saving while
    // B's own save is in flight, and B's Save is enabled again for a second mutation.
    it.each([
        ['success', (d: { resolve: (v: any) => void, reject: (e: any) => void }) => d.resolve(saved('Apache-2.0', { expression: 'Apache-2.0', url: null, level: 'RELEASE' }))],
        ['refusal', (d: { resolve: (v: any) => void, reject: (e: any) => void }) => d.reject({ graphQLErrors: [{ message: 'licenseExpression invalid: unknown licence id "Apache-2"' }] })],
    ])('keeps B saving when a late %s for the previous release lands during B\'s save', async (_kind, settleA) => {
        const { s, pending, mutations, mutate } = await loadedWithMit()
        s.override.value = 'Apache-2.0'
        const a = s.save()
        expect(s.saving.value).toBe(true)
        s.reset()
        const b = s.load('rel-B', ORG)
        pending[1].resolve(read('GPL-3.0-only', { expression: 'GPL-3.0-only', url: null, level: 'RELEASE' }))
        expect(await b).toBe('loaded')
        s.override.value = 'MIT'
        const bSave = s.save()
        expect(mutate.mock.calls[1][0].variables).toEqual({ release: { uuid: 'rel-B', org: ORG, licenseExpression: 'MIT' } })
        expect(s.saving.value).toBe(true)
        settleA(mutations[0])
        expect(await a).toBe('dropped')
        expect(s.saving.value).toBe(true)
        expect(s.error.value).toBeNull()
        expect(s.override.value).toBe('MIT')
        mutations[1].resolve(saved('MIT', MIT_RELEASE))
        expect(await bSave).toBe('saved')
        expect(s.saving.value).toBe(false)
        expect(s.baseline.value).toBe('MIT')
        expect(s.effective.value).toEqual(MIT_RELEASE)
    })
})
