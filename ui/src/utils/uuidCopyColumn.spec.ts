// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NConfigProvider, NDataTable } from 'naive-ui'

import { copyToClipboard, uuidCopyColumn } from './uuidCopyColumn'

/**
 * TPF-84: the UUID column of the Org settings Perspectives table (OrgSettings is never mounted in
 * tests, so the column is mounted here inside a real NDataTable, the apiKeyControls.spec.ts rig;
 * the page wiring is pinned in components/orgSettingsPerspectivesWiring.spec.ts).
 */
const FIRST = '11111111-1111-4111-8111-111111111111'
const SECOND = '22222222-2222-4222-8222-222222222222'

let notify: ReturnType<typeof vi.fn>
let writeText: ReturnType<typeof vi.fn>

function setClipboard (value: any) {
    Object.defineProperty(navigator, 'clipboard', { value, configurable: true })
}

function mountTable (rows: any[]) {
    const columns = [
        { key: 'name', title: 'Perspective Name' },
        uuidCopyColumn({ what: 'Perspective UUID', testid: 'perspective-uuid', notify })
    ]
    const Host = defineComponent({ render: () => h(NConfigProvider, null, { default: () => h(NDataTable, { columns, data: rows, rowKey: (r: any) => r.uuid }) }) })
    return mount(Host, { attachTo: document.body })
}

const twoRows = () => [{ uuid: FIRST, name: 'alpha' }, { uuid: SECOND, name: 'beta' }]

beforeEach(() => {
    notify = vi.fn()
    writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
})
afterEach(() => {
    document.body.innerHTML = ''
    setClipboard(undefined)
})

describe('uuidCopyColumn: the Perspectives UUID column', () => {
    it('shows each row its full uuid beside one enabled, labelled copy button', () => {
        const w = mountTable(twoRows())
        expect(w.findAll('thead th').map(th => th.text())).toEqual(['Perspective Name', 'UUID'])
        const rows = w.findAll('tbody tr')
        expect(rows).toHaveLength(2)
        ;[FIRST, SECOND].forEach((uuid, i) => {
            const code = rows[i].findAll('[data-testid="perspective-uuid"]')
            expect(code).toHaveLength(1)
            expect(code[0].text()).toBe(uuid)
            expect(code[0].text()).toHaveLength(36)
            const buttons = rows[i].findAll('[data-testid="perspective-uuid-copy"]')
            expect(buttons).toHaveLength(1)
            const button = buttons[0].element as HTMLButtonElement
            expect(button.tagName).toBe('BUTTON')
            expect(button.disabled).toBe(false)
            expect(button.getAttribute('aria-label')).toBe('Copy Perspective UUID')
            expect(button.getAttribute('title')).toBe('Copy Perspective UUID')
        })
    })

    it('copies only the clicked row uuid and reports success', async () => {
        const w = mountTable(twoRows())
        await w.findAll('tbody tr')[1].find('[data-testid="perspective-uuid-copy"]').trigger('click')
        await flushPromises()
        expect(writeText).toHaveBeenCalledTimes(1)
        expect(writeText).toHaveBeenCalledWith(SECOND)
        expect(writeText).not.toHaveBeenCalledWith(FIRST)
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('success', 'Copied', 'Perspective UUID copied')
    })

    it('warns, without throwing, where the clipboard API is unavailable', async () => {
        setClipboard(undefined)
        const w = mountTable(twoRows())
        await w.find('[data-testid="perspective-uuid-copy"]').trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('warning', 'Copy unavailable', 'Select the text and copy it.')
        expect(writeText).not.toHaveBeenCalled()
    })

    it('reports a rejected clipboard write as an error with its message', async () => {
        writeText = vi.fn().mockRejectedValue(new Error('denied'))
        setClipboard({ writeText })
        const w = mountTable(twoRows())
        await w.find('[data-testid="perspective-uuid-copy"]').trigger('click')
        await flushPromises()
        expect(notify).toHaveBeenCalledTimes(1)
        expect(notify).toHaveBeenCalledWith('error', 'Copy failed', 'denied')
    })

    it('renders no uuid cell and no copy button for a row without a uuid', () => {
        const w = mountTable([...twoRows(), { name: 'gamma' }, { uuid: '', name: 'delta' }])
        const rows = w.findAll('tbody tr')
        expect(rows).toHaveLength(4)
        for (const row of rows.slice(2)) {
            expect(row.find('[data-testid="perspective-uuid"]').exists()).toBe(false)
            expect(row.find('[data-testid="perspective-uuid-copy"]').exists()).toBe(false)
        }
        expect(w.findAll('[data-testid="perspective-uuid-copy"]')).toHaveLength(2)
    })
})

describe('copyToClipboard', () => {
    it('writes the text and names what was copied', async () => {
        await copyToClipboard('x', 'Thing', notify)
        expect(writeText).toHaveBeenCalledWith('x')
        expect(notify).toHaveBeenCalledWith('success', 'Copied', 'Thing copied')
    })

    it('does nothing for empty text', async () => {
        await copyToClipboard('', 'Thing', notify)
        expect(writeText).not.toHaveBeenCalled()
        expect(notify).not.toHaveBeenCalled()
    })
})
