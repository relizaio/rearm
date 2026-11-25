// @vitest-environment happy-dom
//
// T-506 and T-507 (TPF-185 design 3.6): the upstream source form. T-506: an edit opens on Keep with
// no secret field and "Stored: API key"; choosing Replace shows empty secret fields that do not
// autofill. T-507: a typed secret leaves the page only inside the mutation's variables; Cancel and
// a successful save clear it, a refused save keeps the form open with the backend's sentence and
// logs nothing, and no route is changed in any case.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NModal } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('@/utils/commonFunctions', () => ({ default: { dateDisplay: (d: string) => 'D:' + d } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router, useRoute: () => ({ query: {}, params: {} }) }))

const { default: FormModal } = await import('./UpstreamSourceFormModal.vue')
const { CREATE_UPSTREAM_SOURCE, UPDATE_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { nothingToChange, rootChangeNeedsCredential } = await import('@/utils/upstreamSourceForm')

const ORG = '11111111-2222-3333-4444-555555555555'
const SECRET = 'S3CR3T-marker-185'
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

const LOADED = {
    uuid: 'aaaaaaaa-0000-4000-8000-000000000001',
    name: 'Acme TEA',
    tea: { root: 'https://tea.acme.example/tea' },
    credential: { kind: 'API_KEY' as const, keyId: 'kid-1', setDate: '2026-11-01T10:00:00Z' },
    documentClasses: ['SBOM', 'VEX'] as ('SBOM' | 'VEX')[],
    vexImportMode: 'STAGE' as const,
    autoIntegrate: 'DISABLED' as const,
    syncIntervalMinutes: 1440,
    documentReServing: 'WITHHELD' as const,
}

const spies: Record<string, any> = {}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
    for (const m of ['log', 'info', 'warn', 'error', 'debug']) spies[m] = vi.spyOn(console, m as any).mockImplementation(() => {})
})

afterEach(() => {
    vi.restoreAllMocks()
})

function mountForm (props: Record<string, any> = {}) {
    return mount(FormModal, { props: { show: true, orgUuid: ORG, ...props }, global, attachTo: document.body })
}

async function setInput (w: any, testid: string, value: string) {
    const input = w.find(`[data-testid="${testid}"] input`)
    expect(input.exists()).toBe(true)
    await input.setValue(value)
}

async function chooseRadio (w: any, testid: string) {
    await w.find(`[data-testid="${testid}"]`).trigger('click')
    await flushPromises()
}

const tokenField = (w: any) => w.find('[data-testid="upstream-form-token"] input').element as HTMLInputElement

async function fillCreate (w: any) {
    await setInput(w, 'upstream-form-name', 'Acme TEA')
    await setInput(w, 'upstream-form-root', 'https://tea.acme.example/tea')
    await chooseRadio(w, 'upstream-form-kind-BEARER_TOKEN')
    await setInput(w, 'upstream-form-token', SECRET)
    expect(tokenField(w).value).toBe(SECRET)
}

function consoleCalls (): any[][] {
    return Object.values(spies).flatMap((s: any) => s.mock.calls)
}

describe('UpstreamSourceFormModal edit credential', () => {
    it('keepShowsTheStoredKindAndNoSecretField', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        expect(w.text()).toContain('Stored: API key')
        expect(document.body.querySelector('input[type=password]')).toBeNull()
        expect(w.find('input[type=password]').exists()).toBe(false)
        w.unmount()
    })

    it('replaceShowsEmptyNonAutofillingSecretFields', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        await chooseRadio(w, 'upstream-form-change-replace')

        const passwords = w.findAll('input[type=password]')
        expect(passwords.length).toBeGreaterThan(0)
        for (const p of passwords) {
            expect((p.element as HTMLInputElement).value).toBe('')
            expect(p.attributes('autocomplete')).toBe('new-password')
        }
        expect(w.find('[data-testid="upstream-form-credential-kind"]').exists()).toBe(true)
        w.unmount()
    })

    it('replaceWithAnApiKeyStartsWithAnEmptyKeyId', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        await chooseRadio(w, 'upstream-form-change-replace')
        await chooseRadio(w, 'upstream-form-kind-API_KEY')

        expect((w.find('[data-testid="upstream-form-key-id"] input').element as HTMLInputElement).value).toBe('')
        const passwords = w.findAll('input[type=password]')
        expect(passwords.length).toBe(1)
        expect((passwords[0].element as HTMLInputElement).value).toBe('')
        expect(passwords[0].attributes('autocomplete')).toBe('new-password')
        w.unmount()
    })
})

// The two secret refs of the modal, read from its setup state: the close must clear them by itself,
// not only the next opening.
function secretRefs (w: any): { token: string, keySecret: string } {
    return { token: w.vm.token, keySecret: w.vm.keySecret }
}

describe('UpstreamSourceFormModal secret handling', () => {
    it('cancelClearsTheTypedSecret', async () => {
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)

        expect(secretRefs(w)).toEqual({ token: SECRET, keySecret: '' })
        await w.find('[data-testid="upstream-form-cancel"]').trigger('click')
        await flushPromises()
        expect(w.emitted('update:show')).toEqual([[false]])
        // cleared by the close itself, before any reopening
        expect(secretRefs(w)).toEqual({ token: '', keySecret: '' })

        // the parent closes and opens the modal again
        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        await chooseRadio(w, 'upstream-form-kind-BEARER_TOKEN')
        expect(tokenField(w).value).toBe('')

        expect(mutate).not.toHaveBeenCalled()
        expect(query).not.toHaveBeenCalled()
        expect(router.push).not.toHaveBeenCalled()
        expect(router.replace).not.toHaveBeenCalled()
        w.unmount()
    })

    it('closingByTheMaskOrEscapeClearsTheSecretsAndUnmountClearsThemToo', async () => {
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)
        expect(secretRefs(w).token).toBe(SECRET)
        w.findComponent(NModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(w.emitted('update:show')).toEqual([[false]])
        expect(secretRefs(w)).toEqual({ token: '', keySecret: '' })

        const v = mountForm()
        await flushPromises()
        await fillCreate(v)
        const vm: any = v.vm
        expect(vm.token).toBe(SECRET)
        v.unmount()
        expect(vm.token).toBe('')
    })

    it('aRefusedSaveKeepsTheFormOpenShowsTheSentenceAndLogsNothingSecret', async () => {
        const sentence = 'An upstream source with this root already exists.'
        mutate.mockRejectedValueOnce({
            message: 'x',
            errors: [{ message: sentence, extensions: { code: 'UPSTREAM_SOURCE_REFUSED', reason: 'DUPLICATE_ROOT' } }],
        })
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)

        await w.find('[data-testid="upstream-form-save"]').trigger('click')
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        expect(w.emitted('update:show')).toBeUndefined()
        expect(w.emitted('saved')).toBeUndefined()
        expect(w.find('[data-testid="upstream-source-form"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-form-error"]').text()).toBe(sentence)
        expect(tokenField(w).value).toBe(SECRET)
        expect(secretRefs(w).token).toBe(SECRET)
        for (const call of consoleCalls()) {
            for (const arg of call) {
                expect(JSON.stringify(arg) ?? '').not.toContain(SECRET)
            }
        }
        expect(router.push).not.toHaveBeenCalled()
        expect(router.replace).not.toHaveBeenCalled()
        w.unmount()
    })

    it('aSuccessfulSaveSendsTheSecretOnlyInTheCredentialAndClearsIt', async () => {
        mutate.mockResolvedValueOnce({ data: { createUpstreamSource: { uuid: 'some-uuid' } } })
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)

        await w.find('[data-testid="upstream-form-save"]').trigger('click')
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        const arg = mutate.mock.calls[0][0]
        expect(arg.mutation).toBe(CREATE_UPSTREAM_SOURCE)
        expect(arg.variables.org).toBe(ORG)
        expect(JSON.stringify(arg.variables.source.credential)).toContain(SECRET)
        const copy = JSON.parse(JSON.stringify(arg))
        delete copy.variables.source.credential
        expect(JSON.stringify(copy)).not.toContain(SECRET)

        expect(w.emitted('saved')).toEqual([['some-uuid']])
        expect(w.emitted('update:show')).toEqual([[false]])
        expect(secretRefs(w)).toEqual({ token: '', keySecret: '' })
        expect(consoleCalls()).toEqual([])
        expect(router.push).not.toHaveBeenCalled()
        expect(router.replace).not.toHaveBeenCalled()

        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        await chooseRadio(w, 'upstream-form-kind-BEARER_TOKEN')
        expect(tokenField(w).value).toBe('')
        w.unmount()
    })
})

// The choices of the credential and the life of what was typed for one of them.
describe('UpstreamSourceFormModal credential choices', () => {
    it('anotherCredentialChangeClearsTheTypedSecretAndKeyId', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        await chooseRadio(w, 'upstream-form-change-replace')
        await setInput(w, 'upstream-form-key-id', 'kid-2')
        await setInput(w, 'upstream-form-key-secret', SECRET)
        expect(secretRefs(w).keySecret).toBe(SECRET)

        await chooseRadio(w, 'upstream-form-change-keep')
        expect(secretRefs(w)).toEqual({ token: '', keySecret: '' })
        expect((w.vm as any).model.keyId).toBe('')
        w.unmount()
    })

    it('anotherKindClearsTheTypedSecret', async () => {
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)
        await chooseRadio(w, 'upstream-form-kind-API_KEY')
        expect(secretRefs(w)).toEqual({ token: '', keySecret: '' })
        w.unmount()
    })

    it('theTokenFieldIsAPasswordThatDoesNotAutofill', async () => {
        const w = mountForm()
        await flushPromises()
        await chooseRadio(w, 'upstream-form-kind-BEARER_TOKEN')
        const input = w.find('[data-testid="upstream-form-token"] input')
        expect(input.attributes('type')).toBe('password')
        expect(input.attributes('autocomplete')).toBe('new-password')
        w.unmount()
    })

    it('replaceOffersNoKindNone', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        await chooseRadio(w, 'upstream-form-change-replace')
        expect(w.find('[data-testid="upstream-form-kind-BEARER_TOKEN"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-form-kind-API_KEY"]').exists()).toBe(true)
        expect(w.find('[data-testid="upstream-form-kind-NONE"]').exists()).toBe(false)
        w.unmount()
    })

    it('removeIsOfferedOnlyWhenACredentialIsStored', async () => {
        const stored = mountForm({ source: LOADED })
        await flushPromises()
        expect(stored.find('[data-testid="upstream-form-change-remove"]').exists()).toBe(true)
        stored.unmount()

        const w = mountForm({ source: { ...LOADED, credential: { kind: 'NONE', keyId: null, setDate: null } } })
        await flushPromises()
        expect(w.text()).toContain('Stored: no credential')
        expect(w.text()).toContain('Keep no credential')
        expect(w.text()).toContain('Add one')
        expect(w.find('[data-testid="upstream-form-change-remove"]').exists()).toBe(false)
        w.unmount()
    })
})

// When the modal sends, and what.
describe('UpstreamSourceFormModal save', () => {
    it('aFormWithProblemsSendsNothingAndShowsThem', async () => {
        const w = mountForm()
        await flushPromises()
        await w.find('[data-testid="upstream-form-save"]').trigger('click')
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        expect(w.text()).toContain('Enter a name.')
        expect(w.text()).toContain('Enter the server root.')
        expect(w.emitted('saved')).toBeUndefined()
        w.unmount()
    })

    it('anUnchangedEditDisablesSaveAndSendsNothingEvenWhenSaveRuns', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        expect(w.find('[data-testid="upstream-form-nothing"]').text()).toBe(nothingToChange)
        expect(w.find('[data-testid="upstream-form-save"]').attributes('disabled')).toBeDefined()
        // the guard inside save holds on its own, without the disabled button
        await (w.vm as any).save()
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('anEditSendsOnlyTheChangedFieldsForThatSourceAndEmitsItsUuid', async () => {
        mutate.mockResolvedValueOnce({ data: { updateUpstreamSource: { uuid: LOADED.uuid, name: 'Renamed' } } })
        const w = mountForm({ source: LOADED })
        await flushPromises()
        await setInput(w, 'upstream-form-name', 'Renamed')
        await w.find('[data-testid="upstream-form-save"]').trigger('click')
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(UPDATE_UPSTREAM_SOURCE)
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: LOADED.uuid, source: { name: 'Renamed' } })
        expect(w.emitted('saved')).toEqual([[LOADED.uuid]])
        expect(w.emitted('update:show')).toEqual([[false]])
        w.unmount()
    })

    it('theRootRuleShowsBeforeTheFirstSaveAndBlocksIt', async () => {
        const w = mountForm({ source: LOADED })
        await flushPromises()
        await setInput(w, 'upstream-form-root', 'https://other.acme.example/tea')
        expect(w.text()).toContain(rootChangeNeedsCredential)
        await w.find('[data-testid="upstream-form-save"]').trigger('click')
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

// Opening and closing by the parent.
describe('UpstreamSourceFormModal opened and closed by the parent', () => {
    it('aCloseByTheParentClearsTheSecrets', async () => {
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)
        await w.setProps({ show: false })
        await flushPromises()
        expect(secretRefs(w)).toEqual({ token: '', keySecret: '' })
        w.unmount()
    })

    it('reopeningStartsFromTheStartingValues', async () => {
        mutate.mockRejectedValueOnce({ message: 'x', errors: [{ message: 'Resource is busy, please retry' }] })
        const w = mountForm()
        await flushPromises()
        await fillCreate(w)
        await w.find('[data-testid="upstream-form-save"]').trigger('click')
        await flushPromises()
        expect(w.find('[data-testid="upstream-form-error"]').exists()).toBe(true)

        await w.setProps({ show: false })
        await w.setProps({ show: true })
        await flushPromises()
        expect((w.vm as any).model.name).toBe('')
        expect((w.vm as any).model.credentialKind).toBe('NONE')
        expect(w.find('[data-testid="upstream-form-error"]').exists()).toBe(false)
        w.unmount()
    })
})
