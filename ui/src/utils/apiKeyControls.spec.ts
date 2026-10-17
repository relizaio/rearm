// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { NConfigProvider, NDataTable } from 'naive-ui'

const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { mutate: (...a: any[]) => mutate(...a) } }))
const fire = vi.fn()
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a), showValidationMessage: vi.fn() } }))

import { apiKeyActivityColumn, apiKeyLeadColumns, createApiKeyControls, secretDetailsTooltip } from './apiKeyControls'
import { apiKeyPagination, fmtKeyDate } from './apiKeyTable'

const EXPIRES = '2026-12-31T13:55:00Z'
function freeFormRow (i: number): any {
    return {
        uuid: `key-${i}`, type: 'FREEFORM', object: 'org-1', keyOrder: String(i), status: 'ACTIVE',
        createdDate: '2026-05-18, 9:36:21 p.m.', accessDate: 'Never', updatedByName: 'Jane Admin',
        secrets: [{ slot: 1, active: true, createdDate: '2026-10-06T08:00:00Z', lastUsedDate: '2026-10-07T08:00:00Z', expiresDate: EXPIRES }]
    }
}

function mountTable (table: 'freeForm' | 'scoped', rows: any[]) {
    const controls = createApiKeyControls({ notify: vi.fn(), reload: vi.fn(), canManage: () => true })
    const columns = [
        ...apiKeyLeadColumns(table),
        apiKeyActivityColumn(),
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
