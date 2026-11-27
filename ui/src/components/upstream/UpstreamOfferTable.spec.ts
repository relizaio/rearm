// @vitest-environment happy-dom
//
// T-1144, T-1146, T-1152, T-1158 (TPF-243 design 3.5 to 3.7), mutation round: the table of the rows
// of a source's list on its own. It receives the rows and emits clicks; it sends no request. Pinned
// here: the head of the table, the cut name and values at the exact limits, the three identifiers
// and "and n more", the expandable rows and what they show, the action of a row (Prepare, or
// Followed with the link only for two uuids), the click that emits the row itself, the disabled
// state, the table page and its change, and the keys of the rows.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { toRaw } from 'vue'
import { FOLLOW, ORG, RouterLink, U, item, items, present } from './startDialogTestKit'
import Table from './UpstreamOfferTable.vue'

function mountTable (rows: any[], props: Record<string, any> = {}) {
    return mount(Table, { props: { rows, orgUuid: ORG, disabled: false, page: 1, ...props }, global: { stubs: { RouterLink } }, attachTo: document.body })
}

const bodyRows = (w: any) => w.findAll('tbody tr').filter((r: any) => r.find('td[data-col-key="upstreamUuid"]').exists())
const q = (parent: any, sel: string) => present(parent.find(sel), sel)
function cell (w: any, index: number, col: string) {
    const row = bodyRows(w)[index]
    expect(row, `no row ${index}`).toBeDefined()
    return q(row, `td[data-col-key="${col}"]`)
}
const ids = (n: number, value = 'value') => Array.from({ length: n }, (_, i) => ({ idType: 'PURL', idValue: `${value}-${i + 1}` }))
const triggers = (w: any) => w.findAll('.n-data-table-expand-trigger')

async function expand (w: any, index: number) {
    await q(bodyRows(w)[index], '.n-data-table-expand-trigger').trigger('click')
    await w.vm.$nextTick()
}

describe('the head and the cells of the table', () => {
    it('hasTheFiveColumnsWithTheirTitlesAndKeys', () => {
        const w = mountTable([item(1)])
        const heads = w.findAll('thead th')
        expect(heads.map((h: any) => h.text())).toEqual(['', 'Name', 'Uuid', 'Identifiers', ''])
        expect(heads.map((h: any) => h.attributes('data-col-key'))).toEqual(['__n_expand__', 'name', 'upstreamUuid', 'identifiers', 'action'])
        expect(q(w, '[data-testid="upstream-start-list"]').exists()).toBe(true)
        w.unmount()
    })

    it('showsTheWholeUuidInItsOwnColumnAndTheNameInItsOwn', () => {
        const w = mountTable([item(7, { name: 'Gateway' })])
        expect(cell(w, 0, 'upstreamUuid').text()).toBe(U(7))
        expect(cell(w, 0, 'upstreamUuid').find('.upstream-offer-uuid').text()).toBe(U(7))
        expect(cell(w, 0, 'name').text()).toBe('Gateway')
        expect(cell(w, 0, 'name').find('.upstream-offer-noname').exists()).toBe(false)
        w.unmount()
    })

    it.each([[null], [undefined]])('showsNoNameInTheMutedWordsForAName%s', (name) => {
        const w = mountTable([item(1, { name })])
        expect(cell(w, 0, 'name').text()).toBe('(no name)')
        expect(cell(w, 0, 'name').find('.upstream-offer-noname').text()).toBe('(no name)')
        w.unmount()
    })

    it('cutsAnAboveTheLimitNameAtTheLimitOfCodePointsAndShowsTheOneAtTheLimitWhole', () => {
        const emoji = '\u{1F600}'
        const w = mountTable([
            item(1, { name: 'n'.repeat(120) }), item(2, { name: 'n'.repeat(121) }), item(3, { name: emoji.repeat(120) }),
            item(4, { name: emoji.repeat(121) }), item(5, { name: '' }),
        ])
        expect(cell(w, 0, 'name').text()).toBe('n'.repeat(120))
        expect(cell(w, 1, 'name').text()).toBe('n'.repeat(120) + '...')
        expect(cell(w, 2, 'name').text()).toBe(emoji.repeat(120))
        expect(cell(w, 3, 'name').text()).toBe(emoji.repeat(120) + '...')
        expect(cell(w, 4, 'name').text()).toBe('')
        w.unmount()
    })

    it('cutsAValueAboveTheLimitAndShowsTheTypeLabelOfEachIdentifier', () => {
        const w = mountTable([item(1, { identifiers: [
            { idType: 'CPE', idValue: 'c'.repeat(100) }, { idType: 'TEI', idValue: 't'.repeat(101) }, { idType: 'COMPLIANCE_DOCUMENT', idValue: 'doc' },
        ] })])
        const lines = cell(w, 0, 'identifiers').findAll('.upstream-offer-identifier')
        expect(lines.map((l: any) => l.find('.upstream-offer-idtype').text())).toEqual(['CPE', 'TEI', 'Compliance document'])
        expect(lines.map((l: any) => l.find('.upstream-offer-idvalue').text())).toEqual(['c'.repeat(100), 't'.repeat(100) + '...', 'doc'])
        w.unmount()
    })

    it.each([[0, 0, null], [1, 1, null], [3, 3, null], [4, 3, 'and 1 more'], [5, 3, 'and 2 more'], [12, 3, 'and 9 more']])(
        'showsAtMostThreeIdentifiersFor%i(shown %i, then %s)', (count, shown, more) => {
            const w = mountTable([item(1, { identifiers: ids(count) })])
            const c = cell(w, 0, 'identifiers')
            expect(c.findAll('.upstream-offer-identifier')).toHaveLength(shown)
            expect(c.findAll('.upstream-offer-idvalue').map((v: any) => v.text())).toEqual(ids(shown).map(i => i.idValue))
            expect(c.findAll('.upstream-offer-more').map((m: any) => m.text())).toEqual(more ? [more] : [])
            w.unmount()
        })

    it('showsNoIdentifierForARowWithoutTheList', () => {
        const w = mountTable([item(1, { identifiers: undefined })])
        expect(cell(w, 0, 'identifiers').text()).toBe('')
        w.unmount()
    })
})

describe('the action of a row', () => {
    it.each([[null], [undefined]])('offersPrepareForAnActiveFollowOf%s', (activeFollow) => {
        const w = mountTable([item(1, { activeFollow })])
        const button = cell(w, 0, 'action').find('[data-testid="upstream-start-row-prepare"]')
        expect(button.text()).toBe('Prepare')
        expect(button.classes()).toContain('n-button--primary-type')
        expect(button.classes()).toContain('n-button--tiny-type')
        expect(cell(w, 0, 'action').find('[data-testid="upstream-start-row-followed"]').exists()).toBe(false)
        expect(cell(w, 0, 'action').find('[data-testid="upstream-start-row-follow-link"]').exists()).toBe(false)
        w.unmount()
    })

    it('showsFollowedAndTheLinkBeforeNothingForAFollowedRowAndNoPrepare', () => {
        let w: any
        expect(() => { w = mountTable([item(1, { activeFollow: FOLLOW })]) }).not.toThrow()
        const action = cell(w, 0, 'action')
        expect(action.find('[data-testid="upstream-start-row-prepare"]').exists()).toBe(false)
        const tag = action.find('[data-testid="upstream-start-row-followed"]')
        expect(tag.text()).toBe('Followed')
        expect(tag.attributes('style')).toContain('--n-text-color: #18a058')
        expect(tag.attributes('style')).toContain('--n-height: 22px')
        expect(tag.element.parentElement!.getAttribute('style')).toContain('margin-right: 8px')
        expect(tag.element.parentElement!.parentElement!.getAttribute('style')).toContain('align-items: center;')
        const link = action.find('[data-testid="upstream-start-row-follow-link"]')
        expect(link.text()).toBe('Open the follow')
        expect(JSON.parse(link.attributes('data-to')!)).toEqual({ name: 'UpstreamFollow', params: { orguuid: ORG, uuid: FOLLOW } })
        expect(link.attributes('target')).toBeUndefined()
        expect(action.text()).toBe('FollowedOpen the follow')
        expect(action.html().indexOf('upstream-start-row-followed')).toBeLessThan(action.html().indexOf('upstream-start-row-follow-link'))
        w.unmount()
    })

    it.each([
        ['an active follow that is not a uuid', 'not-a-uuid', ORG],
        ['an active follow with a quote', 'x"><a', ORG],
        ['an empty active follow', '', ORG],
    ])('showsFollowedWithoutALinkFor%s', (_name, activeFollow, org) => {
        const w = mountTable([item(1, { activeFollow })], { orgUuid: org })
        const action = cell(w, 0, 'action')
        expect(action.find('[data-testid="upstream-start-row-followed"]').text()).toBe('Followed')
        expect(action.find('[data-testid="upstream-start-row-follow-link"]').exists()).toBe(false)
        expect(action.find('[data-testid="upstream-start-row-prepare"]').exists()).toBe(false)
        w.unmount()
    })

    it.each([['not-a-uuid'], [''], ['00000000-0000-0000-0000-000000000000']])('showsNoLinkWhenTheOrganizationIs%j', (org) => {
        const w = mountTable([item(1, { activeFollow: FOLLOW })], { orgUuid: org })
        const action = cell(w, 0, 'action')
        expect(action.find('[data-testid="upstream-start-row-followed"]').exists()).toBe(true)
        expect(action.find('[data-testid="upstream-start-row-follow-link"]').exists()).toBe(false)
        w.unmount()
    })

    it('emitsThePrepareOfARowWithTheRowItselfOnceAndOnlyOnAClick', async () => {
        const rows = [item(1), item(2)]
        const w = mountTable(rows)
        expect(w.emitted('prepare')).toBeUndefined()
        await cell(w, 1, 'action').find('[data-testid="upstream-start-row-prepare"]').trigger('click')
        expect(w.emitted('prepare')).toHaveLength(1)
        expect((w.emitted('prepare') as any[])[0]).toHaveLength(1)
        expect(toRaw((w.emitted('prepare') as any[])[0][0])).toBe(rows[1])
        expect((w.emitted('prepare') as any[])[0][0].upstreamUuid).toBe(U(2))
        w.unmount()
    })

    it('disablesEveryPrepareWhileAPreparationIsInFlightAndNoneOtherwise', async () => {
        const w = mountTable([item(1), item(2)], { disabled: true })
        const buttons = w.findAll('[data-testid="upstream-start-row-prepare"]')
        expect(buttons).toHaveLength(2)
        expect(buttons.every((b: any) => b.attributes('disabled') !== undefined)).toBe(true)
        await w.setProps({ disabled: false })
        expect(w.findAll('[data-testid="upstream-start-row-prepare"]').every((b: any) => b.attributes('disabled') === undefined)).toBe(true)
        w.unmount()
    })
})

describe('the expanded row', () => {
    it('offersTheTriggerOnlyForARowThatHasMoreThanItShows', () => {
        const w = mountTable([
            item(1), item(2, { name: 'n'.repeat(121) }), item(3, { identifiers: ids(4) }), item(4, { identifiersDropped: 1 }),
            item(5, { identifiers: [{ idType: 'CPE', idValue: 'v'.repeat(101) }] }), item(6, { name: 'n'.repeat(120), identifiers: ids(3) }),
        ])
        expect(bodyRows(w).map((r: any) => r.find('.n-data-table-expand-trigger').exists())).toEqual([false, true, true, true, true, false])
        w.unmount()
    })

    it('showsTheWholeNameEveryValueUncutAndTheDroppedLine', async () => {
        const name = 'N'.repeat(130)
        const long = 'V'.repeat(150)
        const w = mountTable([item(1, { name, identifiers: [...ids(3), { idType: 'TEI', idValue: long }], identifiersDropped: 2 })])
        expect(q(w, '[data-testid="upstream-start-row-expanded"]').exists()).toBe(false)
        await expand(w, 0)
        const expanded = q(w, '[data-testid="upstream-start-row-expanded"]')
        expect(expanded.find('.upstream-offer-whole-name').text()).toBe(name)
        expect(expanded.findAll('.upstream-offer-idvalue').map((v: any) => v.text())).toEqual([...ids(3).map(i => i.idValue), long])
        expect(expanded.findAll('.upstream-offer-idtype').map((v: any) => v.text())).toEqual(['PURL', 'PURL', 'PURL', 'TEI'])
        expect(q(w, '[data-testid="upstream-start-row-dropped"]').text()).toBe('2 identifiers of the source were not usable and are not shown.')
        w.unmount()
    })

    it.each([[null], [undefined]])('saysNoNameInTheExpandedRowForAName%s', async (name) => {
        const w = mountTable([item(1, { name, identifiers: ids(4) })])
        await expand(w, 0)
        const expanded = q(w, '[data-testid="upstream-start-row-expanded"]')
        expect(expanded.find('.upstream-offer-noname').text()).toBe('(no name)')
        expect(expanded.find('.upstream-offer-whole-name').exists()).toBe(false)
        w.unmount()
    })

    it('showsTheDroppedLineOnlyForAtLeastOneDroppedIdentifierInTheWordsOfTheCount', async () => {
        const w = mountTable([
            item(1, { identifiers: ids(4), identifiersDropped: 0 }), item(2, { identifiers: ids(4), identifiersDropped: undefined }),
            item(3, { identifiers: ids(4), identifiersDropped: null }), item(4, { identifiersDropped: 1 }), item(5, { identifiers: ids(4), identifiersDropped: 3 }),
        ])
        for (let i = 0; i < 5; i++) await expand(w, i)
        const dropped = w.findAll('[data-testid="upstream-start-row-dropped"]').map((d: any) => d.text())
        expect(dropped).toEqual([
            '1 identifier of the source was not usable and is not shown.',
            '3 identifiers of the source were not usable and are not shown.',
        ])
        w.unmount()
    })

    it('keepsSeveralRowsExpandedAtOnceAndExpandsOnlyTheRowClicked', async () => {
        const w = mountTable([item(1, { identifiers: ids(4, 'one') }), item(2, { identifiers: ids(4, 'two') }), item(3, { identifiers: ids(4, 'three') })])
        await expand(w, 0)
        await expand(w, 2)
        const expanded = w.findAll('[data-testid="upstream-start-row-expanded"]')
        expect(expanded).toHaveLength(2)
        expect(expanded[0].text()).toContain('one-4')
        expect(expanded[1].text()).toContain('three-4')
        expect(expanded.map((e: any) => e.text()).join('')).not.toContain('two-')
        await expand(w, 0)
        expect(w.findAll('[data-testid="upstream-start-row-expanded"]')).toHaveLength(1)
        w.unmount()
    })

    it('keysTheRowsByUuidSoTwoRowsOfOneNameExpandApart', async () => {
        const w = mountTable([item(1, { name: 'Same', identifiers: ids(4, 'first') }), item(2, { name: 'Same', identifiers: ids(4, 'second') })])
        await expand(w, 1)
        const expanded = w.findAll('[data-testid="upstream-start-row-expanded"]')
        expect(expanded).toHaveLength(1)
        expect(expanded[0].text()).toContain('second-4')
        expect(triggers(w)).toHaveLength(2)
        w.unmount()
    })
})

describe('the table page', () => {
    it('showsTwentyFiveRowsOfThePageGivenAndNoRowOfAnother', async () => {
        const w = mountTable(items(60), { page: 1 })
        expect(bodyRows(w)).toHaveLength(25)
        expect(cell(w, 0, 'name').text()).toBe('Item 1')
        expect(cell(w, 24, 'name').text()).toBe('Item 25')
        await w.setProps({ page: 3 })
        expect(bodyRows(w)).toHaveLength(10)
        expect(cell(w, 0, 'name').text()).toBe('Item 51')
        await w.setProps({ page: 2 })
        expect(cell(w, 0, 'name').text()).toBe('Item 26')
        expect(cell(w, 24, 'name').text()).toBe('Item 50')
        w.unmount()
    })

    it('showsAllTheRowsOfExactlyOnePageAndOneMorePageForOneRowMore', () => {
        const numbers = (w: any) => w.findAll('.n-pagination-item').map((p: any) => p.text()).filter((x: string) => x !== '')
        const w = mountTable(items(25))
        expect(bodyRows(w)).toHaveLength(25)
        expect(numbers(w)).toEqual(['1'])
        w.unmount()
        const w2 = mountTable(items(26))
        expect(bodyRows(w2)).toHaveLength(25)
        expect(numbers(w2)).toEqual(['1', '2'])
        w2.unmount()
    })

    it('emitsTheNewPageWhenAPageIsClickedAndNothingElse', async () => {
        const w = mountTable(items(60))
        expect(w.emitted('update:page')).toBeUndefined()
        const three = w.findAll('.n-pagination-item').find((p: any) => p.text() === '3')!
        await three.trigger('click')
        expect(w.emitted('update:page')).toEqual([[3]])
        expect(w.emitted('prepare')).toBeUndefined()
        w.unmount()
    })
})
