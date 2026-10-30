// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { NConfigProvider, NDataTable } from 'naive-ui'

const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { mutate: (...a: any[]) => mutate(...a) } }))
const fire = vi.fn()
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a), showValidationMessage: vi.fn() } }))

import { apiKeyActivityColumns, apiKeyCreatedColumn, apiKeyInstantSorter, apiKeyLabelColumn, apiKeyLastAccessColumn, apiKeyLeadColumns, apiKeyNotesColumn, apiKeySearchMatches, apiKeySorters, createApiKeyControls, filterApiKeysBySearch, secretDetailsTooltip } from './apiKeyControls'
import { apiKeyPagination, fmtKeyDate, keyActivityLines } from './apiKeyTable'

const EXPIRES = '2026-12-31T13:55:00Z'
function freeFormRow (i: number): any {
    return {
        uuid: `key-${i}`, type: 'FREEFORM', object: 'org-1', keyOrder: String(i), status: 'ACTIVE',
        createdDate: '2026-05-18, 9:36:21 p.m.', accessDate: 'Never', updatedByName: 'Jane Admin',
        secrets: [{ slot: 1, active: true, createdDate: '2026-10-06T08:00:00Z', lastUsedDate: '2026-10-07T08:00:00Z', expiresDate: EXPIRES }]
    }
}

/** The stacked Activity column as it was before TPF-88, kept local so the existing rows keep their column count and mount order. */
const stackedActivityColumn = {
    key: 'activity', width: 200, title: 'Activity',
    render: (row: any) => h('div', { style: 'font-size: 12px;' }, keyActivityLines(row).map(l => h('div', [h('span', { class: 'subtle' }, `${l.label} `), h('span', { style: l.label === 'Updated by' ? '' : 'white-space: nowrap;' }, l.value)])))
}

function mountTable (table: 'freeForm' | 'scoped', rows: any[]) {
    const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true })
    const columns = [
        ...apiKeyLeadColumns(table),
        stackedActivityColumn,
        { key: 'status', title: 'Status', render: controls.statusCell },
        { key: 'secrets', title: 'Secrets', render: controls.secretsCell }
    ]
    const pagination = apiKeyPagination()
    const Host = defineComponent({ render: () => h(NConfigProvider, null, { default: () => h(NDataTable, { columns, data: rows, pagination, rowKey: (r: any) => r.uuid }) }) })
    return mount(Host, { attachTo: document.body })
}

/** TPF-88: the rig of the sortable key tables, with the UUID column and Created descending at mount. */
function mountSortableTable (table: 'freeForm' | 'scoped', rows: any[], notify = vi.fn()) {
    const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true })
    const columns = [
        ...apiKeyLeadColumns(table, undefined, { testid: 'freeFormKeys-key-uuid', notify }),
        ...apiKeyActivityColumns(),
        { key: 'status', title: 'Status', render: controls.statusCell },
        { key: 'secrets', title: 'Secrets', render: controls.secretsCell }
    ]
    const pagination = apiKeyPagination()
    const Host = defineComponent({ render: () => h(NConfigProvider, null, { default: () => h(NDataTable, { columns, data: rows, pagination, rowKey: (r: any) => r.uuid }) }) })
    return mount(Host, { attachTo: document.body })
}

beforeEach(() => { mutate.mockReset(); fire.mockReset() })
afterEach(() => { document.body.innerHTML = '' })

describe('API key tables: type column', () => {
    it('shows no Type column or FREEFORM label on the Free Form table', () => {
        const w = mountTable('freeForm', [freeFormRow(1), freeFormRow(2)])
        const headers = w.findAll('thead th').map(th => th.text())
        expect(headers).toEqual(['Key', 'Activity', 'Status', 'Secrets'])
        expect(w.find('tbody').text()).not.toContain('FREEFORM')
    })
    it('keeps the Type column on the scoped table, which mixes types', () => {
        const rows = [{ ...freeFormRow(1), type: 'COMPONENT' }, { ...freeFormRow(2), type: 'ORGANIZATION_RW' }]
        const w = mountTable('scoped', rows)
        expect(w.findAll('thead th').map(th => th.text())[0]).toBe('Type')
        expect(w.find('tbody').text()).toContain('COMPONENT')
        expect(w.find('tbody').text()).toContain('ORGANIZATION_RW')
    })
})

describe('API key tables: key label', () => {
    it('names each key and adds its short id, so keys of one type without notes tell apart', () => {
        const rows = [
            { ...freeFormRow(1), uuid: '1c069715-aaaa-4aaa-8aaa-aaaaaaaaaaaa' },
            { ...freeFormRow(2), uuid: 'ae9254bd-bbbb-4bbb-8bbb-bbbbbbbbbbbb' },
            { ...freeFormRow(3), uuid: '77781336-cccc-4ccc-8ccc-cccccccccccc', notes: 'CI pipeline' },
            { ...freeFormRow(4), uuid: '6688e1ec-dddd-4ddd-8ddd-dddddddddddd', notes: 'CI pipeline', declaredName: 'release-pipeline' }
        ]
        const w = mountTable('freeForm', rows)
        const cells = w.findAll('tbody tr').map(tr => tr.findAll('td')[0])
        expect(cells.map(c => c.text())).toEqual(['Free Formid 1c069715', 'Free Formid ae9254bd', 'CI pipelineid 77781336', 'release-pipelineid 6688e1ec'])
        // a declared name is code-styled, notes and the type word are plain text
        expect(cells[3].find('code:not(.key-id-chip)').text()).toBe('release-pipeline')
        expect(cells[2].find('code:not(.key-id-chip)').exists()).toBe(false)
    })
    it('carries cut notes whole in the title, and renders markup in notes as text', () => {
        const long = 'n'.repeat(60)
        const w = mountTable('freeForm', [{ ...freeFormRow(1), notes: long }, { ...freeFormRow(2), notes: '<b>x</b>' }])
        const cells = w.findAll('tbody tr').map(tr => tr.findAll('td')[0])
        expect(cells[0].find('span').attributes('title')).toBe(long)
        expect(cells[1].find('b').exists()).toBe(false)
        expect(cells[1].text()).toContain('<b>x</b>')
    })
})

describe('API key tables: denied requests', () => {
    const forged = 'mine | Denied: forged'
    const denied = (i: number, extra: any) => ({ ...freeFormRow(i), status: 'DENIED', secrets: [], holder: 'user-1', ...extra })
    it('shows the reason the admin gave, never one written into the note', () => {
        const w = mountTable('freeForm', [
            denied(1, { uuid: '1c069715-aaaa-4aaa-8aaa-aaaaaaaaaaaa', notes: forged, deniedReason: 'no budget' }),
            denied(2, { uuid: 'ae9254bd-bbbb-4bbb-8bbb-bbbbbbbbbbbb', notes: forged, deniedReason: '' })
        ])
        const rows = w.findAll('tbody tr')
        expect(rows[0].findAll('td')[0].text()).toBe(forged + 'id 1c069715')
        expect(rows[0].text()).toContain('reason: no budget')
        expect(rows[0].text()).not.toContain('reason: forged')
        expect(rows[1].findAll('td')[0].text()).toBe(forged + 'id ae9254bd')
        expect(rows[1].text()).toContain('no reason given')
        expect(rows[1].text()).not.toContain('reason: forged')
    })
    it('reads the reason off the notes of a request denied before the server kept it apart', () => {
        const w = mountTable('freeForm', [denied(1, { uuid: '77781336-cccc-4ccc-8ccc-cccccccccccc', notes: 'Release automation | Denied: no budget', deniedReason: null })])
        const row = w.find('tbody tr')
        expect(row.findAll('td')[0].text()).toBe('Release automationid 77781336')
        expect(row.text()).toContain('reason: no budget')
    })
})

describe('API key tables: secret details', () => {
    it('shows only an info icon in the row, not the dates', () => {
        const w = mountTable('freeForm', [freeFormRow(1)])
        const secrets = w.findAll('tbody td')[3]
        expect(secrets.find('[data-testid="secret-details-icon"]').exists()).toBe(true)
        expect(secrets.text()).not.toContain('created')
        expect(secrets.text()).not.toContain('2026-10-06')
        expect(secrets.text()).toContain('Regenerate')
    })
    it('renders created, last used and expires in the tooltip', async () => {
        const row = freeFormRow(1)
        const w = mount(defineComponent({ render: () => secretDetailsTooltip(row.secrets[0], row) }), { attachTo: document.body })
        const icon = w.find('[data-testid="secret-details-icon"]')
        expect(icon.attributes('aria-label')).toBe(`Secret #1: created 2026-10-06 · last used 2026-10-07 · expires ${fmtKeyDate(EXPIRES)}`)
        await icon.trigger('mouseenter')
        await new Promise(r => setTimeout(r, 300))
        await nextTick()
        const tip = document.body.querySelector('[data-testid="secret-details"]')
        expect(tip).not.toBeNull()
        expect(tip!.textContent).toBe(`Created: 2026-10-06Last used: 2026-10-07Expires: ${fmtKeyDate(EXPIRES)}`)
    })
})

describe('API key tables: pagination', () => {
    it('shows 8 keys per page and the rest on page 2', async () => {
        const w = mountTable('freeForm', Array.from({ length: 11 }, (_, i) => freeFormRow(i + 1)))
        expect(w.findAll('tbody tr')).toHaveLength(8)
        const items = w.findAll('.n-pagination-item').filter(x => /^\d+$/.test(x.text()))
        expect(items.map(x => x.text())).toEqual(['1', '2'])
        await items[1].trigger('click')
        expect(w.findAll('tbody tr')).toHaveLength(3)
    })
    it('row actions on page 2 act on that row', async () => {
        const w = mountTable('freeForm', Array.from({ length: 11 }, (_, i) => freeFormRow(i + 1)))
        await w.findAll('.n-pagination-item').filter(x => x.text() === '2')[0].trigger('click')
        fire.mockResolvedValue({ value: true })
        mutate.mockResolvedValue({ data: {} })
        const firstOnPage2 = w.findAll('tbody tr')[0]
        await firstOnPage2.findAll('button').find(b => b.text() === 'Deactivate')!.trigger('click')
        await new Promise(r => setTimeout(r, 0))
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyUuid: 'key-9', status: 'INACTIVE' })
    })
})

describe('API key dialogs', () => {
    it('puts a key label in titleText, never in the HTML title', async () => {
        fire.mockResolvedValue({ value: false })
        const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true })
        await controls.deleteApiKey(freeFormRow(1), 'the key Free Form · <img src=x onerror=alert(1)> · key-1')
        const opts = fire.mock.calls[0][0]
        expect(opts.title).toBeUndefined()
        expect(opts.titleText).toBe('Delete the key Free Form · <img src=x onerror=alert(1)> · key-1?')
        expect(mutate).not.toHaveBeenCalled()
    })
    it('escapes the expiry dialog text it puts into html, and keeps its title as text', async () => {
        fire.mockResolvedValue({ isConfirmed: false })
        const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true })
        await controls.askExpiry('Regenerate <b>x</b>', 'Key <img src=x onerror=alert(1)>', 'Go')
        const opts = fire.mock.calls[0][0]
        expect(opts.title).toBeUndefined()
        expect(opts.titleText).toBe('Regenerate <b>x</b>')
        expect(opts.html).toContain('Key &lt;img src=x onerror=alert(1)&gt;')
        expect(opts.html).not.toContain('<img')
    })
    it('names the key a row dialog acts on, with a short id unique among the keys listed', async () => {
        fire.mockResolvedValue({ value: false })
        const a = { ...freeFormRow(1), uuid: '77781336-aaaa-4aaa-8aaa-aaaaaaaaaaaa', notes: 'CI pipeline' }
        const b = { ...freeFormRow(2), uuid: '77781336-bbbb-4bbb-8bbb-bbbbbbbbbbbb' }
        const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true, siblings: () => [a, b] })
        await controls.setApiKeyStatus(a, 'INACTIVE')
        expect(fire.mock.calls[0][0].text).toMatch(/^Key Free Form \u00B7 CI pipeline \u00B7 77781336-a\. Every secret/)
    })
})

describe('API key tables: short ids among siblings', () => {
    it('grows the chip where two listed keys start the same', () => {
        const rows = [{ ...freeFormRow(1), uuid: '77781336-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }, { ...freeFormRow(2), uuid: '77781336-bbbb-4bbb-8bbb-bbbbbbbbbbbb' }]
        const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true })
        const columns = [...apiKeyLeadColumns('freeForm', () => rows), { key: 'status', title: 'Status', render: controls.statusCell }]
        const Host = defineComponent({ render: () => h(NConfigProvider, null, { default: () => h(NDataTable, { columns, data: rows, rowKey: (r: any) => r.uuid }) }) })
        const w = mount(Host, { attachTo: document.body })
        expect(w.findAll('.key-id-chip').map(c => c.text())).toEqual(['id 77781336-a', 'id 77781336-b'])
    })
})

// ---- TPF-88: search, sorters and the sortable columns (design 4.2, rows 5-23) ----

function keyRow (uuid: string, ord: string, type: string, declaredName: string | null, notes: string | null, holder: string, created: string, accessed: string, extra: any = {}): any {
    return {
        uuid, type, object: 'org-1', keyOrder: ord, declaredName, notes, holderName: holder, ownerName: holder,
        createdDateIso: created, accessDateIso: accessed,
        createdDate: fmtKeyDate(created), accessDate: accessed ? fmtKeyDate(accessed) : 'Never',
        status: 'ACTIVE', secrets: [], ...extra
    }
}
const A = keyRow('0dfce17a-fc79-4245-937d-eff5213fdfcb', '1', 'FREEFORM', 'ci-main', 'nightly build', 'Olga Admin', '2026-10-01T00:00:00Z', '2026-10-10T00:00:00Z')
const B = keyRow('0dfc1111-0000-4000-8000-000000000002', '2', 'FREEFORM', 'CI-Deploy', null, 'pavel builder', '2026-10-02T00:00:00Z', '')
const C = keyRow('abcd0000-0000-4000-8000-000000000003', '3', 'FREEFORM', null, 'Nightly BUILD of docs', '', '2026-10-02T00:00:00Z', '2026-10-12T00:00:00Z')
const D = keyRow('12340000-0000-4000-8000-000000000004', '4', 'FREEFORM', '', '', 'Zed Ops', '', '')
const E = keyRow('ffff0000-0000-4000-8000-000000000005', '5', 'FREEFORM', 'release bot', 'x | Denied: no reason', 'ann dev', '2026-10-05T00:00:00Z', '2026-10-11T00:00:00Z')
const F = keyRow('99990000-0000-4000-8000-000000000006', '6', 'FEDERATED', null, null, '', '2026-10-03T00:00:00Z', '2026-10-09T00:00:00Z',
    { federation: { repository: 'relizaio/rearm', provider: 'GITHUB_ACTIONS' } })
const KEYS = [A, B, C, D, E, F]
const NAMES = new Map<string, string>(KEYS.map((k, i) => [k.uuid, 'ABCDEF'[i]]))
/** the rows as their fixture letters, so a failure names the rows */
const letters = (rows: any[]): string[] => rows.map(r => NAMES.get(r.uuid) || r.uuid)
const search = (raw: any, rows: any[] = KEYS): string[] => letters(filterApiKeysBySearch(rows, raw))
const sortedBy = (cmp: (a: any, b: any) => number): string[] => letters([...KEYS].sort(cmp))
const reversedBy = (cmp: (a: any, b: any) => number): string[] => letters([...KEYS].sort((a, b) => -cmp(a, b)))

describe('TPF-88 key search: uuid, API id, declared name, notes, repository', () => {
    it('row 5: matches a full uuid, whatever its case', () => {
        expect(search('0dfce17a-fc79-4245-937d-eff5213fdfcb')).toEqual(['A'])
        expect(search('0DFCE17A-FC79-4245-937D-EFF5213FDFCB')).toEqual(['A'])
    })
    it('row 6: matches a uuid prefix of 4+ characters, never an inner part', () => {
        expect(search('0dfc')).toEqual(['A', 'B'])
        expect(search('0DFC')).toEqual(['A', 'B'])
        expect(search('fc79')).toEqual([])
        expect(search('eff5213fdfcb')).toEqual([])
        expect(search('0df')).toEqual([])
    })
    it('row 7: matches the printed API id, exact or by a 4+ character prefix', () => {
        expect(search('freeform__org-1__ord__3')).toEqual(['C'])
        expect(search('freeform__org-1__ord__')).toEqual(['A', 'B', 'C', 'D', 'E'])
        expect(search('fre')).toEqual([])
        expect(search('federated__')).toEqual(['F'])
    })
    it('row 8: matches the declared name by case-insensitive substring', () => {
        expect(search('ci-')).toEqual(['A', 'B'])
        expect(search('release bot')).toEqual(['E'])
        expect(search('ci-main')).toEqual(['A'])
    })
    it('row 9: matches the stored notes, a legacy denial tail included', () => {
        expect(search('build')).toEqual(['A', 'C'])
        expect(search('denied')).toEqual(['E'])
        expect(search('nightly build of')).toEqual(['C'])
    })
    it('row 10: a uuid-shaped term matches by uuid and by text, each row once', () => {
        expect(search('abcd')).toEqual(['C'])
        expect(search('1234')).toEqual(['D'])
        expect(search('ffff')).toEqual(['E'])
        const G = { ...C, uuid: 'cafe0000-0000-4000-8000-000000000007', keyOrder: '7', notes: 'ffff cache' }
        NAMES.set(G.uuid, 'G')
        expect(search('ffff', [...KEYS, G])).toEqual(['E', 'G'])
        expect(search('cafe', [...KEYS, G])).toEqual(['G'])
    })
    it('row 11: matches a federated identity by its repository', () => {
        expect(search('relizaio/')).toEqual(['F'])
        expect(search('rearm')).toEqual(['F'])
    })
    it('row 12: gives an empty result, and a bare row does not throw', () => {
        expect(search('nobody')).toEqual([])
        expect(search('9999')).toEqual(['F'])
        expect(search('7777')).toEqual([])
        expect(() => filterApiKeysBySearch([{ uuid: 'x' }], 'nobody')).not.toThrow()
        expect(filterApiKeysBySearch([{ uuid: 'x' }], 'nobody')).toEqual([])
        expect(filterApiKeysBySearch([{ uuid: 'x' }], '')).toEqual([{ uuid: 'x' }])
    })
    it('row 13: an empty or blank term gives every row, as a new array', () => {
        for (const raw of ['', '   ', undefined, null]) {
            const out = filterApiKeysBySearch(KEYS, raw)
            expect(out).toEqual(KEYS)
            expect(out).not.toBe(KEYS)
        }
    })
    it('row 34: moreTexts adds a table\'s own texts to the text path', () => {
        const holder = (r: any) => [r.holderName]
        expect(search('olga')).toEqual([])
        expect(letters(filterApiKeysBySearch(KEYS, 'olga', holder))).toEqual(['A'])
        expect(search('build')).toEqual(['A', 'C'])
        expect(letters(filterApiKeysBySearch(KEYS, 'build', holder))).toEqual(['A', 'B', 'C'])
        const all = filterApiKeysBySearch(KEYS, '', holder)
        expect(all).toEqual(KEYS)
        expect(all).not.toBe(KEYS)
        const blank = () => [null, undefined]
        expect(() => filterApiKeysBySearch(KEYS, 'nobody', blank)).not.toThrow()
        expect(filterApiKeysBySearch(KEYS, 'nobody', blank)).toEqual([])
        expect(apiKeySearchMatches(A, 'olga', holder)).toBe(true)
        expect(apiKeySearchMatches(A, 'olga')).toBe(false)
    })
})

describe('TPF-88 key sorters: ascending order, empties last, uuid tiebreak', () => {
    it('row 14: name sorts what the Key column prints', () => {
        expect(sortedBy(apiKeySorters.name)).toEqual(['B', 'A', 'F', 'D', 'C', 'E'])
        expect(reversedBy(apiKeySorters.name)).toEqual(['E', 'C', 'D', 'F', 'A', 'B'])
    })
    it('row 15: declared name, the empties last in uuid order', () => {
        expect(sortedBy(apiKeySorters.declaredName)).toEqual(['B', 'A', 'E', 'D', 'F', 'C'])
        expect(reversedBy(apiKeySorters.declaredName)).toEqual(['C', 'F', 'D', 'E', 'A', 'B'])
    })
    it('row 16: notes, the shorter prefix first and the empties last', () => {
        expect(sortedBy(apiKeySorters.notes)).toEqual(['A', 'C', 'E', 'B', 'D', 'F'])
        expect(reversedBy(apiKeySorters.notes)).toEqual(['F', 'D', 'B', 'E', 'C', 'A'])
    })
    it('row 17: holder and owner, case-insensitive, the empties last', () => {
        expect(sortedBy(apiKeySorters.holder)).toEqual(['E', 'A', 'B', 'D', 'F', 'C'])
        expect(reversedBy(apiKeySorters.holder)).toEqual(['C', 'F', 'D', 'B', 'A', 'E'])
        expect(sortedBy(apiKeySorters.owner)).toEqual(['E', 'A', 'B', 'D', 'F', 'C'])
        expect(reversedBy(apiKeySorters.owner)).toEqual(['C', 'F', 'D', 'B', 'A', 'E'])
    })
    it('row 18: created by instant, a missing instant oldest, same-instant rows by uuid', () => {
        expect(sortedBy(apiKeySorters.created)).toEqual(['D', 'A', 'B', 'C', 'F', 'E'])
        expect(reversedBy(apiKeySorters.created)).toEqual(['E', 'F', 'C', 'B', 'A', 'D'])
        expect(apiKeySorters.created(B, C)).toBe(apiKeySorters.created(B, C))
        expect(apiKeySorters.created(B, C)).toBeLessThan(0)
    })
    it('row 19: last accessed, never-accessed rows first ascending and last descending', () => {
        expect(sortedBy(apiKeySorters.lastAccess)).toEqual(['B', 'D', 'F', 'A', 'E', 'C'])
        expect(reversedBy(apiKeySorters.lastAccess)).toEqual(['C', 'E', 'A', 'F', 'D', 'B'])
        const cmp = apiKeyInstantSorter((r: any) => r.x)
        const rows = [{ uuid: 'b', x: 'not a date' }, { uuid: 'a', x: 'not a date' }]
        expect(() => [...rows].sort(cmp)).not.toThrow()
        expect([...rows].sort(cmp).map(r => r.uuid)).toEqual(['a', 'b'])
    })
})

describe('TPF-88 key table columns: UUID, Created, Last accessed, sortable', () => {
    const firstCells = (w: any): string[] => w.findAll('tbody tr').map((tr: any) => tr.findAll('td')[0].text())
    const startsAs = (cells: string[]): string[] => cells.map(t => t.startsWith('ci-main') ? 'A' : t.startsWith('Nightly BUILD of docs') ? 'C' : t.startsWith('release bot') ? 'E' : t)

    it('row 20: headers of the sortable rig, and the lead column keys with and without the UUID column', () => {
        const ff = mountSortableTable('freeForm', [A])
        expect(ff.findAll('thead th').map(th => th.text())).toEqual(['Key', 'UUID', 'Created', 'Last accessed', 'Status', 'Secrets'])
        ff.unmount()
        const sc = mountSortableTable('scoped', [{ ...A, type: 'COMPONENT' }])
        expect(sc.findAll('thead th').map(th => th.text())).toEqual(['Type', 'Key', 'UUID', 'Created', 'Last accessed', 'Status', 'Secrets'])
        expect(apiKeyLeadColumns('freeForm').map(c => c.key)).toEqual(['label'])
        expect(apiKeyLeadColumns('scoped').map(c => c.key)).toEqual(['type', 'label'])
        expect(apiKeyLeadColumns('freeForm', undefined, { testid: 't', notify: vi.fn() }).map(c => c.key)).toEqual(['label', 'uuid'])
    })
    it('row 21: the uuid cell shows the full uuid with a copy button that copies that row', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined)
        Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
        const notify = vi.fn()
        const w = mountSortableTable('freeForm', [A, B], notify)
        const cells = w.findAll('[data-testid="freeFormKeys-key-uuid"]')
        expect(cells).toHaveLength(2)
        expect(cells.map(c => c.text())).toEqual([B.uuid, A.uuid])
        const buttons = w.findAll('[data-testid="freeFormKeys-key-uuid-copy"]')
        expect(buttons).toHaveLength(2)
        for (const b of buttons) {
            expect(b.element.tagName).toBe('BUTTON')
            expect(b.attributes('aria-label')).toBe('Copy Key UUID')
        }
        await buttons[1].trigger('click')
        await new Promise(r => setTimeout(r, 0))
        expect(writeText).toHaveBeenCalledTimes(1)
        expect(writeText).toHaveBeenCalledWith(A.uuid)
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('success', 'Copied', 'Key UUID copied')
    })
    it('row 22: the builders carry sorters, Created descends at mount, Last accessed cycles on click', async () => {
        for (const col of [apiKeyLabelColumn(), apiKeyCreatedColumn(), apiKeyLastAccessColumn(), apiKeyNotesColumn(100)]) {
            expect(typeof col.sorter).toBe('function')
        }
        expect(apiKeyCreatedColumn().defaultSortOrder).toBe('descend')
        expect(apiKeyNotesColumn(220, 'Purpose / Notes').title).toBe('Purpose / Notes')
        expect(apiKeyNotesColumn(220, 'Purpose / Notes').key).toBe('notes')
        const w = mountSortableTable('freeForm', [A, C, E])
        expect(startsAs(firstCells(w))).toEqual(['E', 'C', 'A'])
        const created = w.findAll('thead th').find(th => th.text() === 'Created')!
        expect(created.find('.n-data-table-sorter').exists()).toBe(true)
        const lastAccessed = () => w.findAll('thead th').find(th => th.text() === 'Last accessed')!
        await lastAccessed().trigger('click')
        await nextTick()
        expect(startsAs(firstCells(w))).toEqual(['C', 'E', 'A'])
        await lastAccessed().trigger('click')
        await nextTick()
        expect(startsAs(firstCells(w))).toEqual(['A', 'E', 'C'])
    })
    it('row 23: the Created cell carries the updater, the Last accessed cell says Never', () => {
        const w = mountSortableTable('freeForm', [{ ...A, updatedByName: 'Jane Admin' }])
        const createdCell = w.findAll('tbody td')[2]
        expect(createdCell.text()).toContain(fmtKeyDate('2026-10-01T00:00:00Z'))
        expect(createdCell.text()).toContain('Updated by Jane Admin')
        w.unmount()
        const v = mountSortableTable('freeForm', [B])
        expect(v.findAll('tbody td')[3].text()).toBe('Never')
    })
})
