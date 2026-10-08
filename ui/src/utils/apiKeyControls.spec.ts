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
        expect(headers).toEqual(['IDs', 'Activity', 'Status', 'Secrets'])
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
