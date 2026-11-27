// @vitest-environment happy-dom
//
// The pending badge beside the release's "Safety Context" tab label: pending and downgrade counts of
// the release summary, nothing for a reader without Finding Analysis read or on any error.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const query = vi.fn()
vi.mock('@/utils/graphql', () => ({ default: { query } }))
vi.mock('sweetalert2', () => ({ default: { fire: vi.fn() } }))
const { default: Swal } = await import('sweetalert2')
const { default: Badge } = await import('./SafetyPendingBadge.vue')

const tooltip = { template: '<span><slot name="trigger"/><span class="tip"><slot/></span></span>' }

function summary (over: any) {
    return { canRead: true, pending: 0, pendingDowngrade: 0, pendingUncomparable: 0, pendingAwaitingSbom: 0, ...over }
}

async function mounted (answer: any, props: any = {}) {
    query.mockImplementation(() => answer instanceof Error ? Promise.reject(answer)
        : Promise.resolve({ data: { getSafetyContextReleaseSummary: answer } }))
    const w = mount(Badge, {
        props: { orgUuid: 'org-1', releaseUuid: 'rel-1', refreshKey: 0, ...props },
        global: { stubs: { NTooltip: tooltip, Tooltip: tooltip } }
    })
    await flushPromises()
    return w
}

const tags = (w: any) => w.findAll('.n-tag').map((t: any) => t.text())
const tagTypes = (w: any) => w.findAllComponents({ name: 'Tag' }).map((t: any) => t.props('type'))

describe('SafetyPendingBadge (u11)', () => {
    let warn: any
    beforeEach(() => {
        query.mockReset(); vi.mocked(Swal.fire).mockClear()
        warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    })

    it('asks the release summary of its release', async () => {
        await mounted(summary({}))
        expect(query.mock.calls[0][0].query.definitions[0].name.value).toBe('GetSafetyContextReleaseSummary')
        expect(query.mock.calls[0][0].variables).toEqual({ org: 'org-1', release: 'rel-1' })
    })

    it.each([
        ['pending 0', summary({})],
        ['canRead false', { canRead: false, pending: null, pendingDowngrade: null, pendingUncomparable: null, pendingAwaitingSbom: null }]
    ])('renders nothing for %s', async (_, s) => {
        const w = await mounted(s)
        expect(w.find('[data-testid="safety-pending-badge"]').exists()).toBe(false)
        expect(w.text()).toBe('')
    })

    it('renders nothing while loading', async () => {
        query.mockImplementation(() => new Promise(() => {}))
        const w = mount(Badge, { props: { orgUuid: 'org-1', releaseUuid: 'rel-1' }, global: { stubs: { NTooltip: tooltip } } })
        await flushPromises()
        expect(w.text()).toBe('')
    })

    it('an error renders nothing, warns on the console, and calls no popup', async () => {
        const w = await mounted(new Error('Internal server error (ref ab12cd34)'))
        expect(w.text()).toBe('')
        expect(warn).toHaveBeenCalled()
        expect(Swal.fire).not.toHaveBeenCalled()
    })

    it('pending without downgrades: one warning tag', async () => {
        const w = await mounted(summary({ pending: 3, pendingUncomparable: 1 }))
        expect(tags(w)).toEqual(['3 pending'])
        expect(tagTypes(w)).toEqual(['warning'])
    })

    it('one downgrade: the singular, as an error tag', async () => {
        const w = await mounted(summary({ pending: 3, pendingDowngrade: 1 }))
        expect(tags(w)).toEqual(['3 pending', '1 downgrade'])
        expect(tagTypes(w)).toEqual(['warning', 'error'])
    })

    it('several downgrades: the plural', async () => {
        const w = await mounted(summary({ pending: 5, pendingDowngrade: 2 }))
        expect(tags(w)).toEqual(['5 pending', '2 downgrades'])
    })

    it('over 999: 999+', async () => {
        const w = await mounted(summary({ pending: 1200, pendingDowngrade: 1000 }))
        expect(tags(w)).toEqual(['999+ pending', '999+ downgrades'])
    })

    it('the tooltip sentence keeps every clause, zeros included', async () => {
        const w = await mounted(summary({ pending: 4, pendingDowngrade: 1, pendingUncomparable: 0, pendingAwaitingSbom: 2 }))
        expect(w.find('[data-testid="safety-pending-tooltip"]').text()).toBe('4 pending proposals uploaded to this release: '
            + '1 lower than the current level, 0 not comparable, 2 waiting for the SBOM to finish processing. Counts use '
            + 'the comparison made when each level was staged.')
    })

    it('a refreshKey change asks again', async () => {
        const w = await mounted(summary({ pending: 2 }))
        query.mockResolvedValueOnce({ data: { getSafetyContextReleaseSummary: summary({ pending: 1 }) } })
        await w.setProps({ refreshKey: 1 })
        await flushPromises()
        expect(query).toHaveBeenCalledTimes(2)
        expect(tags(w)).toEqual(['1 pending'])
    })
})
