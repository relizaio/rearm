// @vitest-environment happy-dom
//
// The SAFETY_CONTEXT review inbox: pending rows offer accept and reject; an asset that declares
// conflicting levels cannot be accepted; a downgrade cannot be accepted without a comment; an
// unmatched level needs a release component before it can be accepted.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
const mutate = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query, mutate } }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { orguuid: 'org-1' } }), RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
const { default: Proposals } = await import('./SafetyContextProposals.vue')

// Modals teleport; render them in place when shown.
const modal = { props: ['show'], template: '<div v-if="show" class="modal"><slot/></div>' }
const tooltip = { template: '<span><slot name="trigger"/><slot/></span>' }

function row (over: any) {
    return {
        uuid: 'p1', kind: 'PROPOSAL', org: 'org-1', proposalStatus: 'PENDING', releaseUuid: 'rel-1',
        componentPurl: 'pkg:generic/acme/fw@1.0.0', schemeKey: 'IEC-62304', levelRaw: 'Class C',
        levelNormalized: 'Class C', levelChange: 'NEW', gateRule: 'THIRD_PARTY_STAGE', matchStatus: 'MATCHED',
        issuerClassEffective: 'THIRD_PARTY', notes: [], ...over
    }
}

async function mounted (rows: any[]) {
    query.mockResolvedValue({ data: { getSafetyContextProposals: rows } })
    const w = mount(Proposals, { global: { stubs: { NModal: modal, Modal: modal, NTooltip: tooltip, Tooltip: tooltip, SafetyContextSourceModal: true } } })
    await flushPromises()
    return w
}

function button (w: any, title: string) {
    return w.findAll('button').find((b: any) => b.attributes('title') === title)
}

describe('SafetyContextProposals', () => {
    beforeEach(() => { query.mockReset(); mutate.mockReset(); mutate.mockResolvedValue({ data: {} }) })

    it('lists pending proposals of the org and accepts one', async () => {
        const w = await mounted([row({})])
        expect(query.mock.calls[0][0].variables).toMatchObject({ org: 'org-1', status: 'PENDING' })
        expect(w.text()).toContain('pkg:generic/acme/fw@1.0.0')
        await button(w, 'Accept')!.trigger('click')
        await flushPromises()
        const confirm = w.find('.modal').findAll('button').find((b: any) => b.text() === 'Accept')!
        await confirm.trigger('click')
        await flushPromises()
        expect(mutate.mock.calls[0][0].variables).toEqual({ uuid: 'p1', component: null, comment: null })
    })

    it('does not offer accept for conflicting levels in one asset', async () => {
        const w = await mounted([row({ gateRule: 'INTRA_ASSET_CONFLICT', levelRaw: 'C | A', levelNormalized: null })])
        const accept = button(w, 'Conflicting levels: reject and correct the document')!
        expect(accept.attributes('disabled')).toBeDefined()
    })

    it('needs a comment to accept a downgrade', async () => {
        const w = await mounted([row({ levelChange: 'DOWNGRADE', gateRule: 'DOWNGRADE' })])
        await button(w, 'Accept')!.trigger('click')
        await flushPromises()
        const confirm = w.find('.modal').findAll('button').find((b: any) => b.text() === 'Accept')!
        expect(confirm.attributes('disabled')).toBeDefined()
        await w.find('.modal textarea').setValue('re-assessed')
        expect(confirm.attributes('disabled')).toBeUndefined()
    })

    it('asks for a release component before accepting an unmatched level', async () => {
        const w = await mounted([row({ matchStatus: 'UNMATCHED', gateRule: 'UNMATCHED' })])
        query.mockResolvedValueOnce({ data: { getReleaseSbomComponents: [
            { sbomComponentUuid: 'sc-1', component: { canonicalPurl: 'pkg:generic/acme/fw@1.0.0' } }] } })
        await button(w, 'Accept')!.trigger('click')
        await flushPromises()
        expect(query.mock.calls[1][0].variables).toEqual({ releaseUuid: 'rel-1' })
        const confirm = w.find('.modal').findAll('button').find((b: any) => b.text() === 'Accept')!
        expect(confirm.attributes('disabled')).toBeDefined()
    })

    it('says a level the upload mode staged was staged by the mode, not auto-accepted', async () => {
        const w = await mounted([row({ gateRule: 'SELF_AUTO', notes: ['USER_MODE_STAGE'], issuerClassEffective: 'SELF' })])
        expect(w.text()).toContain("Staged by the upload's import mode")
        expect(w.text()).not.toContain('auto-accepted')
    })

    it('switches the org inbox status with labelled buttons', async () => {
        const w = await mounted([row({})])
        expect(w.find('[data-testid="safety-context-status-filter"]').text()).toContain('Rejected')
        query.mockResolvedValueOnce({ data: { getSafetyContextProposals: [] } })
        const rejected = w.findAll('input[type="radio"]').find((i: any) => i.attributes('value') === 'REJECTED')!
        await rejected.setValue(true)
        await flushPromises()
        expect(query.mock.calls.at(-1)![0].variables).toMatchObject({ status: 'REJECTED' })
    })
})
