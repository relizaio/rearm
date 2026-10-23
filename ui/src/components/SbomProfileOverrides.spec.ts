// @vitest-environment happy-dom
//
// Per-scope SBOM profile overrides (task TEA-30, design 4.3 row 17), at COMPONENT scope: the stored
// and CONFLICT tags, the effective line per row, Override and Define opening the hosted editor at
// this scope, Remove override with the COMPONENT prefix, and `changed`: emitted once after each
// successful write, after the two reads ran again, never on a cancel or a refusal.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { NNotificationProvider } from 'naive-ui'

const query = vi.fn()
const mutate = vi.fn()
const fire = vi.fn()
const log: string[] = []
vi.mock('@/utils/graphql', () => ({ default: { query: (...a: any[]) => query(...a), mutate: (...a: any[]) => mutate(...a) } }))
vi.mock('sweetalert2', () => ({ default: { fire: (...a: any[]) => fire(...a) } }))
vi.mock('./SbomProfileEditor.vue', () => ({ default: { name: 'SbomProfileEditor',
    props: ['name', 'scope', 'objectUuid', 'orgUuid'], emits: ['saved', 'removed', 'cancel'], render: () => null } }))

const { default: Overrides } = await import('./SbomProfileOverrides.vue')

const P1 = { uuid: 'p1', name: 'Payments', type: 'PERSPECTIVE' }
const P2 = { uuid: 'p2', name: 'Retail', type: 'PERSPECTIVE' }
const operation = (opts: any) => (opts.query ?? opts.mutation)?.definitions?.[0]?.name?.value

const CATALOG = [
    { name: 'default', resolution: { status: 'RESOLVED', source: 'ORGANIZATION', sourceObject: null,
        profile: { name: 'default', revision: 2 }, conflictingPerspectives: [] } },
    { name: 'mine', resolution: { status: 'RESOLVED', source: 'COMPONENT', sourceObject: 'c1',
        profile: { name: 'mine', revision: 0 }, conflictingPerspectives: [] } },
    { name: 'top', resolution: { status: 'CONFLICT', source: null, sourceObject: null, profile: null,
        conflictingPerspectives: [P1, P2] } },
]
const STORED = [{ name: 'mine', scope: 'COMPONENT', object: 'c1', revision: 0 }]

function answers () {
    query.mockImplementation(async (opts: any) => {
        const op = operation(opts)
        log.push(op)
        if (op === 'SbomProfileCatalog') return { data: { sbomProfileCatalog: CATALOG } }
        if (op === 'SbomProfiles') return { data: { sbomProfiles: STORED } }
        throw new Error('unexpected query ' + op)
    })
}

async function mountOverrides () {
    let inner: any = null
    const w = mount(defineComponent({
        render: () => h(NNotificationProvider, () => h(Overrides, { orgUuid: 'o1', scope: 'COMPONENT', objectUuid: 'c1',
            objectName: 'widget', isWritable: true, installationType: 'SAAS',
            onChanged: () => { log.push('changed') } })),
    }), { attachTo: document.body })
    await flushPromises()
    inner = w.findComponent(Overrides)
    return { w, inner }
}

const rowOf = (w: any, name: string) => w.findAll('[data-testid="sbom-override-rows"] tbody tr')
    .find((r: any) => r.find('td').text() === name)
const editor = (w: any) => w.findComponent({ name: 'SbomProfileEditor' })
const changedCount = () => log.filter(l => l === 'changed').length

describe('SbomProfileOverrides', () => {
    beforeEach(() => {
        query.mockReset()
        mutate.mockReset()
        fire.mockReset()
        log.length = 0
        answers()
        mutate.mockResolvedValue({ data: { deleteSbomProfile: true } })
    })

    it('reads the catalog and the stored rows at this scope and tags the rows', async () => {
        const { w } = await mountOverrides()
        for (const op of ['SbomProfileCatalog', 'SbomProfiles']) {
            expect(query.mock.calls.find(c => operation(c[0]) === op)![0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1' })
        }
        expect(rowOf(w, 'mine').find('[data-testid="sbom-override-tag"]').exists()).toBe(true)
        expect(rowOf(w, 'default').find('[data-testid="sbom-override-tag"]').exists()).toBe(false)
        expect(rowOf(w, 'top').find('[data-testid="sbom-override-tag"]').exists()).toBe(false)
        expect(rowOf(w, 'top').find('[data-testid="sbom-conflict-tag"]').exists()).toBe(true)
        expect(rowOf(w, 'default').find('[data-testid="sbom-conflict-tag"]').exists()).toBe(false)
        expect(rowOf(w, 'default').text()).toContain('Effective here: organization, revision 2')
        expect(rowOf(w, 'mine').text()).toContain('Effective here: component, revision 0')
        expect(rowOf(w, 'top').text()).toContain('conflict between perspectives Payments, Retail')
        expect(rowOf(w, 'top').text()).toContain('Choose Override here, or Follow')
        expect(rowOf(w, 'mine').find('[data-testid="sbom-remove-override"]').exists()).toBe(true)
        expect(rowOf(w, 'default').find('[data-testid="sbom-remove-override"]').exists()).toBe(false)
        w.unmount()
    })

    it('Override opens the editor for the row name at this scope; Define opens create mode', async () => {
        const { w } = await mountOverrides()
        await rowOf(w, 'top').find('[data-testid="sbom-override"]').trigger('click')
        await flushPromises()
        expect(editor(w).props('name')).toBe('top')
        expect(editor(w).props('scope')).toBe('COMPONENT')
        expect(editor(w).props('objectUuid')).toBe('c1')
        w.unmount()
        const again = await mountOverrides()
        await again.w.find('[data-testid="sbom-define"]').trigger('click')
        await flushPromises()
        expect(editor(again.w).props('name')).toBeNull()
        expect(editor(again.w).props('scope')).toBe('COMPONENT')
        again.w.unmount()
    })

    it('emits changed once after saved, removed and a confirmed Remove override, each after the two reads', async () => {
        const { w } = await mountOverrides()
        await rowOf(w, 'default').find('[data-testid="sbom-override"]').trigger('click')
        await flushPromises()

        let mark = log.length
        editor(w).vm.$emit('saved', { name: 'default' })
        await flushPromises()
        expect(changedCount()).toBe(1)
        expect(log.slice(mark)).toEqual(['SbomProfileCatalog', 'SbomProfiles', 'changed'])

        mark = log.length
        editor(w).vm.$emit('removed', 'default')
        await flushPromises()
        expect(changedCount()).toBe(2)
        expect(log.slice(mark)).toEqual(['SbomProfileCatalog', 'SbomProfiles', 'changed'])

        mark = log.length
        fire.mockResolvedValueOnce({ isConfirmed: true })
        await rowOf(w, 'mine').find('[data-testid="sbom-remove-override"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[0][0].text.startsWith('Remove the definition of mine at this component. ')).toBe(true)
        expect(fire.mock.calls[0][0].title).toBe('Delete SBOM profile?')
        expect(mutate.mock.calls[0][0].variables).toEqual({ org: 'o1', scope: 'COMPONENT', object: 'c1', name: 'mine' })
        expect(changedCount()).toBe(3)
        expect(log.slice(mark)).toEqual(['SbomProfileCatalog', 'SbomProfiles', 'changed'])
        w.unmount()
    })

    it('emits nothing on a cancelled or a refused Remove override', async () => {
        const { w } = await mountOverrides()
        fire.mockResolvedValueOnce({ isConfirmed: false })
        await rowOf(w, 'mine').find('[data-testid="sbom-remove-override"]').trigger('click')
        await flushPromises()
        expect(mutate).not.toHaveBeenCalled()
        fire.mockResolvedValueOnce({ isConfirmed: true })
        mutate.mockRejectedValueOnce(new Error('SBOM profile mine of component widget cannot be deleted: the TEA publishing '
            + 'settings of component widget name it and it would no longer resolve there; point them at another profile first'))
        await rowOf(w, 'mine').find('[data-testid="sbom-remove-override"]').trigger('click')
        await flushPromises()
        expect(fire.mock.calls[2]).toHaveLength(1)
        expect(fire.mock.calls[2][0].text).toContain('cannot be deleted')
        expect(changedCount()).toBe(0)
        w.unmount()
    })
})
