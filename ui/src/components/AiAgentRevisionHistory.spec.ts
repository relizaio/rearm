// @vitest-environment happy-dom
//
// The history section (22ddc644): nothing is read until it is opened, the live object leads so
// the last edit is visible, a prompt edit is shown as the two texts, and paging asks for the
// next offset.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AiAgentRevisionHistory from './AiAgentRevisionHistory.vue'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch }) }))

const LONG = 'design it\nand say what the tester should check'

function roleRevs (n: number, from: number) {
    return Array.from({ length: n }, (_, i) => ({ revision: from - i, at: '2026-09-25T09:00:00Z',
        roleConfig: { uuid: 'r1', name: 'designer', prompt: `v${from - i}`, orderIndex: 10, active: true } }))
}

async function open (w: any) {
    await w.find('.n-collapse-item__header-main').trigger('click')
    await flushPromises()
}

describe('revision history', () => {
    beforeEach(() => dispatch.mockReset())

    it('reads nothing until opened, then the first page', async () => {
        dispatch.mockResolvedValue(roleRevs(2, 1))
        const w = mount(AiAgentRevisionHistory, { props: { kind: 'role', uuid: 'r1' } })
        await flushPromises()
        expect(dispatch).not.toHaveBeenCalled()
        await open(w)
        expect(dispatch).toHaveBeenCalledWith('fetchAgentRevisions', { kind: 'role', uuid: 'r1', limit: 20, offset: 0 })
        expect(w.findAll('.revhist__rev').map(r => r.text())).toEqual(['rev 1', 'rev 0'])
    })

    it('leads with the live object and shows a prompt edit as the two texts', async () => {
        dispatch.mockResolvedValue(roleRevs(2, 1))
        const current = { uuid: 'r1', name: 'designer', prompt: LONG, orderIndex: 10, active: true, tableOnly: 'x' }
        const w = mount(AiAgentRevisionHistory, { props: { kind: 'role', uuid: 'r1', current } })
        await open(w)
        expect(w.findAll('.revhist__rev').map(r => r.text())).toEqual(['current', 'rev 1', 'rev 0'])
        // Two comparisons (current vs rev 1, rev 1 vs rev 0); the oldest has nothing below it.
        expect(w.findAll('.revhist__compare')).toHaveLength(2)
        await w.findAll('.revhist__compare')[0].trigger('click')
        expect(w.find('.revhist__text--before').text()).toBe('v1')
        expect(w.find('.revhist__text--after').text()).toBe(LONG.replace('\n', '\n'))
        // tableOnly exists on the live object only: not a change.
        expect(w.findAll('.revhist__key').map(k => k.text())).toEqual(['prompt'])
    })

    it('shows a short change inline, old value struck through', async () => {
        dispatch.mockResolvedValue(roleRevs(2, 1))
        const w = mount(AiAgentRevisionHistory, { props: { kind: 'role', uuid: 'r1' } })
        await open(w)
        await w.find('.revhist__compare').trigger('click')
        expect(w.find('.revhist__change .revhist__key').text()).toBe('prompt')
        expect(w.find('.revhist__before').text()).toBe('v0')
    })

    it('shows the raw snapshot on request', async () => {
        dispatch.mockResolvedValue(roleRevs(1, 0))
        const w = mount(AiAgentRevisionHistory, { props: { kind: 'role', uuid: 'r1' } })
        await open(w)
        expect(w.find('.revhist__full').exists()).toBe(false)
        const btn = w.findAll('button').find(b => b.text() === 'snapshot')!
        await btn.trigger('click')
        expect(JSON.parse(w.find('.revhist__full').text()).prompt).toBe('v0')
    })

    it('offers more while a page comes back full, and asks for the next offset', async () => {
        dispatch.mockResolvedValueOnce(roleRevs(20, 30)).mockResolvedValueOnce(roleRevs(3, 10))
        const w = mount(AiAgentRevisionHistory, { props: { kind: 'role', uuid: 'r1' } })
        await open(w)
        expect(w.find('.revhist__more').exists()).toBe(true)
        await w.find('.revhist__more').trigger('click')
        await flushPromises()
        expect(dispatch).toHaveBeenLastCalledWith('fetchAgentRevisions', { kind: 'role', uuid: 'r1', limit: 20, offset: 20 })
        expect(w.findAll('.revhist__rev')).toHaveLength(23)
        expect(w.find('.revhist__more').exists()).toBe(false)
    })

    it('says when there is nothing, and shows a refusal', async () => {
        dispatch.mockResolvedValueOnce([])
        const empty = mount(AiAgentRevisionHistory, { props: { kind: 'task', uuid: 't1' } })
        await open(empty)
        expect(empty.text()).toContain('No earlier revisions.')

        dispatch.mockRejectedValueOnce(new Error('Not authorized'))
        const refused = mount(AiAgentRevisionHistory, { props: { kind: 'task', uuid: 't1' } })
        await open(refused)
        expect(refused.find('.revhist__note--error').text()).toBe('Not authorized')
    })
})
