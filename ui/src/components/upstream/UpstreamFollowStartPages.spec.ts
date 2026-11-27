// @vitest-environment happy-dom
//
// T-1146 to T-1149 and T-1151 (TPF-243 design 3.5 to 3.9), mutation round: the page of the table that
// the dialog keeps for each set (after a first page, after Load more with and without a filter, in a
// lookup, at a change of the filter, at a click on a page), the table that is made again for another
// view, the warnings and the alerts, the words of the buttons and of the identifier types, and the
// link of a followed row.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { FOLLOW, ORG, firstPageVars, item, items, page } from './startDialogTestKit'
import { makeKit } from './startDialogTestKit'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('vue-router', async (orig) => ({ ...(await orig<any>()), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

const { default: StartModal } = await import('./UpstreamFollowStartModal.vue')
const { default: Table } = await import('./UpstreamOfferTable.vue')
const { GET_UPSTREAM_OFFERED_ITEMS } = await import('@/utils/upstreamOfferQueries')
const labels = await import('@/utils/upstreamLabels')
const { answer, deferred, allById, byId, mountList, click, lookUp } = makeKit(query, mutate, StartModal, GET_UPSTREAM_OFFERED_ITEMS, 'upstreamOfferedItems')

const unhandled: any[] = []
const onUnhandled = (e: any) => { unhandled.push(e) }

beforeEach(() => {
    query.mockReset()
    mutate.mockReset()
    unhandled.length = 0
    process.on('unhandledRejection', onUnhandled)
})

afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
    expect(unhandled).toEqual([])
})

const vmOf = (w: any) => w.vm as any
const TOKEN_PAGE = (n: number, from = 1) => page({ items: items(n, from), next: 'TOKEN', nextPageToken: 'tok-1' })
const tablePage = (w: any) => w.findComponent(Table).props('page')
const typeFilter = (w: any, text: string) => byId(w, 'upstream-start-filter').find('input').setValue(text)

describe('the page of the table after a page of the list', () => {
    it('showsThePageOfTheFirstNewRowAfterLoadMoreAndPageOneAfterAFirstPage', async () => {
        answer({ list: [TOKEN_PAGE(60), page({ items: items(30, 61) })] })
        const w = await mountList()
        expect(vmOf(w).browse.tablePage).toBe(1)
        expect(tablePage(w)).toBe(1)
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.tablePage).toBe(3)
        expect(tablePage(w)).toBe(3)
        w.unmount()
    })

    it('keepsThePageForALaterPageWithNoNewRow', async () => {
        answer({ list: [TOKEN_PAGE(30), TOKEN_PAGE(30)] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.set.rows).toHaveLength(30)
        expect(vmOf(w).browse.tablePage).toBe(1)
        w.unmount()
    })

    it('keepsThePageWhileATextIsInTheFilterAndMovesItForABlankOne', async () => {
        answer({ list: [TOKEN_PAGE(30), page({ items: items(30, 31), next: 'TOKEN', nextPageToken: 'tok-2' }), page({ items: items(30, 61) })] })
        const w = await mountList()
        await typeFilter(w, 'Item')
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.set.rows).toHaveLength(60)
        expect(vmOf(w).browse.tablePage).toBe(1)
        await typeFilter(w, '   ')
        await click(w, 'upstream-start-more')
        expect(vmOf(w).browse.set.rows).toHaveLength(90)
        expect(vmOf(w).browse.tablePage).toBe(3)
        w.unmount()
    })

    it('movesThePageOfALookupWhateverTheFilterOfTheListHolds', async () => {
        answer({ list: [page({ items: items(3) }), TOKEN_PAGE(30, 100), page({ items: items(30, 130) })] })
        const w = await mountList()
        await typeFilter(w, 'Item')
        await lookUp(w, 'TEI', 'tei://x')
        await click(w, 'upstream-start-more')
        expect(vmOf(w).lookup.tablePage).toBe(2)
        expect(vmOf(w).browse.tablePage).toBe(1)
        expect(vmOf(w).filterText).toBe('Item')
        w.unmount()
    })

    it('putsTheTableOnPageOneForAFirstPageWhateverTheFilterOfTheListHolds', async () => {
        const d = deferred()
        answer({ list: [page({ items: items(60) }), d.fn] })
        const w = await mountList()
        const vm = vmOf(w)
        vm.browse.tablePage = 3
        vm.filterText = 'Item'
        vm.send(vm.callFor(vm.browse, 'BROWSE', false))
        await flushPromises()
        d.pending[0].resolve(page({ items: items(60) }))
        await flushPromises()
        expect(vm.browse.tablePage).toBe(1)
        w.unmount()
    })
})

describe('the page of the table at a change of the filter and at a click on a page', () => {
    it('putsTheListOnPageOneAtAChangeOfTheFilterAndLeavesTheLookupWhereItIs', async () => {
        answer({ list: page({ items: items(60) }) })
        const w = await mountList()
        const vm = vmOf(w)
        vm.browse.tablePage = 3
        vm.lookup.tablePage = 4
        await typeFilter(w, 'Item 1')
        expect(vm.browse.tablePage).toBe(1)
        expect(vm.lookup.tablePage).toBe(4)
        vm.view = 'LOOKUP'
        vm.browse.tablePage = 3
        vm.onFilter('Item 2')
        expect(vm.browse.tablePage).toBe(1)
        expect(vm.lookup.tablePage).toBe(4)
        vm.onFilter(null)
        expect(vm.filterText).toBe('')
        w.unmount()
    })

    it('keepsThePageThatTheTableAskedForInTheSetThatIsShown', async () => {
        answer({ list: [page({ items: items(60) }), page({ items: items(60, 100) })] })
        const w = await mountList()
        w.findComponent(Table).vm.$emit('update:page', 2)
        await flushPromises()
        expect(vmOf(w).browse.tablePage).toBe(2)
        expect(tablePage(w)).toBe(2)
        await lookUp(w, 'TEI', 'tei://x')
        w.findComponent(Table).vm.$emit('update:page', 3)
        await flushPromises()
        expect(vmOf(w).lookup.tablePage).toBe(3)
        expect(vmOf(w).browse.tablePage).toBe(2)
        w.unmount()
    })

    it('makesTheTableAgainForAnotherViewOfTheDialog', async () => {
        answer({ list: [page({ items: items(3) }), page({ items: items(3, 10) })] })
        const w = await mountList()
        await lookUp(w, 'TEI', 'tei://x')
        const element = w.find('[data-testid="upstream-start-list"]').element
        await click(w, 'upstream-start-lookup-back')
        expect(w.find('[data-testid="upstream-start-list"]').element).not.toBe(element)
        w.unmount()
    })
})

describe('what the dialog passes to the table and shows around it', () => {
    it('passesTheOrganizationToTheTableSoThatAFollowedRowHasItsLink', async () => {
        answer({ list: page({ items: [item(1, { activeFollow: FOLLOW }), item(2)] }) })
        const w = await mountList()
        expect(w.findComponent(Table).props('orgUuid')).toBe(ORG)
        expect(w.findComponent(Table).props('disabled')).toBe(false)
        const link = byId(w, 'upstream-start-row-follow-link')
        expect(JSON.parse(link.attributes('data-to')!)).toEqual({ name: 'UpstreamFollow', params: { orguuid: ORG, uuid: FOLLOW } })
        w.unmount()
    })

    it('showsEveryWarningOfASetInItsOrderAsAWarningAndAFailedPageAsAnError', async () => {
        answer({ list: [
            page({ items: items(3), next: 'TOKEN', nextPageToken: 'tok-1', cut: 'MAX_ROWS', rowsReceived: 500, rowsDropped: 2 }),
            page({ items: items(2, 10), cut: 'OTHER' }),
        ] })
        const w = await mountList()
        await click(w, 'upstream-start-more')
        const warnings = allById(w, 'upstream-start-warning')
        expect(warnings.map((x: any) => x.text())).toEqual([labels.offerMaxRowsSentence(500), labels.offerCutSentence, labels.offerRowsDroppedSentence(2)])
        for (const x of warnings) expect(x.attributes('style')).toContain('--n-icon-color: #f0a020')
        w.unmount()
        answer({ list: page({ items: null, failure: 'LIST_NOT_SUPPORTED', message: 'No list.' }) })
        const failed = await mountList()
        expect(byId(failed, 'upstream-start-list-failure').find('.n-alert').attributes('style')).toContain('--n-icon-color: #d03050')
        failed.unmount()
    })

    it('hasTheWordsOfTheButtonsOfTheListAndTheLabelsAndTheOrderOfTheIdentifierTypes', async () => {
        answer({ list: [TOKEN_PAGE(3), page({ items: null, failure: 'SOURCE_FAILURE', sourceFailure: 'TIMED_OUT', message: 'Slow.' })] })
        const w = await mountList()
        expect(byId(w, 'upstream-start-more').text()).toBe('Load more')
        await click(w, 'upstream-start-more')
        expect(byId(w, 'upstream-start-more').text()).toBe('Load more')
        const select = w.findAllComponents({ name: 'Select' }).find((s: any) => s.attributes('data-testid') === 'upstream-start-lookup-type')!
        expect(select.props('options')).toEqual([
            { label: 'CPE', value: 'CPE' }, { label: 'TEI', value: 'TEI' }, { label: 'PURL', value: 'PURL' },
            { label: 'Compliance document', value: 'COMPLIANCE_DOCUMENT' },
        ])
        w.unmount()
    })

    it('letsTheFilterBeClearedByItsOwnButton', async () => {
        answer({ list: page({ items: items(3) }) })
        const w = await mountList()
        expect(w.find('[data-testid="upstream-start-filter"] .n-base-clear').exists()).toBe(true)
        await typeFilter(w, 'Item 2')
        const clear = w.find('[data-testid="upstream-start-filter"] .n-base-clear')
        expect(clear.exists()).toBe(true)
        w.unmount()
    })

    it('showsTheSpinnerOfTheGateWithItsSize', async () => {
        const d = deferred()
        answer({ source: d.fn })
        const w = await mountList()
        const spin = byId(w, 'upstream-start-gate').find('.n-spin-body .n-spin')
        expect(spin.exists()).toBe(true)
        expect(spin.attributes('style')).toContain('--n-size: 16px')
        w.unmount()
    })

    it('showsFirstPageOfTheKindAndSourceFromTheVariablesOfTheCall', async () => {
        answer({ list: page({ items: items(1) }) })
        const w = await mountList()
        expect(vmOf(w).listInFlight).toBe(false)
        expect(firstPageVars()).toEqual({ source: vmOf(w).model.source, kind: 'PRODUCT', identifier: null, pageSize: 100, pageToken: null })
        w.unmount()
    })
})
