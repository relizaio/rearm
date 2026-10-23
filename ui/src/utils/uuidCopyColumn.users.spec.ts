// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NConfigProvider, NDataTable } from 'naive-ui'

import { uuidCopyColumn } from './uuidCopyColumn'

/**
 * TPF-85 design 4.4, rows 11-13: the UUID columns of the Org settings active and inactive users
 * tables, on TPF-84's uuidCopyColumn contract (OrgSettings is never mounted in tests, so each column
 * is mounted inside a real NDataTable, the apiKeyControls.spec.ts rig; the page wiring is pinned in
 * components/orgSettingsUsersSearchWiring.spec.ts). The guards of the util are uuidCopyColumn.spec.ts.
 */
const USERS = [
    { uuid: '0dfce17a-fc79-4245-937d-eff5213fdfcb', name: 'Olga Admin', email: 'olga@example.io', status: 'ACTIVE' },
    { uuid: '0dfc1111-0000-4000-8000-000000000002', name: 'Pavel Builder', email: 'pavel@example.io', status: 'INACTIVE' },
    { uuid: 'abcd0000-0000-4000-8000-000000000003', name: 'Dana Tester', email: 'abcd@example.io', status: 'ACTIVE' },
    { uuid: '12340000-0000-4000-8000-000000000004', name: 'Ann Dev', email: 'ann@EXAMPLE.io', status: 'ACTIVE' },
    { uuid: 'ffff0000-0000-4000-8000-000000000005', name: 'Zed Ops', email: 'zed@example.io', status: 'ACTIVE' }
]

let notify: ReturnType<typeof vi.fn>
let writeText: ReturnType<typeof vi.fn>
const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')

function mountTable (columns: any[], rows: any[]) {
    const Host = defineComponent({ render: () => h(NConfigProvider, null, { default: () => h(NDataTable, { columns, data: rows, rowKey: (r: any) => r.uuid }) }) })
    return mount(Host, { attachTo: document.body })
}

beforeEach(() => {
    notify = vi.fn()
    writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})
afterEach(() => {
    document.body.innerHTML = ''
    if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard)
    else Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
})

describe('uuidCopyColumn: the active users UUID column', () => {
    it('row 11: is the third column, the full uuid in a code cell beside one enabled, labelled copy button', () => {
        const column = uuidCopyColumn({ what: 'User UUID', testid: 'user-uuid', notify })
        expect(column.key).toBe('uuid')
        expect(column.title).toBe('UUID')
        const rows = USERS.slice(0, 2)
        const w = mountTable([{ key: 'email', title: 'Email' }, { key: 'name', title: 'Name' }, column], rows)
        expect(w.findAll('thead th').map(th => th.text())).toEqual(['Email', 'Name', 'UUID'])
        const codes = w.findAll('[data-testid="user-uuid"]')
        expect(codes).toHaveLength(2)
        codes.forEach((code, i) => {
            expect(code.element.tagName).toBe('CODE')
            expect(code.text()).toBe(rows[i].uuid)
            expect(code.text()).toHaveLength(36)
        })
        const buttons = w.findAll('[data-testid="user-uuid-copy"]')
        expect(buttons).toHaveLength(2)
        buttons.forEach((b) => {
            const button = b.element as HTMLButtonElement
            expect(button.tagName).toBe('BUTTON')
            expect(button.disabled).toBe(false)
            expect(button.getAttribute('aria-label')).toBe('Copy User UUID')
            expect(button.getAttribute('title')).toBe('Copy User UUID')
        })
    })

    it('row 12: a click copies that row uuid only and notifies User UUID copied', async () => {
        const column = uuidCopyColumn({ what: 'User UUID', testid: 'user-uuid', notify })
        const w = mountTable([{ key: 'email', title: 'Email' }, { key: 'name', title: 'Name' }, column], USERS.slice(0, 2))
        await w.findAll('[data-testid="user-uuid-copy"]')[0].trigger('click')
        await flushPromises()
        expect(writeText).toHaveBeenCalledTimes(1)
        expect(writeText).toHaveBeenCalledWith('0dfce17a-fc79-4245-937d-eff5213fdfcb')
        expect(writeText).not.toHaveBeenCalledWith('0dfc1111-0000-4000-8000-000000000002')
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('success', 'Copied', 'User UUID copied')
    })
})

describe('uuidCopyColumn: the inactive users UUID column', () => {
    it('row 13: has its own testids, disjoint from the active table', () => {
        const column = uuidCopyColumn({ what: 'User UUID', testid: 'inactive-user-uuid', notify })
        const inactive = USERS.filter(u => u.status === 'INACTIVE')
        expect(inactive).toHaveLength(1)
        const w = mountTable([{ key: 'email', title: 'Email' }, { key: 'name', title: 'Name' }, column], inactive)
        const codes = w.findAll('[data-testid="inactive-user-uuid"]')
        expect(codes).toHaveLength(1)
        expect(codes[0].element.tagName).toBe('CODE')
        expect(codes[0].text()).toBe(inactive[0].uuid)
        const buttons = w.findAll('[data-testid="inactive-user-uuid-copy"]')
        expect(buttons).toHaveLength(1)
        expect(buttons[0].element.tagName).toBe('BUTTON')
        expect(buttons[0].attributes('aria-label')).toBe('Copy User UUID')
        expect(document.querySelectorAll('[data-testid="user-uuid"]')).toHaveLength(0)
        expect(document.querySelectorAll('[data-testid="user-uuid-copy"]')).toHaveLength(0)
    })
})
