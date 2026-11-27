// @vitest-environment happy-dom
//
// T-1144 to T-1159 (TPF-243 design 3.2 to 3.9 and 3.11, threat model rows i to x): the source's list
// in the start of a follow. The dialog reads the chosen source first and asks for the list only when
// the source is ACTIVE with a probe that reached the server; it pages with Load more over rows kept
// in the dialog, filters the loaded rows without a request, looks an item up by an exact identifier,
// prepares a row through the existing preparation, shows a followed row with a link to its follow,
// shows failures and refusals in words, keeps one list call in flight and drops late answers, and
// renders the source's text as text only. Requests are told apart by their document.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NRadio, NSelect } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => router }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { PREPARE_UPSTREAM_FOLLOW } = await import('@/utils/upstreamFollowQueries')
const { GET_UPSTREAM_SOURCE, PROBE_UPSTREAM_SOURCE } = await import('@/utils/upstreamSourceQueries')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const labels = await import('@/utils/upstreamLabels')

const ORG = '11111111-2222-3333-4444-555555555555'
const S1 = 'aaaaaaaa-0000-4000-8000-000000000001'
const S2 = 'aaaaaaaa-0000-4000-8000-000000000002'
const ITEM = '3f2a6c1e-8b7d-4e5f-9a0b-1c2d3e4f5a6b'
const PREP = 'cccccccc-0000-4000-8000-000000000009'
const FOLLOW = 'eeeeeeee-0000-4000-8000-000000000001'
const SOURCES = [
    { uuid: S1, name: 'Acme TEA', status: 'ACTIVE' },
    { uuid: S2, name: 'Globex TEA', status: 'ACTIVE' },
]

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }, RouterLink } }

const U = (n: number) => `dddddddd-0000-4000-8000-${String(n).padStart(12, '0')}`

function item (n: number, over: Record<string, any> = {}): any {
    return { upstreamUuid: U(n), name: `Item ${n}`, identifiers: [], identifiersDropped: 0, activeFollow: null, ...over }
}

function items (count: number, from = 1, prefix = 'Item'): any[] {
    return Array.from({ length: count }, (_, i) => item(from + i, { name: `${prefix} ${from + i}` }))
}

function page (over: Record<string, any> = {}): any {
    return {
        source: S1, kind: 'PRODUCT', failure: null, sourceFailure: null, message: null, items: [], next: 'NONE', nextPageToken: null,
        rowsReceived: 0, rowsDropped: 0, cut: 'NONE', ...over,
    }
}

function sourceRow (uuid = S1, over: Record<string, any> = {}): any {
    return {
        uuid, org: ORG, kind: 'TEA', name: uuid === S1 ? 'Acme TEA' : 'Globex TEA', status: 'ACTIVE',
        credential: { kind: 'NONE', keyId: null, setDate: null },
        probe: { state: 'DONE', result: 'REACHABLE', message: 'The server answered as a TEA server and did not reject the credential.', requestedDate: null, finishedDate: null },
        ...over,
    }
}

const probeOf = (state: string, result: string | null, message: string | null = null) =>
    ({ probe: { state, result, message, requestedDate: null, finishedDate: null } })

function refusal (message: string, extensions: Record<string, any> | null = null): any {
    const err: any = new Error(message)
    err.graphQLErrors = [extensions ? { message, extensions } : { message }]
    return err
}

type Answer = any | Error | ((vars: any) => any)

/** Reads answered by document: the source read by a function of its variables or a sequence, the list likewise. */
function answer (a: { source?: Answer | Answer[], list?: Answer | Answer[] }) {
    const sources = Array.isArray(a.source) ? [...a.source] : null
    const lists = Array.isArray(a.list) ? [...a.list] : null
    query.mockImplementation(async (args: any) => {
        const pick = async (seq: Answer[] | null, single: Answer) => {
            const v = seq ? (seq.length > 1 ? seq.shift() : seq[0]) : single
            const out = typeof v === 'function' ? await v(args.variables) : v
            if (out instanceof Error) throw out
            return out
        }
        if (args.query === GET_UPSTREAM_SOURCE) {
            return { data: { upstreamSource: await pick(sources, a.source === undefined ? ((vars: any) => sourceRow(vars.uuid)) : a.source) } }
        }
        if (args.query === GET_UPSTREAM_OFFERED_ITEMS) return { data: { upstreamOfferedItems: await pick(lists, a.list) } }
        throw new Error('unexpected query')
    })
}

/** List calls the test resolves itself; tracks how many were unresolved at once. */
function deferredList () {
    const pending: Array<{ vars: any, resolve: (v: any) => void, reject: (e: any) => void }> = []
    let open = 0
    let maxOpen = 0
    const fn = (vars: any) => new Promise((resolve, reject) => {
        open += 1
        maxOpen = Math.max(maxOpen, open)
        pending.push({
            vars,
            resolve: (v: any) => { open -= 1; resolve(v) },
            reject: (e: any) => { open -= 1; reject(e) },
        })
    })
    return { fn, pending, maxOpen: () => maxOpen }
}

const listCalls = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_OFFERED_ITEMS).map(c => c[0])
const sourceReads = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCE).map(c => c[0])
const byId = (w: any, id: string) => w.find(`[data-testid="${id}"]`)
const allById = (w: any, id: string) => w.findAll(`[data-testid="${id}"]`)
const rowsOf = (w: any) => w.findAll('[data-testid="upstream-start-list"] tbody tr.n-data-table-tr')
    .filter((r: any) => !r.classes().includes('n-data-table-tr--expanded'))

const firstPageVars = (over: Record<string, any> = {}) => ({ source: S1, kind: 'PRODUCT', identifier: null, pageSize: 100, pageToken: null, ...over })

async function mountList (props: Record<string, any> = {}) {
    const w = mount(StartModal, { props: { show: true, orgUuid: ORG, sources: SOURCES, source: S1, ...props }, global, attachTo: document.body })
    await flushPromises()
    return w
}

async function click (w: any, id: string, index = 0) {
    await allById(w, id)[index].trigger('click')
    await flushPromises()
}

async function chooseKind (w: any, kind: string) {
    const radio = w.findAllComponents(NRadio).find((r: any) => r.props('value') === kind)!
    await radio.find('input').setValue(true)
    await flushPromises()
}

async function chooseSource (w: any, uuid: string) {
    w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === 'upstream-start-source')!.vm.$emit('update:value', uuid)
    await flushPromises()
}

async function chooseMode (w: any, mode: 'LIST' | 'UUID') {
    await byId(w, `upstream-start-mode-${mode}`).find('input').setValue(true)
    await flushPromises()
}

async function chooseLookupType (w: any, type: string) {
    w.findAllComponents(NSelect).find((s: any) => s.attributes('data-testid') === 'upstream-start-lookup-type')!.vm.$emit('update:value', type)
    await flushPromises()
}

async function advance (ms: number) {
    await vi.advanceTimersByTimeAsync(ms)
    await flushPromises()
}

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    router.push.mockReset()
    router.replace.mockReset()
})

afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
})

describe('T-1144: the first page of a ready source', () => {
    it('readsTheSourceThenOneFirstPageAndShowsTheRows', async () => {
        const long = 'v'.repeat(300)
        const five = [
            { idType: 'PURL', idValue: 'pkg:npm/acme-agent@1.0.0' }, { idType: 'CPE', idValue: long }, { idType: 'TEI', idValue: 'tei://uuid/acme' },
            { idType: 'COMPLIANCE_DOCUMENT', idValue: 'doc-4' }, { idType: 'PURL', idValue: 'pkg:npm/fifth@2' },
        ]
        answer({ list: page({ items: [
            item(1, { name: 'Acme Gateway', identifiers: [{ idType: 'PURL', idValue: 'pkg:npm/acme-gateway@1.0.0' }] }),
            item(2, { name: null }),
            item(3, { name: 'Acme Agent', identifiers: five, identifiersDropped: 2 }),
        ] }) })
        const w = await mountList()
        expect(query.mock.calls.map(c => c[0].query)).toEqual([GET_UPSTREAM_SOURCE, GET_UPSTREAM_OFFERED_ITEMS])
        expect(sourceReads()[0].variables).toEqual({ uuid: S1 })
        expect(listCalls()).toHaveLength(1)
        expect(listCalls()[0].variables).toEqual(firstPageVars())
        expect(listCalls()[0].fetchPolicy).toBe('no-cache')
        const rows = rowsOf(w)
        expect(rows).toHaveLength(3)
        expect(rows[0].text()).toContain('Acme Gateway')
        expect(rows[0].text()).toContain('pkg:npm/acme-gateway@1.0.0')
        expect(rows[1].text()).toContain(labels.offerNoName)
        for (const [i, r] of rows.entries()) expect(r.text()).toContain(U(i + 1))
        const third = rows[2].text()
        expect(third).toContain('PURL')
        expect(third).toContain('CPE')
        expect(third).toContain('TEI')
        expect(third).not.toContain('Compliance document')
        expect(third).toContain('and 2 more')
        expect(third).toContain('v'.repeat(100) + '...')
        expect(third).not.toContain(long)
        expect(rows[0].find('.n-data-table-expand-trigger').exists()).toBe(false)
        expect(rows[1].find('.n-data-table-expand-trigger').exists()).toBe(false)
        await rows[2].find('.n-data-table-expand-trigger').trigger('click')
        await flushPromises()
        const expanded = byId(w, 'upstream-start-row-expanded')
        for (const i of five) expect(expanded.text()).toContain(i.idValue)
        expect(expanded.text()).toContain('Compliance document')
        expect(byId(w, 'upstream-start-row-dropped').text()).toBe('2 identifiers of the source were not usable and are not shown.')
        expect(byId(w, 'upstream-start-mode').exists()).toBe(true)
        expect(byId(w, 'upstream-start-submit').exists()).toBe(false)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

describe('T-1145: the choice of a source and of a kind', () => {
    it('sendsNothingBeforeASourceIsChosenAndOneFirstPagePerChoice', async () => {
        answer({ list: (vars: any) => page({ kind: vars.kind, items: vars.kind === 'PRODUCT' ? items(2, 1, 'Product') : items(2, 10, 'Component') }) })
        const w = await mountList({ source: null })
        expect(byId(w, 'upstream-start-gate').text()).toBe(labels.offerChooseSource)
        expect(query).not.toHaveBeenCalled()
        await chooseSource(w, S1)
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls().map(c => c.variables)).toEqual([firstPageVars()])
        expect(w.text()).toContain('Product 1')
        await chooseKind(w, 'COMPONENT')
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls().map(c => c.variables)).toEqual([firstPageVars(), firstPageVars({ kind: 'COMPONENT' })])
        expect(w.text()).not.toContain('Product 1')
        expect(w.text()).toContain('Component 10')
        w.unmount()
    })
})

describe('T-1146: Load more', () => {
    it('sendsTheNextPageOnlyOnAClickAndAppends', async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
        answer({ list: [page({ items: items(100), next: 'TOKEN', nextPageToken: 'tok-1' }), page({ items: items(30, 101), next: 'NONE' })] })
        const w = await mountList()
        await advance(5 * 60 * 1000)
        expect(listCalls()).toHaveLength(1)
        const statement = () => byId(w, 'upstream-start-statement').text()
        expect(statement()).toContain('100 rows loaded.')
        expect(statement()).toContain(labels.offerMoreSentence)
        expect(rowsOf(w)).toHaveLength(25)
        await click(w, 'upstream-start-more')
        expect(listCalls()).toHaveLength(2)
        expect(listCalls()[1].variables).toEqual({ ...listCalls()[0].variables, pageToken: 'tok-1' })
        expect(statement()).toContain('130 rows loaded.')
        expect(statement()).toContain(labels.offerEndSentence)
        expect(byId(w, 'upstream-start-more').exists()).toBe(false)
        expect(rowsOf(w)[0].text()).toContain('Item 101')
        await advance(5 * 60 * 1000)
        expect(listCalls()).toHaveLength(2)
        w.unmount()
    })
})

describe('T-1147: the statement of a first page', () => {
    const cases: Array<[string, any, string[]]> = [
        ['TOKEN_UNUSABLE', page({ items: items(3), next: 'TOKEN_UNUSABLE' }), [labels.offerTokenUnusableSentence, labels.offerMissingHint]],
        ['MAX_ROWS', page({ items: items(1000), cut: 'MAX_ROWS', rowsReceived: 1500 }),
            ['The source answered 1500 rows in one page, more than ReARM reads. Rows are missing.', labels.offerMissingHint]],
        ['OTHER', page({ items: items(3), cut: 'OTHER' }), [labels.offerCutSentence, labels.offerMissingHint]],
        ['rowsDropped 2', page({ items: items(3), rowsDropped: 2 }), ['2 rows of the source were not usable and are not shown.', labels.offerMissingHint]],
        ['an empty page with a dropped row', page({ rowsDropped: 1 }), ['1 row of the source was not usable and is not shown.', labels.offerMissingHint]],
    ]

    it.each(cases)('%s shows its line and the hint', async (name, answered, lines) => {
        answer({ list: answered })
        const w = await mountList()
        const statement = byId(w, 'upstream-start-statement').text()
        for (const line of lines) expect(statement).toContain(line)
        expect(byId(w, 'upstream-start-more').exists()).toBe(false)
        if (statement.includes(labels.offerEndSentence)) expect(allById(w, 'upstream-start-warning').length).toBeGreaterThan(0)
        if (name === 'MAX_ROWS') expect(rowsOf(w)).toHaveLength(25)
        if (name === 'an empty page with a dropped row') {
            expect(w.text()).not.toContain(labels.offerEmptySentence.PRODUCT)
            expect(statement).toContain('0 rows loaded.')
        }
        w.unmount()
    })

    it('showsTheEmptySentenceAndHintForTheCleanEmptyPage', async () => {
        answer({ list: page() })
        const w = await mountList()
        const empty = byId(w, 'upstream-start-empty').text()
        expect(empty).toContain(labels.offerEmptySentence.PRODUCT)
        expect(empty).toContain(labels.offerEmptyHint)
        expect(w.text()).not.toContain(labels.offerEndSentence)
        expect(byId(w, 'upstream-start-list').exists()).toBe(false)
        w.unmount()
    })
})

describe('T-1148: the filter box', () => {
    it('filtersTheLoadedRowsAndSendsNothing', async () => {
        const forty = items(40).map((r, i) => i < 3 ? { ...r, name: `Gateway ${i}` } : r)
        answer({ list: page({ items: forty, next: 'TOKEN', nextPageToken: 'tok-1' }) })
        const w = await mountList()
        const sentence = 'Filters the rows loaded so far (40), by name, uuid and identifier. It does not search the source.'
        expect(byId(w, 'upstream-start-filter-sentence').text()).toBe(sentence)
        expect(byId(w, 'upstream-start-filter-count').exists()).toBe(false)
        const before = query.mock.calls.length
        const input = byId(w, 'upstream-start-filter').find('input')
        for (const typed of ['g', 'ga', 'gat', 'gate']) {
            await input.setValue(typed)
            await flushPromises()
            expect(byId(w, 'upstream-start-filter-sentence').text()).toBe(sentence)
        }
        expect(byId(w, 'upstream-start-filter-count').text()).toBe('Matching: 3 of 40 loaded rows.')
        expect(rowsOf(w)).toHaveLength(3)
        for (const typed of ['z', 'zz', 'zzz']) {
            await input.setValue(typed)
            await flushPromises()
        }
        expect(byId(w, 'upstream-start-filter-count').text()).toBe('Matching: 0 of 40 loaded rows.')
        expect(rowsOf(w)).toHaveLength(0)
        expect(byId(w, 'upstream-start-statement').text()).toContain(labels.offerMoreSentence)
        expect(query.mock.calls.length).toBe(before)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

describe('T-1149: the lookup by identifier', () => {
    it('validatesSendsOnEnterOnlyAndReturnsToTheList', async () => {
        answer({
            list: (vars: any) => {
                if (!vars.identifier) return page({ items: items(5), next: 'TOKEN', nextPageToken: 'tok-1' })
                return vars.identifier.idValue === 'pkg:npm/acme@1.0.0' ? page({ items: [item(77, { name: 'Looked up' })] }) : page()
            },
        })
        const w = await mountList()
        await byId(w, 'upstream-start-filter').find('input').setValue('Item')
        await flushPromises()
        const listed = listCalls().length
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').text()).toBe(labels.offerLookupTypeProblem)
        await chooseLookupType(w, 'PURL')
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').text()).toBe(labels.identifierValueProblem)
        const value = byId(w, 'upstream-start-lookup-value').find('input')
        await value.setValue('pkg:npm/' + String.fromCharCode(0xe9))
        await click(w, 'upstream-start-lookup-submit')
        expect(byId(w, 'upstream-start-lookup-problem').text()).toBe(labels.identifierValueProblem)
        expect(listCalls()).toHaveLength(listed)
        for (const typed of ['  p', '  pkg:npm/', '  pkg:npm/acme@1.0.0  ']) {
            await value.setValue(typed)
            await flushPromises()
        }
        expect(listCalls()).toHaveLength(listed)
        await value.trigger('keyup', { key: 'Enter' })
        await flushPromises()
        expect(listCalls()).toHaveLength(listed + 1)
        expect(listCalls().at(-1)!.variables).toEqual(firstPageVars({ identifier: { idType: 'PURL', idValue: 'pkg:npm/acme@1.0.0' } }))
        expect(byId(w, 'upstream-start-lookup-problem').exists()).toBe(false)
        expect(w.text()).toContain(labels.offerLookupHead)
        expect(rowsOf(w)).toHaveLength(1)
        expect(rowsOf(w)[0].text()).toContain('Looked up')
        expect(byId(w, 'upstream-start-filter').exists()).toBe(false)
        await value.setValue('pkg:npm/absent@9')
        await click(w, 'upstream-start-lookup-submit')
        expect(listCalls()).toHaveLength(listed + 2)
        const none = byId(w, 'upstream-start-empty').text()
        expect(none).toContain(labels.offerLookupNoneSentence.PRODUCT)
        expect(none).toContain(labels.offerLookupExactHint)
        const sent = query.mock.calls.length
        await click(w, 'upstream-start-lookup-back')
        expect(query.mock.calls.length).toBe(sent)
        expect(rowsOf(w)).toHaveLength(5)
        expect((byId(w, 'upstream-start-filter').find('input').element as HTMLInputElement).value).toBe('Item')
        expect(byId(w, 'upstream-start-statement').text()).toContain(labels.offerMoreSentence)
        w.unmount()
    })
})

describe('T-1150: one list call in flight, late answers dropped', () => {
    it('waitsForTheCallInFlightAndSendsOnlyTheLatestWantedOne', async () => {
        const d = deferredList()
        answer({ list: d.fn })
        const w = await mountList()
        expect(listCalls()).toHaveLength(1)
        await chooseKind(w, 'COMPONENT')
        await chooseKind(w, 'PRODUCT')
        await chooseSource(w, S2)
        expect(listCalls()).toHaveLength(1)
        expect(sourceReads().map(c => c.variables.uuid)).toEqual([S1, S2])
        expect(byId(w, 'upstream-start-waiting').text()).toContain(labels.offerWaiting)
        d.pending[0].resolve(page({ items: items(3, 1, 'From S1'), next: 'TOKEN', nextPageToken: 'tok-S1' }))
        await flushPromises()
        expect(w.text()).not.toContain('From S1')
        expect(listCalls()).toHaveLength(2)
        expect(listCalls()[1].variables).toEqual(firstPageVars({ source: S2 }))
        d.pending[1].resolve(page({ source: S2, items: items(2, 1, 'From S2') }))
        await flushPromises()
        expect(w.text()).toContain('From S2')
        expect(d.maxOpen()).toBe(1)
        expect(listCalls().some(c => c.variables.pageToken === 'tok-S1')).toBe(false)
        w.unmount()
    })
})

describe('T-1151: Prepare on a row', () => {
    async function loaded () {
        answer({ list: page({ kind: 'COMPONENT', items: items(3) }) })
        return mountList({ kind: 'COMPONENT' })
    }

    it('preparesTheRowWithTheSetsSourceAndKindAndOpensTheConfirmation', async () => {
        mutate.mockResolvedValueOnce({ data: { prepareUpstreamFollow: { uuid: PREP, state: 'PENDING' } } })
        const w = await loaded()
        await click(w, 'upstream-start-row-prepare', 1)
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(PREPARE_UPSTREAM_FOLLOW)
        expect(mutate.mock.calls[0][0].variables).toEqual({ source: S1, kind: 'COMPONENT', upstreamUuid: U(2) })
        expect(router.push).toHaveBeenCalledWith({ name: 'UpstreamFollowPreparation', params: { orguuid: ORG, uuid: PREP } })
        expect(w.emitted('update:show')?.at(-1)).toEqual([false])
        w.unmount()
    })

    it('sendsOnePreparationAtATime', async () => {
        mutate.mockImplementationOnce(() => new Promise(() => {}))
        const w = await loaded()
        await click(w, 'upstream-start-row-prepare', 0)
        expect(allById(w, 'upstream-start-row-prepare').every((b: any) => b.attributes('disabled') !== undefined)).toBe(true)
        await click(w, 'upstream-start-row-prepare', 1)
        expect(mutate).toHaveBeenCalledTimes(1)
        w.unmount()
    })

    it('showsARefusalAndKeepsTheRows', async () => {
        mutate.mockRejectedValueOnce(refusal('A preparation limit of the organization is reached. Prepare again later.',
            { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'PREPARATION_LIMIT' }))
        const w = await loaded()
        await click(w, 'upstream-start-row-prepare', 0)
        expect(byId(w, 'upstream-start-error').text()).toBe('A preparation limit of the organization is reached. Prepare again later.')
        expect(rowsOf(w)).toHaveLength(3)
        expect(router.push).not.toHaveBeenCalled()
        expect(w.emitted('update:show')).toBeUndefined()
        for (const c of mutate.mock.calls) expect(c[0].mutation).toBe(PREPARE_UPSTREAM_FOLLOW)
        w.unmount()
    })
})

describe('T-1152: a row that is already followed', () => {
    it('showsFollowedWithALinkFromActiveFollowOnlyAndNoPrepare', async () => {
        answer({ list: page({ items: [item(1, { activeFollow: FOLLOW }), item(2, { activeFollow: 'x"><a' }), item(3)] }) })
        const w = await mountList()
        const [one, two, three] = rowsOf(w)
        expect(one.find('[data-testid="upstream-start-row-followed"]').text()).toBe('Followed')
        expect(JSON.parse(one.find('[data-testid="upstream-start-row-follow-link"]').attributes('data-to')!))
            .toEqual({ name: 'UpstreamFollow', params: { orguuid: ORG, uuid: FOLLOW } })
        expect(one.find('[data-testid="upstream-start-row-follow-link"]').text()).toBe('Open the follow')
        expect(one.find('[data-testid="upstream-start-row-prepare"]').exists()).toBe(false)
        expect(two.find('[data-testid="upstream-start-row-followed"]').text()).toBe('Followed')
        expect(two.find('[data-testid="upstream-start-row-follow-link"]').exists()).toBe(false)
        expect(two.find('[data-testid="upstream-start-row-prepare"]').exists()).toBe(false)
        expect(three.find('[data-testid="upstream-start-row-prepare"]').text()).toBe('Prepare')
        expect(three.find('[data-testid="upstream-start-row-followed"]').exists()).toBe(false)
        expect(query).toHaveBeenCalledTimes(2)
        expect(mutate).not.toHaveBeenCalled()
        w.unmount()
    })
})

describe('T-1153: a first page served with a failure', () => {
    const served: Array<[string, string]> = [
        ['TOKEN_NOT_ACCEPTED', 'The server did not accept the page token. Start again from the first page.'],
        ['FILTER_REFUSED', 'The server refused the identifier lookup.'],
        ['LIST_NOT_SUPPORTED', 'The server does not offer this list.'],
        ['ANSWER_INVALID', 'The server\'s answer is not a TEA list.'],
        ['ANSWER_TOO_LARGE', 'The server\'s answer is larger than ReARM reads. Look the item up by an identifier.'],
        ['CREDENTIAL_UNREADABLE', 'The stored credential can no longer be read. Enter it again.'],
    ]

    async function failedWith (failed: any, source: any = sourceRow()) {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
        answer({ source, list: page({ items: null, ...failed }) })
        const w = await mountList()
        await advance(5 * 60 * 1000)
        expect(listCalls()).toHaveLength(1)
        expect(byId(w, 'upstream-start-list').exists()).toBe(false)
        return w
    }

    async function retriesOnce (w: any) {
        await click(w, 'upstream-start-retry')
        expect(listCalls()).toHaveLength(2)
        expect(listCalls()[1].variables).toEqual(listCalls()[0].variables)
    }

    it.each(served)('%s shows the served sentence', async (failure, message) => {
        const w = await failedWith({ failure, message })
        expect(byId(w, 'upstream-start-list-failure').find('.n-alert').text()).toBe(message)
        await retriesOnce(w)
        w.unmount()
    })

    it('showsTheTagTheSentenceAndTheNetworkHintForATimeout', async () => {
        const w = await failedWith({ failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'The server did not answer in time.' })
        const failure = byId(w, 'upstream-start-list-failure')
        expect(failure.find('[data-testid="upstream-probe-tag"]').text()).toBe('Timed out')
        expect(failure.find('[data-testid="upstream-probe-message"]').text()).toBe('The server did not answer in time.')
        expect(failure.text()).toContain(labels.probeTimeoutHint)
        await retriesOnce(w)
        w.unmount()
    })

    it('showsTheApiKeyHintForAFailedAuthenticationOfASourceWithAKey', async () => {
        const w = await failedWith({ failure: 'SOURCE_FAILURE', sourceFailure: 'AUTHENTICATION_FAILED',
            message: 'The server refused the request: it asks for a credential or rejects the stored one.' },
        sourceRow(S1, { credential: { kind: 'API_KEY', keyId: 'k', setDate: null } }))
        expect(byId(w, 'upstream-start-list-failure').text()).toContain(labels.probeApiKeyHint)
        w.unmount()
    })

    it.each([
        ['FETCHER_BUSY', 'The fetcher was busy. Probe again later.', labels.offerFetcherBusy],
        ['FAILED', 'The probe failed.', labels.offerSourceFailed],
    ])('%s shows the words of the UI and not the word probe', async (sourceFailure, message, shown) => {
        const w = await failedWith({ failure: 'SOURCE_FAILURE', sourceFailure, message })
        const failure = byId(w, 'upstream-start-list-failure')
        expect(failure.find('[data-testid="upstream-probe-message"]').text()).toBe(shown)
        expect(failure.text().toLowerCase()).not.toContain('probe')
        w.unmount()
    })

    it('showsTheServedSentenceOfAFetcherWithoutTheListVerbs', async () => {
        const message = 'Fetching from TEA servers is not available on this installation at the moment.'
        const w = await failedWith({ failure: 'SOURCE_FAILURE', sourceFailure: 'FETCHER_NOT_AVAILABLE', message })
        expect(byId(w, 'upstream-start-list-failure').find('[data-testid="upstream-probe-message"]').text()).toBe(message)
        w.unmount()
    })

    it('showsALabelAndNoRawValueForANullMessage', async () => {
        const w = await failedWith({ failure: 'LIST_NOT_SUPPORTED', message: null })
        const text = byId(w, 'upstream-start-list-failure').text()
        expect(text).toContain('The server does not offer this list.')
        expect(text).not.toContain('LIST_NOT_SUPPORTED')
        w.unmount()
    })
})

describe('T-1154: a first page refused', () => {
    async function refusedWith (err: any) {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
        answer({ list: err })
        const w = await mountList()
        await advance(5 * 60 * 1000)
        expect(listCalls()).toHaveLength(1)
        return byId(w, 'upstream-start-list-failure')
    }

    it.each([
        ['LIST_LIMIT', 'The limit of list requests of the organization is reached. Try again in a minute.'],
        ['SOURCE_ARCHIVED', 'The upstream source is archived.'],
    ])('%s shows the served sentence as it is', async (reason, message) => {
        const failure = await refusedWith(refusal(message, { code: 'UPSTREAM_FOLLOW_REFUSED', reason }))
        expect(failure.find('.n-alert').text()).toBe(message)
    })

    it('showsTheForbiddenSentenceForNotAuthorizedWithoutACode', async () => {
        const failure = await refusedWith(refusal('Not authorized'))
        expect(failure.find('.n-alert').text()).toBe(labels.offerForbiddenSentence)
    })

    it('showsTheNetworkSentenceForAnErrorWithoutGraphqlEntries', async () => {
        const failure = await refusedWith(new Error('fetch failed: ECONNRESET'))
        expect(failure.find('.n-alert').text()).toBe(labels.networkSentence)
    })

    it('showsOnlyTheMessageOfAnErrorWithAStackAndAPath', async () => {
        const err: any = new Error('x')
        err.graphQLErrors = [{ message: 'Something went wrong.', path: ['secretPathSegment'], locations: [{ line: 7, column: 3 }],
            extensions: { classification: 'INTERNAL_ERROR', stacktrace: ['at io.example.SecretFrame.call(SecretFrame.java:42)'] } }]
        const failure = await refusedWith(err)
        expect(failure.find('.n-alert').text()).toBe('Something went wrong.')
        const html = document.body.innerHTML
        expect(html).not.toContain('SecretFrame')
        expect(html).not.toContain('secretPathSegment')
        expect(html).not.toContain('INTERNAL_ERROR')
    })
})

describe('T-1155: a later page that fails', () => {
    async function loadMoreWith (later: any) {
        answer({ list: [page({ items: items(3), next: 'TOKEN', nextPageToken: 'tok-1' }), later, page({ items: items(2, 50, 'Fresh') })] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(listCalls()).toHaveLength(2)
        expect(rowsOf(w)).toHaveLength(3)
        expect(allById(w, 'upstream-start-row-prepare')).toHaveLength(3)
        return w
    }

    it.each([
        ['TOKEN_NOT_ACCEPTED', page({ items: null, failure: 'TOKEN_NOT_ACCEPTED', message: 'The server did not accept the page token. Start again from the first page.' }),
            'The server did not accept the page token. Start again from the first page.'],
        ['INVALID_PAGE_TOKEN', refusal('The page token is not one this list returned. Start again from the first page.',
            { code: 'UPSTREAM_FOLLOW_REFUSED', reason: 'INVALID_PAGE_TOKEN' }),
        'The page token is not one this list returned. Start again from the first page.'],
    ])('%s keeps the rows and offers to start again', async (_name, later, sentence) => {
        const w = await loadMoreWith(later)
        expect(byId(w, 'upstream-start-list-failure').text()).toContain(sentence)
        expect(byId(w, 'upstream-start-more').exists()).toBe(false)
        expect(byId(w, 'upstream-start-restart').text()).toBe('Start again from the first page')
        await click(w, 'upstream-start-restart')
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables).toEqual(firstPageVars())
        expect(rowsOf(w).map((r: any) => r.text().includes('Fresh'))).toEqual([true, true])
        w.unmount()
    })

    it('keepsTheTokenAndLoadMoreAfterASourceFailure', async () => {
        const w = await loadMoreWith(page({ items: null, failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'The server did not answer in time.' }))
        expect(byId(w, 'upstream-start-list-failure').text()).toContain('The server did not answer in time.')
        expect(byId(w, 'upstream-start-restart').exists()).toBe(false)
        await click(w, 'upstream-start-more')
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables).toEqual(firstPageVars({ pageToken: 'tok-1' }))
        w.unmount()
    })
})

describe('T-1156: the gate before any list call', () => {
    beforeEach(() => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    })

    it('pollsAPendingProbeAndListsOnceWhenItReachesTheServer', async () => {
        answer({ source: [sourceRow(S1, probeOf('PENDING', null)), sourceRow(S1, probeOf('PENDING', null)), sourceRow()], list: page({ items: items(2) }) })
        const w = await mountList()
        const gate = byId(w, 'upstream-start-gate').text()
        expect(gate).toContain('Probe in progress')
        expect(gate).toContain(labels.offerProbePending)
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls()).toHaveLength(0)
        await advance(1999)
        expect(sourceReads()).toHaveLength(1)
        await advance(1)
        expect(sourceReads()).toHaveLength(2)
        expect(listCalls()).toHaveLength(0)
        await advance(2000)
        expect(sourceReads()).toHaveLength(3)
        expect(listCalls()).toHaveLength(1)
        await advance(60000)
        expect(sourceReads()).toHaveLength(3)
        expect(listCalls()).toHaveLength(1)
        expect(rowsOf(w)).toHaveLength(2)
        w.unmount()
    })

    it('showsAFailedProbeListsNothingAndProbesAgainOnAClick', async () => {
        answer({ source: sourceRow(S1, probeOf('DONE', 'TIMED_OUT', 'The server did not answer in time.')), list: page() })
        mutate.mockResolvedValueOnce({ data: { probeUpstreamSource: sourceRow(S1, probeOf('PENDING', null)) } })
        const w = await mountList()
        const gate = byId(w, 'upstream-start-gate')
        expect(gate.find('[data-testid="upstream-probe-tag"]').text()).toBe('Timed out')
        expect(gate.text()).toContain('The server did not answer in time.')
        expect(gate.text()).toContain(labels.probeTimeoutHint)
        expect(gate.text()).toContain(labels.offerProbeFailed)
        await advance(10 * 60 * 1000)
        expect(listCalls()).toHaveLength(0)
        expect(sourceReads()).toHaveLength(1)
        expect(byId(w, 'upstream-start-probe').text()).toBe('Probe again')
        await click(w, 'upstream-start-probe')
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].mutation).toBe(PROBE_UPSTREAM_SOURCE)
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: S1 })
        expect(byId(w, 'upstream-start-gate').text()).toContain(labels.offerProbePending)
        await advance(2000)
        expect(sourceReads()).toHaveLength(2)
        w.unmount()
    })

    it.each([
        ['never probed', sourceRow(S1, { probe: null }), labels.offerNeverProbed],
        ['archived', sourceRow(S1, { status: 'ARCHIVED' }), labels.offerSourceArchived],
        ['not authorized', refusal('Not authorized'), labels.offerForbiddenSentence],
    ])('%s shows its sentence and lists nothing', async (name, source, sentence) => {
        answer({ source, list: page() })
        const w = await mountList()
        expect(byId(w, 'upstream-start-gate').text()).toContain(sentence)
        if (name === 'never probed') expect(byId(w, 'upstream-start-probe').text()).toBe('Probe')
        else expect(byId(w, 'upstream-start-probe').exists()).toBe(false)
        await advance(10 * 60 * 1000)
        expect(listCalls()).toHaveLength(0)
        expect(sourceReads()).toHaveLength(1)
        w.unmount()
    })

    it('stopsPollingWhenTheDialogCloses', async () => {
        answer({ source: sourceRow(S1, probeOf('PENDING', null)), list: page() })
        const w = await mountList()
        await advance(2000)
        expect(sourceReads()).toHaveLength(2)
        await w.setProps({ show: false })
        await advance(60000)
        expect(sourceReads()).toHaveLength(2)
        expect(listCalls()).toHaveLength(0)
        w.unmount()
    })
})

describe('T-1157: the dialog as the marker opens it', () => {
    it('opensByUuidWithNoRequestAndListsOnAClick', async () => {
        answer({ list: page({ items: items(2) }) })
        const w = await mountList({ sources: [SOURCES[0]], source: S1, kind: 'PRODUCT', upstreamUuid: ITEM })
        expect((byId(w, 'upstream-start-uuid').find('input').element as HTMLInputElement).value).toBe(ITEM)
        expect(byId(w, 'upstream-start-list-pane').exists()).toBe(false)
        expect(query).not.toHaveBeenCalled()
        await chooseMode(w, 'LIST')
        expect(sourceReads()).toHaveLength(1)
        expect(listCalls()).toHaveLength(1)
        expect(rowsOf(w)).toHaveLength(2)
        expect(byId(w, 'upstream-start-submit').exists()).toBe(false)
        await chooseMode(w, 'UUID')
        await chooseMode(w, 'LIST')
        expect(query).toHaveBeenCalledTimes(2)
        w.unmount()
    })

    it('readsTheSourceAgainWhenItsAnswerCameWhileByUuidWasOpen', async () => {
        let release: (v: any) => void = () => {}
        let reads = 0
        answer({
            source: () => { reads += 1; return reads === 1 ? new Promise(resolve => { release = resolve }) : sourceRow() },
            list: page({ items: items(1) }),
        })
        const w = await mountList()
        await chooseMode(w, 'UUID')
        release(sourceRow())
        await flushPromises()
        expect(listCalls()).toHaveLength(0)
        await chooseMode(w, 'LIST')
        expect(sourceReads()).toHaveLength(2)
        expect(listCalls()).toHaveLength(1)
        expect(rowsOf(w)).toHaveLength(1)
        w.unmount()
    })

    it('sendsNothingByUuidWhenTheSourceOrKindChanges', async () => {
        answer({ list: page() })
        const w = await mountList({ mode: 'UUID' })
        await chooseSource(w, S2)
        await chooseKind(w, 'COMPONENT')
        expect(query).not.toHaveBeenCalled()
        w.unmount()
    })
})

describe('T-1158: the text of a source as text', () => {
    it('rendersHostileTextAsTextNodesAndInNoAttribute', async () => {
        const longName = 'N'.repeat(512)
        const longValue = 'V'.repeat(2048)
        const hostile = ['<img src=x onerror=alert(1)>', '<script>alert(1)</script>', 'javascript:alert(1)', 'https://evil.example/x', '{{constructor}}']
        answer({ list: page({ items: [
            item(1, { name: hostile[0], identifiers: [{ idType: 'PURL', idValue: hostile[1] }, { idType: 'CPE', idValue: hostile[2] }] }),
            item(2, { name: hostile[3], identifiers: [{ idType: 'TEI', idValue: hostile[4] }] }),
            item(3, { name: longName, identifiers: [{ idType: 'PURL', idValue: longValue }] }),
        ] }) })
        const w = await mountList()
        const third = rowsOf(w)[2]
        expect(third.text()).toContain('N'.repeat(120) + '...')
        expect(third.text()).not.toContain(longName)
        expect(third.text()).toContain('V'.repeat(100) + '...')
        await third.find('.n-data-table-expand-trigger').trigger('click')
        await flushPromises()
        const expanded = byId(w, 'upstream-start-row-expanded').text()
        expect(expanded).toContain(longName)
        expect(expanded).toContain(longValue)
        const root = byId(w, 'upstream-follow-start').element as HTMLElement
        for (const text of hostile) expect(root.textContent, text).toContain(text)
        expect(root.querySelectorAll('img, script, iframe').length).toBe(0)
        expect([...root.querySelectorAll('a')].every(a => a.classList.contains('rl'))).toBe(true)
        const texts = [...hostile, longName, longValue, 'onerror', 'evil.example']
        for (const el of [root, ...root.querySelectorAll('*')]) {
            for (const attr of [...el.attributes]) {
                for (const text of texts) expect(attr.value.includes(text), `${el.tagName} ${attr.name}`).toBe(false)
            }
        }
        w.unmount()
    })
})

describe('T-1159: closing and opening again', () => {
    it('dropsEverythingOfTheEarlierOpen', async () => {
        let round = 0
        answer({
            source: () => { round += 1; return sourceRow() },
            list: (vars: any) => vars.identifier ? page({ items: [item(9, { name: 'Looked up' })] }) : page({ items: items(3, 1, round === 1 ? 'Before' : 'After') }),
        })
        const w = await mountList()
        await byId(w, 'upstream-start-filter').find('input').setValue('Before')
        await chooseLookupType(w, 'TEI')
        await byId(w, 'upstream-start-lookup-value').find('input').setValue('tei://uuid/x')
        await click(w, 'upstream-start-lookup-submit')
        expect(w.text()).toContain('Looked up')
        await w.setProps({ show: false })
        await flushPromises()
        await w.setProps({ show: true })
        await flushPromises()
        expect(sourceReads()).toHaveLength(2)
        expect(listCalls()).toHaveLength(3)
        expect(listCalls()[2].variables).toEqual(firstPageVars())
        expect(w.text()).not.toContain('Before')
        expect(w.text()).not.toContain('Looked up')
        expect(w.text()).toContain('After 1')
        expect((byId(w, 'upstream-start-filter').find('input').element as HTMLInputElement).value).toBe('')
        expect((byId(w, 'upstream-start-lookup-value').find('input').element as HTMLInputElement).value).toBe('')
        w.unmount()
    })

    it('dropsTheAnswerOfAnEarlierOpenAndNeverHasTwoCallsInFlight', async () => {
        const d = deferredList()
        answer({ list: d.fn })
        const w = await mountList()
        expect(listCalls()).toHaveLength(1)
        await w.setProps({ show: false })
        await flushPromises()
        await w.setProps({ show: true })
        await flushPromises()
        expect(sourceReads()).toHaveLength(2)
        expect(listCalls()).toHaveLength(1)
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(true)
        d.pending[0].resolve(page({ items: items(2, 1, 'Old answer') }))
        await flushPromises()
        expect(w.text()).not.toContain('Old answer')
        expect(listCalls()).toHaveLength(2)
        d.pending[1].resolve(page({ items: items(2, 1, 'New answer') }))
        await flushPromises()
        expect(w.text()).toContain('New answer')
        expect(d.maxOpen()).toBe(1)
        w.unmount()
    })

    it('sendsNothingAfterACloseWithACallWanted', async () => {
        const d = deferredList()
        answer({ list: d.fn })
        const w = await mountList()
        await chooseKind(w, 'COMPONENT')
        expect(byId(w, 'upstream-start-waiting').exists()).toBe(true)
        await w.setProps({ show: false })
        await flushPromises()
        d.pending[0].resolve(page({ items: items(1) }))
        await flushPromises()
        expect(listCalls()).toHaveLength(1)
        w.unmount()
    })
})
