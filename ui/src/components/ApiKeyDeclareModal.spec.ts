// @vitest-environment happy-dom
//
// RD3-11, API key hygiene: the Declare modal blocks only what every server refuses, and shows the rule for a
// name being assigned as a warning that leaves Declare enabled: the browser's Unicode data can differ from the
// server's. A key keeps a name it already carries, so its current name draws no warning.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const mutate = vi.fn(async (_: any): Promise<any> => ({ data: { declareApiKey: { uuid: 'k1', declaredName: 'x' } } }))
vi.mock('@/utils/graphql', () => ({ default: { mutate: (m: any) => mutate(m) } }))
const { default: Modal } = await import('./ApiKeyDeclareModal.vue')
const { INVISIBLE_NAME } = await import('@/utils/apiKeyDeclaration')

// The modal teleports: render it in place.
const global = { stubs: { Modal: { props: ['show'], template: '<div v-if="show"><slot name="header"/><slot/></div>' } } }

const key = (declaredName: string | null) => ({ uuid: 'k1', type: 'FREEFORM', status: 'ACTIVE', notes: 'ci', declaredName })

function open (declaredName: string | null) {
    return mount(Modal, { props: { show: true, apiKey: key(declaredName), notify: vi.fn() }, global })
}

const declareButton = (w: ReturnType<typeof open>) => w.findAll('button').find(b => b.text() === 'Declare')!

describe('ApiKeyDeclareModal', () => {
    beforeEach(() => mutate.mockClear())

    it('opens on the name the key carries and does not warn on it', async () => {
        const w = open('ci\u200Brelease')
        await flushPromises()
        expect((w.find('input').element as HTMLInputElement).value).toBe('ci\u200Brelease')
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

    it('warns on a rename to such a name', async () => {
        const w = open('ci')
        await w.find('input').setValue('ci\u200Bb')
        expect(w.text()).toContain(INVISIBLE_NAME)
        expect(declareButton(w).attributes('disabled')).toBeUndefined()
    })

    it('shows the server\'s refusal in place of the warning until the name changes', async () => {
        mutate.mockRejectedValueOnce(new Error('another key is declared as ci\u200Bdeploy'))
        const w = open(null)
        await w.find('input').setValue('ci\u200Bdeploy')
        await declareButton(w).trigger('click')
        await flushPromises()
        expect(w.text()).toContain('another key is declared as')
        expect(w.text()).not.toContain(INVISIBLE_NAME)
        await w.find('input').setValue('ci\u200Bdeploy2')
        expect(w.text()).not.toContain('another key is declared as')
        expect(w.text()).toContain(INVISIBLE_NAME)
    })

    it('blocks a name every server refuses', async () => {
        const w = open(null)
        await w.find('input').setValue(' ci')
        expect(w.text()).toContain('A key\'s name has no leading or trailing spaces')
        expect(declareButton(w).attributes('disabled')).toBeDefined()
    })
})
