// Helpers shared by the specs of the source's list in the start of a follow (TPF-264, mutation
// round): the rows and answers of the backend, the mount of the dialog and the clicks. A spec
// mocks the graphql client and the router itself (vi.mock is hoisted per file) and builds the kit
// from its mocks:  const kit = makeKit(query, mutate, StartModal, LIST_DOC, LIST_FIELD)
// The list document and the name of its field are given by the spec: the feature files are scanned
// for them (the single sender of the source list), and this file is one of the feature files.
import { expect, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NRadio, NSelect } from 'naive-ui'
import { GET_UPSTREAM_SOURCE } from '@/utils/upstreamSourceQueries'

export const ORG = '11111111-2222-3333-4444-555555555555'
export const S1 = 'aaaaaaaa-0000-4000-8000-000000000001'
export const S2 = 'aaaaaaaa-0000-4000-8000-000000000002'
export const ITEM = '3f2a6c1e-8b7d-4e5f-9a0b-1c2d3e4f5a6b'
export const PREP = 'cccccccc-0000-4000-8000-000000000009'
export const FOLLOW = 'eeeeeeee-0000-4000-8000-000000000001'
export const SOURCES = [
    { uuid: S1, name: 'Acme TEA', status: 'ACTIVE' },
    { uuid: S2, name: 'Globex TEA', status: 'ACTIVE' },
]

export const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot/></a>' }
export const stubs = { Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }, RouterLink }

export const U = (n: number) => `dddddddd-0000-4000-8000-${String(n).padStart(12, '0')}`

export function item (n: number, over: Record<string, any> = {}): any {
    return { upstreamUuid: U(n), name: `Item ${n}`, identifiers: [], identifiersDropped: 0, activeFollow: null, ...over }
}

export function items (count: number, from = 1, prefix = 'Item'): any[] {
    return Array.from({ length: count }, (_, i) => item(from + i, { name: `${prefix} ${from + i}` }))
}

export function page (over: Record<string, any> = {}): any {
    return {
        source: S1, kind: 'PRODUCT', failure: null, sourceFailure: null, message: null, items: [], next: 'NONE', nextPageToken: null,
        rowsReceived: 0, rowsDropped: 0, cut: 'NONE', ...over,
    }
}

export function sourceRow (uuid = S1, over: Record<string, any> = {}): any {
    return {
        uuid, org: ORG, kind: 'TEA', name: uuid === S1 ? 'Acme TEA' : 'Globex TEA', status: 'ACTIVE',
        credential: { kind: 'NONE', keyId: null, setDate: null },
        probe: { state: 'DONE', result: 'REACHABLE', message: 'The server answered as a TEA server and did not reject the credential.', requestedDate: null, finishedDate: null },
        ...over,
    }
}

export const probeOf = (state: string, result: string | null, message: string | null = null) =>
    ({ probe: { state, result, message, requestedDate: null, finishedDate: null } })

export function refusal (message: string, extensions: Record<string, any> | null = null): any {
    const err: any = new Error(message)
    err.graphQLErrors = [extensions ? { message, extensions } : { message }]
    return err
}

export const firstPageVars = (over: Record<string, any> = {}) => ({ source: S1, kind: 'PRODUCT', identifier: null, pageSize: 100, pageToken: null, ...over })

export type Answer = any | Error | ((vars: any) => any)

/**
 * A wrapper of an element that may be missing: using a missing one fails by an assertion that names
 * the element, not by the error of the wrapper; exists() still answers, and so does find() on a
 * wrapper that exists (its answer is such a wrapper again).
 */
export function present (el: any, what: string): any {
    return new Proxy(el, {
        get (target, prop) {
            if (prop === 'exists') return target.exists.bind(target)
            if (typeof prop === 'symbol' || prop === 'then') return undefined
            if (!target.exists()) return () => expect.fail(`no element ${what} (used by ${String(prop)})`)
            const v = target[prop]
            if (typeof v !== 'function') return v
            if (prop === 'find') return (...a: any[]) => present(v.apply(target, a), `${what} > ${String(a[0])}`)
            return v.bind(target)
        },
    })
}

export function makeKit (query: ReturnType<typeof vi.fn>, mutate: ReturnType<typeof vi.fn>, StartModal: any, listDoc: any, listField: string) {
    /** Reads answered by document: the source read and the list, each one answer, a sequence or a function of the variables. */
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
            if (args.query === listDoc) return { data: { [listField]: await pick(lists, a.list) } }
            throw new Error('unexpected query')
        })
    }

    /** Calls the test resolves itself; tracks how many were unresolved at once. */
    function deferred () {
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

    const listCalls = () => query.mock.calls.filter(c => c[0].query === listDoc).map(c => c[0])
    const sourceReads = () => query.mock.calls.filter(c => c[0].query === GET_UPSTREAM_SOURCE).map(c => c[0])
    const byId = (w: any, id: string) => present(w.find(`[data-testid="${id}"]`), id)
    const allById = (w: any, id: string) => w.findAll(`[data-testid="${id}"]`)
    const rowsOf = (w: any) => w.findAll('[data-testid="upstream-start-list"] tbody tr.n-data-table-tr')
        .filter((r: any) => !r.classes().includes('n-data-table-tr--expanded'))
    const texts = (w: any, id: string) => allById(w, id).map((e: any) => e.text())

    /** errors: when given, the errors of a render or a handler of the dialog are collected there, not thrown. */
    async function mountList (props: Record<string, any> = {}, errors?: any[]) {
        const config = errors ? { errorHandler: (e: any) => { errors.push(e) } } : {}
        const w = mount(StartModal, { props: { show: true, orgUuid: ORG, sources: SOURCES, source: S1, ...props }, global: { stubs, config }, attachTo: document.body })
        await flushPromises()
        return w
    }

    async function click (w: any, id: string, index = 0) {
        const all = allById(w, id)
        expect(all.length, `no element ${id}`).toBeGreaterThan(index)
        await all[index].trigger('click')
        await flushPromises()
    }

    async function chooseKind (w: any, kind: string) {
        const radio = w.findAllComponents(NRadio).find((r: any) => r.props('value') === kind)!
        await radio.find('input').setValue(true)
        await flushPromises()
    }

    async function chooseSource (w: any, uuid: string | null) {
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

    async function lookUp (w: any, type: string, value: string) {
        await chooseLookupType(w, type)
        await byId(w, 'upstream-start-lookup-value').find('input').setValue(value)
        await click(w, 'upstream-start-lookup-submit')
    }

    return { answer, deferred, listCalls, sourceReads, byId, allById, rowsOf, texts, mountList, click, chooseKind, chooseSource, chooseMode, chooseLookupType, advance, lookUp }
}
