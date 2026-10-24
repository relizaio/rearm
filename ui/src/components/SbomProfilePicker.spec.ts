// @vitest-environment happy-dom
//
// The SBOM profile picker (task TEA-30, design 4.3 row 18): single on CE, multiple in publishing
// order on Pro, `single` forcing one; no option is ever disabled, so a stale or CONFLICT name keeps
// its tag's close button and can be dropped; the host's data-testid lands on the root div.
import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { NSelect } from 'naive-ui'

const { default: Picker } = await import('./SbomProfilePicker.vue')

const P1 = { uuid: 'p1', name: 'Payments', type: 'PERSPECTIVE' }
const P2 = { uuid: 'p2', name: 'Retail', type: 'PERSPECTIVE' }
const entry = (name: string, status = 'RESOLVED', changes: Record<string, any> = {}) => ({ name, resolution: {
    status, source: status === 'RESOLVED' ? 'ORGANIZATION' : null, sourceObject: null,
    profile: status === 'RESOLVED' ? { name, revision: 1 } : null, conflictingPerspectives: [], ...changes } })
const CATALOG = [entry('default'), entry('top', 'CONFLICT', { conflictingPerspectives: [P1, P2] })]

function mountPicker (props: Record<string, any>, attrs: Record<string, any> = {}) {
    return mount(Picker, { props: { entries: CATALOG, ...props }, attrs, attachTo: document.body })
}

const select = (w: any) => w.findComponent(NSelect)

describe('SbomProfilePicker', () => {
    it('on OSS is a single select showing the first value and emitting a one-element array', async () => {
        const w = mountPicker({ modelValue: ['default', 'top'], installationType: 'OSS' })
        expect(select(w).props('multiple')).toBe(false)
        expect(select(w).props('value')).toBe('default')
        await select(w).vm.$emit('update:value', 'top')
        expect(w.emitted('update:modelValue')).toEqual([[['top']]])
    })

    it('on SAAS is multiple and keeps the chosen order; single forces one value', async () => {
        const w = mountPicker({ modelValue: ['top', 'default'], installationType: 'SAAS' })
        expect(select(w).props('multiple')).toBe(true)
        expect(select(w).props('value')).toEqual(['top', 'default'])
        await select(w).vm.$emit('update:value', ['top', 'default'])
        expect(w.emitted('update:modelValue')![0]).toEqual([['top', 'default']])
        const single = mountPicker({ modelValue: ['top', 'default'], installationType: 'SAAS', single: true })
        expect(select(single).props('multiple')).toBe(false)
        expect(select(single).props('value')).toBe('top')
    })

    it('renders no disabled option over a catalog with a CONFLICT entry, and the options carry their title', async () => {
        const w = mountPicker({ modelValue: ['default'], installationType: 'SAAS' })
        await w.find('.n-base-selection').trigger('click')
        await flushPromises()
        const options = document.querySelectorAll('.n-base-select-option')
        expect(options.length).toBe(2)
        expect(document.querySelectorAll('.n-base-select-option--disabled').length).toBe(0)
        const titles = [...options].map(o => o.getAttribute('title'))
        expect(titles[0]).toBe('Effective here: organization, revision 1')
        expect(titles[1]).toBe('Perspectives Payments, Retail each define top; override or follow it on the TEA tab, or drop it')
        for (const o of select(w).props('options')) expect(o.disabled).not.toBe(true)
        w.unmount()
    })

    it('marks a stale and a CONFLICT selected name with their labels and red lines', () => {
        const w = mountPicker({ modelValue: ['stale', 'top', 'default'], installationType: 'SAAS' })
        const labels = select(w).props('options').map((o: any) => o.label)
        expect(labels).toContain('stale (not found)')
        expect(labels).toContain('top (conflict)')
        const lines = w.findAll('[data-testid="sbom-picker-line"]')
        expect(lines).toHaveLength(3)
        expect(lines[0].classes()).toContain('text-danger')
        expect(lines[0].text()).toContain('Not found at this scope: drop it or define it')
        expect(lines[1].classes()).toContain('text-danger')
        expect(lines[1].text()).toContain('Payments, Retail')
        expect(lines[2].classes()).not.toContain('text-danger')
    })

    it('closes the stale and the CONFLICT tag in turn, emitting the remaining names in order', async () => {
        const w = mountPicker({ modelValue: ['stale', 'top', 'default'], installationType: 'SAAS' })
        const tagOf = (label: string) => w.findAll('.n-tag').find((t: any) => t.text().includes(label))!
        expect(tagOf('stale (not found)').find('.n-tag__close').exists()).toBe(true)
        expect(tagOf('top (conflict)').find('.n-tag__close').exists()).toBe(true)
        await tagOf('stale (not found)').find('.n-tag__close').trigger('click')
        expect(w.emitted('update:modelValue')![0]).toEqual([['top', 'default']])
        // The picker is controlled by modelValue: feed the emitted array back before the second close.
        await w.setProps({ modelValue: ['top', 'default'] })
        await tagOf('top (conflict)').find('.n-tag__close').trigger('click')
        expect(w.emitted('update:modelValue')![1]).toEqual([['default']])
        w.unmount()
    })

    it('puts the host data-testid on the root div and keeps sbom-profile-picker on the select inside', () => {
        const w = mountPicker({ modelValue: ['default'], installationType: 'SAAS' }, { 'data-testid': 'tea-profiles' })
        const root = w.find('[data-testid="tea-profiles"]')
        expect(root.exists()).toBe(true)
        expect(root.element).toBe(w.element)
        expect(root.element.tagName).toBe('DIV')
        expect(root.classes()).toContain('sbom-profile-picker-root')
        const inner = root.find('[data-testid="sbom-profile-picker"]')
        expect(inner.exists()).toBe(true)
        expect(select(w).attributes('data-testid')).toBe('sbom-profile-picker')
        expect(w.findAll('[data-testid="tea-profiles"]')).toHaveLength(1)
    })
})
