// @vitest-environment happy-dom
//
// The model catalogue page (RD2-27): readable names with what was declared, usage per row, a banner
// that leaves the synthetic row out, "Map to…" on every row with a preview of which row survives,
// and an edit drawer that sends only what changed and shows a refusal beside its field.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import Catalogue from './AiModelCatalogueOfOrg.vue'

const dispatch = vi.fn()
vi.mock('vuex', () => ({ useStore: () => ({ dispatch, getters: {} }) }))
vi.mock('naive-ui', async (orig) => ({ ...(await orig() as any), useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))

const opus = { uuid: 'o1', name: 'claude-opus-5-5', version: '1', canonicalId: 'claude-opus-5-5', resolution: 'RESOLVED',
    pricing: [{ uuid: 'p1', effectiveFrom: '2026-01-01T00:00:00Z' }], createdDate: '2026-09-01T00:00:00Z',
    usage: { sessions: 4, lines: 20, days: 30 }, mergeCandidates: [] }
const opusTwin = { uuid: 'o2', name: 'claude-opus', version: '5.5', canonicalId: 'claude-opus-5-5', resolution: 'RESOLVED',
    pricing: [], createdDate: '2026-09-20T00:00:00Z', usage: { sessions: 0, lines: 0, days: 30 }, mergeCandidates: [{ uuid: 'o1' }] }
const local = { uuid: 'l1', name: 'house-model', version: 'unknown', canonicalId: null, resolution: 'UNRESOLVED',
    suggestedCanonicalId: 'claude-sonnet-5', pricing: [], createdDate: '2026-07-01T00:00:00Z',
    usage: { sessions: 1, lines: 1, days: 30 }, mergeCandidates: [] }
const synthetic = { uuid: 's1', name: 'synthetic', version: 'unknown', resolution: 'SYNTHETIC', pricing: [], mergeCandidates: [] }

// Drawers and modals teleport: render them in place.
const stubs = {
    Drawer: { props: ['show'], template: '<div v-if="show" class="drawer"><slot/></div>' },
    DrawerContent: { template: '<div><slot name="header"/><slot/></div>' },
    Modal: { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' },
}

async function mounted () {
    dispatch.mockImplementation(async (action: string) => {
        if (action === 'fetchModelOntologiesOfOrg') return [opus, opusTwin, local, synthetic]
        if (action === 'fetchModelCatalogueBundle') return [{ canonicalId: 'claude-sonnet-5', publisher: 'Anthropic' }]
        return {}
    })
    const w = mount(Catalogue, { props: { orgUuid: 'org1' }, global: { stubs } })
    await flushPromises()
    return w
}

function row (w: any, uuid: string) {
    return w.findAll('tbody tr').find((r: any) => r.find(`[data-model="${uuid}"]`).exists())
}

describe('AiModelCatalogueOfOrg', () => {
    beforeEach(() => dispatch.mockReset())

    it('names each row readably, with what was declared and its usage', async () => {
        const w = await mounted()
        const o = row(w, 'o1')
        expect(o.find('.mlabel').text()).toContain('claude-opus-5-5')
        expect(o.text()).toContain('declared as claude-opus-5-5 · v1')
        expect(o.text()).not.toContain('claude-opus-5-5 1')
        expect(o.find('[data-usage="o1"]').text()).toBe('4 sessions · 20 lines')
        expect(row(w, 'l1').text()).toContain('declared as house-model')
        expect(row(w, 'l1').find('[data-usage="l1"]').text()).toBe('1 session · 1 line')
    })

    it('counts one unresolved row, not the synthetic one, and hints the duplicates', async () => {
        const w = await mounted()
        expect(w.text()).toContain('1 model is unresolved')
        expect(w.find('[data-testid="merge-duplicates-hint"]').text()).toContain('claude-opus-5-5')
        expect(row(w, 'o1').text()).toContain('duplicate')
        expect(row(w, 'l1').text()).not.toContain('duplicate')
    })

    it('offers Map to on resolved rows and previews which row survives, merging in that direction', async () => {
        const w = await mounted()
        expect(row(w, 'o2').find('[data-testid="model-map"]').exists()).toBe(true)
        expect(row(w, 's1').find('[data-testid="model-map"]').exists()).toBe(false)
        await row(w, 'o2').find('[data-testid="model-map"]').trigger('click')
        const vm = w.vm as any
        expect(vm.mergeOptions[0].value).toBe('o1', 'the server-ranked same-model row first')
        expect(vm.mergeOptions.map((o: any) => o.value)).not.toContain('s1')
        vm.mergeTarget = 'o1'
        await nextTick()
        const preview = w.find('[data-testid="merge-preview"]')
        expect(preview.find('[data-uuid="o1"]').text()).toContain('survives')
        expect(preview.find('[data-uuid="o1"]').text()).toContain('1 live')
        expect(preview.find('[data-uuid="o2"]').text()).toContain('folded')
        expect(preview.find('[data-uuid="o2"]').text()).toContain('unused')
        expect(w.find('[data-testid="merge-direction"]').text()).toContain('will point at the survivor')
        await vm.doMerge()
        expect(dispatch).toHaveBeenCalledWith('mergeModelOntology', { from: 'o2', into: 'o1' })
    })

    it('edits a row, sending only what changed, the suggestion prefilled', async () => {
        const w = await mounted()
        await row(w, 'l1').find('[data-testid="model-edit"]').trigger('click')
        await flushPromises()
        const vm = w.vm as any
        expect(vm.editDraft.canonicalId).toBe('claude-sonnet-5')
        vm.editDraft.name = 'claude-sonnet'
        vm.editDraft.version = '5'
        await vm.saveEdit()
        expect(dispatch).toHaveBeenCalledWith('updateModelOntology',
            { uuid: 'l1', name: 'claude-sonnet', version: '5', canonicalId: 'claude-sonnet-5' })
        expect(row(w, 's1').find('[data-testid="model-edit"]').exists()).toBe(false, 'the synthetic row is fixed')
    })

    it('shows a refusal beside its field and warns about a canonical id the bundle does not price', async () => {
        const w = await mounted()
        await row(w, 'l1').find('[data-testid="model-edit"]').trigger('click')
        await flushPromises()
        const vm = w.vm as any
        vm.editDraft.canonicalId = 'house/model-2'
        await nextTick()
        expect(vm.canonicalNote).toContain('Not in the bundled catalogue')
        dispatch.mockRejectedValueOnce(new Error('GraphQL error: Model claude-opus 5.5 already carries canonical id '
            + 'claude-opus-5-5; merge this row into it instead'))
        vm.editDraft.canonicalId = 'claude-opus-5-5'
        await vm.saveEdit()
        await nextTick()
        expect(vm.fieldError('canonicalId')).toContain('already carries canonical id claude-opus-5-5')
        expect(w.find('[data-testid="model-edit-error"]').exists()).toBe(false)
        expect(vm.showEdit).toBe(true, 'the drawer stays open')
    })
})
