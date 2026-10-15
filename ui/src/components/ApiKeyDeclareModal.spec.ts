// @vitest-environment happy-dom
//
// The Declare modal blocks only what every server refuses, and shows the rule for a name being assigned as a
// warning that leaves Declare enabled: the browser's Unicode data can differ from the server's. A key keeps a
// name it already carries, so its current name draws no warning.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

const mutate = vi.fn(async (_: any) => ({ data: { declareApiKey: { uuid: 'k1', declaredName: 'x' } } }))
vi.mock('@/utils/graphql', () => ({ default: { mutate: (m: any) => mutate(m) } }))
vi.mock('../utils/graphql', () => ({ default: { mutate: (m: any) => mutate(m) } }))
// n-modal teleports its card out of the wrapper; this one renders it in place
vi.mock('naive-ui', async (orig) => ({
    ...(await orig() as any),
    NModal: defineComponent({
        props: { show: Boolean },
        setup: (props, { slots }) => () => (props.show ? h('div', [slots.header?.(), slots.default?.()]) : null)
    })
}))
const { default: Modal } = await import('./ApiKeyDeclareModal.vue')
const { INVISIBLE_NAME } = await import('@/utils/apiKeyDeclaration')

const key = (declaredName: string | null) => ({ uuid: 'k1', type: 'FREEFORM', status: 'ACTIVE', notes: 'ci', declaredName })

function open (declaredName: string | null) {
    return mount(Modal, { props: { show: true, apiKey: key(declaredName), notify: vi.fn() } })
}

const declareButton = (w: ReturnType<typeof open>) => w.findAll('button').find(b => b.text() === 'Declare')!

describe('ApiKeyDeclareModal', () => {
    beforeEach(() => mutate.mockClear())

    it('does not warn on the name the key already carries', async () => {
        const w = open('ci\u200Brelease')
        await flushPromises()
        expect(w.text()).not.toContain(INVISIBLE_NAME)
        expect(declareButton(w).attributes('disabled')).toBeDefined()
    })

    it('warns on a new name with a character that draws nothing, and still lets the server decide', async () => {
        const w = open(null)
        await w.find('input').setValue('ci\u200Bdeploy')
        expect(w.text()).toContain(INVISIBLE_NAME + ' (U+200B at code point 3)')
        expect(declareButton(w).attributes('disabled')).toBeUndefined()
        await declareButton(w).trigger('click')
        await flushPromises()
        expect(mutate).toHaveBeenCalledTimes(1)
        expect(mutate.mock.calls[0][0].variables).toEqual({ apiKeyUuid: 'k1', name: 'ci\u200Bdeploy' })
    })

    it('blocks a name every server refuses', async () => {
        const w = open(null)
        await w.find('input').setValue(' ci')
        expect(w.text()).toContain('A key\'s name has no leading or trailing spaces')
        expect(declareButton(w).attributes('disabled')).toBeDefined()
    })
})
