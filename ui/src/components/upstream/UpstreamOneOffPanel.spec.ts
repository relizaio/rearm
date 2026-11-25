// @vitest-environment happy-dom
//
// T-561 and T-562 (TPF-185 design 3.10, threat model rows 5, 8 and 10): the tab "By TEI". The TEI
// is checked in the browser, sent once as a variable of the resolve and never fetched or put into
// the route; a reader has the list and no form; the roots a TEI led to are text, and only the
// answer's sourceCanBeAdded draws the button that opens the source form with the root; the states
// of an open one-off are worded as the design says.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))

// Reactive, so a test can let the mocked router move the route as the real one does.
const route: any = reactive({ params: {}, query: {} })
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRoute: () => route, useRouter: () => router }))

const { default: Panel } = await import('./UpstreamOneOffPanel.vue')
const { default: SourceFormModal } = await import('./UpstreamSourceFormModal.vue')
const {
    CHOOSE_UPSTREAM_ONE_OFF, GET_UPSTREAM_ONE_OFF, GET_UPSTREAM_ONE_OFFS, REFRESH_UPSTREAM_ONE_OFF, RESOLVE_UPSTREAM_TEI,
} = await import('@/utils/upstreamOneOffQueries')
const { forbiddenSentence, oneOffFrozenSentence } = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const SOURCE = 'aaaaaaaa-0000-4000-8000-000000000001'
const SOURCE_2 = 'aaaaaaaa-0000-4000-8000-000000000002'
const ONE_OFF = 'bbbbbbbb-0000-4000-8000-000000000001'
const RELEASE = 'cccccccc-0000-4000-8000-000000000001'
const UP_RELEASE_1 = 'dddddddd-0000-4000-8000-000000000001'
const UP_RELEASE_2 = 'dddddddd-0000-4000-8000-000000000002'
const NEW_SOURCE = 'aaaaaaaa-0000-4000-8000-000000000009'
const TEI = 'tei://tea.supplier.example/product/1.0'
const TEI_HOST = 'tea.supplier.example'
const TEI_SENTENCE = 'The TEI must be 1 to 2048 printable ASCII characters without spaces, starting with tei://.'
const NO_MATCH_SENTENCE = 'The TEI resolved to a server that is not an upstream source of the organization.'
const ADMIN_CAN_ADD = 'An administrator of the organization can add the server as an upstream source.'
const HOSTILE_ROOT = 'https://tea.other.example/<b>x</b>'
const SCRIPT_ROOT = 'javascript:alert(1)'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }, RouterLink } }

const SOURCES = [
    { uuid: SOURCE, name: 'Acme TEA', status: 'ACTIVE' },
    { uuid: SOURCE_2, name: 'Beta TEA', status: 'ACTIVE' },
    { uuid: 'aaaaaaaa-0000-4000-8000-000000000003', name: 'Old TEA', status: 'ARCHIVED' },
]

function oneOff (state: string, extra: Record<string, any> = {}): any {
    return {
        uuid: ONE_OFF, tei: TEI, state, failure: null, sourceFailure: null, source: null, unmatchedRoots: [], sourceCanBeAdded: false,
        createdDate: '2026-11-20T10:00:00Z', candidates: [], release: null, lastRun: null, ...extra,
    }
}

function ingestedRelease (): any {
    return { uuid: 'eeeeeeee-0000-4000-8000-000000000001', item: 'ffffffff-0000-4000-8000-000000000001', upstreamUuid: UP_RELEASE_1,
        upstreamVersion: '1.0', state: 'INGESTED', failure: null, message: null, release: RELEASE, oneOff: ONE_OFF }
}

function refusal (...messages: string[]): any {
    const err: any = new Error(messages[0])
    err.errors = messages.map(message => ({ message }))
    return err
}

// Reads are answered by document: the list, and the open one-off when the route names one.
function answer (list: any[] | Error, open: any = null) {
    query.mockImplementation(async (args: any) => {
        if (args.query === GET_UPSTREAM_ONE_OFFS) {
            if (list instanceof Error) throw list
            return { data: { upstreamOneOffs: list } }
        }
        if (args.query === GET_UPSTREAM_ONE_OFF) {
            if (open instanceof Error) throw open
            return { data: { upstreamOneOff: open } }
        }
        throw new Error('unexpected query')
    })
}

async function mountPanel (canWrite: boolean) {
    const w = mount(Panel, { props: { orgUuid: ORG, sources: SOURCES, canWrite }, global, attachTo: document.body })
    await flushPromises()
    return w
}

async function mountOpen (one: any, canWrite = true) {
    route.query = { tab: 'tei', oneOff: one.uuid }
    answer([one], one)
    return mountPanel(canWrite)
}

const mutationsOf = (doc: any) => mutate.mock.calls.filter(c => c[0].mutation === doc).map(c => c[0])
const tid = (w: any, id: string) => w.find(`[data-testid="${id}"]`)

let fetchSpy: any

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
    route.params = { orguuid: ORG }
    route.query = { tab: 'tei' }
    fetchSpy = vi.spyOn(globalThis, 'fetch')
})

afterEach(() => {
    fetchSpy.mockRestore()
    vi.useRealTimers()
})

describe('T-561: resolving a TEI', () => {
    it('sendsOneResolveWithTheTrimmedTeiAndNoSourceAndPutsOnlyTheUuidIntoTheRoute', async () => {
        answer([])
        mutate.mockResolvedValue({ data: { resolveUpstreamTei: oneOff('RESOLVING') } })
        router.replace.mockImplementation((to: any) => { route.query = to.query })
        const w = await mountPanel(true)
        await tid(w, 'upstream-tei-input').find('input').setValue('  ' + TEI + '  ')
        await tid(w, 'upstream-tei-ingest').trigger('click')
        await flushPromises()

        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(RESOLVE_UPSTREAM_TEI)
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, tei: TEI, source: null })
        expect(fetchSpy).not.toHaveBeenCalled()

        expect(router.replace).toHaveBeenCalledTimes(1)
        const given = router.replace.mock.calls[0][0]
        expect(given.query.oneOff).toBe(ONE_OFF)
        const text = JSON.stringify(given)
        expect(text).not.toContain(TEI)
        expect(text).not.toContain(TEI_HOST)
        expect(text).not.toContain('tei:')
        expect(router.push).not.toHaveBeenCalled()

        // The answer is open at once, resolving, without reading it again.
        expect(query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_ONE_OFF)).toHaveLength(0)
        expect(tid(w, 'upstream-one-off-resolving').text()).toContain('Resolving the TEI through ReARM. This takes up to two minutes.')
        expect(tid(w, 'upstream-one-off-tei').text()).toBe(TEI)
        w.unmount()
    })

    it('sendsTheChosenSourceWhenOneIsNamed', async () => {
        answer([])
        mutate.mockResolvedValue({ data: { resolveUpstreamTei: oneOff('RESOLVING') } })
        const w = await mountPanel(true)
        const select = w.findComponent({ name: 'Select' })
        const values = select.props('options').map((o: any) => o.value)
        expect(values).toEqual(['ANY', SOURCE, SOURCE_2])
        expect(select.props('options')[0].label).toBe('Any source that matches')
        select.vm.$emit('update:value', SOURCE_2)
        await tid(w, 'upstream-tei-input').find('input').setValue(TEI)
        await tid(w, 'upstream-tei-ingest').trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: ORG, tei: TEI, source: SOURCE_2 })
        w.unmount()
    })

    it('givesAReaderTheListAndNoForm', async () => {
        answer([oneOff('INGESTED', { release: ingestedRelease() })])
        const w = await mountPanel(false)
        expect(tid(w, 'upstream-tei-form').exists()).toBe(false)
        expect(tid(w, 'upstream-tei-input').exists()).toBe(false)
        expect(tid(w, 'upstream-one-offs-table').exists()).toBe(true)
        expect(tid(w, 'upstream-one-offs-table').text()).toContain(TEI)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it.each([
        ['not a TEI', 'https://tea.supplier.example/x'],
        ['a space inside', 'tei://tea.supplier.example/a b'],
        ['empty', ''],
    ])('showsTheSentenceAndSendsNoRequestFor %s', async (_what, text) => {
        answer([])
        const w = await mountPanel(true)
        query.mockClear()
        await tid(w, 'upstream-tei-input').find('input').setValue(text)
        await tid(w, 'upstream-tei-ingest').trigger('click')
        await flushPromises()
        expect(w.text()).toContain(TEI_SENTENCE)
        expect(mutate).not.toHaveBeenCalled()
        expect(query).not.toHaveBeenCalled()
        expect(fetchSpy).not.toHaveBeenCalled()
        expect(router.replace).not.toHaveBeenCalled()
        w.unmount()
    })

    it('showsARefusedResolveInTheFormAndKeepsTheTei', async () => {
        answer([])
        mutate.mockRejectedValue(refusal('A limit of TEI resolves of the organization is reached. Resolve the TEI later.'))
        const w = await mountPanel(true)
        await tid(w, 'upstream-tei-input').find('input').setValue(TEI)
        await tid(w, 'upstream-tei-ingest').trigger('click')
        await flushPromises()
        expect(tid(w, 'upstream-tei-resolve-error').text()).toBe('A limit of TEI resolves of the organization is reached. Resolve the TEI later.')
        expect((tid(w, 'upstream-tei-input').find('input').element as HTMLInputElement).value).toBe(TEI)
        expect(router.replace).not.toHaveBeenCalled()
        w.unmount()
    })

    it('readsAResolvingOneOffAgainEveryTwoSecondsUntilItCloses', async () => {
        vi.useFakeTimers()
        route.query = { tab: 'tei', oneOff: ONE_OFF }
        let open = oneOff('RESOLVING')
        answer([], null)
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_ONE_OFFS) return { data: { upstreamOneOffs: [] } }
            return { data: { upstreamOneOff: open } }
        })
        const w = await mountPanel(true)
        const reads = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_ONE_OFF).length
        expect(reads()).toBe(1)
        await vi.advanceTimersByTimeAsync(2000)
        expect(reads()).toBe(2)
        open = oneOff('INGESTED', { release: ingestedRelease() })
        await vi.advanceTimersByTimeAsync(2000)
        expect(reads()).toBe(3)
        expect(tid(w, 'upstream-one-off-ingested').exists()).toBe(true)
        await vi.advanceTimersByTimeAsync(6000)
        expect(reads()).toBe(3)
        w.unmount()
    })

    it('keepsReadingAfterAFailedReadAndClearsItsSentence', async () => {
        vi.useFakeTimers()
        route.query = { tab: 'tei', oneOff: ONE_OFF }
        let fail = false
        query.mockImplementation(async (args: any) => {
            if (args.query === GET_UPSTREAM_ONE_OFFS) return { data: { upstreamOneOffs: [] } }
            if (fail) throw refusal('Resource is busy, please retry')
            return { data: { upstreamOneOff: oneOff('RESOLVING') } }
        })
        const w = await mountPanel(true)
        fail = true
        await vi.advanceTimersByTimeAsync(2000)
        expect(tid(w, 'upstream-one-off-read-error').text()).toBe('Resource is busy, please retry')
        expect(tid(w, 'upstream-one-off-resolving').exists()).toBe(true)
        fail = false
        await vi.advanceTimersByTimeAsync(2000)
        expect(tid(w, 'upstream-one-off-read-error').exists()).toBe(false)
        expect(query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_ONE_OFF)).toHaveLength(3)
        w.unmount()
    })

    it('stopsReadingWhenUnmounted', async () => {
        vi.useFakeTimers()
        const w = await mountOpen(oneOff('QUEUED'))
        const reads = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_ONE_OFF).length
        expect(reads()).toBe(1)
        w.unmount()
        await vi.advanceTimersByTimeAsync(6000)
        expect(reads()).toBe(1)
    })
})

describe('T-562: an open one-off', () => {
    const noMatch = (canAdd: boolean) => oneOff('REFUSED', {
        failure: 'NO_MATCHING_SOURCE', unmatchedRoots: [HOSTILE_ROOT, SCRIPT_ROOT], sourceCanBeAdded: canAdd,
    })

    it('showsAMemberTheRootsAsTextAndNoButtonWhenTheSourceCannotBeAdded', async () => {
        const w = await mountOpen(noMatch(false))
        const refused = tid(w, 'upstream-one-off-refused')
        expect(refused.text()).toContain(NO_MATCH_SENTENCE)
        const roots = w.findAll('[data-testid="upstream-unmatched-root-text"]').map(r => r.text())
        expect(roots).toEqual([HOSTILE_ROOT, SCRIPT_ROOT])
        expect(refused.find('b').exists()).toBe(false)
        expect(refused.find('a').exists()).toBe(false)
        expect(refused.text()).toContain(ADMIN_CAN_ADD)
        expect(tid(w, 'upstream-add-source').exists()).toBe(false)
        expect(w.findComponent(SourceFormModal).exists()).toBe(false)
        w.unmount()
    })

    it('givesAnAdministratorOneButtonPerRootThatOpensTheSourceFormWithThatRoot', async () => {
        const w = await mountOpen(noMatch(true))
        const buttons = w.findAll('[data-testid="upstream-add-source"]')
        expect(buttons).toHaveLength(2)
        expect(buttons[0].text()).toBe('Add this server as a source')
        expect(w.text()).not.toContain(ADMIN_CAN_ADD)
        expect(w.findComponent(SourceFormModal).exists()).toBe(false)
        query.mockClear()

        await buttons[1].trigger('click')
        await flushPromises()
        const modal = w.findComponent(SourceFormModal)
        expect(modal.exists()).toBe(true)
        expect(modal.props('initialRoot')).toBe(SCRIPT_ROOT)
        expect(modal.props('source')).toBeFalsy()
        expect(mutate).not.toHaveBeenCalled()
        expect(query).not.toHaveBeenCalled()
        expect(router.replace).not.toHaveBeenCalled()
        expect(router.push).not.toHaveBeenCalled()
        w.unmount()
    })

    it('offersToResolveAgainWithTheNewSourceAfterTheSave', async () => {
        const w = await mountOpen(noMatch(true))
        await w.findAll('[data-testid="upstream-add-source"]')[0].trigger('click')
        await flushPromises()
        expect(tid(w, 'upstream-resolve-again').exists()).toBe(false)
        w.findComponent(SourceFormModal).vm.$emit('saved', NEW_SOURCE)
        w.findComponent(SourceFormModal).vm.$emit('update:show', false)
        await flushPromises()
        expect(w.emitted('source-added')).toEqual([[NEW_SOURCE]])
        expect(w.findComponent(SourceFormModal).exists()).toBe(false)

        mutate.mockResolvedValue({ data: { resolveUpstreamTei: oneOff('RESOLVING', { uuid: 'bbbbbbbb-0000-4000-8000-000000000002' }) } })
        await tid(w, 'upstream-resolve-again').trigger('click')
        await flushPromises()
        expect(mutationsOf(RESOLVE_UPSTREAM_TEI)).toHaveLength(1)
        expect(mutationsOf(RESOLVE_UPSTREAM_TEI)[0].variables).toEqual({ org: ORG, tei: TEI, source: NEW_SOURCE })
        expect(router.replace.mock.calls[0][0].query.oneOff).toBe('bbbbbbbb-0000-4000-8000-000000000002')
        w.unmount()
    })

    it('showsTheProbeSentenceOfTheSourceFailureWhenServed', async () => {
        const w = await mountOpen(oneOff('REFUSED', { failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT' }))
        expect(w.text()).toContain('Resolving the TEI failed.')
        expect(tid(w, 'upstream-probe-message').text()).toBe('The server did not answer in time.')
        expect(tid(w, 'upstream-probe-hint').exists()).toBe(true)
        w.unmount()
    })

    it('choosesTheSecondCandidateWithItsReleaseAndSource', async () => {
        const ambiguous = oneOff('AMBIGUOUS', { candidates: [
            { upstreamRelease: UP_RELEASE_1, source: SOURCE, root: 'https://tea.a.example/tea' },
            { upstreamRelease: UP_RELEASE_2, source: SOURCE_2, root: 'https://tea.b.example/tea' },
        ] })
        const w = await mountOpen(ambiguous)
        const block = tid(w, 'upstream-one-off-ambiguous')
        expect(block.text()).toContain('This TEI names more than one release on your sources. Choose the one to ingest.')
        expect(block.text()).toContain('The choice stays open for 30 minutes.')
        expect(block.text()).toContain('Beta TEA')
        expect(block.text()).toContain('https://tea.b.example/tea')
        expect(block.find('a').exists()).toBe(false)

        mutate.mockResolvedValue({ data: { chooseUpstreamOneOff: oneOff('QUEUED') } })
        const buttons = w.findAll('[data-testid="upstream-choose-candidate"]')
        expect(buttons).toHaveLength(2)
        expect(buttons[1].text()).toBe('Ingest this one')
        await buttons[1].trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(CHOOSE_UPSTREAM_ONE_OFF)
        expect(mutate.mock.calls[0][0].variables).toEqual({ oneOff: ONE_OFF, upstreamRelease: UP_RELEASE_2, source: SOURCE_2 })
        expect(tid(w, 'upstream-one-off-queued').text()).toContain('The release is queued for ingestion.')
        w.unmount()
    })

    it('showsARefusedChoiceWithItsSentence', async () => {
        const w = await mountOpen(oneOff('AMBIGUOUS', { candidates: [{ upstreamRelease: UP_RELEASE_1, source: SOURCE, root: 'https://tea.a.example/tea' }] }))
        const err: any = new Error('x')
        err.errors = [{ message: 'The one-off has a run that has not ended.', extensions: { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'RUN_IN_PROGRESS' } }]
        mutate.mockRejectedValue(err)
        await w.find('[data-testid="upstream-choose-candidate"]').trigger('click')
        await flushPromises()
        expect(tid(w, 'upstream-one-off-action-error').text()).toBe('The one-off has a run that has not ended.')
        w.unmount()
    })

    it('showsAnIngestedOneOffWithTheLinkTheFrozenSentenceAndRefresh', async () => {
        const w = await mountOpen(oneOff('INGESTED', { release: ingestedRelease() }))
        const block = tid(w, 'upstream-one-off-ingested')
        expect(block.text()).toContain('Ingested.')
        expect(block.text()).toContain(oneOffFrozenSentence)
        expect(JSON.parse(tid(w, 'upstream-one-off-release-link').attributes('data-to'))).toEqual({ name: 'ReleaseView', params: { uuid: RELEASE } })
        expect(tid(w, 'upstream-one-off-refresh').exists()).toBe(true)

        mutate.mockResolvedValue({ data: { refreshUpstreamOneOff: oneOff('QUEUED', { release: ingestedRelease() }) } })
        await tid(w, 'upstream-one-off-refresh').trigger('click')
        await flushPromises()
        expect(mutationsOf(REFRESH_UPSTREAM_ONE_OFF)).toHaveLength(1)
        expect(mutationsOf(REFRESH_UPSTREAM_ONE_OFF)[0].variables).toEqual({ oneOff: ONE_OFF })
        w.unmount()
    })

    it('givesAReaderNoRefresh', async () => {
        const w = await mountOpen(oneOff('INGESTED', { release: ingestedRelease() }), false)
        expect(tid(w, 'upstream-one-off-ingested').exists()).toBe(true)
        expect(tid(w, 'upstream-one-off-refresh').exists()).toBe(false)
        w.unmount()
    })

    it('showsAnAdoptedOneOffWithTheLinkAndNoRefresh', async () => {
        const w = await mountOpen(oneOff('ADOPTED', { release: { ...ingestedRelease(), oneOff: null } }))
        expect(tid(w, 'upstream-one-off-adopted').text()).toContain('A follow has taken this release over. Its syncs keep it up to date.')
        expect(tid(w, 'upstream-one-off-release-link').exists()).toBe(true)
        expect(tid(w, 'upstream-one-off-refresh').exists()).toBe(false)
        expect(w.text()).not.toContain('Refresh')
        w.unmount()
    })

    it('triesAFailedOneOffWithAReleaseAgainByRefresh', async () => {
        const failed = oneOff('FAILED', { release: { ...ingestedRelease(), state: 'FAILED', message: 'The release could not be read.' },
            lastRun: { uuid: 'r1', state: 'FAILED', stopReason: null, message: null, finishedDate: null } })
        const w = await mountOpen(failed)
        expect(tid(w, 'upstream-one-off-failed-message').text()).toBe('The release could not be read.')
        mutate.mockResolvedValue({ data: { refreshUpstreamOneOff: oneOff('QUEUED') } })
        await tid(w, 'upstream-one-off-try-again').trigger('click')
        await flushPromises()
        expect(mutationsOf(REFRESH_UPSTREAM_ONE_OFF)).toHaveLength(1)
        w.unmount()
    })

    it('fillsTheFormWithTheTeiWhenAFailedOneOffHasNoRelease', async () => {
        const failed = oneOff('FAILED', { lastRun: { uuid: 'r1', state: 'FAILED', stopReason: 'SOURCE_FAILURE', message: 'Reading from the source failed.', finishedDate: null } })
        const w = await mountOpen(failed)
        expect(tid(w, 'upstream-one-off-failed-message').text()).toBe('Reading from the source failed.')
        await tid(w, 'upstream-one-off-try-again').trigger('click')
        await flushPromises()
        expect((tid(w, 'upstream-tei-input').find('input').element as HTMLInputElement).value).toBe(TEI)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })

    it('showsTheQueuedStateWithTheRun', async () => {
        const w = await mountOpen(oneOff('QUEUED', { lastRun: { uuid: 'r1', state: 'RUNNING', stopReason: null, message: null, finishedDate: null } }))
        expect(tid(w, 'upstream-one-off-queued').text()).toContain('The release is queued for ingestion.')
        expect(tid(w, 'upstream-one-off-run-state').text()).toBe('Run: Running')
        w.unmount()
    })

    it('ignoresAOneOffOfTheRouteThatIsNotAUuid', async () => {
        route.query = { tab: 'tei', oneOff: '../x' }
        answer([])
        const w = await mountPanel(true)
        expect(query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_ONE_OFF)).toHaveLength(0)
        expect(tid(w, 'upstream-one-off').exists()).toBe(false)
        w.unmount()
    })

    it('showsTheForbiddenSentenceWhenTheOpenOneOffIsRefused', async () => {
        route.query = { tab: 'tei', oneOff: ONE_OFF }
        answer([], refusal('Not authorized'))
        const w = await mountPanel(true)
        expect(tid(w, 'upstream-one-off-forbidden').text()).toBe(forbiddenSentence)
        w.unmount()
    })
})

describe('the list of one-offs', () => {
    it('readsTheListAtMountAndOpensARowByItsUuid', async () => {
        answer([oneOff('INGESTED', { source: SOURCE, release: ingestedRelease() })])
        const w = await mountPanel(false)
        expect(query.mock.calls[0][0].variables).toEqual({ org: ORG })
        const row = tid(w, 'upstream-one-off-row')
        expect(row.text()).toContain(TEI)
        expect(row.text()).toContain('Ingested')
        expect(row.text()).toContain('Acme TEA')
        expect(JSON.parse(row.find('[data-testid="upstream-one-off-row-release"]').attributes('data-to'))).toEqual({ name: 'ReleaseView', params: { uuid: RELEASE } })
        await row.trigger('click')
        expect(router.replace).toHaveBeenCalledWith({ query: { tab: 'tei', oneOff: ONE_OFF } })
        w.unmount()
    })

    it('showsTheEmptySentence', async () => {
        answer([])
        const w = await mountPanel(true)
        expect(tid(w, 'upstream-one-offs-empty').text()).toBe('No release has been ingested by TEI yet.')
        w.unmount()
    })

    it('showsTheForbiddenSentenceOnlyForThePlainNotAuthorized', async () => {
        answer(refusal('Not authorized'))
        let w = await mountPanel(false)
        expect(tid(w, 'upstream-one-offs-forbidden').text()).toBe(forbiddenSentence)
        w.unmount()
        answer(refusal('System is sealed. Please unseal the system first.'))
        w = await mountPanel(false)
        expect(tid(w, 'upstream-one-offs-error').text()).toBe('System is sealed. Please unseal the system first.')
        expect(w.text()).not.toContain(forbiddenSentence)
        w.unmount()
    })
})
