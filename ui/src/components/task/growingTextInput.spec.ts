// @vitest-environment happy-dom
//
// TPF-89: the one idiom for every instruction, note and reason a person writes to the board. A textarea
// that is one line while short and grows to 12 rows, keeps the call site's testid, style and maxlength,
// passes newlines through, does nothing of its own on Enter, and focuses its textarea for a parent.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { NInput } from 'naive-ui'
import GrowingTextInput from './GrowingTextInput.vue'

function field (props: Record<string, any> = {}) {
    return mount(GrowingTextInput, { props: { value: '', placeholder: 'Why', ...props },
        attrs: { 'data-testid': 'note', style: 'width: 260px' } })
}

describe('GrowingTextInput', () => {
    it('renders one n-input textarea that carries the call site attributes (row 1)', () => {
        const w = field()
        expect(w.element.tagName).toBe('DIV')
        expect(w.classes()).toContain('n-input')
        expect(w.classes()).toContain('n-input--textarea')
        expect(w.attributes('data-testid')).toBe('note')
        expect(w.attributes('style')).toContain('width: 260px')
        expect(w.find('textarea').exists()).toBe(true)
        expect(w.find('input').exists()).toBe(false)
        const p = w.findComponent(NInput).props()
        expect(p.type).toBe('textarea')
        expect(p.autosize).toEqual({ minRows: 1, maxRows: 12 })
        expect(p.size).toBe('small')
        expect(p.placeholder).toBe('Why')
        expect(p.maxlength).toBeUndefined()
        expect(p.showCount).toBe(false)
    })

    it('emits what was typed with its newlines (row 2)', async () => {
        const w = field()
        await w.find('textarea').setValue('a\nb\nc')
        expect(w.emitted('update:value')).toEqual([['a\nb\nc']])
    })

    it('passes maxlength, the counter, the row bounds and the size through (row 3)', () => {
        const w = field({ maxlength: 4000, showCount: true, minRows: 2, maxRows: 6, size: 'medium' })
        expect(w.find('textarea').attributes('maxlength')).toBe('4000')
        const inner = w.findComponent(NInput)
        expect(inner.props('autosize')).toEqual({ minRows: 2, maxRows: 6 })
        expect(inner.props('showCount')).toBe(true)
        expect(inner.props('size')).toBe('medium')
    })

    it('does nothing of its own on Enter (row 4)', async () => {
        const w = field()
        await w.find('textarea').trigger('keydown', { key: 'Enter' })
        await w.find('textarea').trigger('keyup', { key: 'Enter' })
        expect(w.emitted('update:value')).toBeUndefined()
        // test-utils records the native events that bubble to the root; the component emitted nothing.
        expect(Object.keys(w.emitted()).sort()).toEqual(['keydown', 'keyup'])
    })

    it('focus() focuses its textarea (row 21)', () => {
        const w = mount(GrowingTextInput, { props: { value: '', placeholder: 'Why' }, attachTo: document.body })
        const textarea = w.find('textarea').element
        expect(document.activeElement).not.toBe(textarea)
        ;(w.vm as unknown as { focus: () => void }).focus()
        expect(document.activeElement).toBe(textarea)
        expect(document.activeElement?.tagName).toBe('TEXTAREA')
        w.unmount()
    })

    it('exposes focus() to a parent that holds a template ref', () => {
        // test-utils gives w.vm the setup bindings even without defineExpose; a parent's template ref
        // sees only what the component exposes, which is how TPF-69's unassign link calls it.
        const field = ref<{ focus: () => void } | null>(null)
        const Parent = defineComponent({ setup: () => () => h(GrowingTextInput, { ref: field, value: '', placeholder: 'Why' }) })
        const w = mount(Parent, { attachTo: document.body })
        expect(typeof field.value?.focus).toBe('function')
        field.value?.focus()
        expect(document.activeElement).toBe(w.find('textarea').element)
        w.unmount()
    })

    it('takes undefined and null without a prop warning and renders them empty (row 22)', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        try {
            const none = mount(GrowingTextInput, { props: { placeholder: 'Why' } })
            const nul = mount(GrowingTextInput, { props: { value: null, placeholder: 'Why' } })
            expect(warn.mock.calls.filter(c => String(c[0]).includes('Invalid prop'))).toEqual([])
            expect((none.find('textarea').element as HTMLTextAreaElement).value).toBe('')
            expect((nul.find('textarea').element as HTMLTextAreaElement).value).toBe('')
            await none.find('textarea').setValue('x')
            expect(none.emitted('update:value')).toEqual([['x']])
        } finally {
            warn.mockRestore()
        }
    })
})
