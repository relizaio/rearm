// @vitest-environment happy-dom
//
// T-520 (TPF-185 design 3.7, threat model row 6): the start of a follow only prepares. A text that
// is not a uuid, and the nil uuid, give the uuid problem and send nothing; a valid uuid is sent
// trimmed and lower-cased with the chosen source and kind in one PREPARE_UPSTREAM_FOLLOW, and the
// router then opens the confirmation page of the returned preparation. Opened with source, kind
// and uuid as props, the fields start with them.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NRadio, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { PREPARE_UPSTREAM_FOLLOW } = await import('@/utils/upstreamFollowQueries')
const { followUuidHint } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const S1 = 'aaaaaaaa-0000-4000-8000-000000000001'
const S2 = 'aaaaaaaa-0000-4000-8000-000000000002'
const ITEM = '3f2a6c1e-8b7d-4e5f-9a0b-1c2d3e4f5a6b'
const PREP = 'cccccccc-0000-4000-8000-000000000009'
const SOURCES = [
    { uuid: S1, name: 'Acme TEA', status: 'ACTIVE' },
    { uuid: S2, name: 'Globex TEA', status: 'ACTIVE' },
    { uuid: 'aaaaaaaa-0000-4000-8000-000000000003', name: 'Old TEA', status: 'ARCHIVED' },
]
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' } } }

function mountModal (props: Record<string, any> = {}) {
    return mount(StartModal, { props: { show: true, orgUuid: ORG, sources: SOURCES, ...props }, global, attachTo: document.body })
}

const uuidInput = (w: any) => w.find('[data-testid="upstream-start-uuid"] input')
const submit = async (w: any) => { await w.find('[data-testid="upstream-start-submit"]').trigger('click'); await flushPromises() }

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
})

describe('UpstreamFollowStartModal', () => {
    it('offersTheActiveSourcesOnly', async () => {
        const w = mountModal()
        await flushPromises()
        const options = w.findComponent(NSelect).props('options') as any[]
        expect(options.map(o => o.value)).toEqual([S1, S2])
        expect(w.text()).toContain(followUuidHint)
        w.unmount()
    })

    it.each([['abc'], ['00000000-0000-0000-0000-000000000000']])('showsTheUuidProblemForAndSendsNothing %s', async (typed) => {
        const w = mountModal({ source: S1 })
        await uuidInput(w).setValue(typed)
        await submit(w)
        expect(w.text()).toContain('Enter the uuid of the item on the supplier\'s server')
        expect(mutate).not.toHaveBeenCalled()
        expect(query).not.toHaveBeenCalled()
        expect(router.push).not.toHaveBeenCalled()
        w.unmount()
    })

    it('preparesAValidUuidTrimmedAndLowerCasedAndOpensTheConfirmation', async () => {
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: PREP, state: 'PENDING' } } })
        const w = mountModal()
        w.findComponent(NSelect).vm.$emit('update:value', S2)
        const component = w.findAllComponents(NRadio).find(r => r.props('value') === 'COMPONENT')!
        await component.find('input').setValue(true)
        await uuidInput(w).setValue(`  ${ITEM.toUpperCase()}  `)
        await submit(w)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(PREPARE_UPSTREAM_FOLLOW)
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: S2, kind: 'COMPONENT', upstreamUuid: ITEM })
        expect(query).not.toHaveBeenCalled()
        expect(router.push).toHaveBeenCalledWith({ name: 'UpstreamFollowPreparation', params: { orguuid: ORG, uuid: PREP } })
        expect(w.emitted('update:show')?.at(-1)).toEqual([false])
        w.unmount()
    })

    it('sendsOnePreparationWhileTheFirstIsInFlight', async () => {
        let release: (v: any) => void = () => {}
        mutate.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
        const w = mountModal({ source: S1, upstreamUuid: ITEM })
        const first = (w.vm as any).submit()
        await (w.vm as any).submit()
        expect(mutate).toHaveBeenCalledTimes(1)
        release({ data: { prepareUpstreamFollow: { uuid: PREP, state: 'PENDING' } } })
        await first
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(router.push).toHaveBeenCalledTimes(1)
        w.unmount()
    })

    it('opensNothingAndStaysOpenWhenTheAnswerHasNoUuid', async () => {
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: null, state: 'PENDING' } } })
        const w = mountModal({ source: S1, upstreamUuid: ITEM })
        await submit(w)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(router.push).not.toHaveBeenCalled()
        expect(w.emitted('update:show')).toBeUndefined()
        w.unmount()
    })

    it('requiresASource', async () => {
        const w = mountModal()
        await uuidInput(w).setValue(ITEM)
        await submit(w)
        expect(w.text()).toContain('Choose a source.')
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('startsWithTheGivenSourceKindAndUuid', async () => {
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: PREP, state: 'PENDING' } } })
        const w = mountModal({ source: S1, kind: 'COMPONENT', upstreamUuid: ITEM })
        await flushPromises()
        expect(w.findComponent(NSelect).props('value')).toBe(S1)
        expect(w.findAllComponents(NRadio).find(r => r.props('value') === 'COMPONENT')!.find('input').element.checked).toBe(true)
        expect((uuidInput(w).element as HTMLInputElement).value).toBe(ITEM)
        await submit(w)
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: S1, kind: 'COMPONENT', upstreamUuid: ITEM })
        w.unmount()
    })

    it('showsARefusalInTheModalAndKeepsItOpen', async () => {
        const err: any = new Error('x')
        err.graphQLErrors = [{ message: 'A preparation limit of the organization is reached. Prepare again later.', extensions: { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'PREPARATION_LIMIT' } }]
        mutate.mockRejectedValueOnce(err)
        const w = mountModal({ source: S1, upstreamUuid: ITEM })
        await submit(w)
        expect(w.find('[data-testid="upstream-start-error"]').text()).toBe('A preparation limit of the organization is reached. Prepare again later.')
        expect(router.push).not.toHaveBeenCalled()
        expect(w.emitted('update:show')).toBeUndefined()
        expect((uuidInput(w).element as HTMLInputElement).value).toBe(ITEM)
        w.unmount()
    })

    it('readsTheActiveSourcesItselfWithoutTheSourcesProp', async () => {
        query.mockResolvedValueOnce({ data: { upstreamSources: SOURCES } })
        const w = mount(StartModal, { props: { show: true, orgUuid: ORG }, global, attachTo: document.body })
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(1)
        expect(query.mock.calls[0][0].variables).toEqual({ org: ORG, statuses: ['ACTIVE'] })
        expect((w.findComponent(NSelect).props('options') as any[]).map(o => o.value)).toEqual([S1, S2])
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})
